package com.davidjonesgriz.fakellm

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** What the HTTP layer sends back for one faked vendor call. */
internal data class FakeResponse(
    val status: Int,
    val body: String,
    val headers: Map<String, String> = emptyMap(),
)

/**
 * One request the fake served. [ScenarioEngine.calls] and `GET /control/calls` return these.
 * Records are read-only for library users: the constructor and `copy` are internal, so fields
 * added with default values do not break callers.
 */
@ConsistentCopyVisibility
@Serializable
public data class CallRecord internal constructor(
    val seq: Long,
    val vendor: String,
    val model: String,
    val systemShape: String,
    val messageCount: Int,
    /**
     * The matched rule's role label, `"DEFAULT"` when lenient mode served the canned reply, or
     * `"UNMATCHED"` when strict mode rejected the request.
     */
    val role: String,
    /**
     * 1-based count of how many times this rule, or the lenient canned reply, has served a call.
     * `0` for a strict-mode unmatched request, which does not advance a counter.
     */
    val occurrence: Int,
    /** `"text"`, `"error:<status>"`, `"default"`, or `"unmatched"`. */
    val outcome: String,
    val timestampMs: Long,
    /**
     * The last [PROMPT_SAMPLE_CHARS] characters of the parsed prompt. The log keeps a bounded
     * suffix of the prompt instead of the full text.
     */
    val promptSample: String,
)

/** One logged call and the [Step] that serves it. See [ScenarioEngine.resolve]. */
internal data class Resolution(
    val seq: Long,
    val step: Step,
    val role: String,
    val occurrence: Int,
)

/**
 * Matches each request to a step and records it in the call log. Concurrent calls share only the
 * rule's occurrence counter: that counter orders steps when the caller sends requests one at a
 * time, and a single-step rule does not depend on which call wins the increment.
 *
 * Resolution order: (a) the loaded scenario's rules, first match wins; (b) when [lenient] is
 * true, one generic canned assistant reply, logged with outcome `"default"` and role `"DEFAULT"`,
 * rendered in the dialect the caller requested; (c) otherwise HTTP 400, logged with outcome
 * `"unmatched"` and role `"UNMATCHED"`. Strict is the default, so a request the scenario does not
 * cover fails instead of returning a reply the caller can treat as success.
 */
public class ScenarioEngine(
    /** Unmatched requests get the canned reply when true, and HTTP 400 when false. */
    public val lenient: Boolean = false,
) {
    @Volatile private var scenario: Scenario? = null
    private val occurrences = ConcurrentHashMap<Int, AtomicInteger>()

    /**
     * Occurrence counter for the canned reply in lenient mode. Separate from [occurrences], which
     * is keyed by rule index. Strict unmatched requests do not use it. Cleared with the loaded
     * scenario. The canned reply is one repeating step.
     */
    private val genericDefaultOccurrences = AtomicInteger(0)
    private val log = Collections.synchronizedList(mutableListOf<CallRecord>())
    private val seq = AtomicLong(0)

    /**
     * Loads the scenario [name] from the classpath resource `/scenarios/<name>.json` and installs it
     * as [loadInline] does. When the command-line server was started with `--scenario-dir` or
     * `FAKE_LLM_SCENARIO_DIR`, that directory is searched first. Throws when the name
     * is not `[A-Za-z0-9_-]+`, no such scenario exists, the file does not decode, or its declared
     * name differs from [name].
     */
    public fun load(name: String): Scenario = loadInline(ScenarioLoader.load(name))

    /**
     * Installs [s] as the active scenario and resets counters and the call log.
     * By-name [load] delegates here. `POST /control/scenario` with a `scenario` object does too.
     */
    public fun loadInline(s: Scenario): Scenario {
        scenario = s
        reset()
        return s
    }

    /** Clears counters and the call log. Keeps the loaded scenario. */
    public fun reset() {
        occurrences.clear()
        genericDefaultOccurrences.set(0)
        log.clear()
    }

    /** The loaded scenario's name, or null when none is loaded. */
    public fun currentScenarioName(): String? = scenario?.name

    /** A snapshot of the call log, oldest first. */
    public fun calls(): List<CallRecord> = synchronized(log) { log.toList() }

    /** Whole JSON response for one request. */
    internal fun handle(
        vendor: String,
        req: ParsedRequest,
    ): FakeResponse {
        val r = resolve(vendor, req)
        val step = r.step
        return if (step.error != null) {
            FakeResponse(step.error.status, step.error.body, step.error.headers)
        } else {
            FakeResponse(
                HTTP_OK,
                vendorEnvelope(vendor, step.text!!, step.stopReason, r.seq, req.hasCacheBreakpoint, step.thinking),
            )
        }
    }

    /**
     * Matches, advances the rule's (or the lenient canned reply's) occurrence counter, and records
     * the one call-log entry for this request. A strict unmatched request is recorded with
     * occurrence 0. Shared by the buffered [handle] and the SSE path in FakeLlmApp.kt, so a
     * request is matched and logged once whichever transport renders it.
     * See the class doc for the resolution order.
     */
    internal fun resolve(
        vendor: String,
        req: ParsedRequest,
    ): Resolution =
        resolveScenarioRule(vendor, req)
            ?: if (lenient) resolveGenericDefault(vendor, req) else resolveUnmatched(vendor, req)

    /** The loaded scenario's rules, first match wins. Null when none matches or none is loaded. */
    private fun resolveScenarioRule(
        vendor: String,
        req: ParsedRequest,
    ): Resolution? {
        val s = scenario
        val idx = s?.rules?.indexOfFirst { it.match.matches(req) } ?: -1
        if (s == null || idx < 0) return null
        val rule = s.rules[idx]
        val occurrence = occurrences.computeIfAbsent(idx) { AtomicInteger(0) }.incrementAndGet()
        val step = rule.respond[(occurrence - 1).coerceAtMost(rule.respond.size - 1)]
        val n = seq.incrementAndGet()
        val outcome = if (step.error != null) "error:${step.error.status}" else "text"
        recordCall(log, n, vendor, req, rule.role, occurrence, outcome)
        return Resolution(seq = n, step = step, role = rule.role, occurrence = occurrence)
    }

    /** No scenario rule matched. One canned assistant reply, in whatever dialect [handle] renders. */
    private fun resolveGenericDefault(
        vendor: String,
        req: ParsedRequest,
    ): Resolution {
        val occurrence = genericDefaultOccurrences.incrementAndGet()
        val step = Step(text = CANNED_ASSISTANT_TEXT)
        val n = seq.incrementAndGet()
        recordCall(log, n, vendor, req, role = "DEFAULT", occurrence = occurrence, outcome = "default")
        return Resolution(seq = n, step = step, role = "DEFAULT", occurrence = occurrence)
    }

    /**
     * No scenario rule matched, and lenient mode is off. HTTP 400 with a JSON body, logged as
     * [UNMATCHED_ROLE]. The step is an error step with [Step.streamErrorAfterChunks] unset, so the
     * Anthropic streaming path sends that JSON and writes no SSE bytes.
     */
    private fun resolveUnmatched(
        vendor: String,
        req: ParsedRequest,
    ): Resolution {
        val loaded = scenario
        val why = if (loaded == null) NO_SCENARIO_WHY else noRuleWhy(loaded.name)
        val step = Step(error = ErrorStep(status = HTTP_BAD_REQUEST, body = unmatchedBody(vendor, req, why)))
        val n = seq.incrementAndGet()
        recordCall(log, n, vendor, req, role = UNMATCHED_ROLE, occurrence = 0, outcome = UNMATCHED_OUTCOME)
        return Resolution(seq = n, step = step, role = UNMATCHED_ROLE, occurrence = 0)
    }
}

