package com.davidjonesgriz.fakellm

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Path

/** What the HTTP layer sends back for one faked vendor call. */
internal data class FakeResponse(
    val status: Int,
    val body: String,
    val headers: Map<String, String> = emptyMap(),
    val firstByteDelayMs: Long = 0,
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
    /** `anthropic`, `openai` (Chat Completions), `openai-responses` or `gemini`. */
    val vendor: String,
    val model: String,
    val systemShape: String,
    val messageCount: Int,
    /**
     * `"text"`, `"tool-calls"` (a step with [Step.toolCalls] served it), `"error:<status>"`,
     * `"default"`, `"no-scenario"`, or `"no-more-steps"`.
     */
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
    /** The tool calls the call's response carried, in order. Empty for any other response. */
    val toolCalls: List<ToolCallRecord> = emptyList(),
    /**
     * Every tool result in the request, in request order: Anthropic `tool_result` blocks, OpenAI
     * `tool` messages, OpenAI Responses `function_call_output` items, Gemini `functionResponse` parts.
     */
    val toolResults: List<ToolResultRecord> = emptyList(),
    /** The step's [Step.firstByteDelayMs]; 0 when no step served the call. */
    val firstByteDelayMs: Long = 0,
    /**
     * The step's [Step.streamInterChunkDelayMs] when the response was an event stream (a streamed
     * request, and a reply or an error with [Step.streamErrorAfterChunks]); 0 otherwise.
     */
    val streamInterChunkDelayMs: Long = 0,
)

/** One tool call a response carried, as [CallRecord.toolCalls] records it. */
@ConsistentCopyVisibility
@Serializable
public data class ToolCallRecord internal constructor(
    /** The id sent (the `call_id` on OpenAI Responses), or null for a Gemini call with no scripted id. */
    val id: String?,
    val name: String,
)

/** One tool result a request sent back, as [CallRecord.toolResults] records it. */
@ConsistentCopyVisibility
@Serializable
public data class ToolResultRecord internal constructor(
    /**
     * Anthropic `tool_use_id`, OpenAI `tool_call_id`, OpenAI Responses `call_id`, Gemini
     * `functionResponse.id`; null when absent or not a string.
     */
    val id: String?,
    /**
     * Gemini: `functionResponse.name`. Anthropic and OpenAI: the name of the tool call in the same
     * request whose id matches (the first such call), or null when none does. OpenAI Responses: the
     * name of the first `function_call` input item with the same `call_id`.
     */
    val name: String?,
    /**
     * The result as the request sent it, any JSON value: Anthropic `content`, OpenAI `content`,
     * OpenAI Responses `output`, Gemini `response`. Null when absent.
     */
    val content: JsonElement?,
    /** Anthropic `is_error` is `true`. Always false for OpenAI (both routes) and Gemini. */
    val isError: Boolean,
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
 * Next to the call log the engine keeps an exchange log ([exchanges]): for each call received over
 * HTTP, the request as received and the response as written. It is kept under the same lock and
 * emptied with the call log.
 *
 * Each engine has its own scenario, call counter, call log, exchange log and [scenarioDirectory], so
 * engines in the same JVM do not affect each other.
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
    private var exchangeLog = mutableListOf<Pair<Long, ExchangeCapture>>()

    /**
     * Loads the scenario [name] and installs it as [loadInline] does. Reads
     * `<scenarioDirectory>/<name>.json` when [scenarioDirectory] is set and that file exists, then
     * the project's classpath resource `/scenarios/<name>.json`, then the bundled scenario. Throws
     * when the name is not `[A-Za-z0-9_-]+`, no such scenario exists, the file does not decode, or
     * its declared name differs from [name]; the engine is then unchanged. Empties the call log and
     * the exchange log.
     */
    public fun load(name: String): Scenario = loadInline(ScenarioLoader.load(name, scenarioDirectory))

    /**
     * Installs [s] as the loaded scenario and starts it over, as [reset] does: empties the call log
     * and the exchange log. By-name [load] delegates here. `POST /control/scenario` with a
     * `scenario` object does too.
     */
    public fun loadInline(s: Scenario): Scenario {
        replace(s)
        return s
    }

    /**
     * Removes the loaded scenario and empties the call log and the exchange log; the next call is
     * call 1. Calls then get the strict-mode 400 or the lenient canned reply.
     */
    public fun unload() {
        replace(null)
    }

    /**
     * Starts the loaded scenario over: empties the call log and the exchange log, and the next call
     * is call 1 and gets step 1. Keeps the loaded scenario.
     */
    public fun reset() {
        synchronized(lock) { replace(scenario) }
    }

    private fun replace(s: Scenario?) {
        synchronized(lock) {
            scenario = s
            callCount = 0
            log = mutableListOf()
            exchangeLog = mutableListOf()
        }
    }

    /** The loaded scenario's name, or null when none is loaded. */
    public fun currentScenarioName(): String? = synchronized(lock) { scenario?.name }

    /** A snapshot of the call log, in call order. */
    public fun calls(): List<CallRecord> = synchronized(lock) { log.toList() }

    /**
     * A snapshot of the exchange log: for each call received over HTTP, in call order, the request
     * as received and the response as written so far, with the same `seq` values as [calls].
     */
    public fun exchanges(): List<ExchangeRecord> = synchronized(lock) { exchangeLog.toList() }.map { (seq, c) -> c.snapshot(seq) }

    /** Whole JSON response for one request. */
    internal fun handle(
        vendor: String,
        req: ParsedRequest,
        capture: ExchangeCapture? = null,
    ): FakeResponse {
        val r = resolve(vendor, req, capture)
        val step = r.step
        return if (step.error != null) {
            FakeResponse(step.error.status, step.error.body, step.error.headers, step.firstByteDelayMs)
        } else {
            FakeResponse(
                HTTP_OK,
                vendorEnvelope(vendor, step, r.seq, req.hasCacheBreakpoint),
                firstByteDelayMs = step.firstByteDelayMs,
            )
        }
    }

    /**
     * Numbers the call, picks what answers it, and records its one call-log entry, all under the
     * lock; the 400 bodies are built after it. Shared by the buffered [handle] and the Anthropic, OpenAI
     * Chat Completions, OpenAI Responses and Gemini SSE paths, so a request is counted and logged once
     * whichever transport renders it. The 400s are error steps with [Step.streamErrorAfterChunks]
     * unset, so every streaming path sends its JSON and writes no SSE bytes.
     *
     * Throws [IllegalArgumentException] when the next step has a tool call with
     * [ToolCall.rawArguments] that [vendor]'s reply can't carry (a buffered Anthropic or a Gemini
     * request). The request then takes no number and no log entry, and the step stays next.
     *
     * It returns before any wait the step sets, so a call is numbered and logged when it arrives.
     * [capture] is the request's exchange recorder; it joins the exchange log with the call's number.
     */
    internal fun resolve(
        vendor: String,
        req: ParsedRequest,
        capture: ExchangeCapture? = null,
    ): Resolution {
        val (n, loaded, served) =
            synchronized(lock) {
                val s = scenario
                rawArgumentsProblem(vendor, req, s?.steps?.getOrNull(callCount.toInt()), callCount + 1)?.let {
                    throw IllegalArgumentException(it)
                }
                val n = ++callCount
                val step = s?.steps?.takeIf { n <= it.size }?.get((n - 1).toInt())
                val outcome =
                    when {
                        step == null -> {
                            if (s != null) {
                                "no-more-steps"
                            } else if (lenient) {
                                "default"
                            } else {
                                "no-scenario"
                            }
                        }

                        step.error != null -> {
                            "error:${step.error.status}"
                        }

                        step.toolCalls != null -> {
                            "tool-calls"
                        }

                        else -> {
                            "text"
                        }
                    }
                log.add(callRecord(n, vendor, req, outcome, step))
                capture?.let { exchangeLog.add(n to it) }
                Triple(n, s, step)
            }
        val step =
            served ?: when {
                loaded != null -> errorStep(noMoreStepsBody(loaded, n, vendor, req))
                lenient -> Step(text = CANNED_ASSISTANT_TEXT)
                else -> errorStep(noScenarioBody(n, vendor, req))
            }
        return Resolution(seq = n, step = step)
    }
}

