package com.davidjonesgriz.fakellm.conformance

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** The response as the server sent it. Header names are lower-cased; repeated values are joined with `, `. */
data class ActualResponse(
    val status: Int,
    val headers: Map<String, String>,
    val body: String,
)

/** A case that did not get the responses it expects. The message says where and what differed. */
class CaseFailedException(
    message: String,
    cause: Throwable? = null,
) : AssertionError(message, cause)

/**
 * Sends requests to one server over HTTP/1.1 and checks the responses against a case. Each
 * response, body included, must arrive within [requestTimeout], so a stream that stalls fails the
 * case instead of hanging the run.
 */
class CaseRunner(
    baseUrl: String,
    private val requestTimeout: Duration = Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS),
) : AutoCloseable {
    private val base = baseUrl.trimEnd('/')
    private val http: HttpClient =
        HttpClient
            .newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(requestTimeout)
            .build()

    /**
     * Loads the case's scenario (unless it is null), then sends each exchange in order. Throws
     * [CaseFailedException] at the first response that differs, naming the exchange (1-based), the
     * request, the difference and the response.
     */
    fun run(case: Case) {
        loadScenario(case)
        case.exchanges.forEachIndexed { i, exchange ->
            val actual = send(exchange.request)
            mismatch(exchange.response, actual)?.let { difference ->
                throw CaseFailedException(
                    "${case.name}, exchange ${i + 1} (${exchange.request.method} ${exchange.request.path}): $difference\n" +
                        "  response: ${actual.status} ${actual.body.take(BODY_PREVIEW_CHARS)}",
                )
            }
        }
    }

    private fun loadScenario(case: Case) {
        val load =
            when (val scenario = case.scenario) {
                is JsonNull -> return
                is JsonPrimitive -> buildJsonObject { put("name", scenario) }
                else -> buildJsonObject { put("scenario", scenario) }
            }
        val headers =
            mapOf("content-type" to "application/json") +
                listOfNotNull(case.server.controlToken?.let { "authorization" to "Bearer $it" })
        val actual = send(CaseRequest("POST", "/control/scenario", headers, load))
        if (actual.status != HTTP_OK) {
            throw CaseFailedException("${case.name}, scenario load: expected 200, got ${actual.status}: ${actual.body}")
        }
    }

    override fun close() = http.close()

    /**
     * Sends one request. No complete response (body included) within [requestTimeout], or none at
     * all, throws [CaseFailedException].
     */
    fun send(request: CaseRequest): ActualResponse {
        val payload = request.rawBody ?: request.body?.toString()
        val publisher = payload?.let { HttpRequest.BodyPublishers.ofString(it) } ?: HttpRequest.BodyPublishers.noBody()
        val builder =
            HttpRequest
                .newBuilder(uriFor(request))
                .timeout(requestTimeout)
                .method(request.method, publisher)
        request.headers.forEach { (name, value) -> builder.header(name, value) }
        val response = await(http.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofString()), request)
        return ActualResponse(
            status = response.statusCode(),
            headers =
                response
                    .headers()
                    .map()
                    .mapKeys { it.key.lowercase() }
                    .mapValues { it.value.joinToString(", ") },
            body = response.body(),
        )
    }

    private fun uriFor(request: CaseRequest): URI =
        try {
            URI.create(base + request.path)
        } catch (e: IllegalArgumentException) {
            throw CaseFailedException("${request.method} ${request.path} is not a valid request path: ${e.message}", e)
        }

    /** send's timeout stops at the headers; waiting on the future bounds the body too. */
    private fun await(
        future: CompletableFuture<HttpResponse<String>>,
        request: CaseRequest,
    ): HttpResponse<String> =
        try {
            future.get(requestTimeout.toMillis(), TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            future.cancel(true)
            throw CaseFailedException("${request.method} ${request.path}: no complete response within ${requestTimeout.toMillis()} ms")
        } catch (e: ExecutionException) {
            throw CaseFailedException("${request.method} ${request.path} got no response: ${e.cause}", e)
        }

    private companion object {
        const val REQUEST_TIMEOUT_SECONDS = 10L
        const val BODY_PREVIEW_CHARS = 2000
        const val HTTP_OK = 200
    }
}

/** The first way [actual] differs from [expected], or null when it matches. */
fun mismatch(
    expected: ExpectedResponse,
    actual: ActualResponse,
): String? {
    if (actual.status != expected.status) return "expected status ${expected.status}, got ${actual.status}"
    return expected.headers.entries.firstNotNullOfOrNull { (name, value) -> headerMismatch(name, value, actual.headers[name.lowercase()]) }
        ?: expected.body?.let { body -> bodyMismatch(body, actual.body) }
        ?: expected.rawBody?.takeIf { it != actual.body }?.let { "expected the body text '$it', got '${actual.body}'" }
        ?: expected.events?.let { eventsMismatch(it, parseSse(actual.body)) }
}

private fun bodyMismatch(
    expected: JsonElement,
    body: String,
): String? {
    val parsed = parseJson(body) ?: return "expected a JSON body, got: $body"
    return firstDifference(expected, parsed)
}

private fun headerMismatch(
    name: String,
    expected: String,
    actual: String?,
): String? =
    when {
        actual == null -> "missing header '$name' (expected '$expected')"
        !headerMatches(name, expected, actual) -> "header '$name': expected '$expected', got '$actual'"
        else -> null
    }

private fun eventsMismatch(
    expected: List<ExpectedEvent>,
    actual: List<SseEvent>,
): String? {
    val eventMismatch =
        expected.indices.firstNotNullOfOrNull { i ->
            val want = expected[i]
            val got = actual.getOrNull(i)
            when {
                got == null -> "missing event ${i + 1} ('${want.event}'): the stream has ${actual.size} events"
                got.event != want.event -> "event ${i + 1}: expected '${want.event}', got '${got.event}'"
                else -> dataMismatch(want.data, got.data)?.let { "event ${i + 1} ('${want.event}') data: $it" }
            }
        }
    return eventMismatch
        ?: actual.getOrNull(expected.size)?.let { "unexpected event ${expected.size + 1} ('${it.event}'): ${it.data}" }
}

/** A string is compared with the raw data; any other value with the data parsed as JSON. */
private fun dataMismatch(
    expected: JsonElement,
    data: String,
): String? {
    val parsed = parseJson(data)
    return when {
        expected is JsonPrimitive && expected.isString -> {
            if (data ==
                expected.content
            ) {
                null
            } else {
                "expected the text '${expected.content}', got '$data'"
            }
        }

        parsed == null -> {
            "expected JSON, got '$data'"
        }

        else -> {
            firstDifference(expected, parsed)
        }
    }
}

/**
 * Parses [text] as JSON, or returns null. kotlinx-serialization accepts a bare word such as `plain`
 * as a primitive; that is not JSON, so it counts as unparseable here.
 */
private fun parseJson(text: String): JsonElement? {
    val parsed =
        try {
            Json.parseToJsonElement(text)
        } catch (_: SerializationException) {
            return null
        }
    val bareWord =
        parsed is JsonPrimitive &&
            parsed !is JsonNull &&
            !parsed.isString &&
            parsed.content != "true" &&
            parsed.content != "false" &&
            parsed.content.toBigDecimalOrNull() == null
    return if (bareWord) null else parsed
}
