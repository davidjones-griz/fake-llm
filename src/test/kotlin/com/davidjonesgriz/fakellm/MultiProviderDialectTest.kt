package com.davidjonesgriz.fakellm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MultiProviderDialectTest {
    private val helloText = "Hello. I can help with the weather, a simple recipe, or a short how-to."

    private fun openAiBody(prompt: String): String =
        """{"model":"example-gpt","messages":[{"role":"system","content":${JsonPrimitive(prompt)}},""" +
            """{"role":"user","content":"hello"}]}"""

    private fun openAiText(body: String): String {
        val choice =
            Json
                .parseToJsonElement(body)
                .jsonObject
                .getValue("choices")
                .jsonArray
                .single()
                .jsonObject
        return choice
            .getValue("message")
            .jsonObject
            .getValue("content")
            .jsonPrimitive.content
    }

    @Test
    fun `an openai chat completion renders the loaded scenario text`() {
        withServer { client, _ ->
            assertEquals(200, client.post("/control/scenario", """{"name":"hello"}""").statusCode())
            val resp = client.post("/openai/v1/chat/completions", openAiBody("a short how-to"))
            assertEquals(200, resp.statusCode())
            val root = Json.parseToJsonElement(resp.body()).jsonObject
            assertEquals("chat.completion", root.getValue("object").jsonPrimitive.content)
            val choice =
                root
                    .getValue("choices")
                    .jsonArray
                    .single()
                    .jsonObject
            assertEquals("stop", choice.getValue("finish_reason").jsonPrimitive.content)
            assertEquals(
                "assistant",
                choice
                    .getValue("message")
                    .jsonObject
                    .getValue("role")
                    .jsonPrimitive.content,
            )
            assertEquals(helloText, openAiText(resp.body()))

            val call =
                Json
                    .parseToJsonElement(client.get("/control/calls").body())
                    .jsonArray
                    .single()
                    .jsonObject
            assertEquals("openai", call.getValue("vendor").jsonPrimitive.content)
            assertEquals("example-gpt", call.getValue("model").jsonPrimitive.content)
            assertEquals("blocks", call.getValue("systemShape").jsonPrimitive.content)
            assertEquals(
                2,
                call
                    .getValue("messageCount")
                    .jsonPrimitive.content
                    .toInt(),
            )
            assertEquals("1", call.getValue("step").jsonPrimitive.content)
        }
    }

    @Test
    fun `lenient mode returns the canned reply for an openai request with no scenario loaded`() {
        withServer(engine = ScenarioEngine(lenient = true)) { client, _ ->
            val resp = client.post("/openai/v1/chat/completions", openAiBody("an ordinary question"))
            assertEquals(200, resp.statusCode())
            assertEquals(CANNED_ASSISTANT_TEXT, openAiText(resp.body()))
            val root = Json.parseToJsonElement(resp.body()).jsonObject
            val choice =
                root
                    .getValue("choices")
                    .jsonArray
                    .single()
                    .jsonObject
            assertEquals("stop", choice.getValue("finish_reason").jsonPrimitive.content)

            val call =
                Json
                    .parseToJsonElement(client.get("/control/calls").body())
                    .jsonArray
                    .single()
                    .jsonObject
            assertEquals("openai", call.getValue("vendor").jsonPrimitive.content)
            assertEquals("default", call.getValue("outcome").jsonPrimitive.content)
            assertEquals(JsonNull, call.getValue("step"))
            assertTrue(
                call
                    .getValue("promptSample")
                    .jsonPrimitive.content
                    .contains("ordinary question"),
            )
        }
    }

    private fun openAiStreamBody(
        prompt: String = "Hello",
        includeUsage: Boolean = false,
    ): String =
        """{"model":"example-gpt","messages":[{"role":"user","content":${JsonPrimitive(prompt)}}],"stream":true""" +
            (if (includeUsage) ""","stream_options":{"include_usage":true}}""" else "}")

    /** One expected chunk payload, written out independently of the server's builders. */
    private fun chunk(
        delta: String,
        finishReason: String? = null,
        usage: Boolean = false,
        seq: Int = 1,
    ): String =
        """{"id":"fake-openai-$seq","object":"chat.completion.chunk","model":"fake-openai",""" +
            """"choices":[{"index":0,"delta":$delta,"finish_reason":${finishReason?.let { "\"$it\"" } ?: "null"}}]""" +
            (if (usage) ""","usage":null}""" else "}")

    private fun content(text: String) = """{"content":${JsonPrimitive(text)}}"""

    private val role = """{"role":"assistant","content":""}"""

    private fun engineWith(vararg steps: Step) =
        ScenarioEngine().also {
            it.loadInline(Scenario(name = "openai-stream", steps = steps.toList()))
        }

    private fun contentPieces(frames: List<String>): List<String> =
        frames
            .filter { it.startsWith("{") }
            .map { Json.parseToJsonElement(it).jsonObject }
            .mapNotNull { root ->
                root
                    .getValue("choices")
                    .jsonArray
                    .firstOrNull()
                    ?.jsonObject
                    ?.getValue("delta")
                    ?.jsonObject
                    ?.get("content")
                    ?.jsonPrimitive
                    ?.content
            }.filter { it.isNotEmpty() }

    @Test
    fun `a streamed openai text step is chunk frames, a finish chunk and done`() {
        withServer(engineWith(Step(text = "Mild and dry tomorrow.", streamChunkChars = 8))) { client, _ ->
            val resp = client.post("/openai/v1/chat/completions", openAiStreamBody())
            assertEquals(200, resp.statusCode(), resp.body())
            assertTrue(
                resp
                    .headers()
                    .firstValue("content-type")
                    .orElse("")
                    .startsWith("text/event-stream"),
            )
            assertEquals(
                listOf(
                    chunk(role),
                    chunk(content("Mild and")),
                    chunk(content(" dry tom")),
                    chunk(content("orrow.")),
                    chunk("{}", "stop"),
                    "[DONE]",
                ),
                dataFrames(resp.body()),
            )
            assertTrue(resp.body().endsWith("data: [DONE]\n\n"))
        }
    }

    @Test
    fun `include_usage adds a usage chunk before done and a null usage on every other chunk`() {
        withServer(engineWith(Step(text = "Hi."))) { client, _ ->
            val resp = client.post("/openai/v1/chat/completions", openAiStreamBody(includeUsage = true))
            assertEquals(
                listOf(
                    chunk(role, usage = true),
                    chunk(content("Hi."), usage = true),
                    chunk("{}", "stop", usage = true),
                    """{"id":"fake-openai-1","object":"chat.completion.chunk","model":"fake-openai","choices":[],""" +
                        """"usage":{"prompt_tokens":100,"completion_tokens":50,"total_tokens":150}}""",
                    "[DONE]",
                ),
                dataFrames(resp.body()),
            )
        }
    }

    @Test
    fun `max_tokens finishes an openai stream with length`() {
        withServer(engineWith(Step(text = "cut off", stopReason = "max_tokens"))) { client, _ ->
            val frames = dataFrames(client.post("/openai/v1/chat/completions", openAiStreamBody()).body())
            assertEquals(listOf(chunk("{}", "length"), "[DONE]"), frames.takeLast(2))
        }
    }

    @Test
    fun `an openai stream error after chunks has no finish chunk, usage chunk or done`() {
        val body = """{"error":{"message":"server error","type":"server_error","param":null,"code":null}}"""
        withServer(
            engineWith(
                Step(error = ErrorStep(status = 500, body = body), streamErrorAfterChunks = 2, streamChunkChars = 8),
                Step(error = ErrorStep(status = 500), streamErrorAfterChunks = 2, streamChunkChars = 8),
            ),
        ) { client, _ ->
            val scripted = client.post("/openai/v1/chat/completions", openAiStreamBody(includeUsage = true))
            assertEquals(200, scripted.statusCode())
            assertEquals(
                listOf(
                    chunk(role, usage = true),
                    chunk(content("Thinking"), usage = true),
                    chunk(content(" this th"), usage = true),
                    body,
                ),
                dataFrames(scripted.body()),
            )
            val blank = dataFrames(client.post("/openai/v1/chat/completions", openAiStreamBody()).body())
            assertEquals(GENERIC_OPENAI_STREAM_ERROR_BODY, blank.last())
            assertEquals(4, blank.size)
        }
    }

    @Test
    fun `an openai error step without streamErrorAfterChunks is a plain HTTP error even when streamed`() {
        val body = """{"error":{"message":"slow down","type":"requests","param":null,"code":"rate_limit_exceeded"}}"""
        withServer(engineWith(Step(error = ErrorStep(status = 429, body = body, headers = mapOf("retry-after" to "1"))))) { client, _ ->
            val resp = client.post("/openai/v1/chat/completions", openAiStreamBody())
            assertEquals(429, resp.statusCode())
            assertEquals(body, resp.body())
            assertEquals("1", resp.headers().firstValue("retry-after").orElse(""))
            assertTrue(
                resp
                    .headers()
                    .firstValue("content-type")
                    .orElse("")
                    .startsWith("application/json"),
            )
        }
    }

    @Test
    fun `a streamed openai call after the last step is the JSON 400 with no event stream`() {
        withServer(engineWith(Step(text = "only"))) { client, _ ->
            assertEquals(200, client.post("/openai/v1/chat/completions", openAiBody("x")).statusCode())
            val resp = client.post("/openai/v1/chat/completions", openAiStreamBody("Again"))
            assertEquals(400, resp.statusCode())
            assertTrue(
                resp
                    .headers()
                    .firstValue("content-type")
                    .orElse("")
                    .startsWith("application/json"),
            )
            assertEquals(
                "no more steps",
                Json
                    .parseToJsonElement(resp.body())
                    .jsonObject
                    .getValue("fake_llm_error")
                    .jsonPrimitive.content,
            )
            assertTrue(!resp.body().contains("data:"))
        }
    }

    @Test
    fun `lenient mode streams the canned reply for a streamed openai request`() {
        withServer(ScenarioEngine(lenient = true)) { client, engine ->
            val frames = dataFrames(client.post("/openai/v1/chat/completions", openAiStreamBody()).body())
            assertEquals(CANNED_ASSISTANT_TEXT, contentPieces(frames).joinToString(""))
            assertEquals("[DONE]", frames.last())
            assertEquals("default", engine.calls().single().outcome)
        }
    }

    @Test
    fun `streamInterChunkDelayMs waits before each openai delta`() {
        withServer(engineWith(Step(text = "abcdefghi", streamChunkChars = 3, streamInterChunkDelayMs = 100))) { client, _ ->
            val started = System.nanoTime()
            val frames = dataFrames(client.post("/openai/v1/chat/completions", openAiStreamBody()).body())
            val elapsedMs = (System.nanoTime() - started) / 1_000_000
            assertEquals(listOf("abc", "def", "ghi"), contentPieces(frames))
            assertTrue(elapsedMs >= 300, "took $elapsedMs ms")
        }
    }

    @Test
    fun `a streamed openai call is numbered and logged like a buffered one`() {
        withServer(engineWith(Step(text = "one"), Step(text = "two"))) { client, engine ->
            val streamed = dataFrames(client.post("/openai/v1/chat/completions", openAiStreamBody()).body())
            assertTrue(streamed.first().startsWith("""{"id":"fake-openai-1","""))
            assertEquals(200, client.post("/openai/v1/chat/completions", openAiBody("x")).statusCode())
            val calls = engine.calls()
            assertEquals(listOf(1, 2), calls.map { it.step })
            assertEquals(listOf("text", "text"), calls.map { it.outcome })
        }
    }

    private fun toolArgs(json: String) = Json.parseToJsonElement(json).jsonObject

    private fun blockStart(
        index: Int,
        block: String,
    ) = "content_block_start" to """{"type":"content_block_start","index":$index,"content_block":$block}"""

    private fun blockDelta(
        index: Int,
        delta: String,
    ) = "content_block_delta" to """{"type":"content_block_delta","index":$index,"delta":$delta}"""

    private fun blockStop(index: Int) = "content_block_stop" to """{"type":"content_block_stop","index":$index}"""

    private fun inputJson(piece: String) = """{"type":"input_json_delta","partial_json":${JsonPrimitive(piece)}}"""

    @Test
    fun `a streamed anthropic tool call is a tool_use block with input_json_delta pieces`() {
        val step =
            Step(
                text = "Checking.",
                thinking = "Look.",
                toolCalls =
                    listOf(
                        ToolCall(name = "get_weather", arguments = toolArgs("""{"city":"Paris"}""")),
                        ToolCall(name = "get_time", id = "toolu_custom"),
                    ),
                streamChunkChars = 8,
            )
        withServer(engineWith(step)) { client, _ ->
            val frames = namedFrames(client.post("/anthropic/v1/messages", anthropicBody("Hello", stream = true)).body())
            assertEquals("message_start", frames.first().first)
            assertEquals(
                listOf(
                    blockStart(0, """{"type":"thinking","thinking":""}"""),
                    blockDelta(0, """{"type":"thinking_delta","thinking":"Look."}"""),
                    blockDelta(0, """{"type":"signature_delta","signature":"fake-thinking-signature"}"""),
                    blockStop(0),
                    blockStart(1, """{"type":"text","text":""}"""),
                    blockDelta(1, """{"type":"text_delta","text":"Checking"}"""),
                    blockDelta(1, """{"type":"text_delta","text":"."}"""),
                    blockStop(1),
                    blockStart(2, """{"type":"tool_use","id":"toolu_fake_1_1","name":"get_weather","input":{}}"""),
                    blockDelta(2, inputJson("""{"city":""")),
                    blockDelta(2, inputJson(""""Paris"}""")),
                    blockStop(2),
                    blockStart(3, """{"type":"tool_use","id":"toolu_custom","name":"get_time","input":{}}"""),
                    blockDelta(3, inputJson("{}")),
                    blockStop(3),
                    "message_delta" to
                        """{"type":"message_delta","delta":{"stop_reason":"tool_use","stop_sequence":null},"usage":{"output_tokens":50}}""",
                    "message_stop" to """{"type":"message_stop"}""",
                ),
                frames.drop(1),
            )
        }
    }

    @Test
    fun `a streamed anthropic tool call sends rawArguments verbatim`() {
        val step = Step(toolCalls = listOf(ToolCall(name = "get_weather", rawArguments = """{"city": Paris}""")), streamChunkChars = 8)
        withServer(engineWith(step)) { client, _ ->
            val frames = namedFrames(client.post("/anthropic/v1/messages", anthropicBody("Hello", stream = true)).body())
            assertEquals(
                listOf(
                    blockStart(0, """{"type":"tool_use","id":"toolu_fake_1_1","name":"get_weather","input":{}}"""),
                    blockDelta(0, inputJson("""{"city":""")),
                    blockDelta(0, inputJson(" Paris}")),
                    blockStop(0),
                ),
                frames.drop(1).dropLast(2),
            )
        }
    }

    @Test
    fun `a streamed openai tool call is a header chunk and argument chunks per call, then finish_reason tool_calls`() {
        val step =
            Step(
                toolCalls =
                    listOf(
                        ToolCall(name = "get_weather", arguments = toolArgs("""{"city":"Paris"}""")),
                        ToolCall(name = "get_time", rawArguments = """{"tz": Europe/Paris}""", id = "call_custom"),
                    ),
                streamChunkChars = 8,
            )

        fun header(
            index: Int,
            id: String,
            name: String,
        ) = """{"tool_calls":[{"index":$index,"id":"$id","type":"function","function":{"name":"$name","arguments":""}}]}"""

        fun arguments(
            index: Int,
            piece: String,
        ) = """{"tool_calls":[{"index":$index,"function":{"arguments":${JsonPrimitive(piece)}}}]}"""
        withServer(engineWith(step)) { client, _ ->
            val resp = client.post("/openai/v1/chat/completions", openAiStreamBody(includeUsage = true))
            assertEquals(
                listOf(
                    chunk("""{"role":"assistant","content":null}""", usage = true),
                    chunk(header(0, "call_fake_1_1", "get_weather"), usage = true),
                    chunk(arguments(0, """{"city":"""), usage = true),
                    chunk(arguments(0, """"Paris"}"""), usage = true),
                    chunk(header(1, "call_custom", "get_time"), usage = true),
                    chunk(arguments(1, """{"tz": E"""), usage = true),
                    chunk(arguments(1, "urope/Pa"), usage = true),
                    chunk(arguments(1, "ris}"), usage = true),
                    chunk("{}", "tool_calls", usage = true),
                    """{"id":"fake-openai-1","object":"chat.completion.chunk","model":"fake-openai","choices":[],""" +
                        """"usage":{"prompt_tokens":100,"completion_tokens":50,"total_tokens":150}}""",
                    "[DONE]",
                ),
                dataFrames(resp.body()),
            )
        }
    }

    @Test
    fun `a tool loop runs over each format`() {
        val steps =
            arrayOf(
                Step(toolCalls = listOf(ToolCall(name = "get_weather", arguments = toolArgs("""{"city":"Paris"}""")))),
                Step(text = "It is 18 degrees in Paris."),
            )
        val anthropicResult =
            """{"model":"example-model","max_tokens":64,"messages":[{"role":"user","content":"Hello"},""" +
                """{"role":"assistant","content":[""" +
                """{"type":"tool_use","id":"toolu_fake_1_1","name":"get_weather","input":{"city":"Paris"}}]},""" +
                """{"role":"user","content":[{"type":"tool_result","tool_use_id":"toolu_fake_1_1","content":"18 degrees"}]}]}"""
        val openAiResult =
            """{"model":"example-gpt","messages":[{"role":"user","content":"Hello"},""" +
                """{"role":"assistant","content":null,"tool_calls":[{"id":"call_fake_1_1","type":"function",""" +
                """"function":{"name":"get_weather","arguments":"{\"city\":\"Paris\"}"}}]},""" +
                """{"role":"tool","tool_call_id":"call_fake_1_1","content":"18 degrees"}]}"""
        val geminiResult =
            """{"contents":[{"role":"user","parts":[{"text":"Hello"}]},""" +
                """{"role":"model","parts":[{"functionCall":{"name":"get_weather","args":{"city":"Paris"}}}]},""" +
                """{"role":"user","parts":[{"functionResponse":{"name":"get_weather","response":{"temperature":18}}}]}]}"""
        val geminiText = { body: String ->
            Json
                .parseToJsonElement(body)
                .jsonObject
                .getValue("candidates")
                .jsonArray[0]
                .jsonObject
                .getValue("content")
                .jsonObject
                .getValue("parts")
                .jsonArray[0]
                .jsonObject
                .getValue("text")
                .jsonPrimitive.content
        }
        val loops =
            listOf(
                ToolLoop(
                    "/anthropic/v1/messages",
                    anthropicBody("Hello"),
                    anthropicResult,
                    ::anthropicText,
                    ToolResultRecord("toolu_fake_1_1", "get_weather", JsonPrimitive("18 degrees"), isError = false),
                ),
                ToolLoop(
                    "/openai/v1/chat/completions",
                    """{"model":"example-gpt","messages":[{"role":"user","content":"Hello"}]}""",
                    openAiResult,
                    ::openAiText,
                    ToolResultRecord("call_fake_1_1", "get_weather", JsonPrimitive("18 degrees"), isError = false),
                ),
                ToolLoop(
                    "/gemini/v1beta/models/example-gemini:generateContent",
                    """{"contents":[{"parts":[{"text":"Hello"}]}]}""",
                    geminiResult,
                    geminiText,
                    ToolResultRecord(null, "get_weather", toolArgs("""{"temperature":18}"""), isError = false),
                ),
            )
        loops.forEach { loop ->
            withServer(engineWith(*steps)) { client, engine ->
                assertEquals(200, client.post(loop.path, loop.first).statusCode(), loop.path)
                val reply = client.post(loop.path, loop.second)
                assertEquals("It is 18 degrees in Paris.", loop.text(reply.body()), loop.path)
                assertEquals(listOf("tool-calls", "text"), engine.calls().map { it.outcome }, loop.path)
                assertEquals(listOf(loop.result), engine.calls()[1].toolResults, loop.path)
            }
        }
    }

    private class ToolLoop(
        val path: String,
        val first: String,
        val second: String,
        val text: (String) -> String,
        val result: ToolResultRecord,
    )

    private val geminiStreamPath = "/gemini/v1beta/models/example-gemini:streamGenerateContent?alt=sse"

    private fun geminiBody(prompt: String = "Hello") = """{"contents":[{"role":"user","parts":[{"text":${JsonPrimitive(prompt)}}]}]}"""

    /** One expected Gemini chunk payload, written out independently of the server's builders. */
    private fun geminiChunk(
        part: String,
        finishReason: String? = null,
        seq: Int = 1,
    ): String {
        val finish = finishReason?.let { ""","finishReason":"$it"""" } ?: ""
        val usage =
            if (finishReason != null) {
                """"usageMetadata":{"promptTokenCount":100,"candidatesTokenCount":50,"totalTokenCount":150},"""
            } else {
                ""
            }
        return """{"candidates":[{"content":{"role":"model","parts":[$part]}$finish}],$usage"fake_llm_seq":$seq}"""
    }

    private fun geminiText(text: String) = """{"text":${JsonPrimitive(text)}}"""

    private fun geminiTexts(frames: List<String>): List<String> =
        frames.mapNotNull { frame ->
            Json
                .parseToJsonElement(frame)
                .jsonObject["candidates"]
                ?.jsonArray
                ?.single()
                ?.jsonObject
                ?.getValue("content")
                ?.jsonObject
                ?.getValue("parts")
                ?.jsonArray
                ?.single()
                ?.jsonObject
                ?.get("text")
                ?.jsonPrimitive
                ?.content
        }

    private fun contentType(resp: java.net.http.HttpResponse<String>) = resp.headers().firstValue("content-type").orElse("")

    @Test
    fun `a streamed gemini text step is one chunk per piece, the last with finishReason and usageMetadata`() {
        withServer(engineWith(Step(text = "Mild and dry tomorrow.", streamChunkChars = 8))) { client, _ ->
            val resp = client.post(geminiStreamPath, geminiBody())
            assertEquals(200, resp.statusCode())
            assertTrue(contentType(resp).startsWith("text/event-stream"))
            assertEquals(
                listOf(geminiChunk(geminiText("Mild and")), geminiChunk(geminiText(" dry tom")), geminiChunk(geminiText("orrow."), "STOP")),
                dataFrames(resp.body()),
            )
            assertFalse(resp.body().contains("event:"))
            assertTrue(resp.body().endsWith("}\n\n"))
        }
    }

    @Test
    fun `max_tokens finishes a gemini stream with MAX_TOKENS`() {
        withServer(engineWith(Step(text = "cut off", stopReason = "max_tokens"))) { client, _ ->
            assertEquals(
                listOf(geminiChunk(geminiText("cut off"), "MAX_TOKENS")),
                dataFrames(client.post(geminiStreamPath, geminiBody()).body()),
            )
        }
    }

    @Test
    fun `an empty gemini text streams one last chunk with an empty text part`() {
        withServer(engineWith(Step(text = ""))) { client, _ ->
            assertEquals(listOf(geminiChunk(geminiText(""), "STOP")), dataFrames(client.post(geminiStreamPath, geminiBody()).body()))
        }
    }

    @Test
    fun `a streamed gemini step sends each tool call as a whole functionCall chunk after the text`() {
        val step =
            Step(
                text = "Checking.",
                toolCalls =
                    listOf(
                        ToolCall(name = "get_weather", arguments = Json.parseToJsonElement("""{"city":"Paris"}""").jsonObject),
                        ToolCall(name = "get_time", id = "t2"),
                    ),
                streamChunkChars = 8,
            )
        withServer(engineWith(step)) { client, engine ->
            assertEquals(
                listOf(
                    geminiChunk(geminiText("Checking")),
                    geminiChunk(geminiText(".")),
                    geminiChunk("""{"functionCall":{"name":"get_weather","args":{"city":"Paris"}}}"""),
                    geminiChunk("""{"functionCall":{"id":"t2","name":"get_time","args":{}}}""", "STOP"),
                ),
                dataFrames(client.post(geminiStreamPath, geminiBody()).body()),
            )
            val record = engine.calls().single()
            assertEquals("tool-calls", record.outcome)
            assertEquals(listOf(ToolCallRecord(null, "get_weather"), ToolCallRecord("t2", "get_time")), record.toolCalls)
        }
    }

    @Test
    fun `a gemini stream error after chunks ends with the error body and no finishReason`() {
        val body = """{"error":{"code":500,"message":"server error","status":"INTERNAL"}}"""
        val scripted = Step(error = ErrorStep(status = 500, body = body), streamErrorAfterChunks = 2, streamChunkChars = 8)
        val blank = Step(error = ErrorStep(status = 503), streamErrorAfterChunks = 2, streamChunkChars = 8)
        listOf(scripted to body, blank to GENERIC_GEMINI_STREAM_ERROR_BODY).forEach { (step, last) ->
            withServer(engineWith(step)) { client, _ ->
                val resp = client.post(geminiStreamPath, geminiBody())
                assertEquals(200, resp.statusCode())
                assertEquals(
                    listOf(geminiChunk(geminiText("Thinking")), geminiChunk(geminiText(" this th")), last),
                    dataFrames(resp.body()),
                )
            }
        }
    }

    @Test
    fun `a gemini error step without streamErrorAfterChunks is a plain HTTP error even when streamed`() {
        val body = """{"error":{"code":429,"message":"slow down","status":"RESOURCE_EXHAUSTED"}}"""
        withServer(engineWith(Step(error = ErrorStep(status = 429, body = body, headers = mapOf("retry-after" to "1"))))) { client, _ ->
            val resp = client.post(geminiStreamPath, geminiBody())
            assertEquals(429, resp.statusCode())
            assertEquals(body, resp.body())
            assertEquals("1", resp.headers().firstValue("retry-after").orElse(""))
            assertTrue(contentType(resp).startsWith("application/json"))
        }
    }

    @Test
    fun `a streamed gemini call after the last step is the JSON 400 with no event stream`() {
        withServer(engineWith(Step(text = "only"))) { client, _ ->
            assertEquals(200, client.post("/gemini/v1beta/models/example-gemini:generateContent", geminiBody()).statusCode())
            val resp = client.post(geminiStreamPath, geminiBody("Again"))
            assertEquals(400, resp.statusCode())
            assertTrue(contentType(resp).startsWith("application/json"))
            assertEquals(
                "no more steps",
                Json
                    .parseToJsonElement(resp.body())
                    .jsonObject
                    .getValue("fake_llm_error")
                    .jsonPrimitive.content,
            )
            assertFalse(resp.body().contains("data:"))
        }
    }

    @Test
    fun `lenient mode streams the canned reply for a streamed gemini request`() {
        withServer(ScenarioEngine(lenient = true)) { client, engine ->
            val frames = dataFrames(client.post(geminiStreamPath, geminiBody()).body())
            assertEquals(CANNED_ASSISTANT_TEXT, geminiTexts(frames).joinToString(""))
            assertEquals("default", engine.calls().single().outcome)
        }
    }

    @Test
    fun `streamInterChunkDelayMs waits before each gemini text chunk`() {
        withServer(engineWith(Step(text = "abcdefghi", streamChunkChars = 3, streamInterChunkDelayMs = 100))) { client, _ ->
            val started = System.nanoTime()
            val frames = dataFrames(client.post(geminiStreamPath, geminiBody()).body())
            val elapsedMs = (System.nanoTime() - started) / 1_000_000
            assertEquals(listOf("abc", "def", "ghi"), geminiTexts(frames))
            assertTrue(elapsedMs >= 300, "took $elapsedMs ms")
        }
    }

    @Test
    fun `a streamed gemini call is numbered and logged like a buffered one`() {
        withServer(engineWith(Step(text = "one two three", streamChunkChars = 4), Step(text = "four"))) { client, engine ->
            val streamed = dataFrames(client.post(geminiStreamPath, geminiBody()).body())
            assertEquals(4, streamed.size)
            assertTrue(
                streamed.all {
                    Json
                        .parseToJsonElement(it)
                        .jsonObject
                        .getValue("fake_llm_seq")
                        .jsonPrimitive.content == "1"
                },
            )
            assertEquals(200, client.post("/gemini/v1beta/models/example-gemini:generateContent", geminiBody()).statusCode())
            val calls = engine.calls()
            assertEquals(listOf(1, 2), calls.map { it.step })
            assertEquals(listOf("text", "text"), calls.map { it.outcome })
        }
    }

    @Test
    fun `streamGenerateContent without alt=sse is the exception 400 and not a call`() {
        withServer(engineWith(Step(text = "Hi."))) { client, engine ->
            val resp = client.post(geminiStreamPath.substringBefore('?'), geminiBody())
            assertEquals(400, resp.statusCode())
            assertEquals(GEMINI_STREAM_NEEDS_ALT_SSE, errorDetail(resp.body()))
            assertFalse(resp.body().contains("data:"))
            assertEquals(emptyList(), engine.calls())
            assertEquals(listOf(geminiChunk(geminiText("Hi."), "STOP")), dataFrames(client.post(geminiStreamPath, geminiBody()).body()))
            assertEquals(listOf(1), engine.calls().map { it.step })
        }
    }

    @Test
    fun `a streamed gemini request whose next step has rawArguments is the exception 400 and not a call`() {
        withServer(engineWith(Step(toolCalls = listOf(ToolCall(name = "get_weather", rawArguments = "{}"))))) { client, engine ->
            val resp = client.post(geminiStreamPath, geminiBody())
            assertEquals(400, resp.statusCode())
            assertEquals(
                "step 1: tool call 1 sets rawArguments, which a Gemini reply cannot carry; functionCall.args must be a JSON object",
                errorDetail(resp.body()),
            )
            assertFalse(resp.body().contains("data:"))
            assertEquals(emptyList(), engine.calls())
        }
    }
}
