package com.davidjonesgriz.fakellm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** What each key of a step's `match` holds against, on every route. */
class MatchTest {
    private val anthropic =
        parseAnthropicRequest(
            """{"model":"claude-x","system":"Merge the summaries.","stream":true,
            "tools":[{"name":"get_weather"}],"tool_choice":{"type":"tool","name":"get_weather"},"metadata":{"user id":["u1",{"n":1}]},
            "messages":[{"role":"user","content":"Summarize doc-a"},
              {"role":"assistant","content":[{"type":"tool_use","id":"t1","name":"get_weather","input":{}}]},
              {"role":"user","content":[{"type":"tool_result","tool_use_id":"t1","content":"sunny","is_error":true}]}]}""",
        ).withHeaders(mapOf("X-Trace" to listOf("abc", "def")))
    private val openAi =
        parseOpenAiRequest(
            """{"model":"gpt-x","messages":[{"role":"system","content":"Merge"},{"role":"user","content":"Summarize doc-b"},
            {"role":"assistant","content":null,"tool_calls":[{"id":"c1","function":{"name":"f"}}]},
            {"role":"tool","tool_call_id":"c1","content":"{\"temp\":20}"}]}""",
        )
    private val responses =
        parseResponsesRequest(
            """{"model":"gpt-r","input":[{"role":"user","content":"Summarize doc-c"},
            {"type":"function_call","call_id":"c1","name":"f","arguments":"{}"},
            {"type":"function_call_output","call_id":"c1","output":"done"}]}""",
        )
    private val gemini =
        parseGeminiRequest(
            """{"contents":[{"parts":[{"text":"Summarize doc-d"}]},{"role":"model","parts":[{"functionCall":{"name":"f"}}]},
            {"role":"user","parts":[{"functionResponse":{"name":"f","response":{"ok":true}}}]}]}""",
            "gemini-x",
        )

    private fun holds(
        match: Match,
        req: ParsedRequest,
        vendor: String = "anthropic",
    ) = match.holds(vendor, req)

    @Test
    fun `route tells the four routes apart, and model and stream compare exactly`() {
        listOf("anthropic" to anthropic, "openai" to openAi, RESPONSES_VENDOR to responses, "gemini" to gemini).forEach { (vendor, req) ->
            ROUTES.forEach { route -> assertEquals(route == vendor, holds(Match(route = route), req, vendor), "$route on $vendor") }
        }
        assertTrue(holds(Match(model = "claude-x"), anthropic))
        assertFalse(holds(Match(model = "claude"), anthropic))
        assertTrue(holds(Match(stream = true), anthropic))
        assertFalse(holds(Match(stream = true), openAi, "openai"))
    }

    @Test
    fun `lastUser skips a tool-result turn on every route and reads the last user prompt`() {
        listOf("anthropic" to anthropic, "openai" to openAi, RESPONSES_VENDOR to responses, "gemini" to gemini).forEach { (vendor, req) ->
            val doc = "doc-" + mapOf("anthropic" to "a", "openai" to "b", RESPONSES_VENDOR to "c", "gemini" to "d").getValue(vendor)
            assertTrue(holds(Match(lastUser = TextMatch(contains = doc)), req, vendor), vendor)
            assertTrue(holds(Match(lastUser = TextMatch(equalTo = "Summarize $doc")), req, vendor), vendor)
        }
        assertFalse(holds(Match(lastUser = TextMatch(contains = "x")), parseOpenAiRequest("""{"messages":[]}"""), "openai"))
    }

    @Test
    fun `system, prompt and hasSystem read the system prompt`() {
        assertTrue(holds(Match(system = TextMatch(contains = "Merge")), anthropic))
        assertTrue(holds(Match(system = TextMatch(equalTo = "Merge")), openAi, "openai"))
        assertFalse(holds(Match(system = TextMatch(contains = "Merge")), gemini, "gemini"))
        assertTrue(holds(Match(prompt = TextMatch(regex = "doc-a\\s+sunny")), anthropic))
        assertTrue(holds(Match(hasSystem = true), anthropic))
        assertTrue(holds(Match(hasSystem = false), gemini, "gemini"))
        val wrongType = parseAnthropicRequest("""{"system":5,"messages":[]}""")
        assertTrue(holds(Match(hasSystem = false), wrongType))
    }

    @Test
    fun `message picks by index from either end, or the last message with a role`() {
        assertTrue(holds(Match(message = MessageMatch(index = 0, contains = "doc-a")), anthropic))
        assertTrue(holds(Match(message = MessageMatch(index = -3, contains = "doc-a")), anthropic))
        assertFalse(holds(Match(message = MessageMatch(index = 3, contains = "")), anthropic))
        assertFalse(holds(Match(message = MessageMatch(index = -4, contains = "")), anthropic))
        assertTrue(holds(Match(message = MessageMatch(role = "model", equalTo = "")), gemini, "gemini"))
        assertTrue(holds(Match(message = MessageMatch(role = "tool", contains = "temp")), openAi, "openai"))
        assertFalse(holds(Match(message = MessageMatch(role = "developer", contains = "")), openAi, "openai"))
        assertTrue(holds(Match(messageCount = 3), anthropic))
        assertFalse(holds(Match(messageCount = 2), anthropic))
    }

