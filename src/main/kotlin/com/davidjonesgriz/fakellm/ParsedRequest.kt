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
    val hasCacheBreakpoint: Boolean = false,
    /** OpenAI only: the request is streamed and sets `stream_options.include_usage` to true. */
    val includeUsage: Boolean = false,
    /** Every tool result the request sends, in request order. See [CallRecord.toolResults]. */
    val toolResults: List<ToolResultRecord> = emptyList(),
)

/** [key]'s value when it is a JSON string, else null. */
internal fun JsonObject.stringField(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

/** [key]'s value, or null when it is absent or JSON `null`. */
internal fun JsonObject.valueOrNull(key: String): JsonElement? = this[key]?.takeUnless { it is JsonNull }

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

internal fun parseAnthropicRequest(body: String): ParsedRequest {
    val obj = lenientJson.parseToJsonElement(body).jsonObject
    val model = obj.stringField("model").orEmpty()
    val system = obj["system"]
    val systemShape =
        when (system) {
            null, JsonNull -> "absent"
            is JsonPrimitive -> if (system.isString) "string" else "unknown"
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
        hasCacheBreakpoint = hasCacheBreakpoint,
        toolResults = anthropicToolResults(messages),
    )
}

/**
 * Every `tool_result` block in [messages], in order. Each is named after the first `tool_use` block
 * in the request with the same id.
 */
private fun anthropicToolResults(messages: JsonArray): List<ToolResultRecord> {
    val blocks =
        messages.flatMap { m ->
            ((m as? JsonObject)?.get("content") as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
        }
    val names =
        blocks
            .filter { it.stringField("type") == "tool_use" }
            .mapNotNull { block -> block.stringField("id")?.let { it to block.stringField("name") } }
            .distinctBy { it.first }
            .toMap()
    return blocks.filter { it.stringField("type") == "tool_result" }.map { block ->
        val id = block.stringField("tool_use_id")
        val isError = (block["is_error"] as? JsonPrimitive)?.let { !it.isString && it.content == "true" } == true
        ToolResultRecord(id = id, name = id?.let { names[it] }, content = block.valueOrNull("content"), isError = isError)
    }
}

private fun StringBuilder.appendTexts(el: JsonElement?) {
    when (el) {
        null -> {}

        JsonNull -> {}

        // Only JSON strings are text: null, numbers and booleans add nothing.
        is JsonPrimitive -> {
            if (el.isString) append(el.content).append('\n')
        }

        // A block's string `text`; a `tool_result` block adds its own `content` the same way.
        is JsonArray -> {
            el.filterIsInstance<JsonObject>().forEach { block ->
                block.stringField("text")?.let { append(it).append('\n') }
                if (block.stringField("type") == "tool_result") appendTexts(block["content"])
            }
        }

        else -> {}
    }
}

/**
 * The buffered Anthropic message for [step]: the thinking block, the text block when it has text,
 * then one `tool_use` block per call. With tool calls, `stop_reason` is `tool_use`. `stop_sequence`
 * is the step's [Step.stopSequence], or null. [model] is the request's model, echoed as the API
 * echoes the model that served the request.
 */
internal fun anthropicEnvelope(
    step: Step,
    seq: Long,
    model: String,
    cacheable: Boolean = true,
    toolCalls: List<SentToolCall> = emptyList(),
): String {
    val text = step.text
    val stopReason = step.stopReason
    val usage = step.appliedUsage
    val thinking = step.thinking
    return buildJsonObject {
        put("id", "fake-msg-$seq")
        put("type", "message")
        put("role", "assistant")
        put("model", model)
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
                if (text != null) {
                    add(
                        buildJsonObject {
                            put("type", "text")
                            put("text", text)
                        },
                    )
                }
                toolCalls.forEach { call ->
                    add(
                        buildJsonObject {
                            put("type", "tool_use")
                            put("id", call.id)
                            put("name", call.name)
                            put("input", call.arguments ?: JsonObject(emptyMap()))
                        },
                    )
                }
            },
        )
        put("stop_reason", if (toolCalls.isEmpty()) stopReason else "tool_use")
        put("stop_sequence", step.stopSequence)
        put(
            "usage",
            buildJsonObject {
                put("input_tokens", usage.inputTokens)
                put("output_tokens", usage.outputTokens)
                put("cache_read_input_tokens", if (cacheable) FIXED_CACHE_READ_TOKENS else 0)
                put("cache_creation_input_tokens", if (cacheable) FIXED_CACHE_CREATION_TOKENS else 0)
            },
        )
    }.toString()
}

internal const val FIXED_INPUT_TOKENS = 100
internal const val FIXED_OUTPUT_TOKENS = 50
internal const val FAKE_THINKING_SIGNATURE = "fake-thinking-signature"
internal const val FIXED_CACHE_CREATION_TOKENS = 20
internal const val FIXED_CACHE_READ_TOKENS = 10
