package com.davidjonesgriz.fakellm

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import java.net.URI
import java.net.URISyntaxException
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * A client for a running fake-llm server's control API, for tests that reach the server over HTTP:
 * the Docker image, `java -jar`, or a [FakeLlmServer] in another part of the test. Tests in the
 * same JVM can use the server's [ScenarioEngine] directly instead; where both have a method, the
 * name is the same.
 *
 * [baseUrl] is the server's root, such as `http://localhost:8080` or [FakeLlmServer.baseUrl];
 * trailing slashes are ignored. When [controlToken] is set and not blank, every request carries
 * `Authorization: Bearer <controlToken>`, which a server started with `FAKE_LLM_CONTROL_TOKEN`
 * requires on `/control/` routes; a blank token counts as none, as a blank
 * `FAKE_LLM_CONTROL_TOKEN` does on the server. Each
 * request times out after [timeout].
 *
 * The constructor throws [IllegalArgumentException] when [baseUrl] is not an `http` or `https` URL
 * with a host and a valid port and without a query or fragment, or when [timeout] is not positive. After that, every
 * failed call throws [FakeLlmClientException]: an HTTP error status, a transport failure, a
 * response that does not decode, or an interrupt. Instances are thread-safe. [close] releases the
 * underlying HTTP client.
 */
public class FakeLlmClient
    @JvmOverloads
    constructor(
        baseUrl: String,
        controlToken: String? = null,
        private val timeout: Duration = Duration.ofSeconds(DEFAULT_TIMEOUT_SECONDS),
    ) : AutoCloseable {
        private val baseUrl = normalizedBaseUrl(baseUrl)
        private val controlToken = controlToken?.takeIf { it.isNotBlank() }

        init {
            require(!timeout.isNegative && !timeout.isZero) { "timeout must be positive, was $timeout" }
        }

        private val http =
            HttpClient
                .newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(timeout)
                .build()

        /**
         * `GET /health`: the loaded scenario's name and step count, the steps no call has used,
         * whether the server is lenient, and its version.
         */
        public fun health(): FakeLlmHealth = get("/health", FakeLlmHealth.serializer())

        /** `GET /control/scenarios`: the scenario names the server can load by name, sorted. */
        public fun scenarioNames(): List<String> = get("/control/scenarios", ScenarioNames.serializer()).scenarios

        /**
         * `POST /control/scenario` with a name: loads that scenario on the server and starts it over
         * (empty call and exchange logs, next call is call 1), as [ScenarioEngine.load] does.
         */
        public fun load(name: String) {
            send("POST", "/control/scenario", buildJsonObject { put("name", name) }.toString())
        }

        /**
         * `POST /control/scenario` with an inline scenario: installs [scenario] on the server and
         * starts it over, as [ScenarioEngine.loadInline] does.
         */
        public fun loadInline(scenario: Scenario) {
            send("POST", "/control/scenario", encodeLoadInline(scenario))
        }

        /** `POST /control/reset`: starts the server's scenario over (empty call and exchange logs, next call is call 1). */
        public fun reset() {
            send("POST", "/control/reset")
        }

        /**
         * `POST /control/unload`: removes the server's scenario and empties its call and exchange logs,
         * as [ScenarioEngine.unload] does; the next call is call 1. Calls then get the strict-mode 400,
         * or the canned reply on a lenient server. A server older than 0.5.0 has no such route, and
         * this throws with status 404.
         */
        public fun unload() {
            send("POST", "/control/unload")
        }

        /** `GET /control/calls`: the server's call log, oldest first. */
        public fun calls(): List<CallRecord> = get("/control/calls", ListSerializer(CallRecord.serializer()))

        /** `GET /control/exchanges`: each call's request and response, oldest first. */
        public fun exchanges(): List<ExchangeRecord> = get("/control/exchanges", ListSerializer(ExchangeRecord.serializer()))

        /** Releases the underlying HTTP client, waiting for requests still in flight. */
        override fun close() {
            http.close()
        }

        /**
         * Sends one request and returns the response. A non-2xx status throws. If the thread is
         * interrupted, the request may already have reached the server, so an interrupted [load],
         * [reset] or [unload] may still have taken effect.
         */
        @Suppress("ThrowsCount") // two transport failures and the status failure
        internal fun send(
            method: String,
            path: String,
            body: String? = null,
        ): HttpResponse<String> {
            val builder =
                HttpRequest
                    .newBuilder(URI.create(baseUrl + path))
                    .timeout(timeout)
                    .method(method, body?.let { HttpRequest.BodyPublishers.ofString(it) } ?: HttpRequest.BodyPublishers.noBody())
            if (body != null) builder.header("Content-Type", "application/json")
            controlToken?.let { builder.header("Authorization", "Bearer $it") }
            val response =
                try {
                    http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
                } catch (e: IOException) {
                    throw transportFailure(method, path, e)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw transportFailure(method, path, e)
                }
            if (response.statusCode() !in HTTP_SUCCESS) {
                throw httpFailure(method, path, response.statusCode(), response.body(), controlToken != null)
            }
            return response
        }
    }

/** `GET` [path] and decode the body with [deserializer]. A body that does not decode throws. */
private fun <T> FakeLlmClient.get(
    path: String,
    deserializer: DeserializationStrategy<T>,
): T {
    val response = send("GET", path)
    return try {
        responseJson.decodeFromString(deserializer, response.body())
    } catch (e: SerializationException) {
        throw undecodableResponse("GET", path, response.statusCode(), response.body(), e)
    }
}

