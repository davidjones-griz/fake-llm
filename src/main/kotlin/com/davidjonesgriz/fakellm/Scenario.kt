package com.davidjonesgriz.fakellm

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import java.io.File
import java.nio.file.Path

/**
 * One named behavior of the fake: an ordered list of [steps]. After [ScenarioEngine.load] or
 * [ScenarioEngine.reset], call N on any vendor route gets step N, in arrival order. A call after the
 * last step gets HTTP 400 (`"no more steps"`); the last step does not repeat.
 *
 * Scenario files are JSON in this shape. [ScenarioEngine.load] reads one by name from the engine's
 * [ScenarioEngine.scenarioDirectory], the project's classpath resource `/scenarios/<name>.json`, or
 * the bundled scenarios, in that order, and [ScenarioEngine.loadInline] takes one built in code.
 *
 * @throws IllegalArgumentException when [steps] is empty. The message text is diagnostic, not part
 *   of the API.
 */
@Serializable
public data class Scenario(
    val name: String,
    val description: String = "",
    val steps: List<Step>,
) {
    init {
        require(steps.isNotEmpty()) { "scenario '$name' has no steps" }
    }

    public companion object {
        /**
         * Decodes [text], one scenario in the scenario file format, with the checks and messages of
         * an inline load (`POST /control/scenario` with `{"scenario": …}`): an unknown field is an
         * error, a top-level `$schema` string is ignored, and a failing step is named by its 1-based
         * number. Unlike a load by name, the scenario's `name` need not match a file name. From
         * Java, `Scenario.fromJson(text)`.
         *
         * @throws IllegalArgumentException when [text] is not JSON or not a valid scenario. The
         *   message text is diagnostic, not part of the API.
         */
        @JvmStatic
        public fun fromJson(text: String): Scenario = ScenarioLoader.decode(ScenarioShape.parse(Json, text))
    }
}

/**
 * One step of a [Scenario]: a reply ([text], [toolCalls] or both, optionally with [thinking] and
 * [usage]) or a scripted [error].
 *
 * @throws IllegalArgumentException when a value is invalid, including when a step sets both or
 *   neither of a reply and [error]. The message text is diagnostic, not part of the API.
 */
