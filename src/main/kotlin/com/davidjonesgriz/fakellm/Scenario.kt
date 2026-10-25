package com.davidjonesgriz.fakellm

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import java.io.File
import java.nio.file.Path

/**
 * One named behavior of the fake: an ordered list of [Rule]s. The first rule whose [Match]
 * accepts the request wins. Each match consumes the next [Rule.respond] step on a counter scoped
 * to that rule; once the list is exhausted, the last step repeats.
 *
 * A rule that concurrent requests can hit together should have a single step. The occurrence
 * counter is then only a count. A sequence of different steps (for example, fail once, then
 * succeed) is well-defined when the caller sends those requests one at a time: overlapping calls
 * share one counter and take successive steps in arrival order.
 *
 * Scenario files are JSON in this shape. [ScenarioEngine.load] reads one by name from the engine's
 * [ScenarioEngine.scenarioDirectory], the project's classpath resource `/scenarios/<name>.json`, or
 * the bundled scenarios, in that order, and [ScenarioEngine.loadInline] takes one built in code.
 *
 * @throws IllegalArgumentException when [rules] is empty. The message text is diagnostic, not part
 *   of the API.
 */
@Serializable
public data class Scenario(
    val name: String,
    val description: String = "",
    val rules: List<Rule>,
) {
    init {
        require(rules.isNotEmpty()) { "scenario '$name' has no rules" }
    }
}

/**
 * One rule of a [Scenario]: the [match] a request must pass, and the [respond] steps it serves.
 * Call `n` to match this rule gets `respond[n - 1]`, and the last step repeats once the list runs
 * out.
 *
 * @throws IllegalArgumentException when [respond] is empty, or a step sets both or neither of
 *   [Step.text] and [Step.error]. The message text is diagnostic, not part of the API.
 */
@Serializable
public data class Rule(
    /** Free-form label recorded in the call log for each request this rule serves. */
    val role: String,
    val match: Match = Match(),
    val respond: List<Step>,
) {
    init {
        require(respond.isNotEmpty()) { "rule '$role' has no respond steps" }
        respond.forEachIndexed { i, s ->
            require((s.text != null) xor (s.error != null)) {
                "rule '$role' step $i must set exactly one of text/error"
            }
        }
    }
}

/**
 * All present predicates must hold (AND). An empty match matches everything — order rules accordingly.
 *
 * @throws IllegalArgumentException when [systemShape] is not one of the values below. The message
 *   text is diagnostic, not part of the API.
 */
@Serializable
public data class Match(
    /** Substring of the request's `model` field. */
    val modelContains: String? = null,
    /** Every entry must appear in the request's combined prompt text (system + all message content). */
    val promptContainsAll: List<String> = emptyList(),
    /** At least one entry must appear in the combined prompt text (when non-empty). */
    val promptContainsAny: List<String> = emptyList(),
    /** The request's system-field shape: "blocks" | "string" | "absent". */
    val systemShape: String? = null,
    /**
     * Exact match on the request's `X-Fake-LLM-Caller` header: a free-form tag naming which part
     * of the client is calling. Null (the default) skips this predicate. ANDed with the others.
     */
    val caller: String? = null,
) {
    init {
        require(systemShape == null || systemShape in MATCHABLE_SYSTEM_SHAPES) {
            "systemShape must be one of $MATCHABLE_SYSTEM_SHAPES, got '$systemShape'"
        }
    }
}

private val MATCHABLE_SYSTEM_SHAPES = listOf("blocks", "string", "absent")

/**
 * One scripted reply: assistant [text], optionally with [thinking], or a scripted [error]. A
 * [Rule] requires exactly one of [text] and [error] on each of its steps.
 *
 * @throws IllegalArgumentException when a value is invalid. The message text is diagnostic, not
 *   part of the API.
 */
@Serializable
public data class Step(
    /** Assistant text copied into the vendor response. */
    val text: String? = null,
    val thinking: String? = null,
    /** `stop_reason` for a text step. `"max_tokens"` reports that the reply was truncated. */
    val stopReason: String = "end_turn",
    /** A scripted vendor error in place of a text reply. The status classifies it; the body is sent as given. */
    val error: ErrorStep? = null,
    /**
     * SSE streaming: delta size in Unicode code points, not UTF-16 chars. A boundary inside a
     * surrogate pair would encode that half as its own UTF-8 frame and replace it with U+FFFD,
     * so the chunks would no longer reassemble to the original text. Consulted only when the
     * request is streamed; a buffered request ignores it.
     */
    val streamChunkChars: Int = DEFAULT_STREAM_CHUNK_CHARS,
    /**
     * Milliseconds to wait between SSE deltas, for injecting latency. `0` waits nothing, so the
     * default stays immediate. Streaming only; see [streamChunkChars].
     */
    val streamInterChunkDelayMs: Long = 0,
    /**
     * Streaming only, and only valid on an error step ([error] set): emit this many text deltas,
     * then an in-stream `event: error` frame, and skip `content_block_stop`, `message_delta`, and
     * `message_stop`.
     *
     * Null (the default): the step's error is a plain HTTP response, and no SSE bytes are written.
     * That matches a provider that rejects before generation starts — the usual timing for
     * rate-limit and overload errors. This field is the opt-in for a failure after some text has
     * already been generated.
     */
    val streamErrorAfterChunks: Int? = null,
) {
    init {
        require(streamChunkChars > 0) { "streamChunkChars must be positive, got $streamChunkChars" }
        require(streamInterChunkDelayMs >= 0) {
            "streamInterChunkDelayMs must be >= 0, got $streamInterChunkDelayMs"
        }
        require(streamErrorAfterChunks == null || error != null) {
            "streamErrorAfterChunks is only valid on an error-bearing step"
        }
        require(streamErrorAfterChunks == null || streamErrorAfterChunks > 0) {
            "streamErrorAfterChunks must be positive, got $streamErrorAfterChunks"
        }
        require(thinking == null || text != null) { "thinking is only valid on a text step" }
    }
}

private const val DEFAULT_STREAM_CHUNK_CHARS = 24

/**
 * A scripted HTTP error in place of a text reply: the [status], the [body], and extra [headers].
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
 * 3. the bundled scenario, kept under fake-llm's own resource path so a project's files can never
 *    hide it by accident.
 *
 * [projectLoader] finds the project's resources; tests pass their own. The bundled files are always
 * read through this class.
 */
internal object ScenarioLoader {
    private val json = Json { ignoreUnknownKeys = false }

    fun load(
        name: String,
        directory: Path?,
        projectLoader: ClassLoader = defaultLoader,
    ): Scenario {
        require(name.matches(SCENARIO_NAME)) { "illegal scenario name '$name'" }
        val text = readScenarioText(name, directory?.toFile(), projectLoader)
        val scenario = json.decodeFromString<Scenario>(text)
        check(scenario.name == name) {
            "scenario file $name.json declares name '${scenario.name}' — file name and declared name must match"
        }
        return scenario
    }

    /** Parses one scenario value with the same strict settings as [load] (`ignoreUnknownKeys` is false). */
    internal fun decode(element: JsonElement): Scenario = json.decodeFromJsonElement(element)

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

private val SCENARIO_NAME = Regex("[A-Za-z0-9_-]+")

/** The project's scenarios, as a ClassLoader resource path (no leading slash). */
private const val PROJECT_SCENARIOS = "scenarios"

/** The bundled scenarios, under fake-llm's own resource path. */
private const val BUNDLED_SCENARIOS = "/com/davidjonesgriz/fakellm/scenarios"
