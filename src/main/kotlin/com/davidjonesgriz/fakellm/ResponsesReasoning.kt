// The OpenAI Responses reasoning item: a step's `thinking`, buffered as one `reasoning` output item
// with one `summary_text` part, or streamed as that item's summary events.
package com.davidjonesgriz.fakellm

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The reasoning item `rs_fake_<seq>`. A null [summary] gives an empty `summary`, as when a stream opens it. */
internal fun responsesReasoningItem(
    seq: Long,
    summary: String?,
): JsonObject =
    buildJsonObject {
        put("id", "rs_fake_$seq")
        put("type", "reasoning")
        put(
            "summary",
            buildJsonArray {
                if (summary != null) add(summaryTextPart(summary))
            },
        )
    }

/**
 * Streams [thinking] as output item 0: `response.output_item.added`, the summary part's `added`, one
 * `response.reasoning_summary_text.delta` per [Step.streamChunkChars] code points (each after
 * [Step.streamInterChunkDelayMs]), the text's and the part's `done`, then `response.output_item.done`.
 */
internal fun writeReasoning(
    writer: ResponsesEventWriter,
    seq: Long,
    step: Step,
    thinking: String,
) {
    val itemId = "rs_fake_$seq"
    writer.write(
        "response.output_item.added",
        buildJsonObject {
            put("output_index", 0)
            put("item", responsesReasoningItem(seq, null))
        },
    )
    writer.write("response.reasoning_summary_part.added", summaryFields(itemId) { put("part", summaryTextPart("")) })
    chunkText(thinking, step.streamChunkChars).forEach { piece ->
        if (step.streamInterChunkDelayMs > 0) Thread.sleep(step.streamInterChunkDelayMs)
        writer.write("response.reasoning_summary_text.delta", summaryFields(itemId) { put("delta", piece) })
    }
    writer.write("response.reasoning_summary_text.done", summaryFields(itemId) { put("text", thinking) })
    writer.write("response.reasoning_summary_part.done", summaryFields(itemId) { put("part", summaryTextPart(thinking)) })
    writer.write(
        "response.output_item.done",
        buildJsonObject {
            put("output_index", 0)
            put("item", responsesReasoningItem(seq, thinking))
        },
    )
}

private fun summaryTextPart(text: String): JsonObject =
    buildJsonObject {
        put("type", "summary_text")
        put("text", text)
    }

/** `item_id`, `output_index` 0 and `summary_index` 0, then [rest]. */
private fun summaryFields(
    itemId: String,
    rest: JsonObjectBuilder.() -> Unit,
): JsonObject =
    buildJsonObject {
        put("item_id", itemId)
        put("output_index", 0)
        put("summary_index", 0)
        rest()
    }
