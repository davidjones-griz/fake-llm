package com.davidjonesgriz.fakellm

import kotlinx.serialization.json.JsonObject

/** One tool call as a response sends it: the [id] it carries (null for Gemini with no scripted id), and both argument forms. */
internal data class SentToolCall(
    val id: String?,
    val name: String,
    val arguments: JsonObject?,
    val argumentText: String,
)

/**
 * The tool calls [step] sends in a [vendor] response to call [seq], in order. The call log and every
 * renderer use this list. A call without a scripted id gets `toolu_fake_<seq>_<n>` (Anthropic) or
 * `call_fake_<seq>_<n>` (OpenAI Chat Completions and Responses), where `n` is its 1-based position,
 * and no id for Gemini.
 */
internal fun sentToolCalls(
    vendor: String,
    step: Step,
    seq: Long,
): List<SentToolCall> =
    step.toolCalls.orEmpty().mapIndexed { i, call ->
        val id =
            call.id ?: when (vendor) {
                "anthropic" -> "toolu_fake_${seq}_${i + 1}"
                "openai", RESPONSES_VENDOR -> "call_fake_${seq}_${i + 1}"
                else -> null
            }
        SentToolCall(id = id, name = call.name, arguments = call.arguments, argumentText = call.argumentText())
    }
