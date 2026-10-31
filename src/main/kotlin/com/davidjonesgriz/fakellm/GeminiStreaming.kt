package com.davidjonesgriz.fakellm

import com.sun.net.httpserver.HttpExchange
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// SSE framing for `POST /gemini/{version}/models/{model}:streamGenerateContent?alt=sse`.
// Every frame is a data-only line (`data: <payload>\n\n`) holding one `GenerateContentResponse`
// with exactly one part: the text pieces, then each tool call as a whole `functionCall` part. Only
// the last chunk carries `finishReason` and `usageMetadata`. There is no `[DONE]`.

/** The in-stream error payload for a Gemini error step whose `body` is blank. */
internal const val GENERIC_GEMINI_STREAM_ERROR_BODY =
    """{"error":{"code":500,"message":"scripted stream error","status":"INTERNAL"}}"""

/** The `invalid-request` 400 message for a `:streamGenerateContent` request without `alt=sse`. */
internal const val GEMINI_STREAM_NEEDS_ALT_SSE =
    "streamGenerateContent is served only as server-sent events: add alt=sse to the query string"

/** Whether [rawQuery], split on `&`, has an element exactly equal to `alt=sse`. Null or empty is false. */
internal fun geminiAltSse(rawQuery: String?): Boolean = rawQuery?.split('&')?.contains("alt=sse") == true

/**
 * One streamed `GenerateContentResponse` with [part] as its only part, in the buffered body's key
 * order. A non-null [finishReason] marks the last chunk: it adds `finishReason` to the candidate
 * and `usageMetadata` before `modelVersion` ([model], the request's) and `fake_llm_seq`.
 */
internal fun geminiChunkJson(
    seq: Long,
    model: String,
    part: JsonObject,
    finishReason: String?,
    usage: TokenUsage = DEFAULT_USAGE,
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
                                put("parts", buildJsonArray { add(part) })
                            },
                        )
                        if (finishReason != null) put("finishReason", finishReason)
                    },
                )
            },
        )
        if (finishReason != null) {
            put(
                "usageMetadata",
                buildJsonObject {
                    put("promptTokenCount", usage.inputTokens)
                    put("candidatesTokenCount", usage.outputTokens)
                    put("totalTokenCount", usage.totalTokens)
                },
            )
        }
        put("modelVersion", model)
        put("fake_llm_seq", seq)
    }.toString()

private fun geminiTextPart(text: String): JsonObject = buildJsonObject { put("text", text) }

/**
 * Answers a streamed Gemini `streamGenerateContent` request from the next step. The call is
 * numbered and logged once, by [ScenarioEngine.resolve], as a buffered one is.
 *
 * An error step without `streamErrorAfterChunks`, and the no-more-steps and no-scenario 400s,
 * are sent as the plain JSON response, with no SSE bytes. Otherwise the response is an event
 * stream of one-part chunks: for a reply step, one text chunk per `streamChunkChars` code points
 * (each after `streamInterChunkDelayMs`), then each tool call as a whole `functionCall` chunk
 * (no wait), the last chunk carrying `finishReason` and `usageMetadata`. With no text and no tool
 * calls it is one last chunk with an empty text part. `thinking` is ignored. An error step with
 * `streamErrorAfterChunks` sends that many filler chunks, then the error body as a data frame (or
 * [GENERIC_GEMINI_STREAM_ERROR_BODY] when it is blank), and closes the stream with no chunk
 * carrying `finishReason`. [Step.firstByteDelayMs] is waited out first, before any bytes.
 */
internal fun streamGeminiGenerateContent(
    ex: HttpExchange,
    engine: ScenarioEngine,
    req: ParsedRequest,
) {
    val resolution = engine.resolve(vendor = "gemini", req = req, capture = ex.exchangeCapture())
    val step = resolution.step
    pauseBeforeFirstByte(step)
    val error = step.error
    if (error != null && step.streamErrorAfterChunks == null) {
        respond(ex, error.status, error.body, error.headers)
        return
    }
    val seq = resolution.seq
    val texts =
        if (error != null) {
            errorPrefixChunks(step.streamErrorAfterChunks!!, step.streamChunkChars)
        } else {
            step.text?.let { chunkText(it, step.streamChunkChars) }.orEmpty()
        }
    val parts =
        (texts.map(::geminiTextPart) + sentToolCalls("gemini", step, seq).map(::geminiFunctionCallPart))
            .ifEmpty { listOf(geminiTextPart("")) }
    // The error stream's filler chunks never finish; a reply's last chunk does.
    val finishReason = if (error == null) geminiFinishReason(step.stopReason) else null
    val out = startEventStream(ex)
    try {
        parts.forEachIndexed { i, part ->
            if (i < texts.size && step.streamInterChunkDelayMs > 0) Thread.sleep(step.streamInterChunkDelayMs)
            writeSseData(out, geminiChunkJson(seq, req.model, part, finishReason?.takeIf { i == parts.lastIndex }, step.appliedUsage))
        }
        if (error != null) writeSseData(out, error.body.ifBlank { GENERIC_GEMINI_STREAM_ERROR_BODY })
    } finally {
        out.close()
    }
}
