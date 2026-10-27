// Per-step latency: the wait before a response's first byte, and the inter-chunk wait a call used.
package com.davidjonesgriz.fakellm

import com.sun.net.httpserver.HttpExchange

/** The largest [Step.firstByteDelayMs] and [Step.streamInterChunkDelayMs]: 10 minutes. */
internal const val MAX_DELAY_MS = 600_000L

internal fun pauseBeforeFirstByte(step: Step) {
    if (step.firstByteDelayMs > 0) Thread.sleep(step.firstByteDelayMs)
}

internal fun respondFake(
    ex: HttpExchange,
    response: FakeResponse,
) {
    if (response.firstByteDelayMs > 0) Thread.sleep(response.firstByteDelayMs)
    respond(ex, response.status, response.body, response.headers)
}

internal fun appliedInterChunkDelayMs(
    step: Step,
    streamed: Boolean,
): Long = if (streamed && (step.error == null || step.streamErrorAfterChunks != null)) step.streamInterChunkDelayMs else 0
