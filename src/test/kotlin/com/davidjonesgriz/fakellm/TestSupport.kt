// Shared test helpers, not one class's file: TestClient plus the request and response helpers.
@file:Suppress("MatchingDeclarationName")

package com.davidjonesgriz.fakellm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.time.Duration
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertTrue

internal class TestClient(
    val port: Int,
) {
    private val http =
        HttpClient
            .newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5))
            .build()

    fun post(
        path: String,
        body: String,
        authorization: String? = null,
        timeout: Duration = Duration.ofSeconds(15),
        headers: Map<String, String> = emptyMap(),
    ): HttpResponse<String> =
        send(
            request(path).POST(HttpRequest.BodyPublishers.ofString(body)).also { b -> headers.forEach { (k, v) -> b.header(k, v) } },
            authorization,
            timeout,
        )

    fun get(
        path: String,
        authorization: String? = null,
    ): HttpResponse<String> = send(request(path).GET(), authorization, Duration.ofSeconds(15))

    private fun request(path: String): HttpRequest.Builder = HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path"))

    private fun send(
        builder: HttpRequest.Builder,
        authorization: String?,
        timeout: Duration,
    ): HttpResponse<String> {
        authorization?.let { builder.header("Authorization", it) }
        val response = http.send(builder.timeout(timeout).build(), HttpResponse.BodyHandlers.ofString())
        return response
    }
}

/** `GET /control/exchanges`, parsed. */
internal fun exchanges(client: TestClient): JsonArray = Json.parseToJsonElement(client.get("/control/exchanges").body()).jsonArray

internal fun withServer(
    engine: ScenarioEngine = ScenarioEngine(),
    controlToken: String? = null,
    block: (client: TestClient, engine: ScenarioEngine) -> Unit,
) {
    startFakeLlmServer(port = 0, engine = engine, controlToken = controlToken).use { server ->
        check(server.port != 0) { "test server did not bind an ephemeral port" }
        block(TestClient(server.port), engine)
    }
}

internal fun anthropicBody(
    prompt: String,
    stream: Boolean = false,
    model: String = "example-model",
): String {
    val streamField = if (stream) ""","stream":true""" else ""
    return buildString {
        append("""{"model":""")
        append(JsonPrimitive(model))
        append(""","max_tokens":256""")
        append(streamField)
        append(""","messages":[{"role":"user","content":""")
        append(JsonPrimitive(prompt))
        append("}]}")
    }
}

internal fun anthropicText(body: String): String {
    val content =
        Json
            .parseToJsonElement(body)
            .jsonObject
            .getValue("content")
            .jsonArray
    val block =
        content.first {
            it.jsonObject
                .getValue("type")
                .jsonPrimitive.content == "text"
        }
    return block.jsonObject
        .getValue("text")
        .jsonPrimitive.content
}

internal fun anthropicStopReason(body: String): String =
    Json
        .parseToJsonElement(body)
        .jsonObject
        .getValue("stop_reason")
        .jsonPrimitive.content

/** The `message` of one of fake-llm's own error [body]s, after checking that its code is [code] when one is given. */
internal fun errorMessage(
    body: String,
    code: String? = null,
): String {
    val obj = Json.parseToJsonElement(body).jsonObject
    code?.let { assertEquals(it, obj.getValue("fake_llm_error").jsonPrimitive.content, body) }
    return obj.getValue("message").jsonPrimitive.content
}

/** Runs [block] with a new temporary directory, deleted afterwards. */
@OptIn(ExperimentalPathApi::class)
internal fun withScenarioDir(block: (Path) -> Unit) {
    val dir = createTempDirectory("fake-llm-scenarios")
    try {
        block(dir)
    } finally {
        dir.deleteRecursively()
    }
}

/** Writes [json] to `<dir>/<name>.json`. */
internal fun writeScenario(
    dir: Path,
    name: String,
    json: String,
) {
    dir.resolve("$name.json").writeText(json)
}

/**
 * Scenarios that both the server's decoder and the scenario schemas (the spec's and the generated
 * file) must reject, each with the reason it is invalid.
 */
