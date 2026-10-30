package com.davidjonesgriz.fakellm

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.http.HttpResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Every 400 fake-llm sends itself: `fake_llm_error`, a stable code, then `message`, then the code's own fields. */
class ErrorBodyTest {
    private val callFields = listOf("seq", "vendor", "model", "system_shape", "message_count", "prompt_preview")

    /** The 400 body, after checking its code, that the code and message come first, and that the message is not blank. */
    private fun errorBody(
        response: HttpResponse<String>,
        code: String,
    ): JsonObject {
        assertEquals(400, response.statusCode(), response.body())
        assertEquals(listOf("application/json"), response.headers().allValues("content-type"))
        val body = Json.parseToJsonElement(response.body()).jsonObject
        assertEquals(listOf("fake_llm_error", "message"), body.keys.take(2), response.body())
        assertEquals(code, body.getValue("fake_llm_error").jsonPrimitive.content)
        assertTrue(
            body
                .getValue("message")
                .jsonPrimitive.content
                .isNotBlank(),
            response.body(),
        )
        return body
    }

    private fun message(body: JsonObject) = body.getValue("message").jsonPrimitive.content

    private fun engineWith(vararg steps: Step) = ScenarioEngine().also { it.loadInline(Scenario(name = "example", steps = steps.toList())) }

    @Test
    fun `no-more-steps carries the scenario, its step count and the call fields`() {
        withServer(engineWith(Step(text = "only"))) { client, _ ->
            client.post("/anthropic/v1/messages", anthropicBody("one"))
            val body = errorBody(client.post("/anthropic/v1/messages", anthropicBody("two")), "no-more-steps")
            assertEquals(listOf("fake_llm_error", "message", "scenario", "steps") + callFields, body.keys.toList())
            assertEquals(
                "no more steps: call 2 came after the last step of scenario 'example', which has 1 step. The last step does not repeat.",
                message(body),
            )
        }
        withServer(engineWith(Step(text = "a"), Step(text = "b"))) { client, _ ->
            repeat(2) { client.post("/anthropic/v1/messages", anthropicBody("x")) }
            assertTrue(
                "which has 2 steps." in message(errorBody(client.post("/anthropic/v1/messages", anthropicBody("x")), "no-more-steps")),
            )
        }
    }

    @Test
    fun `no-scenario carries the call fields and says how to load a scenario`() {
        withServer { client, _ ->
            val body = errorBody(client.post("/anthropic/v1/messages", anthropicBody("hi")), "no-scenario")
            assertEquals(listOf("fake_llm_error", "message") + callFields, body.keys.toList())
            assertTrue(message(body).startsWith("no scenario loaded: call 1 has no step to answer it."), message(body))
            assertTrue("POST /control/scenario" in message(body) && "--lenient" in message(body), message(body))
        }
    }

    @Test
    fun `invalid-json is a body that is not JSON, or not a JSON object, on any route that reads one`() {
        withServer { client, _ ->
            listOf(
                "/anthropic/v1/messages" to "{",
                "/openai/v1/chat/completions" to "[]",
                "/openai/v1/responses" to "\"text\"",
                "/gemini/v1beta/models/m:generateContent" to "not json",
                "/openai/v1/embeddings" to "[]",
                "/gemini/v1beta/models/m:embedContent" to "{",
                "/anthropic/v1/messages/count_tokens" to "{",
                "/control/scenario" to "[1]",
            ).forEach { (path, body) ->
                assertEquals(listOf("fake_llm_error", "message"), errorBody(client.post(path, body), "invalid-json").keys.toList(), path)
            }
        }
    }

    @Test
    fun `invalid-request is valid JSON the route rejects`() {
        withServer { client, _ ->
            assertTrue("messages" in message(errorBody(client.post("/anthropic/v1/messages", """{"model":"m"}"""), "invalid-request")))
            assertEquals(
                GEMINI_STREAM_NEEDS_ALT_SSE,
                message(
                    errorBody(
                        client.post(
                            "/gemini/v1beta/models/m:streamGenerateContent",
                            """{"contents":[{"role":"user","parts":[{"text":"hi"}]}]}""",
                        ),
                        "invalid-request",
                    ),
                ),
            )
            assertEquals(
                GEMINI_STREAM_NEEDS_ALT_SSE,
                message(errorBody(client.post("/gemini/v1beta/models/m:streamGenerateContent", "not json"), "invalid-request")),
            )
            assertTrue("exactly one" in message(errorBody(client.post("/control/scenario", "{}"), "invalid-request")))
            assertTrue(
                "unknown field" in message(errorBody(client.post("/control/scenario", """{"name":"hello","x":1}"""), "invalid-request")),
            )
            errorBody(client.post("/control/scenario", """{"name":1}"""), "invalid-request")
            errorBody(client.post("/control/scenario", """{"name":" "}"""), "invalid-request")
            errorBody(client.post("/openai/v1/embeddings", """{"input":""}"""), "invalid-request")
        }
    }

    @Test
    fun `unsupported-step is a next step whose rawArguments the request's format can't carry`() {
        val raw = Step(toolCalls = listOf(ToolCall(name = "get_weather", rawArguments = "{")))
        withServer(engineWith(raw)) { client, engine ->
            val body = errorBody(client.post("/anthropic/v1/messages", anthropicBody("hi")), "unsupported-step")
            assertTrue("rawArguments" in message(body), message(body))
            assertEquals(emptyList(), engine.calls())
        }
    }

    @Test
    fun `unknown-scenario and invalid-scenario are the two ways a load fails`() {
        withServer { client, _ ->
            val unknown = errorBody(client.post("/control/scenario", """{"name":"no-such-scenario"}"""), "unknown-scenario")
            assertTrue(message(unknown).startsWith("unknown scenario 'no-such-scenario'"), message(unknown))
            errorBody(client.post("/control/scenario", """{"name":"not a name"}"""), "invalid-scenario")
            errorBody(client.post("/control/scenario", """{"scenario":{"name":"x","steps":[]}}"""), "invalid-scenario")
            errorBody(client.post("/control/scenario", """{"scenario":{"name":"x","steps":[{"txt":"a"}]}}"""), "invalid-scenario")
        }
    }

    @Test
    fun `a failure the routing catches is classified by its type, and anything unexpected is exception`() {
        assertEquals(ErrorCode.UNSUPPORTED_STEP to "why", errorFor(RequestException(ErrorCode.UNSUPPORTED_STEP, "why")))
        assertEquals(ErrorCode.INVALID_JSON, errorFor(SerializationException("bad")).first)
        assertEquals(ErrorCode.INVALID_REQUEST, errorFor(IllegalArgumentException("bad")).first)
        assertEquals(ErrorCode.INVALID_REQUEST, errorFor(IllegalStateException("bad")).first)
        assertEquals(ErrorCode.EXCEPTION to "boom", errorFor(RuntimeException("boom")))
        assertEquals(ErrorCode.EXCEPTION, errorFor(InterruptedException()).first)
        assertTrue(errorFor(InterruptedException()).second.isNotBlank())
    }

    @Test
    fun `the library still throws the exception types it documents`() {
        val unknown = runCatching { ScenarioEngine().load("no-such-scenario") }.exceptionOrNull()
        assertTrue(unknown is IllegalStateException, "$unknown")
        val raw = engineWith(Step(toolCalls = listOf(ToolCall(name = "f", rawArguments = "{"))))
        val unsupported = runCatching { raw.resolve("anthropic", parseAnthropicRequest(anthropicBody("hi"))) }.exceptionOrNull()
        assertTrue(unsupported is IllegalArgumentException, "$unsupported")
    }
}
