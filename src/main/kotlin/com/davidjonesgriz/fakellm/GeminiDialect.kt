package com.davidjonesgriz.fakellm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

private val geminiLenientJson = Json { ignoreUnknownKeys = true }

internal fun parseGeminiRequest(
    body: String,
    modelFromPath: String,
    stream: Boolean = false,
): ParsedRequest {
    val obj = geminiLenientJson.parseToJsonElement(body).jsonObject
    val contents = obj["contents"] as? JsonArray ?: error("request has no contents array")
    val text =
        buildString {
            contents.forEach { c ->
                val parts = (c as? JsonObject)?.get("parts") as? JsonArray
                parts?.forEach { p ->
                    val t = (p as? JsonObject)?.get("text") as? JsonPrimitive
                    if (t != null) append(t.content).append('\n')
                }
            }
        }
    return ParsedRequest(
        model = modelFromPath,
        systemShape = "absent",
        promptText = text,
        messageCount = contents.size,
        stream = stream,
        toolResults = geminiToolResults(contents),
    )
}

/** Every `functionResponse` part in [contents], in order. */
private fun geminiToolResults(contents: JsonArray): List<ToolResultRecord> =
    contents
        .flatMap { c -> ((c as? JsonObject)?.get("parts") as? JsonArray).orEmpty() }
        .mapNotNull { p -> (p as? JsonObject)?.get("functionResponse") as? JsonObject }
        .map { r ->
            ToolResultRecord(id = r.stringField("id"), name = r.stringField("name"), content = r.valueOrNull("response"), isError = false)
        }

/**
 * The buffered `generateContent` reply: a text part when [text] is set, then one `functionCall`
 * part per tool call, whose `id` is sent only when scripted. With tool calls, `finishReason` is
 * `STOP`.
 */
internal fun geminiEnvelope(
    text: String?,
    stopReason: String,
    seq: Long,
    toolCalls: List<SentToolCall> = emptyList(),
    usage: TokenUsage = DEFAULT_USAGE,
): String =
    buildJsonObject {
        put(
            "candidates",
            buildJsonArray {
                add(
                    buildJsonObject {
                        put(
                            "content",
                            buildJsonObject {
                                put("role", "model")
                                put(
                                    "parts",
                                    buildJsonArray {
                                        if (text != null) add(buildJsonObject { put("text", text) })
                                        toolCalls.forEach { add(geminiFunctionCallPart(it)) }
                                    },
                                )
                            },
                        )
                        // A step with tool calls has stopReason end_turn or tool_use, both STOP.
                        put("finishReason", geminiFinishReason(stopReason))
                    },
                )
            },
        )
        put(
            "usageMetadata",
            buildJsonObject {
                put("promptTokenCount", usage.inputTokens)
                put("candidatesTokenCount", usage.outputTokens)
                put("totalTokenCount", usage.totalTokens)
            },
        )
        put("fake_llm_seq", seq)
    }.toString()

internal fun geminiFunctionCallPart(call: SentToolCall): JsonObject =
    buildJsonObject {
        put(
            "functionCall",
            buildJsonObject {
                if (call.id != null) put("id", call.id)
                put("name", call.name)
                put("args", call.arguments ?: JsonObject(emptyMap()))
            },
        )
    }

internal fun geminiFinishReason(anthropicStopReason: String): String = if (anthropicStopReason == "max_tokens") "MAX_TOKENS" else "STOP"

internal fun geminiModelFromPath(path: String): String {
    val marker = "/models/"
    val idx = path.indexOf(marker)
    if (idx < 0) return ""
    val rest = path.substring(idx + marker.length)
    return rest.substringBefore(':')
}
