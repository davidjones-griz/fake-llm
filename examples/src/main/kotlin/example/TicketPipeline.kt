package example

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.stream.Stream

/** One stage's model: its API root without the version segment, its key, and the model id. */
data class Provider(
    val baseUrl: String,
    val apiKey: String,
    val model: String,
)

enum class Category { BILLING, BUG, SPAM }

/** What the extraction stage reads from a ticket. */
@Serializable
data class TicketDetails(
    val orderId: String,
    val problem: String,
)

/** The pipeline's result. A spam ticket stops after classification, with no details or summary. */
data class Triage(
    val category: Category,
    val details: TicketDetails? = null,
    val summary: Summary? = null,
)

/**
 * Triages a support ticket in three model calls, each to a different provider's API:
 * 1. Classify it as billing, bug or spam with an Anthropic Messages call. Spam stops here.
 * 2. Extract the order id and the problem with an OpenAI Chat Completions call that forces the
 *    `record_ticket` tool. When the tool call's arguments are not valid, the call is made again.
 * 3. Summarize it for the support queue with a streamed Gemini `streamGenerateContent` call.
 *
 * A 429 or 529 is retried after its `retry-after` seconds (1 if absent), up to [maxAttempts] calls
 * for that request; any other error status is not retried. Invalid tool-call arguments are asked
 * for again, up to [maxAttempts] extraction requests. An error status that is not retried or is
 * still returned on the last call, or arguments still invalid after the last request, throws
 * [IllegalStateException] naming the stage.
 */
