package com.davidjonesgriz.fakellm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

class ResponsesDialectTest {
    private val usage =
        """"usage":{"input_tokens":100,"input_tokens_details":{"cached_tokens":0},"output_tokens":50,""" +
            """"output_tokens_details":{"reasoning_tokens":0},"total_tokens":150}"""

    private fun response(
        status: String,
        output: String,
        seq: Int = 1,
    ): String {
        val incomplete = if (status == "incomplete") """{"reason":"max_output_tokens"}""" else "null"
        return """{"id":"resp_fake_$seq","object":"response","status":"$status","error":null,"incomplete_details":$incomplete,""" +
            """"model":"example-gpt","output":[$output],"parallel_tool_calls":true,"tool_choice":"auto","tools":[],$usage}"""
    }

    private fun message(
        text: String,
        status: String = "completed",
        seq: Int = 1,
    ) = """{"id":"msg_fake_$seq","type":"message","status":"$status","role":"assistant",""" +
        """"content":[{"type":"output_text","text":${JsonPrimitive(text)},"annotations":[]}]}"""

    @Test
    fun `a string input is one message, and string instructions come first in the prompt with the string system shape`() {
        val req = parseResponsesRequest("""{"model":"example-gpt","instructions":"Answer briefly.","input":"Hello"}""")
        assertEquals(
            ParsedRequest(model = "example-gpt", systemShape = "string", promptText = "Answer briefly.\nHello\n", messageCount = 1),
            req,
        )
    }

    @Test
    fun `input items are counted one by one, and only message text and string function_call_output add prompt text`() {
        val req =
            parseResponsesRequest(
                """{"model":"m","input":[
                  {"role":"user","content":"Hello"},
                  {"role":"user","content":[{"type":"input_text","text":"Look"},{"type":"input_image","file_id":"file_1"}]},
                  {"type":"message","role":"assistant","content":[{"type":"output_text","text":"Checking."}]},
                  {"type":"function_call","call_id":"c1","name":"get_weather","arguments":"{}"},
                  {"type":"function_call_output","call_id":"c1","output":"18 degrees"},
                  {"type":"function_call_output","call_id":"c1","output":[{"type":"input_text","text":"ignored"}]},
                  {"type":"reasoning","summary":[{"type":"summary_text","text":"ignored"}]}
                ]}""",
            )
        assertEquals(7, req.messageCount)
        assertEquals("Hello\nLook\nChecking.\n18 degrees\n", req.promptText)
        assertEquals("absent", req.systemShape)
    }

    @Test
    fun `a system or developer input message sets the blocks system shape when instructions are absent`() {
        listOf("system", "developer").forEach { role ->
            val req =
                parseResponsesRequest("""{"model":"m","input":[{"role":"$role","content":"Be brief."},{"role":"user","content":"Hi"}]}""")
            assertEquals("blocks", req.systemShape, role)
            assertEquals("Be brief.\nHi\n", req.promptText, role)
        }
        assertEquals(
            "blocks",
            parseResponsesRequest("""{"model":"m","instructions":null,"input":[{"role":"system","content":"x"}]}""").systemShape,
        )
    }

    @Test
    fun `non-string instructions are unknown and add no prompt text`() {
        listOf("1", """{"a":1}""", """["x"]""").forEach { instructions ->
            val req = parseResponsesRequest("""{"model":"m","instructions":$instructions,"input":"Hello"}""")
            assertEquals("unknown", req.systemShape, instructions)
            assertEquals("Hello\n", req.promptText, instructions)
        }
    }

    @Test
    fun `an absent, null or non-list input is zero messages and no prompt text`() {
        listOf(
            """{"model":"m"}""",
            """{"model":"m","input":null}""",
            """{"model":"m","input":{"role":"user"}}""",
            """{"model":"m","input":3}""",
        ).forEach { body ->
            val req = parseResponsesRequest(body)
            assertEquals(0 to "", req.messageCount to req.promptText, body)
            assertEquals("absent", req.systemShape, body)
        }
    }

    @Test
    fun `function_call_output items are recorded with the name of the matching function_call`() {
        val req =
            parseResponsesRequest(
                """{"model":"m","input":[
                  {"type":"function_call","call_id":"c1","name":"get_weather","arguments":"{}"},
                  {"type":"function_call_output","call_id":"c1","output":"18 degrees"},
                  {"type":"function_call_output","call_id":"missing","output":"x"},
                  {"type":"function_call_output","call_id":7,"output":{"a":1}}
                ]}""",
            )
        assertEquals(
            listOf(
                ToolResultRecord("c1", "get_weather", JsonPrimitive("18 degrees"), isError = false),
                ToolResultRecord("missing", null, JsonPrimitive("x"), isError = false),
                ToolResultRecord(null, null, Json.parseToJsonElement("""{"a":1}"""), isError = false),
            ),
            req.toolResults,
        )
    }

    @Test
    fun `stream is read like chat completions, and previous_response_id, store and conversation change nothing`() {
        val plain = parseResponsesRequest("""{"model":"m","input":"Hello","stream":true}""")
        val stateful =
            parseResponsesRequest(
                """{"model":"m","input":"Hello","stream":"TRUE","previous_response_id":"resp_fake_1","store":true,"conversation":"conv_1"}""",
            )
        assertEquals(true, plain.stream)
        assertEquals(plain, stateful)
        assertEquals(false, parseResponsesRequest("""{"model":"m","input":"Hello"}""").stream)
    }

    @Test
    fun `the responses envelope is a completed response with one message item and fixed usage`() {
        assertEquals(response("completed", message("Billing.")), responsesEnvelope(Step(text = "Billing."), 1, "example-gpt"))
    }

    @Test
    fun `max_tokens makes the response and its message item incomplete with reason max_output_tokens`() {
        assertEquals(
            response("incomplete", message("cut off", "incomplete", seq = 2), seq = 2),
            responsesEnvelope(Step(text = "cut off", stopReason = "max_tokens"), 2, "example-gpt"),
        )
    }

    @Test
    fun `a tool-call step adds function_call items after the message, with call_id and argument text`() {
        val step =
            Step(
                text = "Checking.",
                toolCalls =
                    listOf(
                        ToolCall(name = "get_weather", arguments = Json.parseToJsonElement("""{"city":"Paris"}""").jsonObject),
                        ToolCall(name = "get_time", rawArguments = """{"tz": Europe/Paris}""", id = "call_custom"),
                    ),
            )
        assertEquals(
            response(
                "completed",
                message("Checking.") +
                    """,{"id":"fc_fake_1_1","type":"function_call","status":"completed","call_id":"call_fake_1_1","name":"get_weather",""" +
                    """"arguments":"{\"city\":\"Paris\"}"},""" +
                    """{"id":"fc_fake_1_2","type":"function_call","status":"completed","call_id":"call_custom","name":"get_time",""" +
                    """"arguments":"{\"tz\": Europe/Paris}"}""",
            ),
            vendorEnvelope(RESPONSES_VENDOR, step, seq = 1, model = "example-gpt"),
        )
    }

    @Test
    fun `a tool-call step without text has only function_call items`() {
        val step = Step(toolCalls = listOf(ToolCall(name = "get_time")))
        assertEquals(
            response(
                "completed",
                """{"id":"fc_fake_3_1","type":"function_call","status":"completed","call_id":"call_fake_3_1","name":"get_time","arguments":"{}"}""",
                seq = 3,
            ),
            vendorEnvelope(RESPONSES_VENDOR, step, seq = 3, model = "example-gpt"),
        )
    }
}
