// HTTP plumbing for the fake LLM server: the listen socket, request dispatch, the control API,
// and the JSON response writer shared by those handlers. Vendor request handlers live in
// VendorHandlers.kt and ResponsesStreaming.kt, and the embeddings routes in EmbeddingRoutes.kt.
package com.davidjonesgriz.fakellm

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketException
import java.nio.channels.UnsupportedAddressTypeException
import java.nio.charset.StandardCharsets

/**
 * Binds the listen socket. Tries the IPv6 wildcard `::` first, so an IPv6-only or dual-stack host
 * can accept connections on that family. A host with no IPv6 stack has no `::` address to bind;
 * the JDK throws [UnsupportedAddressTypeException] or [SocketException], and this falls back to
 * the IPv4 wildcard `0.0.0.0` (every IPv4 interface). The fallback follows the address family the
 * host can actually bind.
 */
internal fun createFakeLlmHttpServer(port: Int): HttpServer =
    try {
        HttpServer.create(InetSocketAddress(InetAddress.getByName("::"), port), 0)
    } catch (_: UnsupportedAddressTypeException) {
        HttpServer.create(InetSocketAddress(InetAddress.getByName("0.0.0.0"), port), 0)
    } catch (_: SocketException) {
        HttpServer.create(InetSocketAddress(InetAddress.getByName("0.0.0.0"), port), 0)
    }

internal fun route(
    ex: HttpExchange,
    engine: ScenarioEngine,
    controlToken: String?,
) {
    runCatching {
        if (needsControlAuth(ex.requestURI.path) && !isAuthorized(ex, controlToken)) {
            respond(
                ex,
                HTTP_UNAUTHORIZED,
                """{"fake_llm_error":"unauthorized: missing or invalid Authorization: Bearer <token>"}""",
                headers = mapOf("WWW-Authenticate" to "Bearer"),
            )
            return@runCatching
        }
        val handled = controlRoute(ex, engine) || embeddingRoute(ex) || vendorRoute(ex, engine)
        if (!handled) {
            respond(ex, HTTP_NOT_FOUND, """{"fake_llm_error":"no route: ${ex.requestMethod} ${ex.requestURI.path}"}""")
        }
    }.onFailure { e ->
        if ((e is IOException || e is InterruptedException) && ex.responseCode != -1) {
            // The client went away, or the server is stopping, mid-response: nothing more can be sent.
            ex.exchangeCapture()?.abort()
            ex.close()
        } else {
            // A parse or handler failure answers 400 with the message. The exchange is not left open.
            respond(ex, HTTP_BAD_REQUEST, """{"fake_llm_error":"exception","detail":${jsonString(e.message)}}""")
        }
    }
}

/** Health and the control API. Returns false when the request is neither. */
private fun controlRoute(
    ex: HttpExchange,
    engine: ScenarioEngine,
): Boolean {
    val key = "${ex.requestMethod} ${ex.requestURI.path}"
    when (key) {
        "GET /health" -> {
            val progress = engine.progress
            respond(
                ex,
                HTTP_OK,
                buildJsonObject {
                    put("status", "ok")
                    put("scenario", progress.name)
                    put("lenient", engine.lenient)
                    put("steps", progress.steps)
                    put("unusedSteps", buildJsonArray { progress.unusedSteps.forEach { add(JsonPrimitive(it)) } })
                }.toString(),
            )
        }

        "GET /openapi" -> {
            val (status, contentType, body) = openApiResponse(bundledOpenApiSpec)
            respond(ex, status, body, contentType = contentType)
        }

        "POST /control/scenario" -> {
            loadScenario(ex, engine)
        }

        "GET /control/scenarios" -> {
            respond(ex, HTTP_OK, scenariosJson(engine))
        }

        "POST /control/reset" -> {
            engine.reset()
            respond(ex, HTTP_OK, """{"reset":true}""")
        }

        "POST /control/unload" -> {
            engine.unload()
            respond(ex, HTTP_OK, """{"unloaded":true}""")
        }

        "GET /control/calls" -> {
            respond(ex, HTTP_OK, logJson.encodeToString(engine.calls()))
        }

        "GET /control/exchanges" -> {
            respond(ex, HTTP_OK, logJson.encodeToString(engine.exchanges()))
        }

        else -> {
            return false
        }
    }
    return true
}

