package com.davidjonesgriz.fakellm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
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
                openAiFrames(resp.body()),
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
                openAiFrames(resp.body()),
            )
        }
    }

    @Test
    fun `max_tokens finishes an openai stream with length`() {
        withServer(engineWith(Step(text = "cut off", stopReason = "max_tokens"))) { client, _ ->
            val frames = openAiFrames(client.post("/openai/v1/chat/completions", openAiStreamBody()).body())
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
                openAiFrames(scripted.body()),
            )
            val blank = openAiFrames(client.post("/openai/v1/chat/completions", openAiStreamBody()).body())
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
            val frames = openAiFrames(client.post("/openai/v1/chat/completions", openAiStreamBody()).body())
            assertEquals(CANNED_ASSISTANT_TEXT, contentPieces(frames).joinToString(""))
            assertEquals("[DONE]", frames.last())
            assertEquals("default", engine.calls().single().outcome)
        }
    }

    @Test
    fun `streamInterChunkDelayMs waits before each openai delta`() {
        withServer(engineWith(Step(text = "abcdefghi", streamChunkChars = 3, streamInterChunkDelayMs = 100))) { client, _ ->
            val started = System.nanoTime()
            val frames = openAiFrames(client.post("/openai/v1/chat/completions", openAiStreamBody()).body())
            val elapsedMs = (System.nanoTime() - started) / 1_000_000
            assertEquals(listOf("abc", "def", "ghi"), contentPieces(frames))
            assertTrue(elapsedMs >= 300, "took $elapsedMs ms")
        }
    }

    @Test
    fun `a streamed openai call is numbered and logged like a buffered one`() {
        withServer(engineWith(Step(text = "one"), Step(text = "two"))) { client, engine ->
            val streamed = openAiFrames(client.post("/openai/v1/chat/completions", openAiStreamBody()).body())
            assertTrue(streamed.first().startsWith("""{"id":"fake-openai-1","""))
            assertEquals(200, client.post("/openai/v1/chat/completions", openAiBody("x")).statusCode())
            val calls = engine.calls()
            assertEquals(listOf(1, 2), calls.map { it.step })
            assertEquals(listOf("text", "text"), calls.map { it.outcome })
        }
    }
}
