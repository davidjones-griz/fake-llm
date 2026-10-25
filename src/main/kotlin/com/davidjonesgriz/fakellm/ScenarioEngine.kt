package com.davidjonesgriz.fakellm

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Path

/** What the HTTP layer sends back for one faked vendor call. */
internal data class FakeResponse(
    val status: Int,
    val body: String,
    val headers: Map<String, String> = emptyMap(),
)

/**
 * One call the fake received. [ScenarioEngine.calls] and `GET /control/calls` return these, in
 * [seq] order. Records are read-only for library users: the constructor and `copy` are internal,
 * so fields added with default values do not break callers.
 */
@ConsistentCopyVisibility
@Serializable
public data class CallRecord internal constructor(
    /** The call's number since the last load or reset, from 1. */
    val seq: Long,
    val vendor: String,
    val model: String,
    val systemShape: String,
    val messageCount: Int,
    /** `"text"`, `"error:<status>"`, `"default"`, `"no-scenario"`, or `"no-more-steps"`. */
    val outcome: String,
    val timestampMs: Long,
    /**
     * The last [PROMPT_SAMPLE_CHARS] characters of the parsed prompt. The log keeps a bounded
     * suffix of the prompt instead of the full text.
     */
    val promptSample: String,
    /**
     * The 1-based step that served the call, equal to [seq] whenever it is not null. Null when no
     * step served it: [outcome] is `"default"`, `"no-scenario"` or `"no-more-steps"`.
     */
    val step: Int? = null,
)

/** One logged call and the [Step] that answers it. See [ScenarioEngine.resolve]. */
internal data class Resolution(
    val seq: Long,
    val step: Step,
)

/**
 * Serves the loaded scenario's steps in call order and records each call in the call log.
 *
 * After [load], [loadInline], [reset] or [unload], the next call is call 1. Call N gets step N of
 * the loaded scenario. A call after the last step gets HTTP 400 (`"no more steps"`), in strict and
 * lenient mode. With no scenario loaded, a call gets HTTP 400 (`"no scenario loaded"`), or the
 * canned reply when [lenient] is true. Every call takes a number and one log entry, whatever it is
 * answered with.
 *
 * One lock covers the scenario, the call counter and the log: a call reads the scenario, takes its
 * number and appends its entry under it, and [load], [reset] and [unload] replace all three under
 * it. So the log is always in call order, and a call that overlaps a reset belongs entirely to the
 * run before it or the run after it.
 *
 * Each engine has its own scenario, call counter, call log and [scenarioDirectory], so engines in
 * the same JVM do not affect each other.
 */
