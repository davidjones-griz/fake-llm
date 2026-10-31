package com.davidjonesgriz.fakellm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

/** The facts a step's `match` reads from a request, on each of the four routes. */
class RequestFactsTest {
    private fun m(
        role: String?,
        text: String,
    ) = RequestMessage(role, text)

    @Test
    fun `Anthropic system text, messages without tool results, tool names and the request body`() {
        val req =
            parseAnthropicRequest(
                """{"model":"m","system":[{"type":"text","text":"Be brief."},{"type":"text","text":"Be kind."}],
                "tools":[{"name":"get_weather"},{"name":"search"},{"nope":1}],
                "messages":[{"role":"user","content":"Summarize doc-a"},
                  {"role":"assistant","content":[{"type":"tool_use","id":"t1","name":"get_weather","input":{}}]},
                  {"role":"user","content":[{"type":"tool_result","tool_use_id":"t1","content":"sunny"}]},
                  5]}""",
            )
        assertEquals("Be brief.\nBe kind.", req.systemText)
        assertEquals(
            listOf(m("user", "Summarize doc-a"), m("assistant", ""), m("user", ""), m(null, "")),
            req.messages,
        )
        assertEquals(req.messageCount, req.messages.size)
        assertEquals(listOf("get_weather", "search"), req.toolNames)
        assertEquals("auto" to null, req.toolChoice to req.toolChoiceName)
        assertEquals(JsonPrimitive("m"), req.body["model"])
        assertEquals("Be only this.", parseAnthropicRequest("""{"system":"Be only this.","messages":[]}""").systemText)
    }

    @Test
    fun `OpenAI system and developer messages are the system text, and every message counts`() {
        val req =
            parseOpenAiRequest(
                """{"model":"m","messages":[{"role":"system","content":"Rule one."},
                {"role":"developer","content":[{"type":"text","text":"Rule two."}]},
                {"role":"user","content":[{"type":"text","text":"doc-b"},{"type":"image_url","image_url":{}}]},
                {"role":"assistant","content":null,"tool_calls":[{"id":"c1","function":{"name":"f"}}]},
                {"role":"tool","tool_call_id":"c1","content":"42"}],
                "tools":[{"type":"function","function":{"name":"f"}},{"type":"function"}]}""",
            )
        assertEquals("Rule one.\nRule two.", req.systemText)
        assertEquals(
            listOf(m("system", "Rule one."), m("developer", "Rule two."), m("user", "doc-b"), m("assistant", ""), m("tool", "42")),
            req.messages,
        )
        assertEquals(listOf("f"), req.toolNames)
    }

    @Test
    fun `OpenAI Responses reads instructions, a string input as one user message, and items without a role`() {
        val items =
            parseResponsesRequest(
                """{"model":"m","input":[{"role":"developer","content":"Dev rule."},
                {"role":"user","content":[{"type":"input_text","text":"doc-c"}]},
                {"type":"function_call","call_id":"c1","name":"f","arguments":"{}"},
                {"type":"function_call_output","call_id":"c1","output":"done"},"stray"],
                "tools":[{"type":"function","name":"f"}]}""",
            )
        assertEquals("Dev rule.", items.systemText)
        assertEquals(
            listOf(m("developer", "Dev rule."), m("user", "doc-c"), m(null, ""), m(null, ""), m(null, "")),
            items.messages,
        )
        assertEquals(items.messageCount, items.messages.size)
        assertEquals(listOf("f"), items.toolNames)
        val plain = parseResponsesRequest("""{"model":"m","instructions":"Top rule.","input":"hello"}""")
        assertEquals("Top rule.", plain.systemText)
        assertEquals(listOf(m("user", "hello")), plain.messages)
        assertEquals("none" to null, plain.toolChoice to plain.toolChoiceName)
    }

    @Test
    fun `Gemini reads a missing role as user, and both spellings of the system instruction and tools`() {
        val req =
            parseGeminiRequest(
                """{"system_instruction":{"parts":[{"text":"Sys."}]},
                "contents":[{"parts":[{"text":"doc-d"}]},{"role":"model","parts":[{"functionCall":{"name":"f"}}]},
                  {"role":"user","parts":[{"functionResponse":{"name":"f","response":{}}}]},7],
                "tools":[{"function_declarations":[{"name":"f"}]},{"functionDeclarations":[{"name":"g"}]}]}""",
                "gemini-m",
            )
        assertEquals("Sys.", req.systemText)
        assertEquals(listOf(m("user", "doc-d"), m("model", ""), m("user", ""), m(null, "")), req.messages)
        assertEquals(req.messageCount, req.messages.size)
        assertEquals(listOf("f", "g"), req.toolNames)
    }