    @Test
    fun `toolResult searches every result, and tools reads the declared names and the normalized choice`() {
        assertTrue(holds(Match(toolResult = ToolResultMatch(id = "t1", name = "get_weather", isError = true)), anthropic))
        assertFalse(holds(Match(toolResult = ToolResultMatch(id = "t1", isError = false)), anthropic))
        assertTrue(holds(Match(toolResult = ToolResultMatch(content = TextMatch(equalTo = "sunny"))), anthropic))
        assertTrue(holds(Match(toolResult = ToolResultMatch(content = TextMatch(equalTo = "{\"ok\":true}"))), gemini, "gemini"))
        assertTrue(holds(Match(toolResult = ToolResultMatch(name = "f", content = TextMatch(contains = "temp"))), openAi, "openai"))
        assertTrue(holds(Match(tools = ToolsMatch(declares = "get_weather", choice = "tool", choiceTool = "get_weather")), anthropic))
        assertTrue(holds(Match(tools = ToolsMatch(choiceTool = "get_weather")), anthropic))
        assertFalse(holds(Match(tools = ToolsMatch(choiceTool = "other")), anthropic))
        assertFalse(holds(Match(tools = ToolsMatch(declares = "search")), anthropic))
        assertTrue(holds(Match(tools = ToolsMatch(choice = "none")), gemini, "gemini"))
    }

    @Test
    fun `body follows a path and compares as JSON, numbers by value`() {
        assertTrue(holds(Match(body = BodyMatch("$.metadata['user id'][0]", JsonPrimitive("u1"))), anthropic))
        assertTrue(holds(Match(body = BodyMatch("$.metadata['user id'][-1].n", JsonPrimitive(1.0))), anthropic))
        assertTrue(holds(Match(body = BodyMatch("$.metadata['user id'][-1]", Json.parseToJsonElement("""{"n":1.00}"""))), anthropic))
        assertFalse(holds(Match(body = BodyMatch("$.metadata['user id'][0]", JsonPrimitive(1))), anthropic))
        assertFalse(holds(Match(body = BodyMatch("$.metadata.missing", JsonNull)), anthropic))
        assertTrue(holds(Match(body = BodyMatch("$.stream", JsonPrimitive(true))), anthropic))
        assertFalse(holds(Match(body = BodyMatch("$.stream", JsonPrimitive("true"))), anthropic))
        assertTrue(holds(Match(body = BodyMatch("$.messages[2].content[0].tool_use_id", JsonPrimitive("t1"))), anthropic))
        assertTrue(holds(Match(body = BodyMatch("$['model']", JsonPrimitive("claude-x"))), anthropic))
        assertTrue(
            holds(
                Match(body = BodyMatch("$.a", JsonNull)),
                parseOpenAiRequest("""{"messages":[],"a":null}"""),
                "openai",
            ),
        )
    }

    @Test
    fun `header matches any value of the named header in any case`() {
        assertTrue(holds(Match(header = HeaderMatch(name = "x-trace", equalTo = "def")), anthropic))
        assertTrue(holds(Match(header = HeaderMatch(name = "X-TRACE", regex = "^ab")), anthropic))
        assertFalse(holds(Match(header = HeaderMatch(name = "x-other", contains = "")), anthropic))
    }

    @Test
    fun `every key that is set must hold`() {
        assertTrue(holds(Match(route = "anthropic", model = "claude-x", lastUser = TextMatch(contains = "doc-a")), anthropic))
        assertFalse(holds(Match(route = "anthropic", model = "claude-x", lastUser = TextMatch(contains = "doc-b")), anthropic))
    }

    @Test
    fun `a match describes itself for the no-match message`() {
        assertEquals("""lastUser contains "doc-b"""", Match(lastUser = TextMatch(contains = "doc-b")).describe())
        assertEquals(
            """route is "gemini" and stream is true and message[-1] regex "a+" and messageCount is 2 and """ +
                """body $.a equals {"b":[1,null]} and header x-trace contains "a"""",
            Match(
                route = "gemini",
                stream = true,
                messageCount = 2,
                body = BodyMatch("$.a", Json.parseToJsonElement("""{"b":[1,null]}""")),
                header = HeaderMatch("x-trace", contains = "a"),
                message = MessageMatch(index = -1, regex = "a+"),
            ).describe(),
        )
        assertEquals("""message(role "user") equals "x"""", Match(message = MessageMatch(role = "user", equalTo = "x")).describe())
        assertEquals(
            """toolResult(id "t1", content contains "x", isError false) and tools(declares "f", choice "tool", choiceTool "f")""",
            Match(
                toolResult = ToolResultMatch(id = "t1", content = TextMatch(contains = "x"), isError = false),
                tools = ToolsMatch(declares = "f", choice = "tool", choiceTool = "f"),
            ).describe(),
        )
    }
}
