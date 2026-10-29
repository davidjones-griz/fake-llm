package com.davidjonesgriz.fakellm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

// The OpenAI Responses API, `POST /openai/v1/responses`: the request parser and the buffered
// `Response` body. The route is stateless: `previous_response_id`, `store` and `conversation` are
// accepted and ignored, nothing is stored or resolved, and each call is the next step. Only the
// request's own `instructions` and `input` are read.

/** The call log's and the 400 bodies' vendor for the Responses route. */
internal const val RESPONSES_VENDOR = "openai-responses"

private val responsesLenientJson = Json { ignoreUnknownKeys = true }

/**
 * Reads a Responses request leniently: a field of the wrong JSON type is skipped or read as absent.
 * `messageCount` is 1 for a string `input`, the item count for an array, and 0 otherwise. The prompt
 * text is `instructions` and a string `input`, then each message item's string content or part
 * `text`, and each `function_call_output`'s string `output`. Every other field is ignored. A body
 * that is not a JSON object throws.
 */
internal fun parseResponsesRequest(body: String): ParsedRequest {
    val obj = responsesLenientJson.parseToJsonElement(body).jsonObject
    val input = obj["input"]
    val items = (input as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
    return ParsedRequest(
        model = (obj["model"] as? JsonPrimitive)?.content ?: "",
        systemShape = responsesSystemShape(obj["instructions"], items),
        promptText = responsesPromptText(obj["instructions"], input, items),
        messageCount =
            when (input) {
                is JsonArray -> input.size
                is JsonPrimitive -> if (input.isString) 1 else 0
                else -> 0
            },
        stream = (obj["stream"] as? JsonPrimitive)?.content?.toBoolean() ?: false,
        toolResults = responsesToolResults(items),
    )
}

/** `string` for string `instructions`; without them, `blocks` for a system or developer item, else `absent`; otherwise `unknown`. */
private fun responsesSystemShape(
    instructions: JsonElement?,
    items: List<JsonObject>,
): String =
    when {
        instructions == null || instructions == JsonNull -> {
            if (items.any { it.stringField("role") in setOf("system", "developer") }) "blocks" else "absent"
        }

        instructions is JsonPrimitive && instructions.isString -> {
            "string"
        }

        else -> {
            "unknown"
        }
    }

private fun responsesPromptText(
    instructions: JsonElement?,
    input: JsonElement?,
    items: List<JsonObject>,
): String =
    buildString {
        (instructions as? JsonPrimitive)?.takeIf { it.isString }?.let { append(it.content).append('\n') }
        (input as? JsonPrimitive)?.takeIf { it.isString }?.let { append(it.content).append('\n') }
        items.forEach { appendItemText(it) }
    }

/** Appends one `input` item's prompt text. An item whose `type` is absent or not a string is a message. */
private fun StringBuilder.appendItemText(item: JsonObject) {
    when (item.stringField("type")) {
        null, "message" -> {
            when (val content = item["content"]) {
                is JsonPrimitive -> {
                    if (content.isString) append(content.content).append('\n')
                }

                is JsonArray -> {
                    content.filterIsInstance<JsonObject>().mapNotNull { it.stringField("text") }.forEach { append(it).append('\n') }
                }

                else -> {}
            }
        }

        "function_call_output" -> {
            item.stringField("output")?.let { append(it).append('\n') }
        }

        else -> {}
    }
}

/** Every `function_call_output` item, in order, named after the first `function_call` item with the same `call_id`. */
private fun responsesToolResults(items: List<JsonObject>): List<ToolResultRecord> {
    val names =
        items
            .filter { it.stringField("type") == "function_call" }
            .mapNotNull { call -> call.stringField("call_id")?.let { it to call.stringField("name") } }
            .distinctBy { it.first }
            .toMap()
    return items.filter { it.stringField("type") == "function_call_output" }.map { item ->
        val id = item.stringField("call_id")
        ToolResultRecord(id = id, name = id?.let { names[it] }, content = item.valueOrNull("output"), isError = false)
    }
}

/**
 * The buffered `Response` for call [seq]: a message item when [text] is set, then one
 * `function_call` item per tool call. `max_tokens` makes it `incomplete` with
 * `max_output_tokens`; anything else is `completed`.
 */
internal fun responsesEnvelope(
    text: String?,
    stopReason: String,
    seq: Long,
    toolCalls: List<SentToolCall> = emptyList(),
    usage: TokenUsage = DEFAULT_USAGE,
): String {
    val status = if (stopReason == "max_tokens") "incomplete" else "completed"
    return responsesResponseObject(seq, status, responsesOutput(text, stopReason, seq, toolCalls), usage).toString()
}

/**
 * The `Response` object in the API's key order. `incomplete_details` is `max_output_tokens` when
 * [status] is `incomplete`, and null otherwise. When [usage] is null, `usage` is null, as on the
 * in-progress response a stream starts with.
 */
internal fun responsesResponseObject(
    seq: Long,
    status: String,
    output: JsonArray,
    usage: TokenUsage?,
): JsonObject =
    buildJsonObject {
        put("id", "resp_fake_$seq")
        put("object", "response")
        put("status", status)
        put("error", JsonNull)
        put("incomplete_details", if (status == "incomplete") buildJsonObject { put("reason", "max_output_tokens") } else JsonNull)
        put("model", "fake-openai")
        put("output", output)
        put("parallel_tool_calls", true)
        put("tool_choice", "auto")
        put("tools", buildJsonArray {})
        if (usage != null) {
            put(
                "usage",
                buildJsonObject {
                    put("input_tokens", usage.inputTokens)
                    put("input_tokens_details", buildJsonObject { put("cached_tokens", 0) })
                    put("output_tokens", usage.outputTokens)
                    put("output_tokens_details", buildJsonObject { put("reasoning_tokens", 0) })
                    put("total_tokens", usage.totalTokens)
                },
            )
        } else {
            put("usage", JsonNull)
        }
    }

/** The `output` items: the message (when [text] is set), then the function calls `fc_fake_<seq>_<n>`. */
internal fun responsesOutput(
    text: String?,
    stopReason: String,
    seq: Long,
    toolCalls: List<SentToolCall>,
): JsonArray =
    buildJsonArray {
        if (text != null) add(responsesMessageItem(seq, if (stopReason == "max_tokens") "incomplete" else "completed", text))
        toolCalls.forEachIndexed { i, call ->
            add(responsesFunctionCallItem("fc_fake_${seq}_${i + 1}", call, "completed", call.argumentText))
        }
    }

/** The assistant message item `msg_fake_<seq>`. A null [text] gives empty `content`, as when a stream opens it. */
internal fun responsesMessageItem(
    seq: Long,
    status: String,
    text: String?,
): JsonObject =
    buildJsonObject {
        put("id", "msg_fake_$seq")
        put("type", "message")
        put("status", status)
        put("role", "assistant")
        put(
            "content",
            buildJsonArray {
                if (text != null) {
                    add(
                        buildJsonObject {
                            put("type", "output_text")
                            put("text", text)
                            put("annotations", buildJsonArray {})
                        },
                    )
                }
            },
        )
    }

/** One `function_call` output item; its `call_id` is the [call]'s sent id. */
internal fun responsesFunctionCallItem(
    itemId: String,
    call: SentToolCall,
    status: String,
    arguments: String,
): JsonObject =
    buildJsonObject {
        put("id", itemId)
        put("type", "function_call")
        put("status", status)
        put("call_id", call.id)
        put("name", call.name)
        put("arguments", arguments)
    }
