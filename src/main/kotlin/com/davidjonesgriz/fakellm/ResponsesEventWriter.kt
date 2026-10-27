package com.davidjonesgriz.fakellm

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.OutputStream

/** One Responses stream event: `type` first, then [fields], then `sequence_number`. */
internal fun responsesEventJson(
    type: String,
    fields: JsonObject,
    sequenceNumber: Int,
): String =
    buildJsonObject {
        put("type", type)
        fields.forEach { (k, v) -> put(k, v) }
        put("sequence_number", sequenceNumber)
    }.toString()

/**
 * Writes one Responses stream's named events (`event: <type>` and the payload), numbered from 0.
 * [writeError] sends `event: error` with a scripted body as given, or, for a blank body, the API's
 * own error event at the next sequence number.
 */
internal class ResponsesEventWriter(
    private val out: OutputStream,
) {
    private var next = 0

    fun write(
        type: String,
        fields: JsonObject,
    ) {
        writeSse(out, type, responsesEventJson(type, fields, next++))
    }

    fun writeError(body: String) {
        val data =
            body.ifBlank {
                responsesEventJson(
                    "error",
                    buildJsonObject {
                        put("code", "server_error")
                        put("message", "scripted stream error")
                        put("param", JsonNull)
                    },
                    next++,
                )
            }
        writeSse(out, "error", data)
    }
}
