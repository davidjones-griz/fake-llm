// The facts a step's `match` reads from a request, beyond what the call log keeps: the system
// prompt's text, each message's role and text, the declared tools and the tool choice. Each
// dialect fills them; like the rest of the parsers, a field of the wrong JSON type reads as absent.
package com.davidjonesgriz.fakellm

import com.sun.net.httpserver.HttpExchange
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** One entry of a request's message list: its [role] as the request spells it (null when absent), and its [text]. */
internal data class RequestMessage(
    val role: String?,
    val text: String,
)

/**
 * The text of a message's or system prompt's content: a string as given, or the string `text` of
 * each object in an array, joined with newlines. Tool results and other parts add nothing.
 */
internal fun contentText(content: JsonElement?): String =
    when (content) {
        is JsonPrimitive -> if (content.isString) content.content else ""
        is JsonArray -> content.filterIsInstance<JsonObject>().mapNotNull { it.stringField("text") }.joinToString("\n")
        else -> ""
    }

/**
 * Each entry of [list] as a message, so the list has as many entries as the request's message count:
 * an object gives its `role` (or [defaultRole]) and the text of its [contentKey]; anything else gives
 * a message with no role and no text.
 */
internal fun requestMessages(
    list: JsonArray,
    contentKey: String = "content",
    defaultRole: String? = null,
    text: (JsonObject) -> String = { contentText(it[contentKey]) },
): List<RequestMessage> =
    list.map { entry ->
        if (entry is JsonObject) RequestMessage(entry.stringField("role") ?: defaultRole, text(entry)) else RequestMessage(null, "")
    }

/** The string [key] of each object in [list]. */
internal fun namesIn(
    list: JsonElement?,
    key: String = "name",
): List<String> = (list as? JsonArray).orEmpty().filterIsInstance<JsonObject>().mapNotNull { it.stringField(key) }

/**
 * A normalized tool choice: `auto`, `none`, `required`, `tool` (with the forced tool's name) or
 * `other`. Without one in the request, it is `auto` when tools are declared and `none` otherwise,
 * each provider's default.
 */
internal data class ToolChoiceFact(
    val choice: String,
    val name: String? = null,
)

internal fun defaultToolChoice(toolNames: List<String>): ToolChoiceFact = ToolChoiceFact(if (toolNames.isEmpty()) "none" else "auto")

internal val OTHER_TOOL_CHOICE = ToolChoiceFact("other")

/** Anthropic `tool_choice`: an object whose `type` is `auto`, `any`, `none`, or `tool` with a `name`. */
internal fun anthropicToolChoice(
    raw: JsonElement?,
    toolNames: List<String>,
): ToolChoiceFact {
    val obj = raw as? JsonObject
    return when {
        raw == null || raw is JsonNull -> defaultToolChoice(toolNames)
        obj == null -> OTHER_TOOL_CHOICE
        else -> anthropicChoiceOf(obj)
    }
}

private fun anthropicChoiceOf(obj: JsonObject): ToolChoiceFact =
    when (obj.stringField("type")) {
        "auto" -> ToolChoiceFact("auto")
        "any" -> ToolChoiceFact("required")
        "none" -> ToolChoiceFact("none")
        "tool" -> obj.stringField("name")?.let { ToolChoiceFact("tool", it) } ?: OTHER_TOOL_CHOICE
        else -> OTHER_TOOL_CHOICE
    }

/**
 * OpenAI Chat Completions and Responses `tool_choice`: `"auto"`, `"none"`, `"required"`, or an
 * object of type `function` whose name [forcedName] reads.
 */
internal fun openAiToolChoice(
    raw: JsonElement?,
    toolNames: List<String>,
    forcedName: (JsonObject) -> String?,
): ToolChoiceFact =
    when (raw) {
        null, JsonNull -> {
            defaultToolChoice(toolNames)
        }

        is JsonPrimitive -> {
            if (raw.isString && raw.content in OPENAI_TOOL_CHOICES) ToolChoiceFact(raw.content) else OTHER_TOOL_CHOICE
        }

        is JsonObject -> {
            raw.takeIf { it.stringField("type") == "function" }?.let(forcedName)?.let { ToolChoiceFact("tool", it) }
                ?: OTHER_TOOL_CHOICE
        }

        else -> {
            OTHER_TOOL_CHOICE
        }
    }

private val OPENAI_TOOL_CHOICES = setOf("auto", "none", "required")

/**
 * Gemini `toolConfig.functionCallingConfig` (or the proto spellings): mode `AUTO`, `NONE`, or `ANY`,
 * which forces the one tool when `allowedFunctionNames` names exactly one.
 */
internal fun geminiToolChoice(
    request: JsonObject,
    toolNames: List<String>,
): ToolChoiceFact {
    val config = (request.valueOrNull("toolConfig") ?: request.valueOrNull("tool_config")) as? JsonObject
    val calling = (config?.valueOrNull("functionCallingConfig") ?: config?.valueOrNull("function_calling_config")) as? JsonObject
    val mode = calling?.valueOrNull("mode") ?: return defaultToolChoice(toolNames)
    val allowed = (calling.valueOrNull("allowedFunctionNames") ?: calling.valueOrNull("allowed_function_names")) as? JsonArray
    val only = allowed?.singleOrNull()?.let { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
    return when ((mode as? JsonPrimitive)?.takeIf { it.isString }?.content) {
        "AUTO" -> ToolChoiceFact("auto")
        "NONE" -> ToolChoiceFact("none")
        "ANY" -> if (only != null) ToolChoiceFact("tool", only) else ToolChoiceFact("required")
        else -> OTHER_TOOL_CHOICE
    }
}

/** This request with [headers], names lowercased; a name sent in two casings keeps every value. */
internal fun ParsedRequest.withHeaders(headers: Map<String, List<String>>): ParsedRequest =
    copy(headers = headers.entries.groupBy({ it.key.lowercase() }, { it.value }).mapValues { (_, v) -> v.flatten() })

/** This request with the exchange's request headers. */
internal fun ParsedRequest.withHeaders(ex: HttpExchange): ParsedRequest = withHeaders(ex.requestHeaders)
