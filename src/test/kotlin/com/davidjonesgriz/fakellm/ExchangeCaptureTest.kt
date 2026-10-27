package com.davidjonesgriz.fakellm

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ExchangeCaptureTest {
    private val request = ExchangeRequest("POST", "/x", emptyMap(), "{}")

    @Test
    fun `the recorded path keeps the raw query and redacts only a key parameter`() {
        assertEquals("/x", recordedPath("/x", null))
        assertEquals("/x?", recordedPath("/x", ""))
        assertEquals("/x?alt=sse&key=[redacted]", recordedPath("/x", "alt=sse&key=abc"))
        assertEquals("/x?key=[redacted]", recordedPath("/x", "key="))
        assertEquals("/x?key=[redacted]&key=[redacted]", recordedPath("/x", "key=a&key=b"))
        listOf("KEY=abc", "xkey=abc", "key", "a=%20b").forEach { assertEquals("/x?$it", recordedPath("/x", it)) }
    }

    @Test
    fun `recorded headers are lowercased, sorted by name, and keep repeated values in order`() {
        val recorded = recordedHeaders(mapOf("X-B" to listOf("2", "1"), "A" to listOf("a"), "x-b" to listOf("3")), redact = false)
        assertEquals(listOf("a", "x-b"), recorded.keys.toList())
        assertEquals(listOf("2", "1", "3"), recorded["x-b"])
    }

    @Test
    fun `request auth header values are each replaced by the redaction marker`() {
        val headers =
            mapOf(
                "Authorization" to listOf("Bearer a", "Bearer b"),
                "Proxy-Authorization" to listOf("p"),
                "X-Api-Key" to listOf("k"),
                "X-Goog-Api-Key" to listOf("g"),
                "Api-Key" to listOf("z"),
                "X-Other" to listOf("keep"),
            )
        val redacted = recordedHeaders(headers, redact = true)
        assertEquals(listOf(REDACTED, REDACTED), redacted["authorization"])
        listOf("proxy-authorization", "x-api-key", "x-goog-api-key", "api-key").forEach { assertEquals(listOf(REDACTED), redacted[it]) }
        assertEquals(listOf("keep"), redacted["x-other"])
        assertEquals(listOf("Bearer a", "Bearer b"), recordedHeaders(headers, redact = false)["authorization"])
    }

    @Test
    fun `only the first responseStarted is recorded`() {
        val capture = ExchangeCapture(request)
        capture.responseStarted(200, mapOf("A" to listOf("1")))
        capture.responseStarted(500, mapOf("B" to listOf("2")))
        val response = capture.snapshot(1).response!!
        assertEquals(200, response.status)
        assertEquals(mapOf("a" to listOf("1")), response.headers)
        assertEquals("open", response.state)
    }

    @Test
    fun `a failed write marks the exchange aborted and keeps the bytes written before it`() {
        val sink = ByteArrayOutputStream()
        var writes = 0
        val failing =
            object : OutputStream() {
                override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

                override fun write(
                    b: ByteArray,
                    off: Int,
                    len: Int,
                ) {
                    if (++writes == 2) throw IOException("gone")
                    sink.write(b, off, len)
                }
            }
        val capture = ExchangeCapture(request)
        capture.responseStarted(200, emptyMap())
        val stream = capture.Stream(failing)
        stream.write("first".toByteArray())
        assertFailsWith<IOException> { stream.write("second".toByteArray()) }
        stream.close()
        val response = capture.snapshot(1).response!!
        assertEquals("first", response.body)
        assertEquals("aborted", response.state)
    }

    @Test
    fun `abort marks the exchange aborted and a later normal close leaves it aborted`() {
        val capture = ExchangeCapture(request)
        capture.responseStarted(200, emptyMap())
        val stream = capture.Stream(ByteArrayOutputStream())
        stream.write("partial".toByteArray())
        capture.abort()
        stream.close()
        val response = capture.snapshot(1).response!!
        assertEquals("partial", response.body)
        assertEquals("aborted", response.state)
    }

    @Test
    fun `a snapshot before responseStarted has a null response`() {
        val record = ExchangeCapture(request).snapshot(3)
        assertEquals(3, record.seq)
        assertEquals(request, record.request)
        assertNull(record.response)
    }
}
