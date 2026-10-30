package com.davidjonesgriz.fakellm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

/** Every vendor reply names the request's model, as the providers do. */
class ModelEchoTest {
    private fun engineWith(vararg steps: Step) =
        ScenarioEngine().also { it.loadInline(Scenario(name = "model-echo", steps = steps.toList())) }

    private fun field(
        body: String,
        key: String,
    ): String =
        Json
            .parseToJsonElement(body)
            .jsonObject
            .getValue(key)
            .jsonPrimitive.content

    @Test
    fun `buffered replies echo the request model on every route`() {
        withServer(engineWith(*Array(4) { Step(text = "Hi.") })) { client, _ ->
            assertEquals(
                "example-model-2",
                field(client.post("/anthropic/v1/messages", anthropicBody("Hi", model = "example-model-2")).body(), "model"),
            )
            assertEquals(
                "example-gpt-2",
                field(
                    client
                        .post(
                            "/openai/v1/chat/completions",
                            """{"model":"example-gpt-2","messages":[{"role":"user","content":"Hi"}]}""",
                        ).body(),
                    "model",
                ),
            )
            assertEquals(
                "example-gpt-3",
                field(client.post("/openai/v1/responses", """{"model":"example-gpt-3","input":"Hi"}""").body(), "model"),
            )
            val gemini =
                client.post(
                    "/gemini/v1beta/models/example-gemini-2:generateContent",
                    """{"contents":[{"parts":[{"text":"Hi"}]}]}""",
                )
            assertEquals("example-gemini-2", field(gemini.body(), "modelVersion"))
        }
    }

    @Test
    fun `every streamed chunk echoes the request model`() {
        withServer(
            engineWith(Step(text = "Hello there.", streamChunkChars = 3), Step(text = "Hello there.", streamChunkChars = 3)),
        ) { client, _ ->
            val openAi =
                client.post(
                    "/openai/v1/chat/completions",
                    """{"model":"example-gpt-2","stream":true,"stream_options":{"include_usage":true},"messages":[{"role":"user","content":"Hi"}]}""",
                )
            val frames = dataFrames(openAi.body()).dropLast(1) // [DONE]
            assertEquals(setOf("example-gpt-2"), frames.map { field(it, "model") }.toSet())
            val gemini =
                client.post(
                    "/gemini/v1beta/models/example-gemini-2:streamGenerateContent?alt=sse",
                    """{"contents":[{"parts":[{"text":"Hi"}]}]}""",
                )
            assertEquals(setOf("example-gemini-2"), dataFrames(gemini.body()).map { field(it, "modelVersion") }.toSet())
        }
    }

    @Test
    fun `a request without a string model echoes an empty one, and the lenient canned reply echoes too`() {
        withServer(engineWith(Step(text = "Hi."))) { client, _ ->
            val body = client.post("/openai/v1/chat/completions", """{"messages":[{"role":"user","content":"Hi"}]}""").body()
            assertEquals("", field(body, "model"))
        }
        withServer(engineWith(Step(text = "Hi."), Step(text = "Hi."))) { client, engine ->
            for (model in listOf("null", "42")) {
                val body =
                    client
                        .post(
                            "/openai/v1/chat/completions",
                            """{"model":$model,"messages":[{"role":"user","content":"Hi"}]}""",
                        ).body()
                assertEquals("", field(body, "model"), model)
            }
            assertEquals(listOf("", ""), engine.calls().map { it.model })
        }
        withServer(ScenarioEngine(lenient = true)) { client, _ ->
            assertEquals(
                "example-model-2",
                field(client.post("/anthropic/v1/messages", anthropicBody("Hi", model = "example-model-2")).body(), "model"),
            )
        }
    }
}