/**
 * A failed [FakeLlmClient] call. [statusCode] is the HTTP status, or null when no response arrived
 * (a transport failure or an interrupt, available as [cause]). [serverMessage] is the server's
 * own explanation of an error status, verbatim, when the response carried one. A 2xx response that
 * this client cannot decode has its status, no [serverMessage], and the decoding error as [cause].
 */
public class FakeLlmClientException internal constructor(
    message: String,
    public val statusCode: Int?,
    public val serverMessage: String?,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/**
 * The server's `GET /health` response: the loaded scenario's name, or null when none is loaded,
 * whether a call with no scenario loaded gets the canned reply, the scenario's step count and the
 * steps no call has used, and the server's version. Read-only: the constructor and `copy` are
 * internal, so fields added with default values do not break callers.
 */
@ConsistentCopyVisibility
@Serializable
public data class FakeLlmHealth internal constructor(
    val scenario: String?,
    val lenient: Boolean,
    /** The loaded scenario's step count; null with none loaded, or from a server older than this release. */
    val steps: Int? = null,
    /**
     * The 1-based numbers of the steps no call has used since the last load, reset or unload; empty
     * with no scenario loaded. Null from a server older than this release.
     */
    val unusedSteps: List<Int>? = null,
    /** The server's version, such as `0.5.0`; null from a server older than this release, which does not report it. */
    val version: String? = null,
)

@Serializable
private class ScenarioNames(
    val scenarios: List<String>,
)

/** Responses: unknown keys are ignored, so a newer server's added fields do not break this client. */
private val responseJson = Json { ignoreUnknownKeys = true }

/** Requests: default values are left out, so an older server's strict decoding accepts them. */
private val requestJson = Json { encodeDefaults = false }

private const val DEFAULT_TIMEOUT_SECONDS = 10L
private const val HTTP_UNAUTHORIZED = 401
private const val HTTP_SUCCESS_MIN = 200
private const val HTTP_SUCCESS_MAX = 299
private val HTTP_SUCCESS = HTTP_SUCCESS_MIN..HTTP_SUCCESS_MAX
private const val RAW_BODY_LIMIT = 500
private const val NO_PORT = -1
private const val MAX_URL_PORT = 65535

internal fun encodeLoadInline(scenario: Scenario): String =
    buildJsonObject { put("scenario", requestJson.encodeToJsonElement(Scenario.serializer(), scenario)) }.toString()

/**
 * The server's explanation in an error [body]: `message` when it is a string, else `detail` (a server
 * older than 0.7.0), else `fake_llm_error` (the 401, 404 and 501 bodies), else the raw body (first
 * [RAW_BODY_LIMIT] characters), which covers a non-JSON page from a proxy. Null for an empty body.
 */
internal fun serverMessageOf(body: String): String? {
    if (body.isBlank()) return null
    val json = parseObjectOrNull(body)
    return json?.stringField("message")
        ?: json?.stringField("detail")
        ?: json?.stringField("fake_llm_error")
        ?: body.take(RAW_BODY_LIMIT)
}

// A body that is not JSON is expected here (for example a proxy's error page); the caller uses the raw text.
@Suppress("SwallowedException")
private fun parseObjectOrNull(body: String): JsonObject? =
    try {
        Json.parseToJsonElement(body) as? JsonObject
    } catch (e: SerializationException) {
        null
    }

internal fun httpFailure(
    method: String,
    path: String,
    status: Int,
    body: String,
    tokenConfigured: Boolean,
): FakeLlmClientException {
    val serverMessage = serverMessageOf(body)
    val explanation =
        when {
            status != HTTP_UNAUTHORIZED -> serverMessage ?: "(empty response body)"
            tokenConfigured -> "the server rejected the control token"
            else -> "the server requires a control token (pass controlToken)"
        }
    return FakeLlmClientException("$method $path returned HTTP $status: $explanation", status, serverMessage)
}

private fun transportFailure(
    method: String,
    path: String,
    cause: Exception,
): FakeLlmClientException = FakeLlmClientException("$method $path failed: $cause", statusCode = null, serverMessage = null, cause = cause)

private fun undecodableResponse(
    method: String,
    path: String,
    status: Int,
    body: String,
    cause: SerializationException,
): FakeLlmClientException =
    FakeLlmClientException(
        "$method $path returned HTTP $status with a body this client cannot read: ${body.take(RAW_BODY_LIMIT)}",
        statusCode = status,
        serverMessage = null,
        cause = cause,
    )

/**
 * [raw] without trailing slashes. Throws [IllegalArgumentException] unless it is an `http` or
 * `https` URL with a host and without a query or fragment, since request paths are appended to it.
 */
internal fun normalizedBaseUrl(raw: String): String {
    val trimmed = raw.trimEnd('/')
    val uri = parseUriOrNull(trimmed)
    val scheme = uri?.scheme
    require(
        uri != null &&
            (scheme.equals("http", ignoreCase = true) || scheme.equals("https", ignoreCase = true)) &&
            !uri.host.isNullOrEmpty(),
    ) {
        "baseUrl must be an http:// or https:// URL with a valid host name (no underscores), such as " +
            "http://localhost:8080; was '$raw'"
    }
    require(uri.port in NO_PORT..MAX_URL_PORT) { "baseUrl has a port outside 0-65535; was '$raw'" }
    require(uri.rawQuery == null && uri.rawFragment == null) { "baseUrl must not have a query or fragment; was '$raw'" }
    return trimmed
}

// An unparseable URL is reported by the caller's require, with the original text.
@Suppress("SwallowedException")
private fun parseUriOrNull(value: String): URI? =
    try {
        URI(value)
    } catch (e: URISyntaxException) {
        null
    }