@Serializable
public data class Step(
    /** Assistant text copied into the vendor response. */
    val text: String? = null,
    val thinking: String? = null,
    /**
     * `stop_reason` for a reply. `"max_tokens"` reports that the reply was truncated. On a step with
     * [toolCalls] it must be `end_turn` or `tool_use`, and every format reports a tool-call stop.
     */
    val stopReason: String = "end_turn",
    /** A scripted vendor error in place of a reply. The status classifies it; the body is sent as given. */
    val error: ErrorStep? = null,
    /**
     * SSE streaming (Anthropic, OpenAI Chat Completions and Responses, and Gemini): delta size in
     * Unicode code points, not UTF-16 chars. A boundary inside a surrogate pair would encode that
     * half as its own UTF-8 frame and replace it with U+FFFD, so the chunks would no longer
     * reassemble to the original text. Consulted only when the request is streamed; a buffered
     * request ignores it.
     */
    val streamChunkChars: Int = DEFAULT_STREAM_CHUNK_CHARS,
    /**
     * Milliseconds to wait between SSE deltas (Anthropic, OpenAI Chat Completions and Responses, and
     * Gemini), for injecting latency. `0` waits nothing, so the default stays immediate. Streaming
     * only; see [streamChunkChars]. At most [MAX_DELAY_MS].
     */
    val streamInterChunkDelayMs: Long = 0,
    /**
     * Streaming only (Anthropic, OpenAI Chat Completions and Responses, and Gemini), and only valid on
     * an error step ([error] set):
     * emit this many text deltas, then an in-stream error. On the Anthropic stream that is an
     * `event: error` frame, with no `content_block_stop`, `message_delta` or `message_stop`; on the
     * OpenAI stream it is a `data:` frame with the error body, with no finish chunk, usage chunk
     * or `[DONE]`; on the OpenAI Responses stream it is an `event: error` frame with the error body,
     * or the API's own error event when the body is blank; on the Gemini stream it is a `data:`
     * frame with the error body, with no chunk carrying `finishReason`.
     *
     * Null (the default): the step's error is a plain HTTP response, and no SSE bytes are written.
     * That matches a provider that rejects before generation starts — the usual timing for
     * rate-limit and overload errors. This field is the opt-in for a failure after some text has
     * already been generated.
     */
    val streamErrorAfterChunks: Int? = null,
    /** Tool calls the reply makes, in order, after any `text`. */
    val toolCalls: List<ToolCall>? = null,
    /**
     * Milliseconds to wait before any response bytes, status and headers included, on every vendor
     * route: a buffered or streamed reply, and an error step. The call is numbered and logged when it
     * arrives, before the wait. `0` waits nothing. At most [MAX_DELAY_MS].
     */
    val firstByteDelayMs: Long = 0,
    /**
     * The token counts this reply reports. Null, the default, reports 100 input and 50 output
     * tokens. Only on a reply step. Totals are the sum. The Anthropic cache figures do not change.
     */
    val usage: TokenUsage? = null,
) {
    init {
        require(((text != null) || (toolCalls != null)) xor (error != null)) {
            "a step must set either error or a reply: text, toolCalls or both"
        }
        require(toolCalls == null || toolCalls.isNotEmpty()) { "toolCalls must not be empty" }
        require(toolCalls == null || stopReason == "end_turn" || stopReason == "tool_use") {
            "stopReason must be end_turn or tool_use on a step with toolCalls, got '$stopReason'"
        }
        require(streamChunkChars > 0) { "streamChunkChars must be positive, got $streamChunkChars" }
        require(streamInterChunkDelayMs in 0..MAX_DELAY_MS) {
            "streamInterChunkDelayMs must be from 0 to $MAX_DELAY_MS (10 minutes), got $streamInterChunkDelayMs"
        }
        require(firstByteDelayMs in 0..MAX_DELAY_MS) {
            "firstByteDelayMs must be from 0 to $MAX_DELAY_MS (10 minutes), got $firstByteDelayMs"
        }
        require(streamErrorAfterChunks == null || error != null) {
            "streamErrorAfterChunks is only valid on an error-bearing step"
        }
        require(streamErrorAfterChunks == null || streamErrorAfterChunks > 0) {
            "streamErrorAfterChunks must be positive, got $streamErrorAfterChunks"
        }
        require(thinking == null || text != null || toolCalls != null) {
            "thinking is only valid on a step with text or toolCalls"
        }
        require(usage == null || text != null || toolCalls != null) {
            "usage is only valid on a step with text or toolCalls"
        }
    }
}

/**
 * One scripted tool call in a [Step]'s reply.
 *
 * @throws IllegalArgumentException when [name] or [id] is empty, or when both [arguments] and
 *   [rawArguments] are set. The message text is diagnostic, not part of the API.
 */
@Serializable
public data class ToolCall(
    /** The tool's name, sent as given: it need not match a tool the request declared. */
    val name: String,
    /** The arguments as a JSON object. Null, with rawArguments null too, means `{}`. */
    val arguments: JsonObject? = null,
    /**
     * The argument text sent verbatim, for invalid or non-object JSON. Only formats that carry text:
     * OpenAI Chat Completions and Responses, and a streamed Anthropic reply.
     */
    val rawArguments: String? = null,
    /** The call's id. Null uses the format's default (see Rendering), which is also the OpenAI Responses `call_id`. */
    val id: String? = null,
) {
    init {
        require(name.isNotEmpty()) { "a tool call's name must not be empty" }
        require(id == null || id.isNotEmpty()) { "a tool call's id must not be empty" }
        require(arguments == null || rawArguments == null) { "a tool call sets at most one of arguments and rawArguments" }
    }
}

/**
 * Token counts a reply step reports, in every vendor's usage fields. An unset value keeps the
 * default: 100 input tokens and 50 output tokens. Totals are the sum.
 *
 * @throws IllegalArgumentException when a count is negative. The message text is diagnostic, not
 *   part of the API.
 */
