package com.davidjonesgriz.fakellm

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
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
    /** The call's number since the last load, reset or unload, from 1. */
    val seq: Long,
    /** `anthropic`, `openai` (Chat Completions), `openai-responses` or `gemini`. */
    val vendor: String,
    val model: String,
    val systemShape: String,
    val messageCount: Int,
    /**
     * `"text"`, `"tool-calls"` (a step with [Step.toolCalls] served it), `"error:<status>"`,
     * `"default"`, `"no-scenario"`, `"no-more-steps"`, or `"no-match"` (the call matched none of the
     * steps it could have taken).
     */
    val outcome: String,
    val timestampMs: Long,
    /**
     * The last [PROMPT_SAMPLE_CHARS] characters of the parsed prompt. The log keeps a bounded
     * suffix of the prompt instead of the full text.
     */
    val promptSample: String,
    /**
     * The 1-based step that served the call; for a scenario with no `match`, no parallel step and no
     * `times`, equal to [seq] whenever it is not null. A parallel step's number when one of its steps served
     * the call (see [innerStep]). Null when no step served it: [outcome] is `"default"`,
     * `"no-scenario"`, `"no-more-steps"` or `"no-match"`.
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
    /**
     * The 1-based step within the parallel [step] that served the call; null for a plain step and
     * when no step served it. Left out of the JSON when null, so a scenario with no parallel step
     * writes the same call log as before.
     */
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val innerStep: Int? = null,
    /**
     * Which call of a step with [Step.times] this was, from 1. Null for a step without `times` and
     * when no step served the call. Left out of the JSON when null, so a scenario without `times`
     * writes the same call log as before.
     */
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val useNumber: Int? = null,
    /**
     * The `metadata.name` of the step that served the call: the step within the parallel step for
     * a call [innerStep] names. Null when that step has no name and when no step served the call.
     * Left out of the JSON when null, so a scenario without step names writes the same call log as before.
     */
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val stepName: String? = null,
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

/** Where the loaded scenario came from: a load by name, or an inline load. [json] is how `GET /health` writes it. */
internal enum class ScenarioSource(
    val json: String,
) {
    FILE("file"),
    INLINE("inline"),
}

/**
 * The loaded scenario's [key], [displayName] and [source], its step count, the steps no call has
 * used and the names of those that have one, read together.
 */
internal data class ScenarioProgress(
    val key: String?,
    val displayName: String?,
    val steps: Int?,
    val unusedSteps: List<Int>,
    val unusedStepNames: Map<String, String>,
    val source: ScenarioSource? = null,
)

/** One logged call and the [Step] that answers it. See [ScenarioEngine.resolve]. */
internal data class Resolution(
    val seq: Long,
    val step: Step,
)

