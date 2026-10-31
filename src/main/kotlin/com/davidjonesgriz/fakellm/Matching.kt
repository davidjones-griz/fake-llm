// Whether a step's `match` holds for a request, and how the no-match message describes it.
package com.davidjonesgriz.fakellm

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Whether every key this match sets holds for [req], a [vendor] request. */
internal fun Match.holds(
    vendor: String,
    req: ParsedRequest,
): Boolean = holdsOnShape(vendor, req) && holdsOnText(req) && holdsOnPayload(req)

/** The keys about the request's shape: route, model, stream, message count and system prompt. */
private fun Match.holdsOnShape(
    vendor: String,
    req: ParsedRequest,
): Boolean =
    (route == null || route == vendor) &&
        (model == null || model == req.model) &&
        (stream == null || stream == req.stream) &&
        (messageCount == null || messageCount == req.messageCount) &&
        (hasSystem == null || hasSystem == (req.systemShape in SYSTEM_PRESENT))

/** The keys about the request's text: system, lastUser, prompt and message. */
private fun Match.holdsOnText(req: ParsedRequest): Boolean =
    (system == null || system.matches(req.systemText)) &&
        (
            lastUser == null ||
                req.messages.lastOrNull { it.role == "user" && it.text.isNotEmpty() }?.let { lastUser.matches(it.text) } == true
        ) &&
        (prompt == null || prompt.matches(req.promptText)) &&
        (message == null || message.holds(req.messages))

/** The keys about the rest of the request: tool results, tools, body and headers. */
private fun Match.holdsOnPayload(req: ParsedRequest): Boolean =
    (toolResult == null || req.toolResults.any { toolResult.holds(it) }) &&
        (tools == null || tools.holds(req)) &&
        (body == null || body.valueIn(req.body)?.let { jsonEquals(it, body.equalTo) } == true) &&
        (header == null || req.headers[header.name.lowercase()].orEmpty().any { header.text.matches(it) })

private fun MessageMatch.holds(messages: List<RequestMessage>): Boolean {
    val chosen =
        if (index != null) {
            messages.getOrNull(if (index < 0) messages.size + index else index)
        } else {
            messages.lastOrNull { it.role == role }
        }
    return chosen != null && text.matches(chosen.text)
}

private fun ToolResultMatch.holds(result: ToolResultRecord): Boolean =
    (id == null || id == result.id) &&
        (name == null || name == result.name) &&
        (isError == null || isError == result.isError) &&
        (content == null || content.matches(resultText(result.content)))

private fun ToolsMatch.holds(req: ParsedRequest): Boolean =
    (declares == null || declares in req.toolNames) &&
        (choice == null || choice == req.toolChoice) &&
        (choiceTool == null || (req.toolChoice == "tool" && req.toolChoiceName == choiceTool))

/** The value at this match's path in [body], or null when the path is not there. */
private fun BodyMatch.valueIn(body: JsonObject): JsonElement? =
    segments.fold<PathSegment, JsonElement?>(body) { value, segment ->
        when (segment) {
            is PathSegment.Key -> {
                (value as? JsonObject)?.get(segment.name)
            }

            is PathSegment.Index -> {
                (value as? JsonArray)?.let { array ->
                    array.getOrNull(
                        if (segment.index <
                            0
                        ) {
                            array.size + segment.index
                        } else {
                            segment.index
                        },
                    )
                }
            }
        }
    }

/** The system shapes that mean the request has a system prompt. */
private val SYSTEM_PRESENT = setOf("string", "blocks")
