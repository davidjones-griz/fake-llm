// Shared test helpers, not one class's file: TestClient plus the request and response helpers.
@file:Suppress("MatchingDeclarationName")

package com.davidjonesgriz.fakellm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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

internal class TestClient(
    private val port: Int,
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
    ): HttpResponse<String> = send(request(path).POST(HttpRequest.BodyPublishers.ofString(body)), authorization)

    fun get(
        path: String,
        authorization: String? = null,
    ): HttpResponse<String> = send(request(path).GET(), authorization)

    private fun request(path: String): HttpRequest.Builder = HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path"))

    private fun send(
        builder: HttpRequest.Builder,
        authorization: String?,
    ): HttpResponse<String> {
        authorization?.let { builder.header("Authorization", it) }
        val response = http.send(builder.timeout(Duration.ofSeconds(15)).build(), HttpResponse.BodyHandlers.ofString())
        return response
    }
}

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

internal fun errorDetail(body: String): String =
    Json
        .parseToJsonElement(body)
        .jsonObject
        .getValue("detail")
        .jsonPrimitive.content

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
