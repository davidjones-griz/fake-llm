package dev.fakellm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.http.HttpResponse
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FakeLlmServerTest {
    private val inlineMatch =
        """
        {
          "scenario": {
            "name": "inline-match",
            "rules": [
              {
                "role": "summarizer",
                "match": { "caller": "summarizer" },
                "respond": [ { "text": "Summary ready." } ]
              },
              {
                "role": "weather",
                "match": { "modelContains": "weather", "promptContainsAll": ["forecast"] },
                "respond": [ { "text": "Sunny." } ]
              }
            ]
          }
        }
        """.trimIndent()

    private fun calls(client: TestClient) =
        Json.parseToJsonElement(client.get("/control/calls").body()).jsonArray.map { it.jsonObject }

    private fun assertUnmatched(
        response: HttpResponse<String>,
        vendor: String,
        why: String,
        model: String,
        systemShape: String,
        messageCount: Int,
        caller: String?,
        promptPreview: String,
    ) {
        assertEquals(400, response.statusCode(), response.body())
        assertTrue(response.headers().firstValue("content-type").orElse("").startsWith("application/json"))
        val obj = Json.parseToJsonElement(response.body()).jsonObject
        assertEquals("unmatched request", obj.getValue("fake_llm_error").jsonPrimitive.content)
        assertEquals(why, obj.getValue("why").jsonPrimitive.content)
        assertEquals(vendor, obj.getValue("vendor").jsonPrimitive.content)
        assertEquals(model, obj.getValue("model").jsonPrimitive.content)
        assertEquals(systemShape, obj.getValue("system_shape").jsonPrimitive.content)
        assertFalse(obj.getValue("message_count").jsonPrimitive.isString)
        assertEquals(messageCount, obj.getValue("message_count").jsonPrimitive.content.toInt())
        if (caller == null) {
            assertEquals(JsonNull, obj.getValue("caller"))
        } else {
            assertTrue(obj.getValue("caller").jsonPrimitive.isString)
            assertEquals(caller, obj.getValue("caller").jsonPrimitive.content)
        }
        assertEquals(promptPreview, obj.getValue("prompt_preview").jsonPrimitive.content)
    }

    @Test
    fun `an inline scenario matches the caller header prompt and model and records the call log`() {
        withServer(engine = ScenarioEngine(lenient = true)) { client, _ ->
            val loaded = client.post("/control/scenario", inlineMatch)
            assertEquals(200, loaded.statusCode(), loaded.body())
            val loadedJson = Json.parseToJsonElement(loaded.body()).jsonObject
            assertEquals("inline-match", loadedJson.getValue("loaded").jsonPrimitive.content)
            assertEquals(2, loadedJson.getValue("rules").jsonPrimitive.content.toInt())
            assertContains(client.get("/health").body(), "inline-match")

            val summary =
                client.post(
                    "/anthropic/v1/messages",
                    anthropicBody("weekly forecast", model = "weather-model"),
                    caller = "summarizer",
                )
            assertEquals(200, summary.statusCode())
            assertEquals("Summary ready.", anthropicText(summary.body()))

            val weather =
                client.post(
                    "/anthropic/v1/messages",
                    anthropicBody("weekly forecast", model = "weather-model"),
                )
            assertEquals("Sunny.", anthropicText(weather.body()))

            val wrongModel =
                client.post(
                    "/anthropic/v1/messages",
                    anthropicBody("weekly forecast", model = "other-model"),
                )
            assertEquals(CANNED_ASSISTANT_TEXT, anthropicText(wrongModel.body()))

            val wrongPrompt =
                client.post(
                    "/anthropic/v1/messages",
                    anthropicBody("hello there", model = "weather-model"),
                )
            assertEquals(CANNED_ASSISTANT_TEXT, anthropicText(wrongPrompt.body()))

            val log = calls(client)
            assertEquals(listOf("summarizer", "weather", "DEFAULT", "DEFAULT"), log.map { it.getValue("role").jsonPrimitive.content })
            assertEquals(listOf("text", "text", "default", "default"), log.map { it.getValue("outcome").jsonPrimitive.content })
            assertEquals(listOf(1, 1, 1, 2), log.map { it.getValue("occurrence").jsonPrimitive.content.toInt() })
            assertEquals(listOf(1L, 2L, 3L, 4L), log.map { it.getValue("seq").jsonPrimitive.content.toLong() })
            assertTrue(log.all { it.getValue("vendor").jsonPrimitive.content == "anthropic" })
            assertEquals("weather-model", log[0].getValue("model").jsonPrimitive.content)
            assertEquals("absent", log[0].getValue("systemShape").jsonPrimitive.content)
            assertEquals(1, log[0].getValue("messageCount").jsonPrimitive.content.toInt())
            assertContains(log[0].getValue("promptSample").jsonPrimitive.content, "weekly forecast")
            assertContains(log[3].getValue("promptSample").jsonPrimitive.content, "hello there")

            val reloaded = client.post("/control/scenario", """{"name":"hello"}""")
            assertEquals(200, reloaded.statusCode(), reloaded.body())
            assertEquals("[]", client.get("/control/calls").body())
            assertContains(client.get("/health").body(), "hello")
            val hello = client.post("/anthropic/v1/messages", anthropicBody("hi"))
            assertEquals(
                "Hello. I can help with the weather, a simple recipe, or a short how-to.",
                anthropicText(hello.body()),
            )
        }
    }

    @Test
    fun `reset clears the call log and occurrence counters and keeps the scenario`() {
        val inlineSteps =
            """
            {
              "scenario": {
                "name": "inline-steps",
                "rules": [
                  { "role": "counter", "respond": [ { "text": "one" }, { "text": "two" } ] }
                ]
              }
            }
            """.trimIndent()
        withServer { client, _ ->
            assertEquals(200, client.post("/control/scenario", inlineSteps).statusCode())
            assertEquals("one", anthropicText(client.post("/anthropic/v1/messages", anthropicBody("a")).body()))
            assertEquals("two", anthropicText(client.post("/anthropic/v1/messages", anthropicBody("b")).body()))
            assertEquals("two", anthropicText(client.post("/anthropic/v1/messages", anthropicBody("c")).body()))
            assertEquals(listOf(1, 2, 3), calls(client).map { it.getValue("occurrence").jsonPrimitive.content.toInt() })

            val reset = client.post("/control/reset", "")
            assertEquals(200, reset.statusCode())
            assertEquals("""{"reset":true}""", reset.body())
            assertEquals("[]", client.get("/control/calls").body())
            val health = Json.parseToJsonElement(client.get("/health").body()).jsonObject
            assertEquals("ok", health.getValue("status").jsonPrimitive.content)
            assertEquals("inline-steps", health.getValue("scenario").jsonPrimitive.content)
            assertFalse(health.getValue("lenient").jsonPrimitive.isString)
            assertEquals("false", health.getValue("lenient").jsonPrimitive.content)

            val again = client.post("/anthropic/v1/messages", anthropicBody("d"))
            assertEquals("one", anthropicText(again.body()))
            assertEquals(1, calls(client).single().getValue("occurrence").jsonPrimitive.content.toInt())
        }
    }

    @Test
    fun `lenient mode with no scenario loaded returns the canned assistant text`() {
        withServer(engine = ScenarioEngine(lenient = true)) { client, _ ->
            val health = Json.parseToJsonElement(client.get("/health").body()).jsonObject
            assertEquals(200, client.get("/health").statusCode())
            assertEquals("ok", health.getValue("status").jsonPrimitive.content)
            assertEquals(JsonNull, health.getValue("scenario"))
            assertFalse(health.getValue("lenient").jsonPrimitive.isString)
            assertEquals("true", health.getValue("lenient").jsonPrimitive.content)

            val resp = client.post("/anthropic/v1/messages", anthropicBody("hello"))
            assertEquals(200, resp.statusCode())
            assertEquals(CANNED_ASSISTANT_TEXT, anthropicText(resp.body()))
            val call = calls(client).single()
            assertEquals("DEFAULT", call.getValue("role").jsonPrimitive.content)
            assertEquals("default", call.getValue("outcome").jsonPrimitive.content)
            assertEquals(1, call.getValue("occurrence").jsonPrimitive.content.toInt())
        }
    }

    @Test
    fun `health reports lenient mode`() {
        withServer { client, _ ->
            val health = Json.parseToJsonElement(client.get("/health").body()).jsonObject
            assertEquals("ok", health.getValue("status").jsonPrimitive.content)
            assertEquals(JsonNull, health.getValue("scenario"))
            assertFalse(health.getValue("lenient").jsonPrimitive.isString)
            assertEquals("false", health.getValue("lenient").jsonPrimitive.content)
        }
        withServer(engine = ScenarioEngine(lenient = true)) { client, _ ->
            val health = Json.parseToJsonElement(client.get("/health").body()).jsonObject
            assertFalse(health.getValue("lenient").jsonPrimitive.isString)
            assertEquals("true", health.getValue("lenient").jsonPrimitive.content)
        }
    }

    @Test
    fun `strict mode returns 400 for an unmatched request on every vendor including an anthropic stream`() {
        val noScenario = "no scenario loaded; POST /control/scenario first (or start with --lenient)"
        withServer { client, _ ->
            val health = Json.parseToJsonElement(client.get("/health").body()).jsonObject
            assertFalse(health.getValue("lenient").jsonPrimitive.isString)
            assertEquals("false", health.getValue("lenient").jsonPrimitive.content)
            assertEquals(JsonNull, health.getValue("scenario"))

            val quoted = "Hello \"there\""
            val buffered =
                client.post(
                    "/anthropic/v1/messages",
                    """{"model":"example-model","max_tokens":32,"system":"sys","messages":[{"role":"user","content":${JsonPrimitive(quoted)}}]}""",
                    caller = "planner",
                )
            assertUnmatched(
                buffered,
                vendor = "anthropic",
                why = noScenario,
                model = "example-model",
                systemShape = "string",
                messageCount = 1,
                caller = "planner",
                promptPreview = "sys\nHello \"there\"\n",
            )

            val streamed = client.post("/anthropic/v1/messages", anthropicBody("stream me", stream = true))
            assertUnmatched(
                streamed,
                vendor = "anthropic",
                why = noScenario,
                model = "example-model",
                systemShape = "absent",
                messageCount = 1,
                caller = null,
                promptPreview = "stream me\n",
            )
            assertTrue(streamed.headers().firstValue("content-type").orElse("").startsWith("application/json"))
            assertFalse(streamed.headers().firstValue("content-type").orElse("").contains("event-stream"))
            assertTrue(streamed.body().trimStart().startsWith("{"))
            assertFalse(streamed.body().contains("message_start"))
            assertFalse(streamed.body().contains("event:"))

            val openai =
                client.post(
                    "/openai/v1/chat/completions",
                    """{"model":"example-gpt","stream":true,"messages":[{"role":"system","content":"sys"},{"role":"user","content":"hi"}]}""",
                    caller = "summarizer",
                )
            assertUnmatched(
                openai,
                vendor = "openai",
                why = noScenario,
                model = "example-gpt",
                systemShape = "blocks",
                messageCount = 2,
                caller = "summarizer",
                promptPreview = "sys\nhi\n",
            )
            assertFalse(openai.body().contains("chat.completion"))

            val gemini =
                client.post(
                    "/gemini/v1beta/models/gemini-2.0-flash:generateContent",
                    """{"contents":[{"parts":[{"text":"Hello"}]}]}""",
                )
            assertUnmatched(
                gemini,
                vendor = "gemini",
                why = noScenario,
                model = "gemini-2.0-flash",
                systemShape = "absent",
                messageCount = 1,
                caller = null,
                promptPreview = "Hello\n",
            )
            assertFalse(gemini.body().contains("candidates"))

            val log = calls(client)
            assertEquals(listOf("anthropic", "anthropic", "openai", "gemini"), log.map { it.getValue("vendor").jsonPrimitive.content })
            assertTrue(log.all { it.getValue("role").jsonPrimitive.content == "UNMATCHED" })
            assertTrue(log.all { it.getValue("outcome").jsonPrimitive.content == "unmatched" })
            assertTrue(log.all { it.getValue("occurrence").jsonPrimitive.content == "0" })
            assertEquals(listOf(1L, 2L, 3L, 4L), log.map { it.getValue("seq").jsonPrimitive.content.toLong() })
            assertEquals("sys\nHello \"there\"\n", log[0].getValue("promptSample").jsonPrimitive.content)

            val loaded =
                client.post(
                    "/control/scenario",
                    """{"scenario":{"name":"only-summarizer","rules":[{"role":"summarizer","match":{"caller":"summarizer"},"respond":[{"text":"ok"}]}]}}""",
                )
            assertEquals(200, loaded.statusCode(), loaded.body())
            assertEquals("[]", client.get("/control/calls").body())

            val missed = client.post("/anthropic/v1/messages", anthropicBody("nope"))
            assertUnmatched(
                missed,
                vendor = "anthropic",
                why = "no rule in scenario 'only-summarizer' matches this request",
                model = "example-model",
                systemShape = "absent",
                messageCount = 1,
                caller = null,
                promptPreview = "nope\n",
            )
            val missedCall = calls(client).single()
            assertEquals("UNMATCHED", missedCall.getValue("role").jsonPrimitive.content)
            assertEquals("unmatched", missedCall.getValue("outcome").jsonPrimitive.content)
            assertEquals(0, missedCall.getValue("occurrence").jsonPrimitive.content.toInt())
            assertEquals(5L, missedCall.getValue("seq").jsonPrimitive.content.toLong())

            val hit = client.post("/anthropic/v1/messages", anthropicBody("hi"), caller = "summarizer")
            assertEquals(200, hit.statusCode(), hit.body())
            assertEquals("ok", anthropicText(hit.body()))
            assertEquals("summarizer", calls(client)[1].getValue("role").jsonPrimitive.content)
            assertEquals(1, calls(client)[1].getValue("occurrence").jsonPrimitive.content.toInt())
            assertEquals("text", calls(client)[1].getValue("outcome").jsonPrimitive.content)
        }
    }

    @Test
    fun `lenient mode streams the canned reply when anthropic stream is true`() {
        withServer(engine = ScenarioEngine(lenient = true)) { client, _ ->
            val resp = client.post("/anthropic/v1/messages", anthropicBody("hello", stream = true))
            assertEquals(200, resp.statusCode(), resp.body())
            assertTrue(resp.headers().firstValue("content-type").orElse("").startsWith("text/event-stream"))
            val text =
                resp.body().split("\n\n").filter { it.isNotBlank() }.mapNotNull { frame ->
                    val lines = frame.lines()
                    val event = lines.first { it.startsWith("event: ") }.removePrefix("event: ")
                    if (event != "content_block_delta") return@mapNotNull null
                    val data = lines.first { it.startsWith("data: ") }.removePrefix("data: ")
                    Json.parseToJsonElement(data).jsonObject.getValue("delta").jsonObject.getValue("text").jsonPrimitive.content
                }
            assertEquals(CANNED_ASSISTANT_TEXT, text.joinToString(""))
            val call = calls(client).single()
            assertEquals("DEFAULT", call.getValue("role").jsonPrimitive.content)
            assertEquals("default", call.getValue("outcome").jsonPrimitive.content)
            assertEquals(1, call.getValue("occurrence").jsonPrimitive.content.toInt())
        }
    }

    @Test
    fun `cache tokens follow the request cache breakpoint`() {
        withServer(engine = ScenarioEngine(lenient = true)) { client, _ ->
            val withBreakpoint =
                client.post(
                    "/anthropic/v1/messages",
                    """
                    {"model":"example-model","max_tokens":64,
                     "system":[{"type":"text","text":"sys","cache_control":{"type":"ephemeral"}}],
                     "messages":[{"role":"user","content":"hello"}]}
                    """.trimIndent(),
                )
            assertEquals(200, withBreakpoint.statusCode(), withBreakpoint.body())
            val usage = Json.parseToJsonElement(withBreakpoint.body()).jsonObject.getValue("usage").jsonObject
            assertEquals(FIXED_INPUT_TOKENS, usage.getValue("input_tokens").jsonPrimitive.content.toInt())
            assertEquals(FIXED_OUTPUT_TOKENS, usage.getValue("output_tokens").jsonPrimitive.content.toInt())
            assertEquals(FIXED_CACHE_CREATION_TOKENS, usage.getValue("cache_creation_input_tokens").jsonPrimitive.content.toInt())
            assertEquals(FIXED_CACHE_READ_TOKENS, usage.getValue("cache_read_input_tokens").jsonPrimitive.content.toInt())
            assertTrue(FIXED_CACHE_CREATION_TOKENS >= 1)
            assertTrue(FIXED_CACHE_READ_TOKENS >= 1)

            val without =
                client.post(
                    "/anthropic/v1/messages",
                    """{"model":"example-model","max_tokens":64,"messages":[{"role":"user","content":"hello"}]}""",
                )
            val plain = Json.parseToJsonElement(without.body()).jsonObject.getValue("usage").jsonObject
            assertEquals(0, plain.getValue("cache_creation_input_tokens").jsonPrimitive.content.toInt())
            assertEquals(0, plain.getValue("cache_read_input_tokens").jsonPrimitive.content.toInt())
            assertEquals(FIXED_INPUT_TOKENS, plain.getValue("input_tokens").jsonPrimitive.content.toInt())
        }
    }

    @Test
    fun `a null cache_control ttl returns 400`() {
        withServer { client, _ ->
            val resp =
                client.post(
                    "/anthropic/v1/messages",
                    """
                    {"model":"example-model","max_tokens":64,
                     "system":[{"type":"text","text":"sys","cache_control":{"type":"ephemeral","ttl":null}}],
                     "messages":[{"role":"user","content":"hello"}]}
                    """.trimIndent(),
                )
            assertEquals(400, resp.statusCode())
            assertContains(errorDetail(resp.body()), "cache_control")
        }
    }

    @Test
    fun `malformed and incomplete JSON bodies return 400`() {
        withServer { client, _ ->
            val malformed = client.post("/anthropic/v1/messages", "{")
            assertEquals(400, malformed.statusCode())
            assertEquals("exception", Json.parseToJsonElement(malformed.body()).jsonObject.getValue("fake_llm_error").jsonPrimitive.content)
            assertTrue(errorDetail(malformed.body()).isNotBlank())

            val missingMessages = client.post("/anthropic/v1/messages", """{"model":"example-model"}""")
            assertEquals(400, missingMessages.statusCode())
            assertContains(errorDetail(missingMessages.body()), "messages")

            val control = client.post("/control/scenario", "{")
            assertEquals(400, control.statusCode())
            assertTrue(errorDetail(control.body()).isNotBlank())
        }
    }

    @Test
    fun `an unknown route returns 404`() {
        withServer { client, _ ->
            val getResp = client.get("/nope")
            assertEquals(404, getResp.statusCode())
            assertContains(getResp.body(), "GET /nope")

            val postResp = client.post("/also-missing", "{}")
            assertEquals(404, postResp.statusCode())
            assertContains(postResp.body(), "POST /also-missing")
        }
    }

    @Test
    fun `the messages batches route returns 501`() {
        withServer { client, _ ->
            val root = client.post("/anthropic/v1/messages/batches", "{}")
            assertEquals(501, root.statusCode())
            assertContains(root.body(), "the Anthropic Batches API is not implemented")

            val nested = client.post("/anthropic/v1/messages/batches/job-1", "{}")
            assertEquals(501, nested.statusCode())
            assertContains(nested.body(), "not implemented")
        }
    }

    @Test
    fun `loading a scenario requires exactly one of name or scenario`() {
        val inline = """{"name":"inline","rules":[{"role":"r","respond":[{"text":"t"}]}]}"""
        withServer { client, _ ->
            val both = client.post("/control/scenario", """{"name":"hello","scenario":$inline}""")
            assertEquals(400, both.statusCode())
            assertContains(errorDetail(both.body()), "exactly one")

            val neither = client.post("/control/scenario", "{}")
            assertEquals(400, neither.statusCode())
            assertContains(errorDetail(neither.body()), "exactly one")

            val notObject = client.post("/control/scenario", "[1]")
            assertEquals(400, notObject.statusCode())
            assertContains(errorDetail(notObject.body()), "JSON object")

            val blankName = client.post("/control/scenario", """{"name":" "}""")
            assertEquals(400, blankName.statusCode())
            assertContains(errorDetail(blankName.body()), "non-blank")

            val scenarioNotObject = client.post("/control/scenario", """{"scenario":"hello"}""")
            assertEquals(400, scenarioNotObject.statusCode())
            assertContains(errorDetail(scenarioNotObject.body()), "JSON object")
        }
    }

    @Test
    fun `an unknown scenario name returns 400`() {
        withServer { client, _ ->
            val resp = client.post("/control/scenario", """{"name":"no-such-scenario"}""")
            assertEquals(400, resp.statusCode())
            assertContains(errorDetail(resp.body()), "unknown scenario")
        }
    }

    @Test
    fun `the scenario index lists the names the loader can see`() {
        ScenarioLoader.setScenarioDirectory(null)
        withServer { client, _ ->
            val resp = client.get("/control/scenarios")
            assertEquals(200, resp.statusCode(), resp.body())
            val names =
                Json.parseToJsonElement(resp.body()).jsonObject.getValue("scenarios").jsonArray.map {
                    it.jsonPrimitive.content
                }
            assertEquals(ScenarioLoader.availableNames(), names)
            assertContains(names, "hello")
            assertContains(names, "fail-then-retry")
            assertContains(names, "rate-limit-429")
        }
    }

    @Test
    fun `health and vendor routes stay open when a control token is set`() {
        withServer(engine = ScenarioEngine(lenient = true), controlToken = "test-token") { client, _ ->
            val health = client.get("/health")
            assertEquals(200, health.statusCode())
            assertContains(health.body(), "ok")

            val vendor = client.post("/anthropic/v1/messages", anthropicBody("hello"))
            assertEquals(200, vendor.statusCode())
            assertEquals(CANNED_ASSISTANT_TEXT, anthropicText(vendor.body()))
        }
    }

    @Test
    fun `control routes reject a missing or wrong bearer token`() {
        withServer(controlToken = "test-token") { client, _ ->
            val responses =
                listOf(
                    client.get("/control/calls"),
                    client.get("/control/scenarios"),
                    client.post("/control/reset", ""),
                    client.post("/control/scenario", """{"name":"hello"}"""),
                    client.get("/control/calls", authorization = "Bearer wrong-token"),
                    client.post("/control/reset", "", authorization = "Bearer wrong-token"),
                )
            responses.forEach { resp ->
                assertEquals(401, resp.statusCode(), resp.body())
                assertContains(resp.body(), "unauthorized")
            }
        }
    }

    @Test
    fun `control routes accept the configured bearer token`() {
        withServer(controlToken = "test-token") { client, _ ->
            val auth = "Bearer test-token"
            assertEquals(200, client.get("/control/calls", authorization = auth).statusCode())
            assertEquals(200, client.get("/control/scenarios", authorization = auth).statusCode())
            val reset = client.post("/control/reset", "", authorization = auth)
            assertEquals(200, reset.statusCode())
            assertEquals("""{"reset":true}""", reset.body())
            val load = client.post("/control/scenario", """{"name":"hello"}""", authorization = auth)
            assertEquals(200, load.statusCode(), load.body())
            assertContains(load.body(), "hello")
        }
    }

    @Test
    fun `control routes are open when no token is configured`() {
        withServer(controlToken = null) { client, _ ->
            assertEquals(200, client.get("/control/calls").statusCode())
            assertEquals(200, client.get("/control/scenarios").statusCode())
            assertEquals(200, client.post("/control/reset", "").statusCode())
        }
    }
}
