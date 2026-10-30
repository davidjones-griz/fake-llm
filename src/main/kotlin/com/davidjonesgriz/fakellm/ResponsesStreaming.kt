package com.davidjonesgriz.fakellm

import com.sun.net.httpserver.HttpExchange
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// SSE framing for `POST /openai/v1/responses` when the body sets `"stream": true`. Every frame is
// a named event (`event: <type>\ndata: <payload>\n\n`) whose payload carries `type` first and
// `sequence_number` last, from `response.created` to `response.completed` (or
// `response.incomplete`). There is no `[DONE]`.

/**
 * `POST /openai/v1/responses`: a streamed request is answered by [streamOpenAiResponse], any other
 * by one JSON `Response`. The other vendor handlers are in VendorHandlers.kt.
 */
internal fun handleOpenAiResponses(
    ex: HttpExchange,
    engine: ScenarioEngine,
) {
    val body = ex.requestBody.readBytes().decodeToString()
    val parsed = parseResponsesRequest(body)
    if (parsed.stream) {
        streamOpenAiResponse(ex, engine, parsed)
    } else {
        respondFake(ex, engine.handle(vendor = RESPONSES_VENDOR, req = parsed, capture = ex.exchangeCapture()))
    }
}

/**
 * Answers a streamed OpenAI Responses request from the next step. The call is numbered and logged
 * once, by [ScenarioEngine.resolve], as a buffered one is.
 *
 * An error step without `streamErrorAfterChunks`, and the no-more-steps and no-scenario 400s, are
 * sent as the plain JSON response, with no SSE bytes. Otherwise the stream starts with
 * `response.created` and `response.in_progress`. A reply step's `thinking` is streamed first as a
 * reasoning item (see [writeReasoning]). The step then sends the message item (its
 * opening events, one `response.output_text.delta` per `streamChunkChars` code points, each after
 * `streamInterChunkDelayMs`, and its done events), then each tool call as a `function_call` item
 * with its argument text as delta events, and ends with `response.completed`, or
 * `response.incomplete` for `max_tokens`. An error step with
 * `streamErrorAfterChunks` opens the message, sends that many filler deltas, then `event: error`
 * with the error body (or the API's own error event when it is blank), and closes the stream.
 * [Step.firstByteDelayMs] is waited out first, before any bytes.
 */
internal fun streamOpenAiResponse(
    ex: HttpExchange,
    engine: ScenarioEngine,
    req: ParsedRequest,
) {
    val resolution = engine.resolve(vendor = RESPONSES_VENDOR, req = req, capture = ex.exchangeCapture())
    val step = resolution.step
    pauseBeforeFirstByte(step)
    val error = step.error
    if (error != null && step.streamErrorAfterChunks == null) {
        respond(ex, error.status, error.body, error.headers)
        return
    }
    val seq = resolution.seq
    val out = startEventStream(ex)
    try {
        val writer = ResponsesEventWriter(out)
        val inProgress =
            buildJsonObject {
                put(
                    "response",
                    responsesResponseObject(seq, req.model, "in_progress", JsonArray(emptyList()), usage = null),
                )
            }
        writer.write("response.created", inProgress)
        writer.write("response.in_progress", inProgress)
        if (error != null) {
            writeMessageOpening(writer, seq)
            val prefix = errorPrefixChunks(step.streamErrorAfterChunks!!, step.streamChunkChars)
            writeTextDeltas(writer, seq, prefix, step.streamInterChunkDelayMs)
            writer.writeError(error.body)
        } else {
            val toolCalls = sentToolCalls(RESPONSES_VENDOR, step, seq)
            // The reasoning item (thinking) comes first, then the message (text), then the tool calls.
            step.thinking?.let { writeReasoning(writer, seq, step, it) }
            val messageIndex = if (step.thinking != null) 1 else 0
            val firstCallIndex = messageIndex + (if (step.text != null) 1 else 0)
            step.text?.let { writeMessage(writer, seq, step, it, messageIndex) }
            toolCalls.forEachIndexed { i, call ->
                writeFunctionCall(writer, firstCallIndex + i, "fc_fake_${seq}_${i + 1}", call, step)
            }
            val status = if (step.stopReason == "max_tokens") "incomplete" else "completed"
            val final =
                responsesResponseObject(
                    seq,
                    req.model,
                    status,
                    responsesOutput(step.text, step.stopReason, seq, toolCalls, step.thinking),
                    usage = step.appliedUsage,
                )
            writer.write("response.$status", buildJsonObject { put("response", final) })
        }
    } finally {
        out.close()
    }
}

