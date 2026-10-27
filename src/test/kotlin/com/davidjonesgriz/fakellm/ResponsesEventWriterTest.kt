package com.davidjonesgriz.fakellm

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals

class ResponsesEventWriterTest {
    private val delta =
        buildJsonObject {
            put("item_id", "msg_fake_1")
            put("delta", "Hi")
        }

    @Test
    fun `an event has its type first and its sequence number last`() {
        assertEquals(
            """{"type":"response.output_text.delta","item_id":"msg_fake_1","delta":"Hi","sequence_number":4}""",
            responsesEventJson("response.output_text.delta", delta, 4),
        )
    }

    @Test
    fun `the writer numbers events from 0 and writes an event line and a data line per frame`() {
        val out = ByteArrayOutputStream()
        val writer = ResponsesEventWriter(out)
        writer.write("response.in_progress", buildJsonObject {})
        writer.write("response.output_text.delta", delta)
        assertEquals(
            "event: response.in_progress\ndata: {\"type\":\"response.in_progress\",\"sequence_number\":0}\n\n" +
                "event: response.output_text.delta\n" +
                "data: {\"type\":\"response.output_text.delta\",\"item_id\":\"msg_fake_1\",\"delta\":\"Hi\",\"sequence_number\":1}\n\n",
            out.toString(Charsets.UTF_8),
        )
    }

    @Test
    fun `a blank error body writes the generic error event at the next sequence number, and any other body is sent verbatim`() {
        val blank = ByteArrayOutputStream()
        ResponsesEventWriter(blank).apply {
            write("response.created", buildJsonObject {})
            writeError("  ")
        }
        assertEquals(
            "event: error\n" +
                "data: {\"type\":\"error\",\"code\":\"server_error\",\"message\":\"scripted stream error\",\"param\":null,\"sequence_number\":1}\n\n",
            blank.toString(Charsets.UTF_8).substringAfter("\n\n"),
        )
        val scripted = ByteArrayOutputStream()
        ResponsesEventWriter(scripted).writeError("""{"error":{"message":"server error"}}""")
        assertEquals("event: error\ndata: {\"error\":{\"message\":\"server error\"}}\n\n", scripted.toString(Charsets.UTF_8))
    }
}
