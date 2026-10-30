package com.davidjonesgriz.fakellm

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GeminiStreamingTest {
    private val text = buildJsonObject { put("text", "Mild and") }

    @Test
    fun `a gemini chunk carries one part and the request model, and no finishReason or usageMetadata`() {
        assertEquals(
            """{"candidates":[{"content":{"role":"model","parts":[{"text":"Mild and"}]}}],"modelVersion":"example-gemini","fake_llm_seq":3}""",
            geminiChunkJson(3, "example-gemini", text, null),
        )
    }

    @Test
    fun `the last gemini chunk adds finishReason and usageMetadata`() {
        val usage = """"usageMetadata":{"promptTokenCount":100,"candidatesTokenCount":50,"totalTokenCount":150}"""
        listOf("STOP", "MAX_TOKENS").forEach { reason ->
            assertEquals(
                """{"candidates":[{"content":{"role":"model","parts":[{"text":"Mild and"}]},"finishReason":"$reason"}],""" +
                    """$usage,"modelVersion":"example-gemini","fake_llm_seq":1}""",
                geminiChunkJson(1, "example-gemini", text, reason),
            )
        }
    }

    @Test
    fun `alt=sse is read from the raw query`() {
        listOf("alt=sse", "alt=sse&key=k", "key=k&alt=sse").forEach { assertTrue(geminiAltSse(it), it) }
        listOf(null, "", "alt=json", "alt=SSE", "xalt=sse").forEach { assertFalse(geminiAltSse(it), "$it") }
    }
}
