package com.davidjonesgriz.fakellm

import com.sun.net.httpserver.HttpExchange
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.OutputStream

// SSE framing for `POST /openai/v1/chat/completions` when the body sets `"stream": true`.
// Every frame is a data-only line (`data: <payload>\n\n`): a role chunk, one content chunk per
// piece of the text, a finish chunk, a usage chunk when `stream_options.include_usage` is true,
// then `data: [DONE]`.

/** The last frame of a completed OpenAI stream. */
internal const val OPENAI_STREAM_DONE = "[DONE]"

/** The in-stream error payload for an OpenAI error step whose `body` is blank. */
internal const val GENERIC_OPENAI_STREAM_ERROR_BODY =
    """{"error":{"message":"scripted stream error","type":"server_error","param":null,"code":null}}"""

/**
 * One `chat.completion.chunk`: [delta] in a single choice, with [finishReason] (JSON `null` when
 * null). With [includeUsage] the chunk ends with `"usage":null`; without it there is no `usage` key.
 */
internal fun openAiChunkJson(
    seq: Long,
    delta: JsonObject,
    finishReason: String?,
    includeUsage: Boolean,
): String =
    buildJsonObject {
        put("id", "fake-openai-$seq")
        put("object", "chat.completion.chunk")
        put("model", "fake-openai")
        put(
            "choices",
            buildJsonArray {
                add(
                    buildJsonObject {
                        put("index", 0)
                        put("delta", delta)
                        put("finish_reason", finishReason)
                    },
                )
            },
        )
        if (includeUsage) put("usage", JsonNull)
    }.toString()

/** The usage chunk sent before `[DONE]` when the request set `stream_options.include_usage`: no choices, fixed token counts. */
internal fun openAiUsageChunkJson(seq: Long): String =
    buildJsonObject {
        put("id", "fake-openai-$seq")
        put("object", "chat.completion.chunk")
        put("model", "fake-openai")
        put("choices", buildJsonArray {})
        put(
            "usage",
            buildJsonObject {
                put("prompt_tokens", FIXED_INPUT_TOKENS)
                put("completion_tokens", FIXED_OUTPUT_TOKENS)
                put("total_tokens", FIXED_INPUT_TOKENS + FIXED_OUTPUT_TOKENS)
            },
        )
    }.toString()

/** Writes one content chunk per piece of [chunks], waiting [delayMs] before each (0 = no wait), as `emitDeltas` does. */
internal fun emitOpenAiContentDeltas(
    out: OutputStream,
    seq: Long,
    chunks: List<String>,
    delayMs: Long,
    includeUsage: Boolean,
) {
    chunks.forEach { chunk ->
        if (delayMs > 0) Thread.sleep(delayMs)
        writeSseData(out, openAiChunkJson(seq, buildJsonObject { put("content", chunk) }, null, includeUsage))
    }
}

/**
 * Answers a streamed OpenAI Chat Completions request from the next step. The call is numbered
 * and logged once, by [ScenarioEngine.resolve], as a buffered one is.
 *
 * An error step without `streamErrorAfterChunks`, and the no-more-steps and no-scenario 400s,
 * are sent as the plain JSON response, with no SSE bytes. Otherwise the response is an event
 * stream: the role chunk, then for a text step one content chunk per `streamChunkChars` code
 * points (each after `streamInterChunkDelayMs`), the finish chunk, the usage chunk when the
 * request set `stream_options.include_usage`, and `data: [DONE]`. `thinking` is ignored. An error
 * step with `streamErrorAfterChunks` sends that many filler chunks, then the error body as a
 * data frame (or [GENERIC_OPENAI_STREAM_ERROR_BODY] when it is blank), and closes the stream
 * with no finish chunk, usage chunk or `[DONE]`.
 */
internal fun streamOpenAiChatCompletion(
    ex: HttpExchange,
    engine: ScenarioEngine,
    req: ParsedRequest,
) {
    val resolution = engine.resolve(vendor = "openai", req = req)
    val step = resolution.step
    val error = step.error
    if (error != null && step.streamErrorAfterChunks == null) {
        respond(ex, error.status, error.body, error.headers)
        return
    }
    val seq = resolution.seq
    val usage = req.includeUsage
    val out = startEventStream(ex)
    try {
        val role =
            buildJsonObject {
                put("role", "assistant")
                put("content", "")
            }
        writeSseData(out, openAiChunkJson(seq, role, null, usage))
        if (error != null) {
            val prefix = errorPrefixChunks(step.streamErrorAfterChunks!!, step.streamChunkChars)
            emitOpenAiContentDeltas(out, seq, prefix, step.streamInterChunkDelayMs, usage)
            writeSseData(out, error.body.ifBlank { GENERIC_OPENAI_STREAM_ERROR_BODY })
        } else {
            emitOpenAiContentDeltas(out, seq, chunkText(step.text!!, step.streamChunkChars), step.streamInterChunkDelayMs, usage)
            writeSseData(out, openAiChunkJson(seq, buildJsonObject {}, openAiFinishReason(step.stopReason), usage))
            if (usage) writeSseData(out, openAiUsageChunkJson(seq))
            writeSseData(out, OPENAI_STREAM_DONE)
        }
    } finally {
        out.close()
    }
}
