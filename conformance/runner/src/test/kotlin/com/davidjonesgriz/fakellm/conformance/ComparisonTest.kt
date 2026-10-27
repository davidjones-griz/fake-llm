package com.davidjonesgriz.fakellm.conformance

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ComparisonTest {
    private fun json(text: String): JsonElement = Json.parseToJsonElement(text)

    private fun diff(
        expected: String,
        actual: String,
    ): String? = firstDifference(json(expected), json(actual))

    @Test
    fun `equal values match, whatever the key order`() {
        assertNull(diff("""{"a":1,"b":[true,null,"x"],"c":{"d":1.5}}""", """{"c":{"d":1.5},"b":[true,null,"x"],"a":1}"""))
    }

    @Test
    fun `each kind of difference is reported with its JSON path`() {
        assertEquals("$.a: expected 1, got 2", diff("""{"a":1}""", """{"a":2}"""))
        assertEquals("$: missing field 'b'", diff("""{"a":1,"b":2}""", """{"a":1}"""))
        assertEquals("$: unexpected field 'b' = 2", diff("""{"a":1}""", """{"a":1,"b":2}"""))
        assertEquals("$.a[1]: expected \"y\", got \"z\"", diff("""{"a":["x","y"]}""", """{"a":["x","z"]}"""))
        assertEquals("$.a: expected 2 elements, got 1: [1]", diff("""{"a":[1,2]}""", """{"a":[1]}"""))
        assertEquals("$: expected an object, got an array []", diff("""{}""", """[]"""))
        assertEquals("$: expected an array, got an object {}", diff("""[]""", """{}"""))
        assertEquals("$: expected null, got 0", diff("""null""", """0"""))
        assertEquals("$: expected \"1\", got 1", diff("\"1\"", "1"))
        assertEquals("$: expected true, got \"true\"", diff("true", "\"true\""))
    }

    @Test
    fun `an expected integer needs an integer token, other numbers compare by value`() {
        assertNotNull(diff("100", "100.0"))
        assertNotNull(diff("100", "1e2"))
        assertNull(diff("1.5", "1.50"))
        assertNotNull(diff("1.5", "1.6"))
    }

    @Test
    fun `the type matcher accepts any value of its type and nothing else`() {
        assertNull(diff("""{"t":{"${'$'}type":"integer"}}""", """{"t":1710000000000}"""))
        assertNull(diff("""{"t":{"${'$'}type":"string"}}""", """{"t":"anything"}"""))
        assertEquals("$.t: expected any integer, got 1.5", diff("""{"t":{"${'$'}type":"integer"}}""", """{"t":1.5}"""))
        assertNotNull(diff("""{"t":{"${'$'}type":"string"}}""", """{"t":null}"""))
        assertNotNull(diff("""{"t":{"${'$'}type":"integer"}}""", """{"t":"1"}"""))
        assertNotNull(diff("""{"${'$'}type":"boolean"}""", "true"), "an unknown matcher type matches nothing")
    }

    @Test
    fun `the object matcher accepts any object, empty or not, and nothing else`() {
        assertNull(diff("""{"h":{"${'$'}type":"object"}}""", """{"h":{"host":["x"],"user-agent":["y","z"]}}"""))
        assertNull(diff("""{"h":{"${'$'}type":"object"}}""", """{"h":{}}"""))
        assertEquals("$.h: expected any object, got an array []", diff("""{"h":{"${'$'}type":"object"}}""", """{"h":[]}"""))
        assertEquals("$.h: expected any object, got null", diff("""{"h":{"${'$'}type":"object"}}""", """{"h":null}"""))
        assertNotNull(diff("""{"h":{"${'$'}type":"object"}}""", """{"h":"{}"}"""))
    }

    @Test
    fun `SSE frames parse into named events with joined data, as a client dispatches them`() {
        val body = "event: a\ndata: {\"x\":1}\n\n: a comment\n\nretry: 100\n\nevent: b\ndata: line 1\ndata: line 2\n\ndata:no-name\n\n"
        assertEquals(
            listOf(SseEvent("a", """{"x":1}"""), SseEvent("b", "line 1\nline 2"), SseEvent("message", "no-name")),
            parseSse(body),
        )
        assertEquals(listOf(SseEvent("a", "x")), parseSse("event: a\r\ndata: x\r\n\r\n"), "CRLF line ends")
        assertEquals(
            listOf(SseEvent("a", "{}")),
            parseSse("event: a\ndata: {}\n\nevent: b\ndata: {\"cut"),
            "an unterminated final frame is discarded",
        )
    }

    @Test
    fun `header values match exactly, except a content-type without parameters`() {
        assertTrue(headerMatches("Content-Type", "application/json", "application/json; charset=utf-8"))
        assertTrue(headerMatches("content-type", "application/json", "Application/JSON"))
        assertFalse(headerMatches("content-type", "application/json", "text/plain"))
        assertFalse(headerMatches("content-type", "text/plain; charset=utf-8", "text/plain"))
        assertTrue(headerMatches("content-type", "text/plain; charset=utf-8", "text/plain; charset=utf-8"))
        assertFalse(headerMatches("retry-after", "2", "2 "))
    }
}
