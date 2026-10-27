// The exchange log's public records: what ScenarioEngine.exchanges(), FakeLlmClient.exchanges() and
// GET /control/exchanges return for each call.
package com.davidjonesgriz.fakellm

import kotlinx.serialization.Serializable

/** One call's request as fake-llm received it and response as fake-llm wrote it. */
@ConsistentCopyVisibility
@Serializable
public data class ExchangeRecord internal constructor(
    /** The call's number, as [CallRecord.seq] in the call log. */
    val seq: Long,
    val request: ExchangeRequest,
    /**
     * Null until fake-llm starts sending the response, for example while the call waits out its
     * step's [Step.firstByteDelayMs].
     */
    val response: ExchangeResponse? = null,
)

/** A call's request as fake-llm received it. */
@ConsistentCopyVisibility
@Serializable
public data class ExchangeRequest internal constructor(
    /** The HTTP method. */
    val method: String,
    /**
     * The raw path, then `?` and the raw query when the request has one, still percent-encoded. A
     * query element named exactly `key` is recorded as `key=[redacted]`.
     */
    val path: String,
    /**
     * Every request header: names lowercased and sorted, each with its values in the order received.
     * Each value of `authorization`, `proxy-authorization`, `x-api-key`, `x-goog-api-key` and
     * `api-key` is `[redacted]`.
     */
    val headers: Map<String, List<String>>,
    /** The raw request body, decoded as UTF-8; not parsed or re-serialized. */
    val body: String,
)

/** A call's response as fake-llm wrote it. */
@ConsistentCopyVisibility
@Serializable
public data class ExchangeResponse internal constructor(
    /** The status fake-llm sent, or started to send. */
    val status: Int,
    /**
     * The headers fake-llm set, in the form of [ExchangeRequest.headers] and not redacted, without
     * the `Date`, `Content-length` and `Transfer-encoding` headers the server adds as it sends.
     */
    val headers: Map<String, List<String>>,
    /**
     * Every byte written to the response body, in order, decoded as UTF-8: the JSON text, or the
     * whole event stream, including a mid-stream error frame and OpenAI's `data: [DONE]`.
     */
    val body: String,
    /**
     * - `open`: the body is still being written; [body] holds what has been written so far.
     * - `complete`: fake-llm finished the response and closed the body normally.
     * - `aborted`: sending the headers, or a write, flush or close, failed (usually because the
     *   client disconnected), or the server stopped mid-response. [body] holds the bytes written
     *   before the failure; the client may have received fewer.
     */
    val state: String,
)
