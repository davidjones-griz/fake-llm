package com.davidjonesgriz.fakellm

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import java.io.File

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
 * Scenario files are JSON. [ScenarioLoader] reads `<dir>/<name>.json` from the optional scenario
 * directory first, then the classpath resource `/scenarios/<name>.json`.
 */
@Serializable
data class Scenario(
    val name: String,
    val description: String = "",
    val rules: List<Rule> = emptyList(),
) {
    init {
        require(rules.isNotEmpty()) { "scenario '$name' has no rules" }
    }
}

@Serializable
data class Rule(
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

/** All present predicates must hold (AND). An empty match matches everything — order rules accordingly. */
@Serializable
data class Match(
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
)

@Serializable
data class Step(
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

@Serializable
data class ErrorStep(
    val status: Int,
    val body: String = "",
    /** Extra response headers, for example `retry-after`. Sent as given. */
    val headers: Map<String, String> = emptyMap(),
)

/**
 * Loads named scenarios. An unknown name is a hard error.
 *
 * When [setScenarioDirectory] is set, `<dir>/<name>.json` is tried first and the classpath resource
 * `/scenarios/<name>.json` is the fallback. [main] installs the directory from `--scenario-dir`, or
 * from `FAKE_LLM_SCENARIO_DIR` when that flag is absent. The flag wins when both are set.
 */
object ScenarioLoader {
    private val json = Json { ignoreUnknownKeys = false }

    /** Directory searched before the classpath. Null uses only bundled `/scenarios`. */
    @Volatile
    private var scenarioDirectory: File? = null

    fun setScenarioDirectory(dir: File?) {
        scenarioDirectory = dir
    }

    fun load(name: String): Scenario {
        require(name.matches(Regex("[A-Za-z0-9_-]+"))) { "illegal scenario name '$name'" }
        val text = readScenarioText(name)
        val scenario = json.decodeFromString<Scenario>(text)
        check(scenario.name == name) {
            "scenario file $name.json declares name '${scenario.name}' — file name and declared name must match"
        }
        return scenario
    }

    /** Parses one scenario value with the same strict settings as [load] (`ignoreUnknownKeys` is false). */
    internal fun decode(element: JsonElement): Scenario = json.decodeFromJsonElement(element)

    /**
     * Names from the scenario directory (`*.json` files in that directory) and from the classpath
     * index `/scenarios/index.txt` (one name per line; blank lines and `#` comments are skipped).
     * The combined list is deduplicated and sorted.
     */
    fun availableNames(): List<String> = (directoryNames() + classpathNames()).distinct().sorted()

    private fun readScenarioText(name: String): String {
        val dir = scenarioDirectory
        if (dir != null) {
            val file = File(dir, "$name.json")
            if (file.isFile) return file.readText()
        }
        val resource = ScenarioLoader::class.java.getResourceAsStream("/scenarios/$name.json")
        if (resource != null) return resource.use { it.readBytes().decodeToString() }
        val where =
            if (dir != null) {
                "no $name.json in ${dir.path} and no /scenarios/$name.json on the classpath"
            } else {
                "no /scenarios/$name.json on the classpath"
            }
        error("unknown scenario '$name' — $where")
    }

    private fun directoryNames(): List<String> {
        val dir = scenarioDirectory ?: return emptyList()
        if (!dir.isDirectory) return emptyList()
        val files = dir.listFiles() ?: return emptyList()
        return files
            .filter { it.isFile && it.name.endsWith(".json") }
            .map { it.name.removeSuffix(".json") }
            .filter { it.isNotEmpty() }
    }

    /** Names from `/scenarios/index.txt`: one per line, with blank lines and `#` comments skipped. */
    private fun classpathNames(): List<String> {
        val raw =
            ScenarioLoader::class.java
                .getResourceAsStream("/scenarios/index.txt")
                ?.use { it.readBytes().decodeToString() }
                ?: error("/scenarios/index.txt missing — it lists every scenario name, one per line")
        return raw
            .lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
    }
}
