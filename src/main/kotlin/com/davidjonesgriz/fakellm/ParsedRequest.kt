package com.davidjonesgriz.fakellm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

internal data class ParsedRequest(
    val model: String,
    val systemShape: String,
    val promptText: String,
    val messageCount: Int,
    val stream: Boolean = false,
    /** Value of the `X-Fake-LLM-Caller` header when the client sent one. */
    val caller: String? = null,
    val hasCacheBreakpoint: Boolean = false,
)

private fun hasCacheControl(el: JsonElement?): Boolean =
    when (el) {
        is JsonArray -> {
            el
                .map { block ->
                    val cacheControl = (block as? JsonObject)?.get("cache_control")
                    if (cacheControl != null) validateCacheControl(cacheControl)
                    cacheControl != null
                }.any { it }
        }

        else -> {
            false
        }
    }

private val VALID_CACHE_TTLS = setOf("5m", "1h")

private fun validateCacheControl(raw: JsonElement) {
    val obj = raw as? JsonObject ?: error("cache_control must be a JSON object, got $raw")
    val type = (obj["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content
    require(type == "ephemeral") { "cache_control.type must be \"ephemeral\", got ${obj["type"]}" }
    val ttl = obj["ttl"] ?: return
    require(ttl !is JsonNull) {
        "cache_control.ttl must not be JSON null — omit the key for the default 5m ttl"
    }
    val ttlValue = (ttl as? JsonPrimitive)?.takeIf { it.isString }?.content
    require(ttlValue in VALID_CACHE_TTLS) { "cache_control.ttl must be one of $VALID_CACHE_TTLS, got $ttl" }
}

private val lenientJson = Json { ignoreUnknownKeys = true }

internal fun parseAnthropicRequest(
    body: String,
    caller: String? = null,
): ParsedRequest {
    val obj = lenientJson.parseToJsonElement(body).jsonObject
    val model = (obj["model"] as? JsonPrimitive)?.content ?: ""
    val system = obj["system"]
    val systemShape =
        when (system) {
            null -> "absent"
            is JsonPrimitive -> "string"
            is JsonArray -> "blocks"
            else -> "unknown"
        }
    val messages = obj["messages"] as? JsonArray ?: error("request has no messages array")
    val text =
        buildString {
            appendTexts(system)
            messages.forEach { m -> appendTexts((m as? JsonObject)?.get("content")) }
        }
    val stream = (obj["stream"] as? JsonPrimitive)?.content?.toBoolean() ?: false
    val systemHasCacheBreakpoint = hasCacheControl(system)
    val messagesHaveCacheBreakpoint =
        messages.map { hasCacheControl((it as? JsonObject)?.get("content")) }.any { it }
    val hasCacheBreakpoint = systemHasCacheBreakpoint || messagesHaveCacheBreakpoint
    return ParsedRequest(
        model = model,
        systemShape = systemShape,
        promptText = text,
        messageCount = messages.size,
        stream = stream,
        caller = caller,
        hasCacheBreakpoint = hasCacheBreakpoint,
    )
}

private fun StringBuilder.appendTexts(el: JsonElement?) {
    when (el) {
        null -> {}

        is JsonPrimitive -> {
            append(el.content).append('\n')
        }

        is JsonArray -> {
            el.forEach { block ->
                val t = (block as? JsonObject)?.get("text") as? JsonPrimitive
                if (t != null) append(t.content).append('\n')
            }
        }

        else -> {}
    }
}

internal fun anthropicEnvelope(
    text: String,
    stopReason: String,
    seq: Long,
    cacheable: Boolean = true,
    thinking: String? = null,
): String =
    buildJsonObject {
        put("id", "fake-msg-$seq")
        put("type", "message")
        put("role", "assistant")
        put(
            "content",
            buildJsonArray {
                if (thinking != null) {
                    add(
                        buildJsonObject {
                            put("type", "thinking")
                            put("thinking", thinking)
                            put("signature", FAKE_THINKING_SIGNATURE)
                        },
                    )
                }
                add(
                    buildJsonObject {
                        put("type", "text")
                        put("text", text)
                    },
                )
            },
        )
        put("stop_reason", stopReason)
        put(
            "usage",
            buildJsonObject {
                put("input_tokens", FIXED_INPUT_TOKENS)
                put("output_tokens", FIXED_OUTPUT_TOKENS)
                put("cache_read_input_tokens", if (cacheable) FIXED_CACHE_READ_TOKENS else 0)
                put("cache_creation_input_tokens", if (cacheable) FIXED_CACHE_CREATION_TOKENS else 0)
            },
        )
    }.toString()

internal const val FIXED_INPUT_TOKENS = 100
internal const val FIXED_OUTPUT_TOKENS = 50
internal const val FAKE_THINKING_SIGNATURE = "fake-thinking-signature"
internal const val FIXED_CACHE_CREATION_TOKENS = 20
internal const val FIXED_CACHE_READ_TOKENS = 10