class TicketPipeline(
    private val classifier: Provider,
    private val extractor: Provider,
    private val summarizer: Provider,
    private val maxAttempts: Int = 2,
    private val http: HttpClient = HttpClient.newHttpClient(),
) {
    fun triage(ticket: String): Triage {
        val category = classify(ticket)
        if (category == Category.SPAM) return Triage(category)
        val details = extract(ticket)
        return Triage(category, details, summarize(ticket, category, details))
    }

    private fun classify(ticket: String): Category {
        val body =
            buildJsonObject {
                put("model", classifier.model)
                put("max_tokens", CLASSIFY_MAX_TOKENS)
                put("system", "Classify the support ticket. Answer with one word: billing, bug or spam.")
                putJsonArray("messages") { add(userMessage(ticket)) }
            }
        val request =
            post("${classifier.baseUrl}/v1/messages", body)
                .header("x-api-key", classifier.apiKey)
                .header("anthropic-version", "2023-06-01")
                .build()
        val text =
            sendJson("classify", request)
                .getValue("content")
                .jsonArray
                .map { it.jsonObject }
                .first { it["type"] == JsonPrimitive("text") }
                .getValue("text")
                .jsonPrimitive.content
        return Category.valueOf(text.trim().uppercase())
    }

    private fun extract(ticket: String): TicketDetails {
        val body =
            buildJsonObject {
                put("model", extractor.model)
                putJsonArray("messages") { add(userMessage("Record the order id and the problem in this ticket:\n\n$ticket")) }
                putJsonArray("tools") { add(RECORD_TICKET_TOOL) }
                putJsonObject("tool_choice") {
                    put("type", "function")
                    putJsonObject("function") { put("name", RECORD_TICKET) }
                }
            }
        val request =
            post("${extractor.baseUrl}/v1/chat/completions", body)
                .header("authorization", "Bearer ${extractor.apiKey}")
                .build()
        var attempt = 1
        while (true) {
            val arguments =
                sendJson("extract", request)
                    .getValue("choices")
                    .jsonArray[0]
                    .jsonObject
                    .getValue("message")
                    .jsonObject
                    .getValue("tool_calls")
                    .jsonArray[0]
                    .jsonObject
                    .getValue("function")
                    .jsonObject
                    .getValue("arguments")
                    .jsonPrimitive.content
            val details = runCatching { Json.decodeFromString<TicketDetails>(arguments) }.getOrNull()
            if (details != null) return details
            check(attempt < maxAttempts) { "extract gave up after $attempt calls with invalid $RECORD_TICKET arguments: $arguments" }
            attempt++
        }
    }

    private fun summarize(
        ticket: String,
        category: Category,
        details: TicketDetails,
    ): Summary {
        val prompt =
            "Summarize this ${category.name.lowercase()} ticket for the support queue in one sentence. " +
                "Order ${details.orderId}: ${details.problem}.\n\n$ticket"
        val body =
            buildJsonObject {
                putJsonArray("contents") {
                    add(
                        buildJsonObject {
                            put("role", "user")
                            putJsonArray("parts") { add(buildJsonObject { put("text", prompt) }) }
                        },
                    )
                }
                putJsonObject("generationConfig") { put("maxOutputTokens", SUMMARY_MAX_TOKENS) }
            }
        val request =
            post("${summarizer.baseUrl}/v1beta/models/${summarizer.model}:streamGenerateContent?alt=sse", body)
                .header("x-goog-api-key", summarizer.apiKey)
                .build()
        val text = StringBuilder()
        var finishReason: String? = null
        send("summarize", request).use { lines ->
            // Each data frame is one chunk with one candidate; only the last one has a finishReason.
            for (line in lines.iterator()) {
                if (!line.startsWith("data: ")) continue
                val candidate =
                    Json
                        .parseToJsonElement(line.removePrefix("data: "))
                        .jsonObject
                        .getValue("candidates")
                        .jsonArray[0]
                        .jsonObject
                text.append(textOf(candidate))
                finishReason = candidate["finishReason"]?.jsonPrimitive?.content ?: finishReason
            }
        }
        return Summary(text.toString(), truncated = finishReason == "MAX_TOKENS")
    }

    private fun textOf(candidate: JsonObject): String =
        candidate["content"]
            ?.jsonObject
            ?.get("parts")
            ?.jsonArray
            .orEmpty()
            .joinToString("") { part ->
                part.jsonObject["text"]
                    ?.jsonPrimitive
                    ?.content
                    .orEmpty()
            }

    private fun post(
        url: String,
        body: JsonObject,
    ): HttpRequest.Builder =
        HttpRequest
            .newBuilder(URI(url))
            .header("content-type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString()))

    private fun sendJson(
        stage: String,
        request: HttpRequest,
    ): JsonObject = send(stage, request).use { lines -> Json.parseToJsonElement(lines.toList().joinToString("\n")).jsonObject }

    /** The response body's lines, after retrying a rate limit or overload. The caller closes the stream. */
    private fun send(
        stage: String,
        request: HttpRequest,
    ): Stream<String> {
        var attempt = 1
        while (true) {
            val response = http.send(request, HttpResponse.BodyHandlers.ofLines())
            val status = response.statusCode()
            if (status == HTTP_OK) return response.body()
            val body = response.body().use { lines -> lines.toList().joinToString("\n") }
            check(status in RETRYABLE && attempt < maxAttempts) { "$stage failed with HTTP $status: $body" }
            val waitSeconds =
                response
                    .headers()
                    .firstValue("retry-after")
                    .map(String::toLong)
                    .orElse(1L)
            Thread.sleep(waitSeconds * MILLIS_PER_SECOND)
            attempt++
        }
    }

    private companion object {
        const val HTTP_OK = 200
        const val CLASSIFY_MAX_TOKENS = 8
        const val SUMMARY_MAX_TOKENS = 128
        const val MILLIS_PER_SECOND = 1000L
        const val RECORD_TICKET = "record_ticket"
        val RETRYABLE = setOf(429, 529)

        val RECORD_TICKET_TOOL =
            buildJsonObject {
                put("type", "function")
                putJsonObject("function") {
                    put("name", RECORD_TICKET)
                    put("description", "Record the ticket's order id and problem.")
                    putJsonObject("parameters") {
                        put("type", "object")
                        putJsonObject("properties") {
                            putJsonObject("orderId") { put("type", "string") }
                            putJsonObject("problem") { put("type", "string") }
                        }
                        putJsonArray("required") {
                            add(JsonPrimitive("orderId"))
                            add(JsonPrimitive("problem"))
                        }
                    }
                }
            }

        fun userMessage(content: String) =
            buildJsonObject {
                put("role", "user")
                put("content", content)
            }
    }
}
