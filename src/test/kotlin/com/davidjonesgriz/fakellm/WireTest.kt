package com.davidjonesgriz.fakellm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
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
    fun `null system shape is absent and adds no prompt text`() {
        val req = parseAnthropicRequest("""{"model":"m","system":null,"messages":[{"role":"user","content":"P"}]}""")
        assertEquals("absent", req.systemShape)
        assertEquals("P\n", req.promptText)
    }

    @Test
    fun `a non-string scalar system is unknown and adds no prompt text`() {
        listOf("5", "true").forEach { system ->
            val req = parseAnthropicRequest("""{"model":"m","system":$system,"messages":[{"role":"user","content":"P"}]}""")
            assertEquals("unknown", req.systemShape, system)
            assertEquals("P\n", req.promptText, system)
        }
    }

    @Test
    fun `a text block whose text is not a string adds no prompt text`() {
        val req =
            parseAnthropicRequest(
                """{"model":"m","system":[{"type":"text","text":null},{"type":"text","text":5}],""" +
                    """"messages":[{"role":"user","content":[{"type":"text","text":true},{"type":"text","text":"P"}]}]}""",
            )
        assertEquals("blocks", req.systemShape)
        assertEquals("P\n", req.promptText)
    }

    @Test
    fun `openai stream and include_usage are parsed, and include_usage counts only on a stream`() {
        fun parse(extra: String) = parseOpenAiRequest("""{"model":"m","messages":[{"role":"user","content":"P"}]$extra}""")
        assertEquals(false, parse("").includeUsage)
        assertEquals(false, parse(",\"stream\":true").includeUsage)
        assertEquals(true, parse(",\"stream\":true").stream)
        assertEquals(true, parse(",\"stream\":true,\"stream_options\":{\"include_usage\":true}").includeUsage)
        assertEquals(false, parse(",\"stream\":false,\"stream_options\":{\"include_usage\":true}").includeUsage)
        assertEquals(false, parse(",\"stream_options\":{\"include_usage\":true}").includeUsage)
        assertEquals(false, parse(",\"stream\":true,\"stream_options\":{\"include_usage\":false}").includeUsage)
        assertEquals(false, parse(",\"stream\":true,\"stream_options\":{\"include_usage\":\"yes\"}").includeUsage)
        assertEquals(false, parse(",\"stream\":true,\"stream_options\":null").includeUsage)
    }

    @Test
    fun `a body without messages is a hard error`() {
        assertFailsWith<IllegalStateException> { parseAnthropicRequest("""{"model":"m"}""") }
    }

    @Test
    fun `envelope is a decodable anthropic response with one text block and fixed usage`() {
        val env = anthropicEnvelope(Step(text = "OUT", stopReason = "max_tokens"), seq = 7, model = "example-model")
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
        val env = anthropicEnvelope(Step(text = "OUT"), seq = 1, model = "example-model")
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
        val env = anthropicEnvelope(Step(text = "OUT", thinking = "reasoning here"), seq = 1, model = "example-model")
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
        val env = anthropicEnvelope(Step(text = "OUT"), seq = 1, model = "example-model")
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

    private fun args(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

    @Test
    fun `anthropic envelope puts thinking, text, then tool_use blocks, with stop_reason tool_use`() {
        val step =
            Step(
                text = "Checking.",
                thinking = "Look.",
                toolCalls =
                    listOf(
                        ToolCall(name = "get_weather", arguments = args("""{"city":"Paris"}""")),
                        ToolCall(name = "get_time", id = "toolu_custom"),
                    ),
            )
        assertEquals(
            """{"id":"fake-msg-1","type":"message","role":"assistant","model":"example-model","content":[""" +
                """{"type":"thinking","thinking":"Look.","signature":"fake-thinking-signature"},""" +
                """{"type":"text","text":"Checking."},""" +
                """{"type":"tool_use","id":"toolu_fake_1_1","name":"get_weather","input":{"city":"Paris"}},""" +
                """{"type":"tool_use","id":"toolu_custom","name":"get_time","input":{}}],"stop_reason":"tool_use",""" +
                """"usage":{"input_tokens":100,"output_tokens":50,"cache_read_input_tokens":0,"cache_creation_input_tokens":0}}""",
            vendorEnvelope("anthropic", step, seq = 1, model = "example-model", cacheable = false),
        )
    }

    @Test
    fun `anthropic envelope without text has no text block`() {
        val env = vendorEnvelope("anthropic", Step(toolCalls = listOf(ToolCall(name = "get_time"))), seq = 2, model = "example-model")
        val content = Json.parseToJsonElement(env).jsonObject["content"]!!.jsonArray
        assertEquals(
            listOf("""{"type":"tool_use","id":"toolu_fake_2_1","name":"get_time","input":{}}"""),
            content.map { it.toString() },
        )
    }

    @Test
    fun `openai envelope has null content and tool_calls with argument text, and finish_reason tool_calls`() {
        val step = Step(toolCalls = listOf(ToolCall(name = "get_time", rawArguments = """{"tz": Europe/Paris}""")))
        assertEquals(
            """{"id":"fake-openai-1","object":"chat.completion","model":"example-gpt","choices":[{"index":0,""" +
                """"message":{"role":"assistant","content":null,"tool_calls":[{"id":"call_fake_1_1","type":"function",""" +
                """"function":{"name":"get_time","arguments":"{\"tz\": Europe/Paris}"}}]},"finish_reason":"tool_calls"}],""" +
                """"usage":{"prompt_tokens":100,"completion_tokens":50,"total_tokens":150}}""",
            vendorEnvelope("openai", step, seq = 1, model = "example-gpt"),
        )
    }

    @Test
    fun `gemini envelope has functionCall parts, with an id only when scripted, and finishReason STOP`() {
        val step =
            Step(
                toolCalls =
                    listOf(
                        ToolCall(name = "get_weather", arguments = args("""{"city":"Paris"}""")),
                        ToolCall(name = "get_time", id = "t2"),
                    ),
                stopReason = "tool_use",
            )
        assertEquals(
            """{"candidates":[{"content":{"role":"model","parts":[""" +
                """{"functionCall":{"name":"get_weather","args":{"city":"Paris"}}},""" +
                """{"functionCall":{"id":"t2","name":"get_time","args":{}}}]},"finishReason":"STOP"}],""" +
                """"usageMetadata":{"promptTokenCount":100,"candidatesTokenCount":50,"totalTokenCount":150},""" +
                """"modelVersion":"example-gemini","fake_llm_seq":1}""",
            vendorEnvelope("gemini", step, seq = 1, model = "example-gemini"),
        )
    }

    @Test
    fun `anthropic tool_result blocks are recorded in order with the name of their tool_use`() {
        val req =
            parseAnthropicRequest(
                """{"model":"m","messages":[
                  {"role":"user","content":"Hello"},
                  {"role":"assistant","content":[{"type":"text","text":"Checking."},
                    {"type":"tool_use","id":"a","name":"get_weather","input":{}},
                    {"type":"tool_use","id":"b","name":"get_time","input":{}},
                    {"type":"tool_use","id":"c","name":"get_date","input":{}}]},
                  {"role":"user","content":[
                    {"type":"tool_result","tool_use_id":"a","content":"18 degrees"},
                    {"type":"tool_result","tool_use_id":"b","content":[{"type":"text","text":"unknown timezone"}],"is_error":true},
                    {"type":"tool_result","tool_use_id":"c","content":"today","is_error":false}]}
                ]}""",
            )
        assertEquals(
            listOf(
                ToolResultRecord("a", "get_weather", JsonPrimitive("18 degrees"), isError = false),
                ToolResultRecord(
                    "b",
                    "get_time",
                    Json.parseToJsonElement("""[{"type":"text","text":"unknown timezone"}]"""),
                    isError = true,
                ),
                ToolResultRecord("c", "get_date", JsonPrimitive("today"), isError = false),
            ),
            req.toolResults,
        )
        assertEquals("Hello\nChecking.\n18 degrees\nunknown timezone\ntoday\n", req.promptText)
    }

    @Test
    fun `openai tool messages are recorded with the name of the matching assistant tool call`() {
        val req =
            parseOpenAiRequest(
                """{"model":"m","messages":[
                  {"role":"user","content":"Hello"},
                  {"role":"assistant","content":null,"tool_calls":[
                    {"id":"call_1","type":"function","function":{"name":"get_weather","arguments":"{}"}},
                    {"id":"call_2","type":"function","function":{"name":"get_time","arguments":"{}"}}]},
                  {"role":"tool","tool_call_id":"call_1","content":"18 degrees"},
                  {"role":"tool","tool_call_id":"call_2","content":[{"type":"text","text":"noon"}]}
                ]}""",
            )
        assertEquals(
            listOf(
                ToolResultRecord("call_1", "get_weather", JsonPrimitive("18 degrees"), isError = false),
                ToolResultRecord("call_2", "get_time", Json.parseToJsonElement("""[{"type":"text","text":"noon"}]"""), isError = false),
            ),
            req.toolResults,
        )
    }

    @Test
    fun `gemini functionResponse parts are recorded with their name, id and response`() {
        val req =
            parseGeminiRequest(
                """{"contents":[
                  {"role":"user","parts":[{"text":"Hello"}]},
                  {"role":"model","parts":[{"functionCall":{"name":"get_weather","args":{}}}]},
                  {"role":"user","parts":[
                    {"functionResponse":{"name":"get_weather","response":{"temperature":18}}},
                    {"functionResponse":{"id":"t2","name":"get_time","response":{"error":"unknown timezone"}}}]}
                ]}""",
                "example-gemini",
            )
        assertEquals(
            listOf(
                ToolResultRecord(null, "get_weather", args("""{"temperature":18}"""), isError = false),
                ToolResultRecord("t2", "get_time", args("""{"error":"unknown timezone"}"""), isError = false),
            ),
            req.toolResults,
        )
        assertEquals("Hello\n", req.promptText)
    }

    @Test
    fun `a tool result with no matching call has a null name, and a non-string id is null`() {
        val anthropic =
            parseAnthropicRequest(
                """{"model":"m","messages":[{"role":"user","content":[
                  {"type":"tool_result","tool_use_id":"missing"},
                  {"type":"tool_result","tool_use_id":7,"content":null,"is_error":"true"}]}]}""",
            )
        assertEquals(
            listOf(ToolResultRecord("missing", null, null, isError = false), ToolResultRecord(null, null, null, isError = false)),
            anthropic.toolResults,
        )
        val openAi = parseOpenAiRequest("""{"model":"m","messages":[{"role":"tool","tool_call_id":"missing","content":"x"}]}""")
        assertEquals(listOf(ToolResultRecord("missing", null, JsonPrimitive("x"), isError = false)), openAi.toolResults)
        val gemini =
            parseGeminiRequest("""{"contents":[{"parts":[{"functionResponse":{"id":1,"name":false}}]}]}""", "example-gemini")
        assertEquals(listOf(ToolResultRecord(null, null, null, isError = false)), gemini.toolResults)
    }

    @Test
    fun `an openai message with null content adds no prompt text`() {
        val req =
            parseOpenAiRequest(
                """{"model":"m","messages":[{"role":"user","content":"Hello"},
                  {"role":"assistant","content":null,"tool_calls":[{"id":"c","type":"function","function":{"name":"f","arguments":"{}"}}]}]}""",
            )
        assertEquals("Hello\n", req.promptText)
    }
}