/** Vendor routes. Returns false when the request matches none of them. */
private fun vendorRoute(
    ex: HttpExchange,
    engine: ScenarioEngine,
): Boolean {
    // The health and control requests controlRoute answers never get here, so they are never wrapped.
    installExchangeCapture(ex)
    val path = ex.requestURI.path
    when {
        ex.requestMethod == "POST" && path == "/anthropic/v1/messages" -> {
            handleMessages(ex, engine)
        }

        path == BATCHES_PATH || path.startsWith("$BATCHES_PATH/") -> {
            respond(
                ex,
                HTTP_NOT_IMPLEMENTED,
                """{"fake_llm_error":"the Anthropic Batches API is not implemented"}""",
            )
        }

        ex.requestMethod == "POST" && path == "/openai/v1/chat/completions" -> {
            handleOpenAi(ex, engine)
        }

        ex.requestMethod == "POST" && path == "/openai/v1/responses" -> {
            handleOpenAiResponses(ex, engine)
        }

        ex.requestMethod == "POST" && GEMINI_CONTENT_ROUTE.matches(path) -> {
            handleGemini(ex, engine, path)
        }

        else -> {
            return false
        }
    }
    return true
}

private fun loadScenario(
    ex: HttpExchange,
    engine: ScenarioEngine,
) {
    val body = ex.requestBody.readBytes().decodeToString()
    val root = Json.parseToJsonElement(body)
    val obj =
        root as? JsonObject
            ?: error("body must be a JSON object with exactly one of \"name\" or \"scenario\"")
    val unknown = obj.keys - setOf("name", "scenario")
    if (unknown.isNotEmpty()) error("unknown field(s) ${unknown.sorted()}; the body takes exactly one of \"name\" or \"scenario\"")
    val hasName = "name" in obj
    val hasScenario = "scenario" in obj
    if (hasName == hasScenario) {
        error("body must contain exactly one of \"name\" or \"scenario\"")
    }
    val scenario =
        if (hasName) {
            val name =
                (obj["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                    ?: error("\"name\" must be a string")
            if (name.isBlank()) error("\"name\" must be a non-blank string")
            engine.load(name)
        } else {
            val inline =
                obj["scenario"] as? JsonObject
                    ?: error("\"scenario\" must be a JSON object")
            engine.loadInline(ScenarioLoader.decode(inline))
        }
    respond(ex, HTTP_OK, """{"loaded":${jsonString(scenario.name)},"steps":${scenario.steps.size}}""")
}

private fun scenariosJson(engine: ScenarioEngine): String {
    val names = ScenarioLoader.availableNames(engine.scenarioDirectory)
    return buildJsonObject {
        put(
            "scenarios",
            buildJsonArray {
                names.forEach { add(JsonPrimitive(it)) }
            },
        )
    }.toString()
}

/**
 * Sends [body] with [status] and [contentType]. A `content-type` among [headers] (a scripted
 * error step's) replaces [contentType] rather than adding a second one; other headers are added
 * as given.
 */
internal fun respond(
    ex: HttpExchange,
    status: Int,
    body: String,
    headers: Map<String, String> = emptyMap(),
    contentType: String = "application/json",
) {
    val bytes = body.toByteArray(StandardCharsets.UTF_8)
    ex.responseHeaders.set("Content-Type", contentType)
    headers.forEach { (k, v) ->
        if (k.equals("Content-Type", ignoreCase = true)) ex.responseHeaders.set(k, v) else ex.responseHeaders.add(k, v)
    }
    ex.sendRecordedResponseHeaders(status, bytes.size.toLong())
    ex.responseBody.use { it.write(bytes) }
}

internal fun jsonString(s: String?): String = if (s == null) "null" else JsonPrimitive(s).toString()

private val logJson = Json { encodeDefaults = true }

/** The OpenAPI document bundled in the jar (see openapi/fake-llm.yaml), or null if it is missing. */
private val bundledOpenApiSpec: String? by lazy {
    FakeLlmServer::class.java
        .getResourceAsStream(
            "/com/davidjonesgriz/fakellm/openapi/fake-llm.yaml",
        )?.use { it.readBytes().decodeToString() }
}

/** `GET /openapi`: the bundled document, or HTTP 500 when the jar was built without it. */
internal fun openApiResponse(spec: String?): Triple<Int, String, String> =
    if (spec != null) {
        Triple(HTTP_OK, "application/yaml", spec)
    } else {
        Triple(HTTP_INTERNAL_ERROR, "application/json", """{"fake_llm_error":"the OpenAPI document is not bundled in this build"}""")
    }

private const val HTTP_BAD_REQUEST = 400
private const val HTTP_UNAUTHORIZED = 401
private const val HTTP_NOT_FOUND = 404
private const val BATCHES_PATH = "/anthropic/v1/messages/batches"

/** `/gemini/{version}/models/{model}:generateContent` or `:streamGenerateContent`, matching the OpenAPI paths exactly. */
private val GEMINI_CONTENT_ROUTE = Regex("/gemini/[^/]+/models/[^/:]+:(generateContent|streamGenerateContent)")

private const val HTTP_INTERNAL_ERROR = 500
private const val HTTP_NOT_IMPLEMENTED = 501