internal val invalidScenarios =
    listOf(
        "no steps" to """{"name":"x","steps":[]}""",
        "no name" to """{"steps":[{"text":"t"}]}""",
        "an unknown field" to """{"name":"x","steps":[{"text":"t"}],"extra":1}""",
        "the removed rules format" to """{"name":"x","rules":[{"role":"r","respond":[{"text":"t"}]}]}""",
        "a null steps list" to """{"name":"x","steps":null}""",
        "a step with text and error" to """{"name":"x","steps":[{"text":"t","error":{"status":500}}]}""",
        "a step with neither" to """{"name":"x","steps":[{}]}""",
        "a step with both null" to """{"name":"x","steps":[{"text":null,"error":null}]}""",
        "thinking on an error step" to
            """{"name":"x","steps":[{"thinking":"h","error":{"status":500}}]}""",
        "streamErrorAfterChunks on a text step" to
            """{"name":"x","steps":[{"text":"t","streamErrorAfterChunks":2}]}""",
        "streamErrorAfterChunks of 0" to
            """{"name":"x","steps":[{"error":{"status":500},"streamErrorAfterChunks":0}]}""",
        "streamChunkChars of 0" to """{"name":"x","steps":[{"text":"t","streamChunkChars":0}]}""",
        "streamChunkChars past Int" to
            """{"name":"x","steps":[{"text":"t","streamChunkChars":3000000000}]}""",
        "a negative delay" to """{"name":"x","steps":[{"text":"t","streamInterChunkDelayMs":-1}]}""",
        "an unknown field in a step" to """{"name":"x","steps":[{"text":"t","extra":1}]}""",
        "an error status of 42" to """{"name":"x","steps":[{"error":{"status":42}}]}""",
        "an error status of 600" to """{"name":"x","steps":[{"error":{"status":600}}]}""",
        "an error with no status" to """{"name":"x","steps":[{"error":{"body":"b"}}]}""",
        "an unknown field in an error" to """{"name":"x","steps":[{"error":{"status":500,"extra":1}}]}""",
        "a null description" to """{"name":"x","description":null,"steps":[{"text":"t"}]}""",
        "a null stopReason" to """{"name":"x","steps":[{"text":"t","stopReason":null}]}""",
        "a delay above the cap" to """{"name":"x","steps":[{"text":"t","streamInterChunkDelayMs":600001}]}""",
        "a negative first-byte delay" to """{"name":"x","steps":[{"text":"t","firstByteDelayMs":-1}]}""",
        "a first-byte delay above the cap" to """{"name":"x","steps":[{"text":"t","firstByteDelayMs":600001}]}""",
        "a null first-byte delay" to """{"name":"x","steps":[{"text":"t","firstByteDelayMs":null}]}""",
        "a null delay" to """{"name":"x","steps":[{"text":"t","streamInterChunkDelayMs":null}]}""",
        "a null error body" to """{"name":"x","steps":[{"error":{"status":500,"body":null}}]}""",
        "null error headers" to """{"name":"x","steps":[{"error":{"status":500,"headers":null}}]}""",
        "a non-string error header" to
            """{"name":"x","steps":[{"error":{"status":500,"headers":{"a":1}}}]}""",
        "a quoted integer" to """{"name":"x","steps":[{"text":"t","streamChunkChars":"24"}]}""",
        "a quoted error status" to """{"name":"x","steps":[{"error":{"status":"500"}}]}""",
        "a null \$schema" to """{"${'$'}schema":null,"name":"x","steps":[{"text":"t"}]}""",
        "a numeric \$schema" to """{"${'$'}schema":1,"name":"x","steps":[{"text":"t"}]}""",
        "\$schema in a step" to """{"name":"x","steps":[{"${'$'}schema":"s","text":"t"}]}""",
        "toolCalls with an error" to """{"name":"x","steps":[{"toolCalls":[{"name":"f"}],"error":{"status":500}}]}""",
        "an empty toolCalls list" to """{"name":"x","steps":[{"toolCalls":[]}]}""",
        "a null toolCalls with no text" to """{"name":"x","steps":[{"toolCalls":null}]}""",
        "a tool call without a name" to """{"name":"x","steps":[{"toolCalls":[{"arguments":{}}]}]}""",
        "an empty tool name" to """{"name":"x","steps":[{"toolCalls":[{"name":""}]}]}""",
        "an empty tool call id" to """{"name":"x","steps":[{"toolCalls":[{"name":"f","id":""}]}]}""",
        "arguments and rawArguments" to """{"name":"x","steps":[{"toolCalls":[{"name":"f","arguments":{},"rawArguments":"{}"}]}]}""",
        "array arguments" to """{"name":"x","steps":[{"toolCalls":[{"name":"f","arguments":[1]}]}]}""",
        "object rawArguments" to """{"name":"x","steps":[{"toolCalls":[{"name":"f","rawArguments":{}}]}]}""",
        "max_tokens with toolCalls" to """{"name":"x","steps":[{"toolCalls":[{"name":"f"}],"stopReason":"max_tokens"}]}""",
        "an unknown field in a tool call" to """{"name":"x","steps":[{"toolCalls":[{"name":"f","input":{}}]}]}""",
        "usage on an error step" to """{"name":"x","steps":[{"error":{"status":500},"usage":{"inputTokens":1}}]}""",
        "a negative inputTokens" to """{"name":"x","steps":[{"text":"t","usage":{"inputTokens":-1}}]}""",
        "an unknown field in usage" to """{"name":"x","steps":[{"text":"t","usage":{"cachedTokens":1}}]}""",
        "stopSequence without stopReason stop_sequence" to """{"name":"x","steps":[{"text":"t","stopSequence":"END"}]}""",
        "an empty stopSequence" to """{"name":"x","steps":[{"text":"t","stopReason":"stop_sequence","stopSequence":""}]}""",
        "outputTokens past Int" to """{"name":"x","steps":[{"text":"t","usage":{"outputTokens":3000000000}}]}""",
    )