/**
 * Serves the loaded scenario's steps in call order and records each call in the call log.
 *
 * After [load], [loadInline], [reset] or [unload], the next call is call 1. In a scenario with no
 * `match` and no parallel step, call N gets step N. A step with a [Step.match] answers only a call
 * that satisfies it; a parallel step's steps answer calls in any order, each call taking the first
 * unused one it matches, and the scenario moves past it once all are used. A call that matches none
 * of the steps it could take gets HTTP 400 (`no-match`). A step with [Step.times] takes from its
 * minimum to its maximum number of calls (see [Position]). A call after the last step gets HTTP 400
 * (`no-more-steps`), in strict and lenient mode. With no scenario loaded, a call gets HTTP 400 (`no-scenario`), or the
 * canned reply when [lenient] is true. Every call takes a number and one log entry, whatever it is
 * answered with.
 *
 * One lock covers the scenario, its position, the call counter and the log: a call reads the
 * scenario, takes its step and number and appends its entry under it, and [load], [reset] and
 * [unload] replace all four under it. So the log is always in call order, and a call that overlaps a reset belongs entirely to the
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

    /** What identifies [scenario]: the name it was loaded by, or for an inline load its [Scenario.inlineKey]. */
    private var scenarioKey: String? = null
    private var scenarioSource: ScenarioSource? = null
    private var callCount = 0L

    /** Where the loaded scenario has got to. */
    private var position = Position()
    private var log = mutableListOf<CallRecord>()
    private var exchangeLog = mutableListOf<Pair<Long, ExchangeCapture>>()

    /**
     * Called with each call's record after it is logged, outside the lock, on the thread serving the
     * call. Set by the CLI to print the call log line; null (the library default) does nothing.
     */
    @Volatile
    internal var onCall: ((CallRecord) -> Unit)? = null

    /**
     * Loads the scenario [name] and installs it as [loadInline] does. Reads
     * `<scenarioDirectory>/<name>.json` when [scenarioDirectory] is set and that file exists, then
     * the project's classpath resource `/scenarios/<name>.json`, then the bundled scenario. Throws
     * when the name is not `[A-Za-z0-9_-]+`, no such scenario exists, or the file does not decode;
     * the engine is then unchanged. The scenario's `metadata.name` may differ from [name], which
     * stays its key ([currentScenarioKey]). Empties the call log and the exchange log.
     */
    public fun load(name: String): Scenario = ScenarioLoader.load(name, scenarioDirectory).also { replace(it, name, ScenarioSource.FILE) }

    /**
     * Installs [s] as the loaded scenario and starts it over, as [reset] does: empties the call log
     * and the exchange log. `POST /control/scenario` with a `scenario` object calls it. Its key
     * ([currentScenarioKey]) is its `metadata.name`, or `inline` when it has none.
     */
    public fun loadInline(s: Scenario): Scenario {
        replace(s, s.inlineKey, ScenarioSource.INLINE)
        return s
    }

    /**
     * Removes the loaded scenario and empties the call log and the exchange log; the next call is
     * call 1. Calls then get the strict-mode 400 or the lenient canned reply. `POST /control/unload`
     * and [FakeLlmClient.unload] call it over HTTP.
     */
    public fun unload() {
        replace(null, null, null)
    }

    /**
     * Starts the loaded scenario over: empties the call log and the exchange log, and the next call
     * is call 1 and gets step 1. Keeps the loaded scenario.
     */
    public fun reset() {
        synchronized(lock) { replace(scenario, scenarioKey, scenarioSource) }
    }

    private fun replace(
        s: Scenario?,
        key: String?,
        source: ScenarioSource?,
    ) {
        synchronized(lock) {
            scenario = s
            scenarioKey = key
            scenarioSource = source
            callCount = 0
            position = Position()
            log = mutableListOf()
            exchangeLog = mutableListOf()
        }
    }

    /**
     * What identifies the loaded scenario, or null when none is loaded: the name [load] was given
     * (the file name), or for [loadInline] the scenario's `metadata.name`, or `inline` when it has none.
     */
    public fun currentScenarioKey(): String? = synchronized(lock) { scenarioKey }

    /**
     * The 1-based numbers of the loaded scenario's steps that are not used up since the last load,
     * reset or unload, in order: a snapshot, read under the same lock as [calls]. A parallel step is
     * used up once every one of its steps is, so a partly used one is listed, and a step with
     * [Step.times] once it has its minimum. In a scenario with no `match`, no parallel step and no
     * `times` these are always the last steps. Empty when no scenario is loaded
     * or every step has served a call. From Java, `getUnusedSteps()`.
     */
    public val unusedSteps: List<Int>
        get() = progress.unusedSteps

    /**
     * The `metadata.name` of each unused step that has one, read under the same lock, keyed by its
     * label: `"2"` for step 2 of [unusedSteps], and `"2.1"` for step 1 of the parallel step 2 when
     * that step is below its minimum. A step without a name has no entry. In step order. From Java,
     * `getUnusedStepNames()`.
     */
    public val unusedStepNames: Map<String, String>
        get() = progress.unusedStepNames

    /** [currentScenarioKey], the display name, the step count, [unusedSteps] and their names, read under one lock. */
    internal val progress: ScenarioProgress
        get() =
            synchronized(lock) {
                val s = scenario
                val unused = s?.let { position.unusedCandidates(it) }.orEmpty()
                ScenarioProgress(
                    key = scenarioKey,
                    displayName = s?.let { it.metadata?.name ?: scenarioKey },
                    steps = s?.steps?.size,
                    unusedSteps = unused.map { it.top + 1 }.distinct(),
                    unusedStepNames = s?.let { unusedNames(it, unused) }.orEmpty(),
                    source = scenarioSource,
                )
            }

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
                vendorEnvelope(vendor, step, r.seq, req.model, req.hasCacheBreakpoint),
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
     * The call takes the first open step in reach whose match it satisfies: the plain step at the
     * scenario's position, the steps of the parallel step or overlap group there, then, once their
     * minimums are met, the steps after them (see [Position]). Closing every step at the position
     * moves the position past them.
     *
     * Throws [IllegalArgumentException] when the chosen step has a tool call with
     * [ToolCall.rawArguments] that [vendor]'s reply can't carry (a buffered Anthropic or a Gemini
     * request). The request then takes no number and no log entry, and the step stays unused.
     *
     * It returns before any wait the step sets, so a call is numbered and logged when it arrives.
     * [capture] is the request's exchange recorder; it joins the exchange log with the call's number.
     */
    internal fun resolve(
        vendor: String,
        req: ParsedRequest,
        capture: ExchangeCapture? = null,
    ): Resolution {
        var logged: CallRecord? = null
        val served =
            synchronized(lock) {
                val s = scenario
                val choice = position.choose(s, vendor, req)
                choice.chosen?.let { chosen ->
                    rawArgumentsProblem(
                        vendor,
                        req,
                        chosen.step,
                        chosen.label,
                    )?.let { throw RequestException(ErrorCode.UNSUPPORTED_STEP, it) }
                }
                val n = ++callCount
                val taken = choice.chosen?.let { position.take(s!!, it) }
                val record = callRecord(n, vendor, req, outcomeOf(choice, s, lenient), taken)
                log.add(record)
                logged = record
                capture?.let { exchangeLog.add(n to it) }
                Served(n, s, scenarioKey, choice)
            }
        logged?.let { record -> onCall?.invoke(record) }
        return Resolution(seq = served.seq, step = served.choice.chosen?.step ?: unservedStep(served, vendor, req, lenient))
    }
}

