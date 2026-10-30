package com.davidjonesgriz.fakellm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.http.HttpResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ResponsesRouteTest {
    private val path = "/openai/v1/responses"

    private fun engineWith(vararg steps: Step) =
        ScenarioEngine().also { it.loadInline(Scenario(name = "responses", steps = steps.toList())) }

    private fun contentType(resp: HttpResponse<String>) = resp.headers().firstValue("content-type").orElse("")

    private fun json(data: String): JsonObject = Json.parseToJsonElement(data).jsonObject

    private val usage =
        """{"input_tokens":100,"input_tokens_details":{"cached_tokens":0},"output_tokens":50,""" +
            """"output_tokens_details":{"reasoning_tokens":0},"total_tokens":150}"""

    /** The expected `Response` object, written out independently of the server's builders. */
    private fun response(
        status: String,
        output: String,
        withUsage: Boolean = true,
        seq: Int = 1,
    ): String {
        val incomplete = if (status == "incomplete") """{"reason":"max_output_tokens"}""" else "null"
        return """{"id":"resp_fake_$seq","object":"response","status":"$status","error":null,"incomplete_details":$incomplete,""" +
            """"model":"example-gpt","output":[$output],"parallel_tool_calls":true,"tool_choice":"auto","tools":[],""" +
            """"usage":${if (withUsage) usage else "null"}}"""
    }

    private fun message(
        text: String?,
        status: String = "completed",
    ): String {
        val content = text?.let { """{"type":"output_text","text":${JsonPrimitive(it)},"annotations":[]}""" } ?: ""
        return """{"id":"msg_fake_1","type":"message","status":"$status","role":"assistant","content":[$content]}"""
    }

    private fun event(
        type: String,
        fields: String,
        n: Int,
    ) = type to """{"type":"$type",$fields,"sequence_number":$n}"""

    private val ids = """"item_id":"msg_fake_1","output_index":0,"content_index":0"""

    private fun part(text: String) = """{"type":"output_text","text":${JsonPrimitive(text)},"annotations":[]}"""

    /** `response.created` to the message's `content_part.added`: sequence numbers 0 to 3. */
    private fun opening(): List<Pair<String, String>> =
        listOf(
            event("response.created", """"response":${response("in_progress", "", withUsage = false)}""", 0),
            event("response.in_progress", """"response":${response("in_progress", "", withUsage = false)}""", 1),
            event("response.output_item.added", """"output_index":0,"item":${message(null, "in_progress")}""", 2),
            event("response.content_part.added", """$ids,"part":${part("")}""", 3),
        )

    private fun delta(
        piece: String,
        n: Int,
    ) = event("response.output_text.delta", """$ids,"delta":${JsonPrimitive(piece)},"logprobs":[]""", n)

    private fun frames(resp: HttpResponse<String>) = namedFrames(resp.body())

    private fun deltas(
        frames: List<Pair<String, String>>,
        type: String,
    ): List<String> = frames.filter { it.first == type }.map { json(it.second).getValue("delta").jsonPrimitive.content }

    @Test
    fun `a buffered responses call returns the envelope and is logged as openai-responses with the request's own input`() {
        withServer(engineWith(Step(text = "Mild and dry tomorrow."))) { client, engine ->
            val resp = client.post(path, responsesBody())
            assertEquals(200, resp.statusCode())
            assertTrue(contentType(resp).startsWith("application/json"))
            assertEquals(response("completed", message("Mild and dry tomorrow.")), resp.body())
            val record = engine.calls().single()
            assertEquals(
                listOf("openai-responses", "example-gpt", "absent", "1", "Hello\n", "text"),
                listOf(record.vendor, record.model, record.systemShape, "${record.messageCount}", record.promptSample, record.outcome),
            )
        }
    }

    @Test
    fun `a streamed responses text step is the full event sequence, numbered from 0`() {
        val text = "Mild and dry tomorrow."
        withServer(engineWith(Step(text = text, streamChunkChars = 8))) { client, _ ->
            val resp = client.post(path, responsesBody(stream = true))
            assertTrue(contentType(resp).startsWith("text/event-stream"))
            val expected =
                opening() +
                    listOf(
                        delta("Mild and", 4),
                        delta(" dry tom", 5),
                        delta("orrow.", 6),
                        event("response.output_text.done", """$ids,"text":"$text","logprobs":[]""", 7),
                        event("response.content_part.done", """$ids,"part":${part(text)}""", 8),
                        event("response.output_item.done", """"output_index":0,"item":${message(text)}""", 9),
                        event("response.completed", """"response":${response("completed", message(text))}""", 10),
                    )
            val frames = frames(resp)
            assertEquals(expected, frames)
            assertTrue(frames.all { (name, data) -> json(data).getValue("type").jsonPrimitive.content == name })
            assertTrue(resp.body().endsWith("}\n\n"))
            assertFalse(resp.body().contains("[DONE]"))
        }
    }

    @Test
    fun `a streamed responses step with thinking sends the reasoning item first and moves the message to output_index 1`() {
        val thinking = "The user asks about billing."
        val text = "Billing."
        withServer(engineWith(Step(text = text, thinking = thinking, streamChunkChars = 12))) { client, _ ->
            val rs = """"item_id":"rs_fake_1","output_index":0,"summary_index":0"""
            val summary = { t: String -> """{"type":"summary_text","text":${JsonPrimitive(t)}}""" }
            val reasoning = { t: String? -> """{"id":"rs_fake_1","type":"reasoning","summary":[${t?.let(summary) ?: ""}]}""" }
            val msg = """"item_id":"msg_fake_1","output_index":1,"content_index":0"""
            val final = response("completed", reasoning(thinking) + "," + message(text))
            assertEquals(
                opening().take(2) +
                    listOf(
                        event("response.output_item.added", """"output_index":0,"item":${reasoning(null)}""", 2),
                        event("response.reasoning_summary_part.added", """$rs,"part":${summary("")}""", 3),
                        event("response.reasoning_summary_text.delta", """$rs,"delta":"The user ask"""", 4),
                        event("response.reasoning_summary_text.delta", """$rs,"delta":"s about bill"""", 5),
                        event("response.reasoning_summary_text.delta", """$rs,"delta":"ing."""", 6),
                        event("response.reasoning_summary_text.done", """$rs,"text":"$thinking"""", 7),
                        event("response.reasoning_summary_part.done", """$rs,"part":${summary(thinking)}""", 8),
                        event("response.output_item.done", """"output_index":0,"item":${reasoning(thinking)}""", 9),
                        event("response.output_item.added", """"output_index":1,"item":${message(null, "in_progress")}""", 10),
                        event("response.content_part.added", """$msg,"part":${part("")}""", 11),
                        event("response.output_text.delta", """$msg,"delta":"$text","logprobs":[]""", 12),
                        event("response.output_text.done", """$msg,"text":"$text","logprobs":[]""", 13),
                        event("response.content_part.done", """$msg,"part":${part(text)}""", 14),
                        event("response.output_item.done", """"output_index":1,"item":${message(text)}""", 15),
                        event("response.completed", """"response":$final""", 16),
                    ),
                frames(client.post(path, responsesBody(stream = true))),
            )
        }
    }

    @Test
    fun `a streamed responses step with thinking and tool calls but no text starts the calls at output_index 1`() {
        val step = Step(thinking = "Need the time.", toolCalls = listOf(ToolCall(name = "get_time")))
        withServer(engineWith(step)) { client, _ ->
            val frames = frames(client.post(path, responsesBody(stream = true)))
            val indices =
                frames
                    .filter { it.first == "response.output_item.added" || it.first == "response.function_call_arguments.done" }
                    .map { (name, data) -> name to json(data).getValue("output_index").jsonPrimitive.int }
            assertEquals(
                listOf(
                    "response.output_item.added" to 0,
                    "response.output_item.added" to 1,
                    "response.function_call_arguments.done" to 1,
                ),
                indices,
            )
            val output =
                json(frames.last().second)
                    .getValue("response")
                    .jsonObject
                    .getValue("output")
                    .jsonArray
            assertEquals(
                listOf("reasoning", "function_call"),
                output.map {
                    it.jsonObject
                        .getValue("type")
                        .jsonPrimitive.content
                },
            )
        }
    }

    @Test
    fun `max_tokens ends a responses stream with response incomplete`() {
        withServer(engineWith(Step(text = "cut off", stopReason = "max_tokens"))) { client, _ ->
            val frames = frames(client.post(path, responsesBody(stream = true)))
            val itemDone = frames.single { it.first == "response.output_item.done" }
            assertEquals(
                "incomplete",
                json(itemDone.second)
                    .getValue("item")
                    .jsonObject
                    .getValue("status")
                    .jsonPrimitive.content,
            )
            assertEquals(
                event("response.incomplete", """"response":${response("incomplete", message("cut off", "incomplete"))}""", 8),
                frames.last(),
            )
        }
    }

    @Test
    fun `a streamed responses step sends each tool call as a function_call item with argument deltas after the message`() {
        val step =
            Step(
                text = "Checking.",
                toolCalls =
                    listOf(
                        ToolCall(name = "get_weather", arguments = Json.parseToJsonElement("""{"city":"Paris"}""").jsonObject),
                        ToolCall(name = "get_time", id = "call_custom"),
                    ),
                streamChunkChars = 8,
            )
        withServer(engineWith(step)) { client, engine ->
            val frames = frames(client.post(path, responsesBody(stream = true)))
            val calls =
                frames
                    .filter { it.first == "response.output_item.added" }
                    .map { json(it.second) }
                    .filter {
                        it
                            .getValue("item")
                            .jsonObject
                            .getValue("type")
                            .jsonPrimitive.content == "function_call"
                    }.map {
                        val item = it.getValue("item").jsonObject
                        listOf(
                            it.getValue("output_index").jsonPrimitive.content,
                            item.getValue("id").jsonPrimitive.content,
                            item.getValue("call_id").jsonPrimitive.content,
                            item.getValue("arguments").jsonPrimitive.content,
                        )
                    }
            assertEquals(listOf(listOf("1", "fc_fake_1_1", "call_fake_1_1", ""), listOf("2", "fc_fake_1_2", "call_custom", "")), calls)
            val argumentDeltas =
                frames.filter { it.first == "response.function_call_arguments.delta" }.map { json(it.second) }.map {
                    it.getValue("item_id").jsonPrimitive.content to it.getValue("delta").jsonPrimitive.content
                }
            assertEquals(listOf("fc_fake_1_1" to """{"city":""", "fc_fake_1_1" to """"Paris"}""", "fc_fake_1_2" to "{}"), argumentDeltas)
            assertEquals("response.completed", frames.last().first)
            val record = engine.calls().single()
            assertEquals("tool-calls", record.outcome)
            assertEquals(
                listOf(ToolCallRecord("call_fake_1_1", "get_weather"), ToolCallRecord("call_custom", "get_time")),
                record.toolCalls,
            )
        }
    }

    @Test
    fun `a streamed responses tool call without text starts at output_index 0 and sends rawArguments verbatim`() {
        val raw = """{"city": Paris}"""
        val step = Step(toolCalls = listOf(ToolCall(name = "get_weather", rawArguments = raw)), streamChunkChars = 8)
        withServer(engineWith(step)) { client, _ ->
            val frames = frames(client.post(path, responsesBody(stream = true)))
            val item =
                """{"id":"fc_fake_1_1","type":"function_call","status":"%s","call_id":"call_fake_1_1",""" +
                    """"name":"get_weather","arguments":%s}"""
            val ids = """"item_id":"fc_fake_1_1","output_index":0"""
            assertEquals(
                listOf(
                    event("response.output_item.added", """"output_index":0,"item":${item.format("in_progress", "\"\"")}""", 2),
                    event("response.function_call_arguments.delta", """$ids,"delta":${JsonPrimitive("""{"city":""")}""", 3),
                    event("response.function_call_arguments.delta", """$ids,"delta":" Paris}"""", 4),
                    event("response.function_call_arguments.done", """$ids,"arguments":${JsonPrimitive(raw)}""", 5),
                    event("response.output_item.done", """"output_index":0,"item":${item.format("completed", JsonPrimitive(raw))}""", 6),
                ),
                frames.drop(2).dropLast(1),
            )
        }
    }

    @Test
    fun `a responses stream error after chunks ends with the error event and nothing after it`() {
        val body = """{"error":{"message":"server error","type":"server_error","param":null,"code":null}}"""
        val scripted = Step(error = ErrorStep(status = 500, body = body), streamErrorAfterChunks = 2, streamChunkChars = 8)
        val generic = """{"type":"error","code":"server_error","message":"scripted stream error","param":null,"sequence_number":6}"""
        val blank = Step(error = ErrorStep(status = 503), streamErrorAfterChunks = 2, streamChunkChars = 8)
        listOf(scripted to body, blank to generic).forEach { (step, last) ->
            withServer(engineWith(step)) { client, _ ->
                val resp = client.post(path, responsesBody(stream = true))
                assertEquals(200, resp.statusCode())
                assertEquals(opening() + listOf(delta("Thinking", 4), delta(" this th", 5), "error" to last), frames(resp))
            }
        }
    }

    @Test
    fun `a responses error step without streamErrorAfterChunks is a plain HTTP error even when streamed`() {
        val body = """{"error":{"message":"slow down","type":"requests","param":null,"code":"rate_limit_exceeded"}}"""
        withServer(engineWith(Step(error = ErrorStep(status = 429, body = body, headers = mapOf("retry-after" to "1"))))) { client, _ ->
            val resp = client.post(path, responsesBody(stream = true))
            assertEquals(429, resp.statusCode())
            assertEquals(body, resp.body())
            assertEquals("1", resp.headers().firstValue("retry-after").orElse(""))
            assertTrue(contentType(resp).startsWith("application/json"))
        }
    }

    @Test
    fun `a streamed responses call after the last step is the JSON 400 with no event stream`() {
        withServer(engineWith(Step(text = "only"))) { client, _ ->
            assertEquals(200, client.post(path, responsesBody()).statusCode())
            val resp = client.post(path, responsesBody("Again", stream = true))
            assertEquals(400, resp.statusCode())
            assertTrue(contentType(resp).startsWith("application/json"))
            val body = json(resp.body())
            assertEquals("no more steps", body.getValue("fake_llm_error").jsonPrimitive.content)
            assertEquals("openai-responses", body.getValue("vendor").jsonPrimitive.content)
            assertFalse(resp.body().contains("event:"))
        }
    }

    @Test
    fun `lenient mode streams the canned reply for a streamed responses request`() {
        withServer(ScenarioEngine(lenient = true)) { client, engine ->
            val frames = frames(client.post(path, responsesBody(stream = true)))
            assertEquals(CANNED_ASSISTANT_TEXT, deltas(frames, "response.output_text.delta").joinToString(""))
            assertEquals("default", engine.calls().single().outcome)
        }
    }

    @Test
    fun `streamInterChunkDelayMs waits before each responses text and argument delta`() {
        val step = Step(text = "abcdef", toolCalls = listOf(ToolCall(name = "f")), streamChunkChars = 3, streamInterChunkDelayMs = 100)
        withServer(engineWith(step)) { client, _ ->
            val started = System.nanoTime()
            val frames = frames(client.post(path, responsesBody(stream = true)))
            val elapsedMs = (System.nanoTime() - started) / 1_000_000
            assertEquals(listOf("abc", "def"), deltas(frames, "response.output_text.delta"))
            assertEquals(listOf("{}"), deltas(frames, "response.function_call_arguments.delta"))
            assertTrue(elapsedMs >= 300, "took $elapsedMs ms")
        }
    }

    @Test
    fun `a streamed responses call is numbered and logged like a buffered one`() {
        withServer(engineWith(Step(text = "one"), Step(text = "two"))) { client, engine ->
            val frames = frames(client.post(path, responsesBody(stream = true)))
            val responseIds =
                frames.mapNotNull {
                    json(it.second)["response"]
                        ?.jsonObject
                        ?.getValue("id")
                        ?.jsonPrimitive
                        ?.content
                }
            assertEquals(listOf("resp_fake_1", "resp_fake_1", "resp_fake_1"), responseIds)
            assertEquals((0 until frames.size).toList(), frames.map { json(it.second).getValue("sequence_number").jsonPrimitive.int })
            assertEquals(200, client.post(path, responsesBody()).statusCode())
            assertEquals(listOf(1, 2), engine.calls().map { it.step })
            assertEquals(listOf("text", "text"), engine.calls().map { it.outcome })
        }
    }

    @Test
    fun `previous_response_id is accepted and not resolved - each call takes the next step`() {
        withServer(engineWith(Step(text = "Sunny."), Step(text = "Rain."), Step(text = "Snow."))) { client, engine ->
            client.post(path, responsesBody())
            val second =
                client.post(path, """{"model":"example-gpt","input":"And tomorrow?","previous_response_id":"resp_fake_1","store":true}""")
            val third =
                client.post(
                    path,
                    """{"model":"example-gpt","input":"Hello","previous_response_id":"resp_never_issued","conversation":"conv_1"}""",
                )
            listOf(second to ("resp_fake_2" to "Rain."), third to ("resp_fake_3" to "Snow.")).forEach { (resp, expected) ->
                val body = json(resp.body())
                val text =
                    body
                        .getValue("output")
                        .jsonArray[0]
                        .jsonObject
                        .getValue("content")
                        .jsonArray[0]
                        .jsonObject
                        .getValue("text")
                        .jsonPrimitive.content
                assertEquals(expected, body.getValue("id").jsonPrimitive.content to text)
            }
            assertEquals(listOf(1, 2, 3), engine.calls().map { it.step })
            assertEquals(listOf("Hello\n", "And tomorrow?\n", "Hello\n"), engine.calls().map { it.promptSample })
        }
    }
}
