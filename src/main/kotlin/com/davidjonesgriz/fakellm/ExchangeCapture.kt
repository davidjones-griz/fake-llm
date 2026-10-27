// Records one call's request and response for the exchange log by wrapping the exchange's streams:
// the request body is read once and handed back to the handler, and every response byte passes
// through the capture on its way to the client.
package com.davidjonesgriz.fakellm

import com.sun.net.httpserver.HttpExchange
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream

/** What a redacted header value or `key` query parameter is recorded as. */
internal const val REDACTED = "[redacted]"

private val REDACTED_HEADERS = setOf("authorization", "proxy-authorization", "x-api-key", "x-goog-api-key", "api-key")

/**
 * One call's [request] and the response written so far. The recorded status, headers, body bytes
 * and state are guarded by the instance's lock; writes to the client are made outside it, so a
 * slow client never blocks a read of the log.
 */
internal class ExchangeCapture(
    private val request: ExchangeRequest,
) {
    private val lock = Any()
    private var status: Int? = null
    private var headers: Map<String, List<String>> = emptyMap()
    private val bytes = ByteArrayOutputStream()
    private var state = "open"

    /**
     * Records the status and headers about to be sent. Only the first call counts, so a second
     * `respond` on an exchange that has already started can't overwrite what was sent.
     */
    fun responseStarted(
        status: Int,
        headers: Map<String, List<String>>,
    ) {
        synchronized(lock) {
            if (this.status == null) {
                this.status = status
                this.headers = recordedHeaders(headers, redact = false)
            }
        }
    }

    /** Marks the exchange `aborted`: sending the headers failed, or the server stopped mid-response. */
    fun abort() {
        synchronized(lock) { state = "aborted" }
    }

    /** The record for call [seq]; `response` is null until [responseStarted]. */
    fun snapshot(seq: Long): ExchangeRecord =
        synchronized(lock) {
            ExchangeRecord(seq, request, status?.let { ExchangeResponse(it, headers, bytes.toByteArray().decodeToString(), state) })
        }

    /**
     * The response body stream: each write goes to [original] first, then into the capture. A
     * failed write, flush or close marks the exchange `aborted` and rethrows; a normal close marks
     * it `complete` unless it is already `aborted`.
     */
    internal inner class Stream(
        private val original: OutputStream,
    ) : OutputStream() {
        val capture: ExchangeCapture get() = this@ExchangeCapture

        override fun write(b: Int) {
            guarded { original.write(b) }
            synchronized(lock) { bytes.write(b) }
        }

        override fun write(
            b: ByteArray,
            off: Int,
            len: Int,
        ) {
            guarded { original.write(b, off, len) }
            synchronized(lock) { bytes.write(b, off, len) }
        }

        override fun flush() = guarded { original.flush() }

        override fun close() {
            // Marked before delegating, since the delegate's close sends the last bytes: a reader
            // that has the whole body then never sees `open`. A failed close marks it `aborted`.
            synchronized(lock) { if (state != "aborted") state = "complete" }
            guarded { original.close() }
        }

        private inline fun guarded(block: () -> Unit) {
            try {
                block()
            } catch (e: IOException) {
                abort()
                throw e
            }
        }
    }
}

/**
 * Reads the whole request body, records the request, and swaps in streams that hand the handler the
 * same body and pass every response byte through the capture. `ex.responseBody` is read before
 * `setStreams`: the JDK creates the stream that `sendResponseHeaders` later connects on that first
 * read.
 */
internal fun installExchangeCapture(ex: HttpExchange) {
    val body = ex.requestBody.readBytes()
    val capture =
        ExchangeCapture(
            ExchangeRequest(
                method = ex.requestMethod,
                path = recordedPath(ex.requestURI.rawPath, ex.requestURI.rawQuery),
                headers = recordedHeaders(ex.requestHeaders, redact = true),
                body = body.decodeToString(),
            ),
        )
    val original = ex.responseBody
    ex.setStreams(ByteArrayInputStream(body), capture.Stream(original))
}

/** Records the status and headers, then sends them; a failed send marks the exchange `aborted`. */
internal fun HttpExchange.sendRecordedResponseHeaders(
    status: Int,
    length: Long,
) {
    val capture = exchangeCapture()
    capture?.responseStarted(status, responseHeaders)
    try {
        sendResponseHeaders(status, length)
    } catch (e: IOException) {
        capture?.abort()
        throw e
    }
}

/**
 * The exchange's capture, or null for a request that wasn't wrapped (one `controlRoute` answered). Not kept
 * as an exchange attribute: on JDK 21 those belong to the `HttpContext`, shared by every request.
 */
internal fun HttpExchange.exchangeCapture(): ExchangeCapture? = (responseBody as? ExchangeCapture.Stream)?.capture

/**
 * [rawPath], then `?` and [rawQuery] when there is one, with the value of each query element named
 * exactly `key` replaced by [REDACTED]. An element's name is the text before its first `=`, and an
 * element with no `=` is kept as it is.
 */
internal fun recordedPath(
    rawPath: String,
    rawQuery: String?,
): String {
    if (rawQuery == null) return rawPath
    val query =
        rawQuery.split('&').joinToString("&") { element ->
            if (element.substringBefore('=', missingDelimiterValue = "") == "key") "key=$REDACTED" else element
        }
    return "$rawPath?$query"
}

/**
 * [headers] with names lowercased and sorted, each name's values in order; with [redact], each value
 * of an auth header is [REDACTED]. Names that lowercase alike have their values concatenated in
 * iteration order.
 */
internal fun recordedHeaders(
    headers: Map<String, List<String>>,
    redact: Boolean,
): Map<String, List<String>> {
    val out = sortedMapOf<String, MutableList<String>>()
    headers.forEach { (name, values) ->
        val lower = name.lowercase()
        out.getOrPut(lower) { mutableListOf() } += if (redact && lower in REDACTED_HEADERS) values.map { REDACTED } else values
    }
    return out
}
