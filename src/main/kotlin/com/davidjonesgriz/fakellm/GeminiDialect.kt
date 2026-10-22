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
    caller: String? = null,
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
        stream = false,
        caller = caller,
    )
}

internal fun geminiEnvelope(
    text: String,
    stopReason: String,
    seq: Long,
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
                                        add(buildJsonObject { put("text", text) })
                                    },
                                )
                            },
                        )
                        put("finishReason", geminiFinishReason(stopReason))
                    },
                )
            },
        )
        put(
            "usageMetadata",
            buildJsonObject {
                put("promptTokenCount", FIXED_INPUT_TOKENS)
                put("candidatesTokenCount", FIXED_OUTPUT_TOKENS)
                put("totalTokenCount", FIXED_INPUT_TOKENS + FIXED_OUTPUT_TOKENS)
            },
        )
        put("fake_llm_seq", seq)
    }.toString()

private fun geminiFinishReason(anthropicStopReason: String): String =
    if (anthropicStopReason == "max_tokens") "MAX_TOKENS" else "STOP"

internal fun geminiModelFromPath(path: String): String {
    val marker = "/models/"
    val idx = path.indexOf(marker)
    if (idx < 0) return ""
    val rest = path.substring(idx + marker.length)
    return rest.substringBefore(':')
}
