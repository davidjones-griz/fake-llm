package com.davidjonesgriz.fakellm

import com.sun.net.httpserver.HttpExchange
import java.io.OutputStream
import java.nio.charset.StandardCharsets

// Server-sent events transport shared by the Anthropic, OpenAI Chat Completions, OpenAI Responses
// and Gemini streams.

/**
 * Starts an event stream on [ex]: HTTP 200 with `Content-Type: text/event-stream`,
 * `Cache-Control: no-cache` and `Connection: keep-alive`, and a chunked body. The caller writes
 * the frames and closes the returned stream.
 */
internal fun startEventStream(ex: HttpExchange): OutputStream {
    ex.responseHeaders.add("Content-Type", "text/event-stream")
    ex.responseHeaders.add("Cache-Control", "no-cache")
    ex.responseHeaders.add("Connection", "keep-alive")
    ex.sendResponseHeaders(HTTP_OK, 0)
    return ex.responseBody
}

/** Writes one data-only SSE frame (`data: <data>\n\n`, no `event:` line) as UTF-8 and flushes. */
internal fun writeSseData(
    out: OutputStream,
    data: String,
) {
    out.write("data: $data\n\n".toByteArray(StandardCharsets.UTF_8))
    out.flush()
}