/**
 * Why [step], the next step, can't answer a [vendor] request: one of its tool calls sets
 * [ToolCall.rawArguments], which a buffered Anthropic `tool_use.input` and a Gemini
 * `functionCall.args` can't carry, since both must be JSON objects. Null when it can.
 */
private fun rawArgumentsProblem(
    vendor: String,
    req: ParsedRequest,
    step: Step?,
    stepNumber: Long,
): String? {
    val i = step?.toolCalls?.indexOfFirst { it.rawArguments != null } ?: -1
    val prefix = "step $stepNumber: tool call ${i + 1} sets rawArguments"
    return when {
        i < 0 -> {
            null
        }

        vendor == "gemini" -> {
            "$prefix, which a Gemini reply cannot carry; functionCall.args must be a JSON object"
        }

        vendor == "anthropic" && !req.stream -> {
            "$prefix, which an Anthropic reply carries only when streamed (input_json_delta); " +
                "a buffered tool_use input must be a JSON object"
        }

        else -> {
            null
        }
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

/** The log entry for call [n], served by [step] (null when no step served it). */
private fun callRecord(
    n: Long,
    vendor: String,
    req: ParsedRequest,
    outcome: String,
    step: Step?,
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
        step = if (step != null) n.toInt() else null,
        toolCalls = step?.let { sentToolCalls(vendor, it, n) }.orEmpty().map { ToolCallRecord(it.id, it.name) },
        toolResults = req.toolResults,
        firstByteDelayMs = step?.firstByteDelayMs ?: 0,
        streamInterChunkDelayMs = step?.let { appliedInterChunkDelayMs(it, req.stream) } ?: 0,
    )

/** The buffered reply to call [seq] from [step], a reply step, in [vendor]'s format. */
internal fun vendorEnvelope(
    vendor: String,
    step: Step,
    seq: Long,
    cacheable: Boolean = true,
): String {
    val toolCalls = sentToolCalls(vendor, step, seq)
    return when (vendor) {
        "openai" -> openAiEnvelope(step.text, step.stopReason, seq, toolCalls)
        RESPONSES_VENDOR -> responsesEnvelope(step.text, step.stopReason, seq, toolCalls)
        "gemini" -> geminiEnvelope(step.text, step.stopReason, seq, toolCalls)
        else -> anthropicEnvelope(step.text, step.stopReason, seq, cacheable, step.thinking, toolCalls)
    }
}

internal const val HTTP_OK = 200
internal const val CANNED_ASSISTANT_TEXT = "This is a canned response from fake-llm."

private const val HTTP_BAD_REQUEST = 400

/** Characters of prompt text kept, from the start, on a 400 body's `prompt_preview`. */
private const val PROMPT_PREVIEW_CHARS = 200

/** Characters of prompt text kept, from the end, on [CallRecord.promptSample]. */
private const val PROMPT_SAMPLE_CHARS = 500