public class ScenarioEngine(
    /**
     * A call made while no scenario is loaded gets the canned reply when true, and HTTP 400 when
     * false. It does not change what a call after the last step gets.
     */
    public val lenient: Boolean = false,
    /**
     * Searched first when [load] reads a scenario by name, as `<scenarioDirectory>/<name>.json`,
     * before the project's classpath scenarios and the bundled ones. Null skips this step. A
     * relative path resolves against the JVM's working directory. `GET /control/scenarios` lists
     * the `*.json` files in it.
     */
    public val scenarioDirectory: Path? = null,
) {
    /** Creates an engine with no scenario directory; for Java, `new ScenarioEngine(lenient)`. */
    public constructor(lenient: Boolean) : this(lenient, null)

    private val lock = Any()
    private var scenario: Scenario? = null
    private var callCount = 0L
    private var log = mutableListOf<CallRecord>()

    /**
     * Loads the scenario [name] and installs it as [loadInline] does. Reads
     * `<scenarioDirectory>/<name>.json` when [scenarioDirectory] is set and that file exists, then
     * the project's classpath resource `/scenarios/<name>.json`, then the bundled scenario. Throws
     * when the name is not `[A-Za-z0-9_-]+`, no such scenario exists, the file does not decode, or
     * its declared name differs from [name]; the engine is then unchanged.
     */
    public fun load(name: String): Scenario = loadInline(ScenarioLoader.load(name, scenarioDirectory))

    /**
     * Installs [s] as the loaded scenario and starts it over, as [reset] does.
     * By-name [load] delegates here. `POST /control/scenario` with a `scenario` object does too.
     */
    public fun loadInline(s: Scenario): Scenario {
        replace(s)
        return s
    }

    /**
     * Removes the loaded scenario and empties the call log; the next call is call 1. Calls then get
     * the strict-mode 400 or the lenient canned reply.
     */
    public fun unload() {
        replace(null)
    }

    /**
     * Starts the loaded scenario over: empties the call log, and the next call is call 1 and gets
     * step 1. Keeps the loaded scenario.
     */
    public fun reset() {
        synchronized(lock) { replace(scenario) }
    }

    private fun replace(s: Scenario?) {
        synchronized(lock) {
            scenario = s
            callCount = 0
            log = mutableListOf()
        }
    }

    /** The loaded scenario's name, or null when none is loaded. */
    public fun currentScenarioName(): String? = synchronized(lock) { scenario?.name }

    /** A snapshot of the call log, in call order. */
    public fun calls(): List<CallRecord> = synchronized(lock) { log.toList() }

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
     * Numbers the call, picks what answers it, and records its one call-log entry, all under the
     * lock. Shared by the buffered [handle] and the SSE path in FakeLlmApp.kt, so a request is
     * counted and logged once whichever transport renders it. The 400s are error steps with
     * [Step.streamErrorAfterChunks] unset, so the Anthropic streaming path sends their JSON and
     * writes no SSE bytes.
     */
    internal fun resolve(
        vendor: String,
        req: ParsedRequest,
    ): Resolution =
        synchronized(lock) {
            val n = ++callCount
            val s = scenario
            val index = (n - 1).toInt()
            val (step, served, outcome) =
                when {
                    s != null && n <= s.steps.size -> {
                        val step = s.steps[index]
                        Triple(step, n.toInt(), if (step.error != null) "error:${step.error.status}" else "text")
                    }

                    s != null -> {
                        Triple(errorStep(noMoreStepsBody(s, n, vendor, req)), null, "no-more-steps")
                    }

                    lenient -> {
                        Triple(Step(text = CANNED_ASSISTANT_TEXT), null, "default")
                    }

                    else -> {
                        Triple(errorStep(noScenarioBody(n, vendor, req)), null, "no-scenario")
                    }
                }
            log.add(callRecord(n, vendor, req, outcome, served))
            Resolution(seq = n, step = step)
        }
}

private fun errorStep(body: String): Step = Step(error = ErrorStep(status = HTTP_BAD_REQUEST, body = body))

private fun noScenarioBody(
    n: Long,
    vendor: String,
    req: ParsedRequest,
): String =
    buildJsonObject {
        put("fake_llm_error", "no scenario loaded")
        putCallFields(n, vendor, req)
    }.toString()

private fun noMoreStepsBody(
    s: Scenario,
    n: Long,
    vendor: String,
    req: ParsedRequest,
): String =
    buildJsonObject {
        put("fake_llm_error", "no more steps")
        put("scenario", s.name)
        put("steps", s.steps.size)
        putCallFields(n, vendor, req)
    }.toString()

private fun JsonObjectBuilder.putCallFields(
    n: Long,
    vendor: String,
    req: ParsedRequest,
) {
    put("seq", n)
    put("vendor", vendor)
    put("model", req.model)
    put("system_shape", req.systemShape)
    put("message_count", req.messageCount)
    put("prompt_preview", req.promptText.take(PROMPT_PREVIEW_CHARS))
}

private fun callRecord(
    n: Long,
    vendor: String,
    req: ParsedRequest,
    outcome: String,
    step: Int?,
): CallRecord =
    CallRecord(
        seq = n,
        vendor = vendor,
        model = req.model,
        systemShape = req.systemShape,
        messageCount = req.messageCount,
        outcome = outcome,
        timestampMs = System.currentTimeMillis(),
        promptSample = req.promptText.takeLast(PROMPT_SAMPLE_CHARS),
        step = step,
    )

@Suppress("LongParameterList") // the vendor-neutral reply fields, passed through to one dialect
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

internal const val HTTP_OK = 200
internal const val CANNED_ASSISTANT_TEXT = "This is a canned response from fake-llm."

private const val HTTP_BAD_REQUEST = 400

/** Characters of prompt text kept, from the start, on a 400 body's `prompt_preview`. */
private const val PROMPT_PREVIEW_CHARS = 200

/** Characters of prompt text kept, from the end, on [CallRecord.promptSample]. */
private const val PROMPT_SAMPLE_CHARS = 500