private fun writeMessageOpening(
    writer: ResponsesEventWriter,
    seq: Long,
    outputIndex: Int = 0,
) {
    writer.write(
        "response.output_item.added",
        buildJsonObject {
            put("output_index", outputIndex)
            put("item", responsesMessageItem(seq, "in_progress", null))
        },
    )
    writer.write(
        "response.content_part.added",
        buildJsonObject {
            put("item_id", "msg_fake_$seq")
            put("output_index", outputIndex)
            put("content_index", 0)
            put(
                "part",
                buildJsonObject {
                    put("type", "output_text")
                    put("text", "")
                    put("annotations", buildJsonArray {})
                },
            )
        },
    )
}

private fun writeTextDeltas(
    writer: ResponsesEventWriter,
    seq: Long,
    pieces: List<String>,
    delayMs: Long,
    outputIndex: Int = 0,
) {
    pieces.forEach { piece ->
        if (delayMs > 0) Thread.sleep(delayMs)
        writer.write(
            "response.output_text.delta",
            buildJsonObject {
                put("item_id", "msg_fake_$seq")
                put("output_index", outputIndex)
                put("content_index", 0)
                put("delta", piece)
                put("logprobs", buildJsonArray {})
            },
        )
    }
}

private fun writeMessage(
    writer: ResponsesEventWriter,
    seq: Long,
    step: Step,
    text: String,
    outputIndex: Int,
) {
    writeMessageOpening(writer, seq, outputIndex)
    writeTextDeltas(writer, seq, chunkText(text, step.streamChunkChars), step.streamInterChunkDelayMs, outputIndex)
    writer.write(
        "response.output_text.done",
        buildJsonObject {
            put("item_id", "msg_fake_$seq")
            put("output_index", outputIndex)
            put("content_index", 0)
            put("text", text)
            put("logprobs", buildJsonArray {})
        },
    )
    writer.write(
        "response.content_part.done",
        buildJsonObject {
            put("item_id", "msg_fake_$seq")
            put("output_index", outputIndex)
            put("content_index", 0)
            put(
                "part",
                buildJsonObject {
                    put("type", "output_text")
                    put("text", text)
                    put("annotations", buildJsonArray {})
                },
            )
        },
    )
    val status = if (step.stopReason == "max_tokens") "incomplete" else "completed"
    writer.write(
        "response.output_item.done",
        buildJsonObject {
            put("output_index", outputIndex)
            put("item", responsesMessageItem(seq, status, text))
        },
    )
}

private fun writeFunctionCall(
    writer: ResponsesEventWriter,
    outputIndex: Int,
    itemId: String,
    call: SentToolCall,
    step: Step,
) {
    writer.write(
        "response.output_item.added",
        buildJsonObject {
            put("output_index", outputIndex)
            put("item", responsesFunctionCallItem(itemId, call, "in_progress", ""))
        },
    )
    chunkText(call.argumentText, step.streamChunkChars).forEach { piece ->
        if (step.streamInterChunkDelayMs > 0) Thread.sleep(step.streamInterChunkDelayMs)
        writer.write(
            "response.function_call_arguments.delta",
            buildJsonObject {
                put("item_id", itemId)
                put("output_index", outputIndex)
                put("delta", piece)
            },
        )
    }
    writer.write(
        "response.function_call_arguments.done",
        buildJsonObject {
            put("item_id", itemId)
            put("output_index", outputIndex)
            put("arguments", call.argumentText)
        },
    )
    writer.write(
        "response.output_item.done",
        buildJsonObject {
            put("output_index", outputIndex)
            put("item", responsesFunctionCallItem(itemId, call, "completed", call.argumentText))
        },
    )
}