private fun unmatchedBody(
    vendor: String,
    req: ParsedRequest,
    why: String,
): String =
    buildJsonObject {
        put("fake_llm_error", "unmatched request")
        put("why", why)
        put("vendor", vendor)
        put("model", req.model)
        put("system_shape", req.systemShape)
        put("message_count", req.messageCount)
        put("caller", req.caller)
        put("prompt_preview", req.promptText.take(PROMPT_PREVIEW_CHARS))
    }.toString()

private fun noRuleWhy(name: String): String = "no rule in scenario '$name' matches this request"

/** Appends one [CallRecord] to [log]. */
@Suppress("LongParameterList") // one flat log row; a wrapper type would only re-nest the same fields
private fun recordCall(
    log: MutableList<CallRecord>,
    n: Long,
    vendor: String,
    req: ParsedRequest,
    role: String,
    occurrence: Int,
    outcome: String,
) {
    log.add(
        CallRecord(
            seq = n,
            vendor = vendor,
            model = req.model,
            systemShape = req.systemShape,
            messageCount = req.messageCount,
            role = role,
            occurrence = occurrence,
            outcome = outcome,
            timestampMs = System.currentTimeMillis(),
            promptSample = req.promptText.takeLast(PROMPT_SAMPLE_CHARS),
        ),
    )
}

@Suppress("LongParameterList")
internal fun vendorEnvelope(
    vendor: String,
    text: String,
    stopReason: String,
    seq: Long,
    cacheable: Boolean = true,
    thinking: String? = null,
): String =
    when (vendor) {
        "openai" -> openAiEnvelope(text, stopReason, seq)
        "gemini" -> geminiEnvelope(text, stopReason, seq)
        else -> anthropicEnvelope(text, stopReason, seq, cacheable, thinking)
    }

private fun Match.matches(req: ParsedRequest): Boolean =
    (modelContains == null || req.model.contains(modelContains)) &&
        (systemShape == null || req.systemShape == systemShape) &&
        (caller == null || req.caller == caller) &&
        promptContainsAll.all { req.promptText.contains(it) } &&
        (promptContainsAny.isEmpty() || promptContainsAny.any { req.promptText.contains(it) })

internal const val HTTP_OK = 200
internal const val CANNED_ASSISTANT_TEXT = "This is a canned response from fake-llm."

private const val HTTP_BAD_REQUEST = 400
private const val UNMATCHED_ROLE = "UNMATCHED"
private const val UNMATCHED_OUTCOME = "unmatched"
private const val NO_SCENARIO_WHY = "no scenario loaded; POST /control/scenario first (or start with --lenient)"

/** Characters of prompt text kept, from the start, on an unmatched request's `prompt_preview`. */
private const val PROMPT_PREVIEW_CHARS = 200

/** Characters of prompt text kept, from the end, on [CallRecord.promptSample]. */
private const val PROMPT_SAMPLE_CHARS = 500