@Serializable
public data class TokenUsage(
    /** Prompt tokens: Anthropic and Responses `input_tokens`, OpenAI `prompt_tokens`, Gemini `promptTokenCount`. */
    val inputTokens: Int = FIXED_INPUT_TOKENS,
    /** Reply tokens: Anthropic and Responses `output_tokens`, OpenAI `completion_tokens`, Gemini `candidatesTokenCount`. */
    val outputTokens: Int = FIXED_OUTPUT_TOKENS,
) {
    init {
        require(inputTokens >= 0) { "usage.inputTokens must not be negative, got $inputTokens" }
        require(outputTokens >= 0) { "usage.outputTokens must not be negative, got $outputTokens" }
    }

    /** Input plus output, as a Long so the sum of two large counts does not overflow. */
    internal val totalTokens: Long get() = inputTokens.toLong() + outputTokens
}

/** The counts a reply from this step reports: [Step.usage], or the defaults. */
internal val Step.appliedUsage: TokenUsage get() = usage ?: DEFAULT_USAGE

/** The counts a step without `usage` reports, and the builders' default. */
internal val DEFAULT_USAGE = TokenUsage()

/** The argument text a format that carries text sends: [ToolCall.rawArguments], or the compact JSON of the arguments. */
internal fun ToolCall.argumentText(): String = rawArguments ?: (arguments ?: JsonObject(emptyMap())).toString()

private const val DEFAULT_STREAM_CHUNK_CHARS = 24

/**
 * A scripted HTTP error in place of a reply: the [status], the [body], and extra [headers].
 *
 * @throws IllegalArgumentException when [status] is not an HTTP status (100 to 599). The message
 *   text is diagnostic, not part of the API.
 */
@Serializable
public data class ErrorStep(
    val status: Int,
    val body: String = "",
    /** Extra response headers, for example `retry-after`. Sent as given. */
    val headers: Map<String, String> = emptyMap(),
) {
    init {
        require(status in HTTP_STATUS_RANGE) { "status must be an HTTP status (100 to 599), got $status" }
    }
}

private const val MIN_HTTP_STATUS = 100
private const val MAX_HTTP_STATUS = 599
private val HTTP_STATUS_RANGE = MIN_HTTP_STATUS..MAX_HTTP_STATUS

/**
 * Loads named scenarios. An unknown name is a hard error. Holds no state: each call takes the
 * directory of the [ScenarioEngine] it serves.
 *
 * A name is looked up in this order, and the first match wins:
 * 1. `<directory>/<name>.json`, when a directory is given;
 * 2. the project's classpath resource `/scenarios/<name>.json` (the first on the classpath);
 * 3. the bundled scenario, kept under fake-llm's own resource path, so a project's index no
 *    longer hides the bundled list. A classpath scenario with a bundled name still replaces it.
 *
 * [projectLoader] finds the project's resources; tests pass their own. The bundled files are always
 * read through this class.
 *
 * Both [load] and [decode] name the 1-based number of a step that fails to decode (`step 2: …`).
 */
internal object ScenarioLoader {
    private val json = Json { ignoreUnknownKeys = false }

    fun load(
        name: String,
        directory: Path?,
        projectLoader: ClassLoader = defaultLoader,
    ): Scenario {
        require(name.matches(SCENARIO_NAME)) { "illegal scenario name '$name'" }
        return decodeFile(name, readScenarioText(name, directory?.toFile(), projectLoader))
    }

    /**
     * Decodes [text], the content of the file `<name>.json`, with the checks a load by name makes:
     * [name] must be a legal scenario name, and the scenario must declare it. Used by [load] and by
     * the `validate` command.
     */
    fun decodeFile(
        name: String,
        text: String,
    ): Scenario {
        require(name.matches(SCENARIO_NAME)) { "'$name' is not a legal scenario name ([A-Za-z0-9_-]+)" }
        val scenario = decode(ScenarioShape.parse(json, text))
        check(scenario.name == name) {
            "scenario file $name.json declares name '${scenario.name}' — file name and declared name must match"
        }
        return scenario
    }

