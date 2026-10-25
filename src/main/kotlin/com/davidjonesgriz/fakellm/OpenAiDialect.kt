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

private val openAiLenientJson = Json { ignoreUnknownKeys = true }

internal fun parseOpenAiRequest(body: String): ParsedRequest {
    val obj = openAiLenientJson.parseToJsonElement(body).jsonObject
    val model = (obj["model"] as? JsonPrimitive)?.content ?: ""
    val messages = obj["messages"] as? JsonArray ?: error("request has no messages array")
    val systemShape =
        if (messages.any {
                ((it as? JsonObject)?.get("role") as? JsonPrimitive)?.content == "system"
            }
        ) {
            "blocks"
        } else {
            "absent"
        }
    val text =
        buildString {
            messages.forEach { m ->
                val content = (m as? JsonObject)?.get("content") as? JsonPrimitive
                if (content != null) append(content.content).append('\n')
            }
        }
    val stream = (obj["stream"] as? JsonPrimitive)?.content?.toBoolean() ?: false
    return ParsedRequest(
        model = model,
        systemShape = systemShape,
        promptText = text,
        messageCount = messages.size,
        stream = stream,
    )
}

internal fun openAiEnvelope(
    text: String,
    stopReason: String,
    seq: Long,
): String =
    buildJsonObject {
        put("id", "fake-openai-$seq")
        put("object", "chat.completion")
        put("model", "fake-openai")
        put(
            "choices",
            buildJsonArray {
                add(
                    buildJsonObject {
                        put("index", 0)
                        put(
                            "message",
                            buildJsonObject {
                                put("role", "assistant")
                                put("content", text)
                            },
                        )
                        put("finish_reason", openAiFinishReason(stopReason))
                    },
                )
            },
        )
        put(
            "usage",
            buildJsonObject {
                put("prompt_tokens", FIXED_INPUT_TOKENS)
                put("completion_tokens", FIXED_OUTPUT_TOKENS)
                put("total_tokens", FIXED_INPUT_TOKENS + FIXED_OUTPUT_TOKENS)
            },
        )
    }.toString()

private fun openAiFinishReason(anthropicStopReason: String): String = if (anthropicStopReason == "max_tokens") "length" else "stop"
