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

/** The message roles that carry a system prompt: `developer` replaces `system` for newer models. */
private val OPENAI_SYSTEM_ROLES = setOf("system", "developer")

internal fun parseOpenAiRequest(body: String): ParsedRequest {
    val obj = parseRequestObject(openAiLenientJson, body)
    val model = obj.stringField("model").orEmpty()
    val messages = obj["messages"] as? JsonArray ?: error("request has no messages array")
    val systemShape =
        if (messages.any { (it as? JsonObject)?.stringField("role") in OPENAI_SYSTEM_ROLES }) "blocks" else "absent"
    val text =
        buildString {
            messages.forEach { m ->
                // String content, or the string `text` of each content part. Anything else, such as
                // an assistant tool-call message's "content": null, adds nothing.
                when (val content = (m as? JsonObject)?.get("content")) {
                    is JsonPrimitive -> {
                        if (content.isString) append(content.content).append('\n')
                    }

                    is JsonArray -> {
                        content.filterIsInstance<JsonObject>().forEach { part ->
                            part.stringField("text")?.let { append(it).append('\n') }
                        }
                    }

                    else -> {}
                }
            }
        }
    val stream = (obj["stream"] as? JsonPrimitive)?.content?.toBoolean() ?: false
    // Read like `stream`, and only on a streamed request.
    val includeUsage =
        stream && ((obj["stream_options"] as? JsonObject)?.get("include_usage") as? JsonPrimitive)?.content?.toBoolean() == true
    val requestMessages = requestMessages(messages)
    val toolNames =
        (obj["tools"] as? JsonArray).orEmpty().mapNotNull {
            ((it as? JsonObject)?.get("function") as? JsonObject)?.stringField("name")
        }
    val choice = openAiToolChoice(obj["tool_choice"], toolNames) { (it["function"] as? JsonObject)?.stringField("name") }
    return ParsedRequest(
        model = model,
        systemShape = systemShape,
        promptText = text,
        messageCount = messages.size,
        stream = stream,
        includeUsage = includeUsage,
        toolResults = openAiToolResults(messages.filterIsInstance<JsonObject>()),
        systemText = requestMessages.filter { it.role in OPENAI_SYSTEM_ROLES }.joinToString("\n") { it.text },
        messages = requestMessages,
        toolNames = toolNames,
        toolChoice = choice.choice,
        toolChoiceName = choice.name,
        body = obj,
    )
}

/**
 * Every `tool` message in [messages], in order. Each is named after the first entry, in any
 * message's `tool_calls`, with the same id.
 */
private fun openAiToolResults(messages: List<JsonObject>): List<ToolResultRecord> {
    val names =
        messages
            .flatMap { (it["tool_calls"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>() }
            .mapNotNull { call -> call.stringField("id")?.let { it to (call["function"] as? JsonObject)?.stringField("name") } }
            .distinctBy { it.first }
            .toMap()
    return messages.filter { it.stringField("role") == "tool" }.map { m ->
        val id = m.stringField("tool_call_id")
        ToolResultRecord(id = id, name = id?.let { names[it] }, content = m.valueOrNull("content"), isError = false)
    }
}

/**
 * The buffered Chat Completions reply to [step], naming [model], the request's model. With tool
 * calls, `message.content` is the step's text or null, `message.tool_calls` lists them, and
 * `finish_reason` is `tool_calls`.
 */
internal fun openAiEnvelope(
    step: Step,
    seq: Long,
    model: String,
    toolCalls: List<SentToolCall> = emptyList(),
): String {
    val text = step.text
    val stopReason = step.stopReason
    val usage = step.appliedUsage
    return buildJsonObject {
        put("id", "fake-openai-$seq")
        put("object", "chat.completion")
        put("model", model)
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
                                if (toolCalls.isNotEmpty()) {
                                    put("tool_calls", buildJsonArray { toolCalls.forEach { add(openAiToolCallJson(it)) } })
                                }
                            },
                        )
                        put("finish_reason", if (toolCalls.isEmpty()) openAiFinishReason(stopReason) else "tool_calls")
                    },
                )
            },
        )
        put(
            "usage",
            buildJsonObject {
                put("prompt_tokens", usage.inputTokens)
                put("completion_tokens", usage.outputTokens)
                put("total_tokens", usage.totalTokens)
            },
        )
    }.toString()
}

/** One entry of a buffered message's `tool_calls`. */
private fun openAiToolCallJson(call: SentToolCall): JsonObject =
    buildJsonObject {
        put("id", call.id)
        put("type", "function")
        put(
            "function",
            buildJsonObject {
                put("name", call.name)
                put("arguments", call.argumentText)
            },
        )
    }

internal fun openAiFinishReason(anthropicStopReason: String): String = if (anthropicStopReason == "max_tokens") "length" else "stop"
