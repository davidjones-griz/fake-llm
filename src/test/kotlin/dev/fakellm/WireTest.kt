package dev.fakellm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class WireTest {
    @Test
    fun `blocks system shape - text collected from system blocks and string messages`() {
        val req =
            parseAnthropicRequest(
                """{"model":"m","system":[{"type":"text","text":"SYS"}],""" +
                    """"messages":[{"role":"user","content":"USER"}]}""",
            )
        assertEquals("blocks", req.systemShape)
        assertTrue(req.promptText.contains("SYS"))
        assertTrue(req.promptText.contains("USER"))
        assertEquals(1, req.messageCount)
    }

    @Test
    fun `string system shape and block-array message content both collect`() {
        val req =
            parseAnthropicRequest(
                """{"model":"m","system":"PLAIN SYS",""" +
                    """"messages":[{"role":"user","content":[{"type":"text","text":"BLOCK MSG"}]}]}""",
            )
        assertEquals("string", req.systemShape)
        assertTrue(req.promptText.contains("PLAIN SYS"))
        assertTrue(req.promptText.contains("BLOCK MSG"))
    }

    @Test
    fun `absent system shape - the flat extractor request`() {
        val req = parseAnthropicRequest("""{"model":"m","messages":[{"role":"user","content":"P"}]}""")
        assertEquals("absent", req.systemShape)
    }

    @Test
    fun `a body without messages is a hard error`() {
        assertFailsWith<IllegalStateException> { parseAnthropicRequest("""{"model":"m"}""") }
    }

    @Test
    fun `envelope is a decodable anthropic response with one text block and fixed usage`() {
        val env = anthropicEnvelope("OUT", stopReason = "max_tokens", seq = 7)
        assertTrue(env.contains(""""text": "OUT"""") || env.contains(""""text":"OUT""""))
        assertTrue(env.contains("max_tokens"))
        assertTrue(env.contains("input_tokens"))
        assertTrue(env.contains("fake-msg-7"))
    }

    @Test
    fun `stream field is absent-safe - defaults to false, true when present`() {
        val noStream = parseAnthropicRequest("""{"model":"m","messages":[{"role":"user","content":"P"}]}""")
        assertEquals(false, noStream.stream)
        val streaming =
            parseAnthropicRequest(
                """{"model":"m","stream":true,"messages":[{"role":"user","content":"P"}]}""",
            )
        assertEquals(true, streaming.stream)
        val explicitFalse =
            parseAnthropicRequest(
                """{"model":"m","stream":false,"messages":[{"role":"user","content":"P"}]}""",
            )
        assertEquals(false, explicitFalse.stream)
    }

    @Test
    fun `buffered envelope usage carries non-zero cache tokens - the fake's non-zero-cache pin fixture`() {
        val env = anthropicEnvelope("OUT", stopReason = "end_turn", seq = 1)
        val usage = Json.parseToJsonElement(env).jsonObject["usage"]!!.jsonObject
        val cacheCreation = (usage["cache_creation_input_tokens"] as JsonPrimitive).content.toInt()
        val cacheRead = (usage["cache_read_input_tokens"] as JsonPrimitive).content.toInt()
        assertEquals(FIXED_CACHE_CREATION_TOKENS, cacheCreation)
        assertEquals(FIXED_CACHE_READ_TOKENS, cacheRead)
        assertTrue(FIXED_CACHE_CREATION_TOKENS >= 1)
        assertTrue(FIXED_CACHE_READ_TOKENS >= 1)
    }

    @Test
    fun `a thinking-carrying envelope emits a leading thinking block, then the text block`() {
        val env = anthropicEnvelope("OUT", stopReason = "end_turn", seq = 1, thinking = "reasoning here")
        val content = Json.parseToJsonElement(env).jsonObject["content"]!!.jsonArray
        assertEquals(2, content.size)
        assertEquals("thinking", (content[0].jsonObject["type"] as JsonPrimitive).content)
        assertEquals("reasoning here", (content[0].jsonObject["thinking"] as JsonPrimitive).content)
        assertTrue((content[0].jsonObject["signature"] as JsonPrimitive).content.isNotBlank())
        assertEquals("text", (content[1].jsonObject["type"] as JsonPrimitive).content)
        assertEquals("OUT", (content[1].jsonObject["text"] as JsonPrimitive).content)
    }

    @Test
    fun `an envelope with no thinking is unchanged - a single text block, first and only`() {
        val env = anthropicEnvelope("OUT", stopReason = "end_turn", seq = 1)
        val content = Json.parseToJsonElement(env).jsonObject["content"]!!.jsonArray
        assertEquals(1, content.size)
        assertEquals("text", (content[0].jsonObject["type"] as JsonPrimitive).content)
    }

    @Test
    fun `a null ttl in cache_control is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            parseAnthropicRequest(
                """{"model":"m","system":[{"type":"text","text":"SYS",""" +
                    """"cache_control":{"type":"ephemeral","ttl":null}}],""" +
                    """"messages":[{"role":"user","content":"USER"}]}""",
            )
        }
    }

    @Test
    fun `an off-vocabulary cache_control type is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            parseAnthropicRequest(
                """{"model":"m","system":[{"type":"text","text":"SYS",""" +
                    """"cache_control":{"type":"permanent"}}],""" +
                    """"messages":[{"role":"user","content":"USER"}]}""",
            )
        }
    }

    @Test
    fun `an off-vocabulary cache_control ttl is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            parseAnthropicRequest(
                """{"model":"m","system":[{"type":"text","text":"SYS",""" +
                    """"cache_control":{"type":"ephemeral","ttl":"10m"}}],""" +
                    """"messages":[{"role":"user","content":"USER"}]}""",
            )
        }
    }

    @Test
    fun `a valid cache_control - no ttl, 5m, or 1h - is accepted and reports a breakpoint`() {
        listOf(
            """{"type":"ephemeral"}""",
            """{"type":"ephemeral","ttl":"5m"}""",
            """{"type":"ephemeral","ttl":"1h"}""",
        ).forEach { cacheControl ->
            val req =
                parseAnthropicRequest(
                    """{"model":"m","system":[{"type":"text","text":"SYS","cache_control":$cacheControl}],""" +
                        """"messages":[{"role":"user","content":"USER"}]}""",
                )
            assertTrue(req.hasCacheBreakpoint, "cache_control=$cacheControl should be accepted")
        }
    }

    @Test
    fun `a malformed cache_control on a message content block is rejected too, not just system`() {
        assertFailsWith<IllegalArgumentException> {
            parseAnthropicRequest(
                """{"model":"m","messages":[{"role":"user","content":[""" +
                    """{"type":"text","text":"USER","cache_control":{"type":"ephemeral","ttl":null}}]}]}""",
            )
        }
    }

    @Test
    fun `a valid system cache_control never short-circuits a malformed one on a later message`() {
        assertFailsWith<IllegalArgumentException> {
            parseAnthropicRequest(
                """{"model":"m","system":[{"type":"text","text":"SYS",""" +
                    """"cache_control":{"type":"ephemeral"}}],""" +
                    """"messages":[{"role":"user","content":[""" +
                    """{"type":"text","text":"USER","cache_control":{"type":"ephemeral","ttl":null}}]}]}""",
            )
        }
    }

    @Test
    fun `a valid cache_control on the first message never short-circuits a malformed one on the second`() {
        assertFailsWith<IllegalArgumentException> {
            parseAnthropicRequest(
                """{"model":"m","messages":[""" +
                    """{"role":"user","content":[{"type":"text","text":"U1",""" +
                    """"cache_control":{"type":"ephemeral"}}]},""" +
                    """{"role":"assistant","content":[{"type":"text","text":"U2",""" +
                    """"cache_control":{"type":"ephemeral","ttl":null}}]}]}""",
            )
        }
    }
}
