package dev.fakellm

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.OutputStream

internal fun thinkingBlockStartJson(index: Int): String =
    buildJsonObject {
        put("type", "content_block_start")
        put("index", index)
        put(
            "content_block",
            buildJsonObject {
                put("type", "thinking")
                put("thinking", "")
            },
        )
    }.toString()

internal fun thinkingDeltaJson(
    thinking: String,
    index: Int,
): String =
    buildJsonObject {
        put("type", "content_block_delta")
        put("index", index)
        put(
            "delta",
            buildJsonObject {
                put("type", "thinking_delta")
                put("thinking", thinking)
            },
        )
    }.toString()

internal fun signatureDeltaJson(
    signature: String,
    index: Int,
): String =
    buildJsonObject {
        put("type", "content_block_delta")
        put("index", index)
        put(
            "delta",
            buildJsonObject {
                put("type", "signature_delta")
                put("signature", signature)
            },
        )
    }.toString()

internal fun emitThinkingDeltas(
    out: OutputStream,
    chunks: List<String>,
    delayMs: Long,
    index: Int,
) {
    chunks.forEach { chunk ->
        if (delayMs > 0) Thread.sleep(delayMs)
        writeSse(out, "content_block_delta", thinkingDeltaJson(chunk, index))
    }
}