    @Test
    fun `the tool choice is normalized the same way on every route`() {
        fun anthropic(choice: String) =
            parseAnthropicRequest("""{"messages":[],"tools":[{"name":"f"}],"tool_choice":$choice}""").let {
                it.toolChoice to
                    it.toolChoiceName
            }

        fun openAi(choice: String) =
            parseOpenAiRequest("""{"messages":[],"tools":[{"function":{"name":"f"}}],"tool_choice":$choice}""").let {
                it.toolChoice to it.toolChoiceName
            }

        fun responses(choice: String) =
            parseResponsesRequest("""{"input":"x","tools":[{"type":"function","name":"f"}],"tool_choice":$choice}""").let {
                it.toolChoice to it.toolChoiceName
            }

        fun gemini(config: String) =
            parseGeminiRequest("""{"contents":[],"tools":[{"functionDeclarations":[{"name":"f"}]}],$config}""", "m").let {
                it.toolChoice to it.toolChoiceName
            }

        assertEquals("auto" to null, anthropic("""{"type":"auto"}"""))
        assertEquals("required" to null, anthropic("""{"type":"any"}"""))
        assertEquals("none" to null, anthropic("""{"type":"none"}"""))
        assertEquals("tool" to "f", anthropic("""{"type":"tool","name":"f"}"""))
        assertEquals("other" to null, anthropic("""{"type":"tool"}"""))
        assertEquals("other" to null, anthropic("\"auto\""))
        assertEquals("auto" to null, anthropic("null"))

        assertEquals("auto" to null, openAi("\"auto\""))
        assertEquals("none" to null, openAi("\"none\""))
        assertEquals("required" to null, openAi("\"required\""))
        assertEquals("tool" to "f", openAi("""{"type":"function","function":{"name":"f"}}"""))
        assertEquals("other" to null, openAi("""{"type":"allowed_tools"}"""))

        assertEquals("required" to null, responses("\"required\""))
        assertEquals("tool" to "f", responses("""{"type":"function","name":"f"}"""))
        assertEquals("other" to null, responses("""{"type":"file_search"}"""))

        assertEquals("auto" to null, gemini(""""toolConfig":{"functionCallingConfig":{"mode":"AUTO"}}"""))
        assertEquals("none" to null, gemini(""""toolConfig":{"functionCallingConfig":{"mode":"NONE"}}"""))
        assertEquals("required" to null, gemini(""""toolConfig":{"functionCallingConfig":{"mode":"ANY"}}"""))
        assertEquals(
            "tool" to "f",
            gemini(""""tool_config":{"function_calling_config":{"mode":"ANY","allowed_function_names":["f"]}}"""),
        )
        assertEquals(
            "required" to null,
            gemini(""""toolConfig":{"functionCallingConfig":{"mode":"ANY","allowedFunctionNames":["f","g"]}}"""),
        )
        assertEquals("other" to null, gemini(""""toolConfig":{"functionCallingConfig":{"mode":"VALIDATED"}}"""))
        assertEquals("auto" to null, gemini(""""generationConfig":{}"""))
    }

    @Test
    fun `wrong-typed fields read as absent`() {
        val req = parseAnthropicRequest("""{"system":5,"tools":"x","tool_choice":7,"messages":[{"role":3,"content":4}]}""")
        assertEquals("", req.systemText)
        assertEquals(listOf(m(null, "")), req.messages)
        assertEquals(emptyList(), req.toolNames)
        assertEquals("other" to null, req.toolChoice to req.toolChoiceName)
        assertEquals(Json.parseToJsonElement("""{"a":1}""").jsonObject, parseOpenAiRequest("""{"messages":[],"a":1}""").body - "messages")
    }

    @Test
    fun `request headers are kept with lowercase names and every value`() {
        val req = parseOpenAiRequest("""{"messages":[]}""").withHeaders(mapOf("X-Trace" to listOf("a", "b"), "Content-type" to listOf("j")))
        assertEquals(mapOf("x-trace" to listOf("a", "b"), "content-type" to listOf("j")), req.headers)
    }
}
