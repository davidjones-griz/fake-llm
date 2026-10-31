package com.davidjonesgriz.fakellm

import com.sun.net.httpserver.HttpExchange

internal fun handleMessages(
    ex: HttpExchange,
    engine: ScenarioEngine,
) {
    val body = ex.requestBody.readBytes().decodeToString()
    val parsed = parseAnthropicRequest(body).withHeaders(ex)
    if (parsed.stream) {
        streamAnthropicMessages(ex, engine, parsed)
    } else {
        respondFake(ex, engine.handle(vendor = "anthropic", req = parsed, capture = ex.exchangeCapture()))
    }
}

internal fun handleOpenAi(
    ex: HttpExchange,
    engine: ScenarioEngine,
) {
    val body = ex.requestBody.readBytes().decodeToString()
    val parsed = parseOpenAiRequest(body).withHeaders(ex)
    if (parsed.stream) {
        streamOpenAiChatCompletion(ex, engine, parsed)
    } else {
        respondFake(ex, engine.handle(vendor = "openai", req = parsed, capture = ex.exchangeCapture()))
    }
}

internal fun handleGemini(
    ex: HttpExchange,
    engine: ScenarioEngine,
    path: String,
) {
    val body = ex.requestBody.readBytes().decodeToString()
    val streamed = path.endsWith(":streamGenerateContent")
    require(!streamed || geminiAltSse(ex.requestURI.rawQuery)) { GEMINI_STREAM_NEEDS_ALT_SSE }
    val parsed = parseGeminiRequest(body, geminiModelFromPath(path), stream = streamed).withHeaders(ex)
    if (streamed) {
        streamGeminiGenerateContent(ex, engine, parsed)
    } else {
        respondFake(ex, engine.handle(vendor = "gemini", req = parsed, capture = ex.exchangeCapture()))
    }
}

/**
 * SSE variant of [handleMessages]. Takes the call's number and step through the same
 * [ScenarioEngine.resolve] that [ScenarioEngine.handle] uses, so the call log records the request
 * once whichever transport answers it.
 *
 * A reply step is written as Anthropic's SSE sequence: the thinking block, the text block, then
 * one `tool_use` block per tool call, whose argument text is sent as `input_json_delta` pieces
 * (`rawArguments` verbatim), with `stop_reason` `tool_use` when there are tool calls. An error step whose
 * [Step.streamErrorAfterChunks] is null is a plain HTTP error, and no SSE bytes are written
 * first: that is how a provider rejects before generation starts (the usual timing for rate-limit
 * and overload errors). Setting [Step.streamErrorAfterChunks] opts into the other timing — some
 * text deltas, then an in-stream `event: error`. The no-more-steps and no-scenario 400s
 * are that same plain HTTP error: [ScenarioEngine.resolve] returns them as error steps with
 * [Step.streamErrorAfterChunks] unset, and the branch below returns before any SSE bytes.
 * [Step.firstByteDelayMs] is waited out first, before any bytes.
 */
private fun streamAnthropicMessages(
    ex: HttpExchange,
    engine: ScenarioEngine,
    req: ParsedRequest,
) {
    val resolution = engine.resolve(vendor = "anthropic", req = req, capture = ex.exchangeCapture())
    val step = resolution.step
    pauseBeforeFirstByte(step)
    val error = step.error
    if (error != null && step.streamErrorAfterChunks == null) {
        respond(ex, error.status, error.body, error.headers)
        return
    }
    val out = startEventStream(ex)
    try {
        writeSse(out, "message_start", messageStartJson(resolution.seq, req.model, req.hasCacheBreakpoint, step.appliedUsage.inputTokens))
        if (error != null) {
            writeSse(out, "content_block_start", contentBlockStartJson())
            val prefix = errorPrefixChunks(step.streamErrorAfterChunks!!, step.streamChunkChars)
            emitDeltas(out, prefix, step.streamInterChunkDelayMs)
            writeSse(out, "error", error.body.ifBlank { GENERIC_STREAM_ERROR_BODY })
        } else {
            var index = 0
            step.thinking?.let { thinking ->
                writeSse(out, "content_block_start", thinkingBlockStartJson(index))
                emitThinkingDeltas(out, chunkText(thinking, step.streamChunkChars), step.streamInterChunkDelayMs, index)
                writeSse(out, "content_block_delta", signatureDeltaJson(FAKE_THINKING_SIGNATURE, index))
                writeSse(out, "content_block_stop", contentBlockStopJson(index++))
            }
            step.text?.let { text ->
                writeSse(out, "content_block_start", contentBlockStartJson(index))
                emitDeltas(out, chunkText(text, step.streamChunkChars), step.streamInterChunkDelayMs, index)
                writeSse(out, "content_block_stop", contentBlockStopJson(index++))
            }
            sentToolCalls("anthropic", step, resolution.seq).forEach { call ->
                writeSse(out, "content_block_start", toolUseBlockStartJson(index, call.id!!, call.name))
                emitInputJsonDeltas(out, chunkText(call.argumentText, step.streamChunkChars), step.streamInterChunkDelayMs, index)
                writeSse(out, "content_block_stop", contentBlockStopJson(index++))
            }
            val stopReason = if (step.toolCalls == null) step.stopReason else "tool_use"
            writeSse(out, "message_delta", messageDeltaJson(stopReason, step.appliedUsage.outputTokens, step.stopSequence))
            writeSse(out, "message_stop", messageStopJson())
        }
    } finally {
        out.close()
    }
}