/** Every directory of scenario files in the repository, relative to the root project. */
internal val scenarioDirectories =
    listOf(
        "src/main/resources/com/davidjonesgriz/fakellm/scenarios",
        "examples/src/test/resources/scenarios",
        "examples/src/test/scenarios",
        "junit-jupiter/src/test/fixture-scenarios",
        "examples/github-actions/scenarios",
        "testcontainers/src/test/scenarios",
    )

private const val MIN_SCENARIO_FILES = 12

/** The `*.json` files in [scenarioDirectories]. Fails if a directory has none, so a moved directory is noticed. */
internal fun repositoryScenarioFiles(): List<File> {
    val byDirectory = scenarioDirectories.associateWith { dir -> File(dir).listFiles { f -> f.extension == "json" }.orEmpty().toList() }
    byDirectory.forEach { (dir, found) -> assertTrue(found.isNotEmpty(), "no scenario files in $dir") }
    val files = byDirectory.values.flatten()
    assertTrue(files.size >= MIN_SCENARIO_FILES, "found only ${files.map { it.path }}")
    return files
}

/**
 * The event name and data payload of each frame of a named-event stream (Anthropic, OpenAI
 * Responses), in order. Fails unless every frame is exactly one `event:` line and one `data:` line.
 */
internal fun namedFrames(body: String): List<Pair<String, String>> =
    body.split("\n\n").filter { it.isNotBlank() }.map { frame ->
        val lines = frame.lines()
        assertEquals(2, lines.size, "a frame is one event line and one data line: $frame")
        lines[0].also { assertTrue(it.startsWith("event: "), frame) }.removePrefix("event: ") to
            lines[1].also { assertTrue(it.startsWith("data: "), frame) }.removePrefix("data: ")
    }

/** A minimal OpenAI Responses request body: `{"model":"example-gpt","input":"<input>"}`, with `"stream":true` when [stream]. */
internal fun responsesBody(
    input: String = "Hello",
    stream: Boolean = false,
): String = """{"model":"example-gpt","input":${JsonPrimitive(input)}""" + (if (stream) ""","stream":true}""" else "}")

/**
 * The data payloads of a data-only stream (OpenAI, Gemini), in order. Fails unless every frame is
 * exactly one `data:` line (no `event:` line).
 */
internal fun dataFrames(body: String): List<String> {
    val frames = body.split("\n\n").filter { it.isNotEmpty() }
    return frames.map { frame ->
        val lines = frame.split("\n")
        assertTrue(lines.size == 1 && lines[0].startsWith("data: "), "not a single data: line: $frame")
        lines[0].removePrefix("data: ")
    }
}
