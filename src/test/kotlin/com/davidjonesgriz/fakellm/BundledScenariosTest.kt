package com.davidjonesgriz.fakellm

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BundledScenariosTest {
    private val bundledNames =
        listOf(
            "fail-then-retry",
            "hello",
            "malformed-json",
            "max-tokens-cutoff",
            "overloaded-529",
            "rate-limit-429",
            "refusal",
            "stream-error-midway",
            "thinking-stream",
        )

    private fun TestClient.load(name: String) {
        val resp = post("/control/scenario", """{"name":"$name"}""")
        assertEquals(200, resp.statusCode(), resp.body())
    }

    private fun TestClient.callLog() = Json.parseToJsonElement(get("/control/calls").body()).jsonArray.map { it.jsonObject }

    @Test
    fun `every bundled scenario name loads and matches its file`() {
        val names = ScenarioLoader.availableNames(directory = null)
        assertEquals(bundledNames, names)
        names.forEach { name ->
            val scenario = ScenarioLoader.load(name, directory = null)
            assertEquals(name, scenario.name)
            assertTrue(scenario.steps.isNotEmpty(), name)
        }
    }

    @Test
    fun `every bundled scenario serves its steps in order, then the no-more-steps 400`() {
        bundledNames.forEach { name ->
            withServer { client, _ ->
                client.load(name)
                val steps = ScenarioLoader.load(name, directory = null).steps
                steps.forEach { step ->
                    val resp = client.post("/anthropic/v1/messages", anthropicBody("hi"))
                    assertEquals(step.error?.status ?: 200, resp.statusCode(), "$name: ${resp.body()}")
                }
                val extra = client.post("/anthropic/v1/messages", anthropicBody("hi"))
                assertEquals(400, extra.statusCode(), name)
                assertContains(extra.body(), "\"no more steps\"", message = name)
                val log = client.callLog()
                assertEquals((1..steps.size).map { it.toString() } + "null", log.map { it.getValue("step").toString() }, name)
            }
        }
    }

    @Test
    fun `hello returns the bundled greeting`() {
        withServer { client, _ ->
            client.load("hello")
            val resp = client.post("/anthropic/v1/messages", anthropicBody("hi"))
            assertEquals(200, resp.statusCode())
            assertEquals(
                "Hello. I can help with the weather, a simple recipe, or a short how-to.",
                anthropicText(resp.body()),
            )
            assertEquals("end_turn", anthropicStopReason(resp.body()))
        }
    }

    @Test
    fun `rate-limit-429 returns 429 with retry-after 2 and then a normal reply`() {
        val success = "Tomorrow will be mild and dry, with a high near 18 C and a light breeze."
        withServer { client, _ ->
            client.load("rate-limit-429")
            val limited = client.post("/anthropic/v1/messages", anthropicBody("forecast"))
            assertEquals(429, limited.statusCode())
            assertEquals("2", limited.headers().firstValue("retry-after").orElse(""))
            assertContains(limited.body(), "rate_limit_error")
            assertFalse(limited.body().contains("fake-msg"))

            val recovered = client.post("/anthropic/v1/messages", anthropicBody("forecast"))
            assertEquals(200, recovered.statusCode())
            assertEquals(success, anthropicText(recovered.body()))

            val log = client.callLog()
            assertEquals(listOf("error:429", "text"), log.map { it.getValue("outcome").jsonPrimitive.content })
        }
    }

    @Test
    fun `overloaded-529 returns 529 and then a normal reply`() {
        val success = "I can help with that. Restate the problem in one sentence, then list what you already tried."
        withServer { client, _ ->
            client.load("overloaded-529")
            val overloaded = client.post("/anthropic/v1/messages", anthropicBody("help"))
            assertEquals(529, overloaded.statusCode())
            assertContains(overloaded.body(), "overloaded_error")
            assertTrue(
                overloaded
                    .headers()
                    .firstValue("content-type")
                    .orElse("")
                    .startsWith("application/json"),
            )

            val recovered = client.post("/anthropic/v1/messages", anthropicBody("help"))
            assertEquals(200, recovered.statusCode())
            assertEquals(success, anthropicText(recovered.body()))

            val log = client.callLog()
            assertEquals(listOf("error:529", "text"), log.map { it.getValue("outcome").jsonPrimitive.content })
        }
    }

    @Test
    fun `overloaded-529 stays a buffered HTTP error when the client requests a stream`() {
        withServer { client, _ ->
            client.load("overloaded-529")
            val resp = client.post("/anthropic/v1/messages", anthropicBody("help", stream = true))
            assertEquals(529, resp.statusCode())
            assertTrue(
                resp
                    .headers()
                    .firstValue("content-type")
                    .orElse("")
                    .startsWith("application/json"),
            )
            assertContains(resp.body(), "overloaded_error")
            assertFalse(resp.body().contains("content_block_delta"))
        }
    }

    @Test
    fun `refusal returns stop_reason refusal`() {
        withServer { client, _ ->
            client.load("refusal")
            val resp = client.post("/anthropic/v1/messages", anthropicBody("a request"))
            assertEquals(200, resp.statusCode())
            assertEquals("I can't help with that request.", anthropicText(resp.body()))
            assertEquals("refusal", anthropicStopReason(resp.body()))
        }
    }

    @Test
    fun `max-tokens-cutoff returns stop_reason max_tokens and the truncated text`() {
        val truncated = """The soup recipe is {"name":"lentil soup","steps":["Rinse the lentils","Simmer until"""
        withServer { client, _ ->
            client.load("max-tokens-cutoff")
            val resp = client.post("/anthropic/v1/messages", anthropicBody("a soup recipe"))
            assertEquals(200, resp.statusCode())
            assertEquals(truncated, anthropicText(resp.body()))
            assertEquals("max_tokens", anthropicStopReason(resp.body()))
            assertFailsWith<SerializationException> { Json.parseToJsonElement(anthropicText(resp.body())) }
        }
    }

    @Test
    fun `malformed-json returns a non-parseable reply and then valid JSON`() {
        withServer { client, _ ->
            client.load("malformed-json")
            val prompt = "Respond in JSON with the city forecast."
            val first = client.post("/anthropic/v1/messages", anthropicBody(prompt))
            assertEquals(200, first.statusCode())
            val firstText = anthropicText(first.body())
            assertEquals("""Sure, the forecast follows. {"city":"Lisbon","conditions":""", firstText)
            assertFailsWith<SerializationException> { Json.parseToJsonElement(firstText) }

            val second = client.post("/anthropic/v1/messages", anthropicBody(prompt))
            assertEquals(200, second.statusCode())
            val secondText = anthropicText(second.body())
            val parsed = Json.parseToJsonElement(secondText).jsonObject
            assertEquals("Lisbon", parsed.getValue("city").jsonPrimitive.content)
            assertEquals("mild and clear", parsed.getValue("conditions").jsonPrimitive.content)
            assertEquals(
                21,
                parsed
                    .getValue("tempC")
                    .jsonPrimitive.content
                    .toInt(),
            )

            assertEquals(listOf("text", "text"), client.callLog().map { it.getValue("outcome").jsonPrimitive.content })
        }
    }

    @Test
    fun `stream-error-midway returns HTTP 529 when stream is false`() {
        withServer { client, _ ->
            client.load("stream-error-midway")
            val resp = client.post("/anthropic/v1/messages", anthropicBody("hello"))
            assertEquals(529, resp.statusCode())
            assertTrue(
                resp
                    .headers()
                    .firstValue("content-type")
                    .orElse("")
                    .startsWith("application/json"),
            )
            assertContains(resp.body(), "overloaded_error")
            assertEquals(
                "error:529",
                client
                    .callLog()
                    .single()
                    .getValue("outcome")
                    .jsonPrimitive.content,
            )
        }
    }

    @Test
    fun `stream-error-midway emits three deltas and then an error event when stream is true`() {
        withServer { client, _ ->
            client.load("stream-error-midway")
            val resp = client.post("/anthropic/v1/messages", anthropicBody("hello", stream = true))
            assertEquals(200, resp.statusCode(), resp.body())
            assertTrue(
                resp
                    .headers()
                    .firstValue("content-type")
                    .orElse("")
                    .startsWith("text/event-stream"),
            )

            val events = parseSse(resp.body())
            assertEquals("message_start", events[0].first)
            assertEquals("content_block_start", events[1].first)
            val deltas = events.drop(2).takeWhile { it.first == "content_block_delta" }
            assertEquals(errorPrefixChunks(3, 8), deltas.map { jsonAt(it.second, "delta", "text") })
            assertEquals("error", events.last().first)
            assertContains(events.last().second, "overloaded_error")
            assertTrue(events.none { it.first == "content_block_stop" })
            assertTrue(events.none { it.first == "message_delta" })
            assertTrue(events.none { it.first == "message_stop" })
            assertEquals(events.size, 2 + deltas.size + 1)

            assertEquals(
                "error:529",
                client
                    .callLog()
                    .single()
                    .getValue("outcome")
                    .jsonPrimitive.content,
            )
        }
    }

    @Test
    fun `thinking-stream emits thinking deltas before text deltas`() {
        val thinking =
            "The question is a short recipe. Keep the ingredient list small and the steps easy to follow at home."
        val text =
            "A simple tomato sauce needs olive oil, garlic, crushed tomatoes, and a pinch of salt. " +
                "Simmer it for about twenty minutes."
        withServer { client, _ ->
            client.load("thinking-stream")
            val resp =
                client.post(
                    "/anthropic/v1/messages",
                    """
                    {"model":"example-model","max_tokens":256,"stream":true,
                     "system":[{"type":"text","text":"You answer recipe questions.","cache_control":{"type":"ephemeral"}}],
                     "messages":[{"role":"user","content":"How do I make a simple tomato sauce?"}]}
                    """.trimIndent(),
                )
            assertEquals(200, resp.statusCode(), resp.body())
            assertTrue(
                resp
                    .headers()
                    .firstValue("content-type")
                    .orElse("")
                    .startsWith("text/event-stream"),
            )
            val events = parseSse(resp.body())

            val message =
                Json
                    .parseToJsonElement(events[0].second)
                    .jsonObject
                    .getValue("message")
                    .jsonObject
            val startUsage = message.getValue("usage").jsonObject
            assertEquals(
                FIXED_INPUT_TOKENS,
                startUsage
                    .getValue("input_tokens")
                    .jsonPrimitive.content
                    .toInt(),
            )
            assertEquals(
                FIXED_CACHE_CREATION_TOKENS,
                startUsage
                    .getValue("cache_creation_input_tokens")
                    .jsonPrimitive.content
                    .toInt(),
            )
            assertEquals(
                FIXED_CACHE_READ_TOKENS,
                startUsage
                    .getValue("cache_read_input_tokens")
                    .jsonPrimitive.content
                    .toInt(),
            )
            assertFalse(startUsage.containsKey("output_tokens"))

            var i = 1
            assertEquals("content_block_start", events[i].first)
            assertEquals("0", jsonAt(events[i].second, "index"))
            assertEquals("thinking", jsonAt(events[i].second, "content_block", "type"))
            i++

            val thinkingParts = mutableListOf<String>()
            while (events[i].first == "content_block_delta" && jsonAt(events[i].second, "delta", "type") == "thinking_delta") {
                assertEquals("0", jsonAt(events[i].second, "index"))
                thinkingParts += jsonAt(events[i].second, "delta", "thinking")
                i++
            }
            assertEquals(thinking, thinkingParts.joinToString(""))

            assertEquals("signature_delta", jsonAt(events[i].second, "delta", "type"))
            assertEquals(FAKE_THINKING_SIGNATURE, jsonAt(events[i].second, "delta", "signature"))
            assertEquals("0", jsonAt(events[i].second, "index"))
            i++

            assertEquals("content_block_stop", events[i].first)
            assertEquals("0", jsonAt(events[i].second, "index"))
            i++

            assertEquals("content_block_start", events[i].first)
            assertEquals("1", jsonAt(events[i].second, "index"))
            assertEquals("text", jsonAt(events[i].second, "content_block", "type"))
            i++

            val textParts = mutableListOf<String>()
            while (events[i].first == "content_block_delta") {
                assertEquals("text_delta", jsonAt(events[i].second, "delta", "type"))
                assertEquals("1", jsonAt(events[i].second, "index"))
                textParts += jsonAt(events[i].second, "delta", "text")
                i++
            }
            assertEquals(text, textParts.joinToString(""))

            assertEquals("content_block_stop", events[i].first)
            assertEquals("1", jsonAt(events[i].second, "index"))
            i++
            assertEquals("message_delta", events[i].first)
            assertEquals("end_turn", jsonAt(events[i].second, "delta", "stop_reason"))
            val deltaUsage =
                Json
                    .parseToJsonElement(events[i].second)
                    .jsonObject
                    .getValue("usage")
                    .jsonObject
            assertEquals(
                FIXED_OUTPUT_TOKENS,
                deltaUsage
                    .getValue("output_tokens")
                    .jsonPrimitive.content
                    .toInt(),
            )
            assertFalse(deltaUsage.containsKey("input_tokens"))
            i++
            assertEquals("message_stop", events[i].first)
            assertEquals(events.lastIndex, i)

            assertEquals(
                "text",
                client
                    .callLog()
                    .single()
                    .getValue("outcome")
                    .jsonPrimitive.content,
            )
        }
    }

    @Test
    fun `fail-then-retry returns 500 and then a normal reply`() {
        withServer { client, _ ->
            client.load("fail-then-retry")
            val failed = client.post("/anthropic/v1/messages", anthropicBody("What does the week look like?"))
            assertEquals(500, failed.statusCode())
            assertContains(failed.body(), "api_error")

            val success = client.post("/anthropic/v1/messages", anthropicBody("What does the week look like?"))
            assertEquals(200, success.statusCode())
            assertEquals("Tomorrow looks mild and dry. A pot of lentil soup takes about forty minutes.", anthropicText(success.body()))
            assertEquals(listOf("error:500", "text"), client.callLog().map { it.getValue("outcome").jsonPrimitive.content })
        }
    }

    private fun parseSse(body: String): List<Pair<String, String>> =
        body.split("\n\n").filter { it.isNotBlank() }.map { frame ->
            val lines = frame.lines()
            val event = lines.first { it.startsWith("event: ") }.removePrefix("event: ")
            val data = lines.first { it.startsWith("data: ") }.removePrefix("data: ")
            event to data
        }

    private fun jsonAt(
        data: String,
        vararg path: String,
    ): String {
        var element: JsonElement = Json.parseToJsonElement(data)
        path.forEach { key -> element = element.jsonObject.getValue(key) }
        return element.jsonPrimitive.content
    }
}
