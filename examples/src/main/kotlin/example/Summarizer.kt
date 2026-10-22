package example

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/** A summary, and whether the model stopped at `max_tokens` before finishing it. */
data class Summary(
    val text: String,
    val truncated: Boolean,
)

/**
 * Summarizes text with one Anthropic Messages call. [baseUrl] is the API root without `/v1`,
 * for example `https://api.anthropic.com`. A 429 or 529 is retried, up to [maxAttempts] calls in
 * total, after the `retry-after` seconds the response asks for.
 */
class Summarizer(
    private val baseUrl: String,
    private val apiKey: String,
    private val model: String,
    private val maxAttempts: Int = 2,
    private val http: HttpClient = HttpClient.newHttpClient(),
) {
    fun summarize(document: String): Summary {
        val request =
            HttpRequest
                .newBuilder(URI("$baseUrl/v1/messages"))
                .header("content-type", "application/json")
                .header("x-api-key", apiKey)
                .header("anthropic-version", "2023-06-01")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody(document)))
                .build()
        var attempt = 1
        while (true) {
            val response = http.send(request, HttpResponse.BodyHandlers.ofString())
            val status = response.statusCode()
            if (status == HTTP_OK) return parseSummary(response.body())
            if (status !in RETRYABLE || attempt >= maxAttempts) {
                error("summarize failed with HTTP $status: ${response.body()}")
            }
            val waitSeconds = response.headers().firstValue("retry-after").map(String::toLong).orElse(1L)
            Thread.sleep(waitSeconds * MILLIS_PER_SECOND)
            attempt++
        }
    }

    private fun requestBody(document: String): String =
        buildJsonObject {
            put("model", model)
            put("max_tokens", MAX_TOKENS)
            put(
                "messages",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("role", "user")
                            put("content", "Summarize in one sentence:\n\n$document")
                        },
                    )
                },
            )
        }.toString()

    private fun parseSummary(body: String): Summary {
        val message = Json.parseToJsonElement(body).jsonObject
        val text =
            message
                .getValue("content")
                .jsonArray
                .map { it.jsonObject }
                .first { it["type"] == JsonPrimitive("text") }
                .getValue("text")
                .jsonPrimitive.content
        val truncated = message["stop_reason"]?.jsonPrimitive?.content == "max_tokens"
        return Summary(text, truncated)
    }

    private companion object {
        const val HTTP_OK = 200
        const val MAX_TOKENS = 256
        const val MILLIS_PER_SECOND = 1000L
        val RETRYABLE = setOf(429, 529)
    }
}
