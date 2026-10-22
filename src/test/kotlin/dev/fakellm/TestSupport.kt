package dev.fakellm

import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.ExecutorService

internal class TestClient(private val port: Int) {
    private val http =
        HttpClient
            .newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5))
            .build()

    fun post(
        path: String,
        body: String,
        caller: String? = null,
        authorization: String? = null,
    ): HttpResponse<String> = send(request(path).POST(HttpRequest.BodyPublishers.ofString(body)), caller, authorization)

    fun get(
        path: String,
        authorization: String? = null,
    ): HttpResponse<String> = send(request(path).GET(), caller = null, authorization = authorization)

    private fun request(path: String): HttpRequest.Builder =
        HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path"))

    private fun send(
        builder: HttpRequest.Builder,
        caller: String?,
        authorization: String?,
    ): HttpResponse<String> {
        caller?.let { builder.header("X-Fake-LLM-Caller", it) }
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
    val server = startFakeLlmServer(port = 0, engine = engine, controlToken = controlToken)
    val port = server.address.port
    check(port != 0) { "test server did not bind an ephemeral port" }
    try {
        block(TestClient(port), engine)
    } finally {
        server.stopTestServer()
    }
}

internal fun HttpServer.stopTestServer() {
    stop(0)
    (executor as? ExecutorService)?.shutdownNow()
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
    val content = Json.parseToJsonElement(body).jsonObject.getValue("content").jsonArray
    val block = content.first { it.jsonObject.getValue("type").jsonPrimitive.content == "text" }
    return block.jsonObject.getValue("text").jsonPrimitive.content
}

internal fun anthropicStopReason(body: String): String =
    Json.parseToJsonElement(body).jsonObject.getValue("stop_reason").jsonPrimitive.content

internal fun errorDetail(body: String): String =
    Json.parseToJsonElement(body).jsonObject.getValue("detail").jsonPrimitive.content
