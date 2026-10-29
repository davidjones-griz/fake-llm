package com.davidjonesgriz.fakellm

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.OutputStream
import java.nio.charset.StandardCharsets

// SSE framing for `POST /anthropic/v1/messages` when the body sets `"stream": true`.
// Event order follows Anthropic's Messages stream:
// `message_start` → `content_block_start` → `content_block_delta`… → `content_block_stop` →
// `message_delta` → `message_stop`.
// [chunkText] turns a step's text into ordered deltas. The buffered path
// ([ScenarioEngine.handle] / [anthropicEnvelope]) is separate; VendorHandlers.kt's streaming branch
// is what calls these functions.

/**
 * Splits [text] into pieces of up to [chunkChars] Unicode code points — not UTF-16 chars.
 * `String.chunked` splits on [Char], so a boundary can fall inside a surrogate pair (an emoji or
 * other supplementary character). Encoding that half alone as UTF-8 replaces the unpaired
 * surrogate with U+FFFD, and joining the pieces would not reproduce [text]. Joining every
 * returned piece equals [text], for any [chunkChars] > 0.
 */
internal fun chunkText(
    text: String,
    chunkChars: Int,
): List<String> {
    require(chunkChars > 0) { "chunkChars must be positive, got $chunkChars" }
    if (text.isEmpty()) return emptyList()
    val codePoints = text.codePoints().toArray().toList()
    return codePoints.chunked(chunkChars).map { cps ->
        buildString { cps.forEach { appendCodePoint(it) } }
    }
}

/**
 * Filler text split into exactly [count] pieces of [chunkChars] code points. This is the partial
 * output before an in-stream error when [Step.streamErrorAfterChunks] is set. The words themselves
 * carry no meaning; a client only needs to observe some text before the injected failure, the same
 * shape as a provider error that starts after generation has begun.
 */
internal fun errorPrefixChunks(
    count: Int,
    chunkChars: Int,
): List<String> {
    if (count <= 0) return emptyList()
    val filler =
        buildString {
            while (length < count * chunkChars) append(STREAM_ERROR_FILLER_PHRASE)
        }
    return chunkText(filler, chunkChars).take(count)
}

private const val STREAM_ERROR_FILLER_PHRASE = "Thinking this through before the connection drops. "

/** The `event: error` frame's `data:` payload when a step's `ErrorStep.body` is blank. */
internal const val GENERIC_STREAM_ERROR_BODY =
    """{"type":"error","error":{"type":"api_error","message":"scripted stream error"}}"""

/**
 * Payload of `message_start`. Input tokens and both cache fields are reported here; `output_tokens`
 * is omitted and reported only on `message_delta`. [inputTokens] comes from the step, as in the
 * buffered envelope in ParsedRequest.kt; the cache figures come from the same constants, so buffered
 * and streamed usage numbers stay the same.
 */
internal fun messageStartJson(
    seq: Long,
    model: String,
    cacheable: Boolean = true,
    inputTokens: Int = FIXED_INPUT_TOKENS,
): String =
    buildJsonObject {
        put("type", "message_start")
        put(
            "message",
            buildJsonObject {
                put("id", "fake-msg-$seq")
                put("type", "message")
                put("role", "assistant")
                put("model", model)
                put("content", buildJsonArray {})
                put("stop_reason", JsonNull)
                put("stop_sequence", JsonNull)
                put(
                    "usage",
                    buildJsonObject {
                        put("input_tokens", inputTokens)
                        put("cache_creation_input_tokens", if (cacheable) FIXED_CACHE_CREATION_TOKENS else 0)
                        put("cache_read_input_tokens", if (cacheable) FIXED_CACHE_READ_TOKENS else 0)
                    },
                )
            },
        )
    }.toString()

internal fun contentBlockStartJson(index: Int = 0): String =
    buildJsonObject {
        put("type", "content_block_start")
        put("index", index)
        put(
            "content_block",
            buildJsonObject {
                put("type", "text")
                put("text", "")
            },
        )
    }.toString()

internal fun contentBlockDeltaJson(
    text: String,
    index: Int = 0,
): String =
    buildJsonObject {
        put("type", "content_block_delta")
        put("index", index)
        put(
            "delta",
            buildJsonObject {
                put("type", "text_delta")
                put("text", text)
            },
        )
    }.toString()

internal fun contentBlockStopJson(index: Int = 0): String =
    buildJsonObject {
        put("type", "content_block_stop")
        put("index", index)
    }.toString()

/** `message_delta` carries only the cumulative `output_tokens`. Input and cache tokens stay on `message_start`. */
internal fun messageDeltaJson(
    stopReason: String,
    outputTokens: Int = FIXED_OUTPUT_TOKENS,
): String =
    buildJsonObject {
        put("type", "message_delta")
        put(
            "delta",
            buildJsonObject {
                put("stop_reason", stopReason)
                put("stop_sequence", JsonNull)
            },
        )
        put(
            "usage",
            buildJsonObject {
                put("output_tokens", outputTokens)
            },
        )
    }.toString()

/** `message_stop` carries only the type discriminator. */
internal fun messageStopJson(): String =
    buildJsonObject {
        put("type", "message_stop")
    }.toString()

/** Writes one SSE frame (`event: <name>\ndata: <json>\n\n`) and flushes — one writer, one frame at a time. */
internal fun writeSse(
    out: OutputStream,
    event: String,
    data: String,
) {
    out.write("event: $event\ndata: $data\n\n".toByteArray(StandardCharsets.UTF_8))
    out.flush()
}

/** Emits [chunks] as `content_block_delta` frames, honoring [delayMs] between each (0 = no delay). */
internal fun emitDeltas(
    out: OutputStream,
    chunks: List<String>,
    delayMs: Long,
    index: Int = 0,
) {
    chunks.forEach { chunk ->
        if (delayMs > 0) Thread.sleep(delayMs)
        writeSse(out, "content_block_delta", contentBlockDeltaJson(chunk, index))
    }
}
