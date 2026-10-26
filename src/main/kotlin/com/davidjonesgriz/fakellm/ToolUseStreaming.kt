package com.davidjonesgriz.fakellm

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.OutputStream

// The Anthropic stream's tool_use blocks: a block start with an empty input, the argument text as
// input_json_delta pieces, then content_block_stop.

internal fun toolUseBlockStartJson(
    index: Int,
    id: String,
    name: String,
): String =
    buildJsonObject {
        put("type", "content_block_start")
        put("index", index)
        put(
            "content_block",
            buildJsonObject {
                put("type", "tool_use")
                put("id", id)
                put("name", name)
                put("input", buildJsonObject {})
            },
        )
    }.toString()

internal fun inputJsonDeltaJson(
    partialJson: String,
    index: Int,
): String =
    buildJsonObject {
        put("type", "content_block_delta")
        put("index", index)
        put(
            "delta",
            buildJsonObject {
                put("type", "input_json_delta")
                put("partial_json", partialJson)
            },
        )
    }.toString()

/** Emits [chunks] as `input_json_delta` frames, waiting [delayMs] before each (0 = no wait), as `emitDeltas` does. */
internal fun emitInputJsonDeltas(
    out: OutputStream,
    chunks: List<String>,
    delayMs: Long,
    index: Int,
) {
    chunks.forEach { chunk ->
        if (delayMs > 0) Thread.sleep(delayMs)
        writeSse(out, "content_block_delta", inputJsonDeltaJson(chunk, index))
    }
}