/**
 * The names of [unused], the steps of [s] below their minimum, by label: each parallel or plain
 * step's own name under its number, then each named step of a parallel step under `N.M`.
 */
private fun unusedNames(
    s: Scenario,
    unused: List<Candidate>,
): Map<String, String> =
    buildMap {
        unused.forEach { c ->
            s.steps[c.top]
                .metadata
                ?.name
                ?.let { putIfAbsent("${c.top + 1}", it) }
            if (c.inner != null) {
                c.step.metadata
                    ?.name
                    ?.let { put("${c.top + 1}.${c.inner + 1}", it) }
            }
        }
    }

/** What [ScenarioEngine.resolve] decided under the lock, for building the response after it. */
internal data class Served(
    val seq: Long,
    val scenario: Scenario?,
    /** [scenario]'s key; see [ScenarioEngine.currentScenarioKey]. */
    val scenarioKey: String?,
    val choice: Choice,
)

/**
 * Why [step], the step a call chose, can't answer a [vendor] request: one of its tool calls sets
 * [ToolCall.rawArguments], which a buffered Anthropic `tool_use.input` and a Gemini
 * `functionCall.args` can't carry, since both must be JSON objects. Null when it can. [label] names
 * the step, `step N` or `step N.M`.
 */
private fun rawArgumentsProblem(
    vendor: String,
    req: ParsedRequest,
    step: Step,
    label: String,
): String? {
    val i = step.toolCalls?.indexOfFirst { it.rawArguments != null } ?: -1
    val prefix = "$label: tool call ${i + 1} sets rawArguments"
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

/** The log entry for call [n], served by [taken]'s step (null when no step served it). */
private fun callRecord(
    n: Long,
    vendor: String,
    req: ParsedRequest,
    outcome: String,
    taken: Taken?,
): CallRecord {
    val chosen = taken?.candidate
    val step = chosen?.step
    return CallRecord(
        seq = n,
        vendor = vendor,
        model = req.model,
        systemShape = req.systemShape,
        messageCount = req.messageCount,
        outcome = outcome,
        timestampMs = System.currentTimeMillis(),
        promptSample = req.promptText.takeLast(PROMPT_SAMPLE_CHARS),
        step = chosen?.top?.plus(1),
        toolCalls = step?.let { sentToolCalls(vendor, it, n) }.orEmpty().map { ToolCallRecord(it.id, it.name) },
        toolResults = req.toolResults,
        firstByteDelayMs = step?.firstByteDelayMs ?: 0,
        streamInterChunkDelayMs = step?.let { appliedInterChunkDelayMs(it, req.stream) } ?: 0,
        innerStep = chosen?.inner?.plus(1),
        useNumber = taken?.useNumber,
        stepName = step?.metadata?.name,
    )
}

/** The buffered reply to call [seq] from [step], a reply step, in [vendor]'s format, naming [model], the request's model. */
internal fun vendorEnvelope(
    vendor: String,
    step: Step,
    seq: Long,
    model: String,
    cacheable: Boolean = true,
): String {
    val toolCalls = sentToolCalls(vendor, step, seq)
    return when (vendor) {
        "openai" -> openAiEnvelope(step, seq, model, toolCalls)
        RESPONSES_VENDOR -> responsesEnvelope(step, seq, model, toolCalls)
        "gemini" -> geminiEnvelope(step, seq, model, toolCalls)
        else -> anthropicEnvelope(step, seq, model, cacheable, toolCalls)
    }
}

internal const val HTTP_OK = 200
internal const val CANNED_ASSISTANT_TEXT = "This is a canned response from fake-llm."

/** Characters of prompt text kept, from the end, on [CallRecord.promptSample]. */
private const val PROMPT_SAMPLE_CHARS = 500
