package com.davidjonesgriz.fakellm

import kotlin.test.Test
import kotlin.test.assertEquals

/** What each dialect reads into the prompt text and the system shape. */
class PromptTextTest {
    @Test
    fun `openai content parts add their string text in order, and other parts add nothing`() {
        val req =
            parseOpenAiRequest(
                """{"model":"m","messages":[
                  {"role":"user","content":[{"type":"text","text":"one"},{"type":"image_url","image_url":{"url":"u"}},
                    {"type":"text","text":2},"three",{"type":"text","text":"four"}]},
                  {"role":"assistant","content":null,"tool_calls":[]},
                  {"role":"assistant","content":[{"type":"refusal","refusal":"no"}]}
                ]}""",
            )
        assertEquals("one\nfour\n", req.promptText)
        assertEquals("absent", req.systemShape)
    }

    @Test
    fun `an openai developer message is a system prompt, as a system message is`() {
        val developer =
            parseOpenAiRequest("""{"model":"m","messages":[{"role":"developer","content":"Be brief."},{"role":"user","content":"Hi"}]}""")
        assertEquals("blocks", developer.systemShape)
        assertEquals("Be brief.\nHi\n", developer.promptText)
        val system = parseOpenAiRequest("""{"model":"m","messages":[{"role":"system","content":[{"type":"text","text":"Be brief."}]}]}""")
        assertEquals("blocks", system.systemShape)
        assertEquals("Be brief.\n", system.promptText)
    }

    @Test
    fun `gemini systemInstruction comes first and sets systemShape, under either field name`() {
        val camel =
            parseGeminiRequest(
                """{"systemInstruction":{"role":"user","parts":[{"text":"Be brief."},{"text":"Use metric."}]},
                   "contents":[{"role":"user","parts":[{"text":"Hi"}]}]}""",
                modelFromPath = "g",
            )
        assertEquals("Be brief.\nUse metric.\nHi\n", camel.promptText)
        assertEquals("blocks", camel.systemShape)
        val snake =
            parseGeminiRequest(
                """{"system_instruction":{"parts":[{"text":"Be brief."}]},"contents":[{"parts":[{"text":"Hi"}]}]}""",
                modelFromPath = "g",
            )
        assertEquals("Be brief.\nHi\n", snake.promptText)
        assertEquals("blocks", snake.systemShape)
    }

    @Test
    fun `a gemini systemInstruction that is not an object is unknown, and only string part text counts`() {
        val req =
            parseGeminiRequest(
                """{"systemInstruction":"Be brief.","contents":[{"parts":[{"text":"Hi"},{"text":7},{"inlineData":{}}]}]}""",
                modelFromPath = "g",
            )
        assertEquals("unknown", req.systemShape)
        assertEquals("Hi\n", req.promptText)
        val none = parseGeminiRequest("""{"systemInstruction":null,"contents":[]}""", modelFromPath = "g")
        assertEquals("absent", none.systemShape)
        val fallback =
            parseGeminiRequest(
                """{"systemInstruction":null,"system_instruction":{"parts":[{"text":"S"}]},"contents":[]}""",
                modelFromPath = "g",
            )
        assertEquals("blocks", fallback.systemShape)
        assertEquals("S\n", fallback.promptText)
    }

    @Test
    fun `an anthropic tool_result adds its string content or its text blocks`() {
        val req =
            parseAnthropicRequest(
                """{"model":"m","messages":[{"role":"user","content":[
                  {"type":"tool_result","tool_use_id":"a","content":"18 degrees"},
                  {"type":"tool_result","tool_use_id":"b","content":[{"type":"text","text":"sunny"},{"type":"image","source":{}}]},
                  {"type":"tool_result","tool_use_id":"c"},
                  {"type":"text","text":"Thanks."}]}]}""",
            )
        assertEquals("18 degrees\nsunny\nThanks.\n", req.promptText)
    }
}