    /**
     * Parses one scenario value with the same strict settings as [load] (`ignoreUnknownKeys` is
     * false). A top-level `$schema` string, which points an editor at the scenario JSON Schema, is
     * removed first. Each step is checked on its own first, so an invalid one is reported by its
     * number. Messages name the path of the field at fault (see [ScenarioShape]).
     */
    internal fun decode(element: JsonElement): Scenario {
        val root = withoutSchemaKey(element)
        ((root as? JsonObject)?.get("steps") as? JsonArray)?.forEachIndexed { i, step ->
            try {
                ScenarioShape.require(step, Step.serializer().descriptor, "$.steps[$i]")
                json.decodeFromJsonElement<Step>(step)
            } catch (e: IllegalArgumentException) {
                throw IllegalArgumentException("step ${i + 1}: ${ScenarioShape.decodeFailure(e)}", e)
            }
        }
        ScenarioShape.require(root, Scenario.serializer().descriptor, "$")
        return try {
            json.decodeFromJsonElement(root)
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException(ScenarioShape.decodeFailure(e), e)
        }
    }

    /** [element] without its top-level `$schema` key. A `$schema` that is not a string is an error. */
    private fun withoutSchemaKey(element: JsonElement): JsonElement {
        val schema = (element as? JsonObject)?.get(SCHEMA_KEY) ?: return element
        ScenarioShape.require(schema, String.serializer().descriptor, "$.$SCHEMA_KEY")
        return JsonObject(element - SCHEMA_KEY)
    }

    /**
     * The names [load] can find: `*.json` files in [directory] (none when it is null or missing),
     * the names in every `/scenarios/index.txt` on the project's classpath, and the bundled names.
     * Indexes list one name per line; blank lines and `#` comments are skipped. Names that are not
     * legal scenario names are skipped; the rest are deduplicated and sorted. An indexed name is
     * listed without checking that its file exists.
     */
    fun availableNames(
        directory: Path?,
        projectLoader: ClassLoader = defaultLoader,
    ): List<String> =
        (directoryNames(directory?.toFile()) + projectNames(projectLoader) + bundledNames())
            .filter { it.matches(SCENARIO_NAME) }
            .distinct()
            .sorted()

    private fun readScenarioText(
        name: String,
        dir: File?,
        projectLoader: ClassLoader,
    ): String =
        dir?.let { File(it, "$name.json") }?.takeIf { it.isFile }?.readText()
            ?: projectLoader.getResourceAsStream("$PROJECT_SCENARIOS/$name.json")?.use { it.readBytes().decodeToString() }
            ?: ScenarioLoader::class.java.getResourceAsStream("$BUNDLED_SCENARIOS/$name.json")?.use { it.readBytes().decodeToString() }
            ?: error(
                "unknown scenario '$name' — " + (if (dir != null) "no $name.json in ${dir.path}, " else "") +
                    "no /$PROJECT_SCENARIOS/$name.json on the classpath and no bundled scenario '$name'",
            )

    private fun directoryNames(dir: File?): List<String> {
        val files = dir?.takeIf { it.isDirectory }?.listFiles() ?: return emptyList()
        return files
            .filter { it.isFile && it.name.endsWith(".json") }
            .map { it.name.removeSuffix(".json") }
    }

    /** Names from every `/scenarios/index.txt` on the project's classpath; none when there is no index. */
    private fun projectNames(projectLoader: ClassLoader): List<String> =
        projectLoader
            .getResources("$PROJECT_SCENARIOS/index.txt")
            .toList()
            .flatMap { url -> url.openStream().use { indexNames(it.readBytes().decodeToString()) } }

    private fun bundledNames(): List<String> =
        ScenarioLoader::class.java
            .getResourceAsStream("$BUNDLED_SCENARIOS/index.txt")
            ?.use { indexNames(it.readBytes().decodeToString()) }
            ?: error("$BUNDLED_SCENARIOS/index.txt missing — it lists every bundled scenario name, one per line")

    private fun indexNames(raw: String): List<String> =
        raw
            .lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }

    private val defaultLoader: ClassLoader = ScenarioLoader::class.java.classLoader
}

/** The key a scenario file uses to name its JSON Schema, for editors. */
private const val SCHEMA_KEY = "\$schema"

private val SCENARIO_NAME = Regex("[A-Za-z0-9_-]+")

/** The project's scenarios, as a ClassLoader resource path (no leading slash). */
private const val PROJECT_SCENARIOS = "scenarios"

/** The bundled scenarios, under fake-llm's own resource path. */
private const val BUNDLED_SCENARIOS = "/com/davidjonesgriz/fakellm/scenarios"
