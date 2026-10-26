package com.davidjonesgriz.fakellm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.net.http.HttpResponse
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FakeLlmServerTest {
    private fun calls(client: TestClient) = Json.parseToJsonElement(client.get("/control/calls").body()).jsonArray.map { it.jsonObject }

    private fun List<JsonObject>.longs(key: String): List<Long?> = map { (it.getValue(key) as? JsonPrimitive)?.longOrNull }

    private fun List<JsonObject>.strings(key: String): List<String> = map { it.getValue(key).jsonPrimitive.content }

    private fun inline(vararg texts: String): String =
        buildJsonObject {
            put(
                "scenario",
                buildJsonObject {
                    put("name", "inline-steps")
                    put("steps", buildJsonArray { texts.forEach { add(buildJsonObject { put("text", it) }) } })
                },
            )
        }.toString()

    /** Checks one of fake-llm's own 400s: JSON, never SSE, with the fields both bodies share. */
    @Suppress("LongParameterList") // one parameter per checked field
    private fun assertCallError(
        response: HttpResponse<String>,
        error: String,
        seq: Long,
        vendor: String,
        model: String,
        systemShape: String,
        messageCount: Int,
        promptPreview: String,
    ): JsonObject {
        assertEquals(400, response.statusCode(), response.body())
        assertEquals(listOf("application/json"), response.headers().allValues("content-type"))
        assertFalse(response.body().contains("event:"))
        val obj = Json.parseToJsonElement(response.body()).jsonObject
        assertEquals(error, obj.getValue("fake_llm_error").jsonPrimitive.content)
        assertEquals(seq, obj.getValue("seq").jsonPrimitive.long)
        assertEquals(vendor, obj.getValue("vendor").jsonPrimitive.content)
        assertEquals(model, obj.getValue("model").jsonPrimitive.content)
        assertEquals(systemShape, obj.getValue("system_shape").jsonPrimitive.content)
        assertFalse(obj.getValue("message_count").jsonPrimitive.isString)
        assertEquals(messageCount, obj.getValue("message_count").jsonPrimitive.int)
        assertEquals(promptPreview, obj.getValue("prompt_preview").jsonPrimitive.content)
        return obj
    }

    @Test
    fun `strict mode with no scenario loaded answers the no-scenario 400 on every vendor, including a stream`() {
        withServer { client, _ ->
            val quoted = "Hello \"there\""
            val buffered =
                client.post(
                    "/anthropic/v1/messages",
                    """{"model":"example-model","max_tokens":32,"system":"sys","messages":[{"role":"user","content":${JsonPrimitive(
                        quoted,
                    )}}]}""",
                )
            val bufferedBody =
                assertCallError(
                    buffered,
                    error = "no scenario loaded",
                    seq = 1,
                    vendor = "anthropic",
                    model = "example-model",
                    systemShape = "string",
                    messageCount = 1,
                    promptPreview = "sys\nHello \"there\"\n",
                )
            assertEquals(
                setOf("fake_llm_error", "seq", "vendor", "model", "system_shape", "message_count", "prompt_preview"),
                bufferedBody.keys,
            )

            val streamed = client.post("/anthropic/v1/messages", anthropicBody("stream me", stream = true))
            assertCallError(streamed, "no scenario loaded", 2, "anthropic", "example-model", "absent", 1, "stream me\n")
            assertFalse(streamed.body().contains("message_start"))

            val openai =
                client.post(
                    "/openai/v1/chat/completions",
                    """{"model":"example-gpt","stream":true,"messages":[{"role":"system","content":"sys"},{"role":"user","content":"hi"}]}""",
                )
            assertCallError(openai, "no scenario loaded", 3, "openai", "example-gpt", "blocks", 2, "sys\nhi\n")

            val gemini =
                client.post("/gemini/v1beta/models/gemini-2.0-flash:generateContent", """{"contents":[{"parts":[{"text":"Hello"}]}]}""")
            assertCallError(gemini, "no scenario loaded", 4, "gemini", "gemini-2.0-flash", "absent", 1, "Hello\n")

            val log = calls(client)
            assertEquals(listOf("anthropic", "anthropic", "openai", "gemini"), log.strings("vendor"))
            assertTrue(log.all { it.getValue("outcome").jsonPrimitive.content == "no-scenario" })
            assertTrue(log.all { it.getValue("step") == JsonNull })
            assertEquals(listOf(1L, 2L, 3L, 4L), log.longs("seq"))
            assertEquals("sys\nHello \"there\"\n", log[0].getValue("promptSample").jsonPrimitive.content)
        }
    }

    @Test
    fun `lenient mode streams the canned reply when anthropic stream is true`() {
        withServer(engine = ScenarioEngine(lenient = true)) { client, _ ->
            val resp = client.post("/anthropic/v1/messages", anthropicBody("hello", stream = true))
            assertEquals(200, resp.statusCode(), resp.body())
            assertTrue(
                resp
                    .headers()
                    .firstValue("content-type")
                    .orElse("")
                    .startsWith("text/event-stream"),
            )
            assertEquals(CANNED_ASSISTANT_TEXT, streamedText(resp.body()))
            assertEquals(
                "default",
                calls(client)
                    .single()
                    .getValue("outcome")
                    .jsonPrimitive.content,
            )
        }
    }

    @Test
    fun `a streamed call takes its step like a buffered one`() {
        withServer { client, _ ->
            client.post("/control/scenario", inline("streamed", "buffered"))
            val streamed = client.post("/anthropic/v1/messages", anthropicBody("a", stream = true))
            assertEquals("streamed", streamedText(streamed.body()))
            assertEquals("buffered", anthropicText(client.post("/anthropic/v1/messages", anthropicBody("b")).body()))
            assertEquals(listOf(1L, 2L), calls(client).longs("step"))
        }
    }

    @Test
    fun `calls that arrive together each get the step matching their own number`() {
        val size = 20
        withServer { client, _ ->
            client.post("/control/scenario", inline(*Array(size) { "step-${it + 1}" }))
            val pool = Executors.newFixedThreadPool(size)
            val start = CountDownLatch(1)
            val bodies =
                try {
                    (1..size)
                        .map {
                            pool.submit<String> {
                                start.await()
                                client.post("/anthropic/v1/messages", anthropicBody("go")).body()
                            }
                        }.also { start.countDown() }
                        .map { it.get(15, TimeUnit.SECONDS) }
                } finally {
                    pool.shutdownNow()
                }
            // Each response's id carries its call number, and its text is that number's step.
            bodies.forEach { body ->
                val n =
                    Json
                        .parseToJsonElement(body)
                        .jsonObject
                        .getValue("id")
                        .jsonPrimitive.content
                        .removePrefix("fake-msg-")
                assertEquals("step-$n", anthropicText(body))
            }
            assertEquals((1..size).map { "step-$it" }.toSet(), bodies.map { anthropicText(it) }.toSet())

            val log = calls(client)
            assertEquals((1L..size).toList(), log.longs("seq"))
            assertEquals((1L..size).toList(), log.longs("step"))
            val extra = client.post("/anthropic/v1/messages", anthropicBody("one too many"))
            assertEquals(400, extra.statusCode())
            assertContains(extra.body(), "no more steps")
        }
    }

    private fun streamedText(body: String): String =
        body
            .split("\n\n")
            .filter { it.isNotBlank() }
            .mapNotNull { frame ->
                val lines = frame.lines()
                val event = lines.first { it.startsWith("event: ") }.removePrefix("event: ")
                if (event != "content_block_delta") return@mapNotNull null
                val data = lines.first { it.startsWith("data: ") }.removePrefix("data: ")
                Json
                    .parseToJsonElement(data)
                    .jsonObject
                    .getValue("delta")
                    .jsonObject
                    .getValue("text")
                    .jsonPrimitive.content
            }.joinToString("")

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
            assertEquals(
                "exception",
                Json
                    .parseToJsonElement(malformed.body())
                    .jsonObject
                    .getValue("fake_llm_error")
                    .jsonPrimitive.content,
            )
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
    fun `loading a scenario requires exactly one of name or scenario`() {
        val inline = """{"name":"inline","steps":[{"text":"t"}]}"""
        withServer { client, engine ->
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

            val extraField = client.post("/control/scenario", """{"name":"hello","extra":1}""")
            assertEquals(400, extraField.statusCode())
            assertContains(errorDetail(extraField.body()), "unknown field(s) [extra]")
            assertEquals(null, engine.currentScenarioName(), "a rejected body loads nothing")
        }
    }

    @Test
    fun `an inline scenario may name the scenario schema in a top-level $schema key`() {
        withServer { client, engine ->
            val withSchema = """{"scenario":{"${'$'}schema":"https://example.invalid/s.json","name":"s","steps":[{"text":"t"}]}}"""
            val loaded = client.post("/control/scenario", withSchema)
            assertEquals(200, loaded.statusCode(), loaded.body())
            assertEquals("s", engine.currentScenarioName())
        }
    }

    @Test
    fun `an inline scenario with an invalid step, no steps, or rules is rejected`() {
        withServer { client, engine ->
            val badStatus = """{"scenario":{"name":"s","steps":[{"text":"ok"},{"error":{"status":42}}]}}"""
            val status = client.post("/control/scenario", badStatus)
            assertEquals(400, status.statusCode(), status.body())
            assertContains(errorDetail(status.body()), "step 2: ")
            assertContains(errorDetail(status.body()), "HTTP status")

            val misspelt = """{"scenario":{"name":"s","steps":[{"text":"ok"},{"text":"x","stopReasn":"max_tokens"}]}}"""
            val field = client.post("/control/scenario", misspelt)
            assertEquals(400, field.statusCode(), field.body())
            assertEquals("step 2: \$.steps[1]: unknown field 'stopReasn'", errorDetail(field.body()))

            val noSteps = client.post("/control/scenario", """{"scenario":{"name":"s","steps":[]}}""")
            assertEquals(400, noSteps.statusCode(), noSteps.body())

            val rules = client.post("/control/scenario", """{"scenario":{"name":"s","rules":[{"role":"r","respond":[{"text":"t"}]}]}}""")
            assertEquals(400, rules.statusCode(), rules.body())
            assertContains(errorDetail(rules.body()), "rules")
            assertEquals(null, engine.currentScenarioName())
        }
    }

    @Test
    fun `scripted headers other than content-type are added as given`() {
        withServer { client, engine ->
            val headers = mapOf("X-Retry-Hint" to "1", "x-retry-hint" to "2")
            engine.loadInline(
                Scenario(name = "h", steps = listOf(Step(error = ErrorStep(429, "{}", headers)))),
            )
            val resp = client.post("/anthropic/v1/messages", anthropicBody("hi"))
            assertEquals(listOf("1", "2"), resp.headers().allValues("x-retry-hint").sorted())
            assertEquals(listOf("application/json"), resp.headers().allValues("content-type"))
        }
    }

    @Test
    fun `the gemini and batches routes match their documented paths exactly`() {
        withServer { client, _ ->
            val body = """{"contents":[{"role":"user","parts":[{"text":"hi"}]}]}"""
            assertEquals(400, client.post("/gemini/v1beta/models/m:generateContent", body).statusCode(), "the documented path, no scenario")
            assertEquals(404, client.post("/gemini/m:generateContent", body).statusCode())
            assertEquals(404, client.post("/gemini/v1beta/models/m:generateContentXYZ", body).statusCode())
            assertEquals(404, client.post("/gemini/v1beta/models/m/extra:generateContent", body).statusCode())
            assertEquals(501, client.get("/anthropic/v1/messages/batches/abc/results").statusCode())
            assertEquals(404, client.get("/anthropic/v1/messages/batchesXYZ").statusCode())
        }
    }

    @Test
    fun `the OpenAPI document is served as YAML, byte for byte`() {
        withServer { client, _ ->
            val resp = client.get("/openapi")
            assertEquals(200, resp.statusCode())
            assertEquals(listOf("application/yaml"), resp.headers().allValues("content-type"))
            // Gradle runs tests from the project directory, where the source of the bundled copy lives.
            assertEquals(java.io.File("openapi/fake-llm.yaml").readText(), resp.body())
        }
    }

    @Test
    fun `a build without the OpenAPI document answers 500 instead of failing the request oddly`() {
        val (status, contentType, body) = openApiResponse(null)
        assertEquals(500, status)
        assertEquals("application/json", contentType)
        assertContains(body, "not bundled")
        assertEquals(Triple(200, "application/yaml", "spec"), openApiResponse("spec"))
    }
}
