package com.davidjonesgriz.fakellm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.net.Socket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExchangeLogTest {
    private val serverJson = Json { encodeDefaults = true }

    private fun engineWith(vararg steps: Step) =
        ScenarioEngine().also { it.loadInline(Scenario(name = "exchanges", steps = steps.toList())) }

    private fun JsonObject.obj(key: String) = getValue(key).jsonObject

    private fun JsonObject.str(key: String) = getValue(key).jsonPrimitive.content

    private fun headersOf(o: JsonObject): Map<String, List<String>> = o.mapValues { (_, v) -> v.jsonArray.map { it.jsonPrimitive.content } }

    private val openAi = """{"model":"example-gpt","messages":[{"role":"user","content":"Hi"}]}"""
    private val openAiStream = """{"model":"example-gpt","stream":true,"messages":[{"role":"user","content":"Hi"}]}"""
    private val gemini = """{"contents":[{"role":"user","parts":[{"text":"Hi"}]}]}"""
    private val streamHeaders =
        mapOf("cache-control" to listOf("no-cache"), "connection" to listOf("keep-alive"), "content-type" to listOf("text/event-stream"))

    @Test
    fun `a buffered call records the request as received and the response as sent`() {
        withServer(engineWith(Step(text = "Hi."))) { client, _ ->
            val sent = """{ "messages":[{"role":"user","content":"Hello"}], "model" : "example-model", "max_tokens": 64 }"""
            val http = client.post("/anthropic/v1/messages", sent, headers = mapOf("x-api-key" to "secret-key", "x-trace-id" to "t1"))
            val entry = exchanges(client).single().jsonObject
            assertEquals(1, entry.getValue("seq").jsonPrimitive.long)
            val request = entry.obj("request")
            assertEquals("POST", request.str("method"))
            assertEquals("/anthropic/v1/messages", request.str("path"))
            assertEquals(sent, request.str("body"))
            val headers = headersOf(request.obj("headers"))
            assertEquals(listOf(REDACTED), headers["x-api-key"])
            assertEquals(listOf("t1"), headers["x-trace-id"])
            val response = entry.obj("response")
            assertEquals(
                200,
                response
                    .getValue("status")
                    .jsonPrimitive.long
                    .toInt(),
            )
            assertEquals(mapOf("content-type" to listOf("application/json")), headersOf(response.obj("headers")))
            assertEquals(http.body(), response.str("body"))
            assertEquals("complete", response.str("state"))
            assertFalse("secret-key" in client.get("/control/exchanges").body())
        }
    }

    @Test
    fun `an authorization header and a key query parameter are recorded redacted`() {
        withServer(engineWith(Step(text = "a"), Step(text = "b"))) { client, _ ->
            client.post("/openai/v1/chat/completions", openAi, authorization = "Bearer sk-test")
            client.post("/gemini/v1beta/models/example-gemini:generateContent?key=secret-key", gemini)
            val (first, second) = exchanges(client).map { it.jsonObject.obj("request") }
            assertEquals(listOf(REDACTED), headersOf(first.obj("headers"))["authorization"])
            assertEquals("/gemini/v1beta/models/example-gemini:generateContent?key=[redacted]", second.str("path"))
            val text = client.get("/control/exchanges").body()
            assertFalse("sk-test" in text)
            assertFalse("secret-key" in text)
        }
    }

    @Test
    fun `a streamed call records every byte written, in order`() {
        withServer(engineWith(Step(text = "Mild and dry tomorrow.", streamChunkChars = 8))) { client, _ ->
            val http = client.post("/openai/v1/chat/completions", openAiStream)
            val response = exchanges(client).single().jsonObject.obj("response")
            assertEquals(http.body(), response.str("body"))
            assertTrue(response.str("body").endsWith("data: [DONE]\n\n"))
            assertEquals(streamHeaders, headersOf(response.obj("headers")))
            assertEquals("complete", response.str("state"))
        }
    }

    @Test
    fun `a stream error midway is recorded up to and including the error frame`() {
        val step =
            Step(error = ErrorStep(500, """{"type":"error","error":{"type":"api_error","message":"x"}}"""), streamErrorAfterChunks = 2)
        withServer(engineWith(step)) { client, _ ->
            val http = client.post("/anthropic/v1/messages", anthropicBody("Hi", stream = true))
            val response = exchanges(client).single().jsonObject.obj("response")
            assertEquals(http.body(), response.str("body"))
            assertEquals("error", namedFrames(response.str("body")).last().first)
            assertEquals(
                200,
                response
                    .getValue("status")
                    .jsonPrimitive.long
                    .toInt(),
            )
            assertEquals("complete", response.str("state"))
        }
    }

    @Test
    fun `a scripted error records its status, scripted headers and body`() {
        val step = Step(error = ErrorStep(429, "oops", mapOf("content-type" to "text/plain", "retry-after" to "1")))
        withServer(engineWith(step)) { client, _ ->
            client.post("/openai/v1/chat/completions", openAi)
            val response = exchanges(client).single().jsonObject.obj("response")
            assertEquals(
                429,
                response
                    .getValue("status")
                    .jsonPrimitive.long
                    .toInt(),
            )
            assertEquals(mapOf("content-type" to listOf("text/plain"), "retry-after" to listOf("1")), headersOf(response.obj("headers")))
            assertEquals("oops", response.str("body"))
        }
    }

    @Test
    fun `the no-more-steps and no-scenario 400s are recorded as calls`() {
        withServer(engineWith(Step(text = "only"))) { client, engine ->
            client.post("/anthropic/v1/messages", anthropicBody("Hi"))
            val second = client.post("/anthropic/v1/messages", anthropicBody("Hi", stream = true))
            val entry = exchanges(client)[1].jsonObject
            assertEquals(2, entry.getValue("seq").jsonPrimitive.long)
            val response = entry.obj("response")
            assertEquals(
                400,
                response
                    .getValue("status")
                    .jsonPrimitive.long
                    .toInt(),
            )
            assertEquals(second.body(), response.str("body"))
            assertContains(response.str("body"), "no more steps")
            assertEquals(mapOf("content-type" to listOf("application/json")), headersOf(response.obj("headers")))
            engine.unload()
            val third = client.post("/anthropic/v1/messages", anthropicBody("Hi"))
            val noScenario = exchanges(client).single().jsonObject
            assertEquals(1, noScenario.getValue("seq").jsonPrimitive.long)
            assertEquals(400, third.statusCode())
            assertEquals(
                400,
                noScenario
                    .obj("response")
                    .getValue("status")
                    .jsonPrimitive.int,
            )
            assertEquals(mapOf("content-type" to listOf("application/json")), headersOf(noScenario.obj("response").obj("headers")))
            assertEquals(third.body(), noScenario.obj("response").str("body"))
            assertContains(third.body(), "no scenario loaded")
        }
    }

    @Test
    fun `lenient mode records the canned reply`() {
        withServer(ScenarioEngine(lenient = true)) { client, _ ->
            val http = client.post("/anthropic/v1/messages", anthropicBody("Hi"))
            val entry = exchanges(client).single().jsonObject
            assertEquals(1, entry.getValue("seq").jsonPrimitive.long)
            val response = entry.obj("response")
            assertEquals("complete", response.str("state"))
            assertEquals(
                200,
                response
                    .getValue("status")
                    .jsonPrimitive.long
                    .toInt(),
            )
            assertEquals(http.body(), response.str("body"))
        }
    }

    @Test
    fun `requests that are not calls are not recorded`() {
        val raw = Step(toolCalls = listOf(ToolCall(name = "f", rawArguments = "{")))
        withServer(engineWith(raw)) { client, _ ->
            assertEquals(400, client.post("/anthropic/v1/messages", "{").statusCode())
            assertEquals(400, client.post("/anthropic/v1/messages", """{"model":"m"}""").statusCode())
            assertEquals(400, client.post("/gemini/v1beta/models/m:streamGenerateContent", gemini).statusCode())
            assertEquals(400, client.post("/gemini/v1beta/models/m:generateContent", gemini).statusCode())
            assertEquals(404, client.post("/nope", "{}").statusCode())
            assertEquals(501, client.get("/anthropic/v1/messages/batches").statusCode())
            client.get("/health")
            client.get("/control/calls")
            assertEquals(JsonArray(emptyList()), exchanges(client))
            assertEquals(200, client.post("/openai/v1/chat/completions", openAi).statusCode())
            assertEquals(
                1,
                exchanges(client)
                    .single()
                    .jsonObject
                    .getValue("seq")
                    .jsonPrimitive.long,
            )
        }
    }

    @Test
    fun `exchanges match the call log one to one across the four routes`() {
        withServer(engineWith(*Array(4) { Step(text = "x") })) { client, _ ->
            val paths =
                listOf(
                    "/anthropic/v1/messages" to anthropicBody("Hi"),
                    "/openai/v1/chat/completions" to openAiStream,
                    "/gemini/v1beta/models/example-gemini:streamGenerateContent?alt=sse" to gemini,
                    "/openai/v1/responses" to responsesBody(),
                )
            paths.forEach { (p, b) -> assertEquals(200, client.post(p, b).statusCode()) }
            val entries = exchanges(client).map { it.jsonObject }
            val calls = Json.parseToJsonElement(client.get("/control/calls").body()).jsonArray.map { it.jsonObject }
            assertEquals((1L..4L).toList(), entries.map { it.getValue("seq").jsonPrimitive.long })
            assertEquals(calls.map { it.getValue("seq").jsonPrimitive.long }, entries.map { it.getValue("seq").jsonPrimitive.long })
            assertEquals(paths.map { it.first }, entries.map { it.obj("request").str("path") })
        }
    }

    @Test
    fun `load, reset and unload empty the exchange log`() {
        val scenario = Scenario(name = "s", steps = listOf(Step(text = "a"), Step(text = "b")))
        withServer(ScenarioEngine(lenient = true)) { client, engine ->
            listOf<() -> Unit>({ engine.loadInline(scenario) }, { engine.reset() }, { engine.unload() }).forEach { clear ->
                client.post("/anthropic/v1/messages", anthropicBody("Hi"))
                clear()
                assertEquals(JsonArray(emptyList()), exchanges(client))
                client.post("/anthropic/v1/messages", anthropicBody("Hi"))
                assertEquals(
                    1,
                    exchanges(client)
                        .single()
                        .jsonObject
                        .getValue("seq")
                        .jsonPrimitive.long,
                )
                clear()
            }
        }
    }

    @Test
    fun `each concurrent call records its own request and response`() {
        withServer(engineWith(*Array(8) { Step(text = "t$it") })) { client, _ ->
            val pool = Executors.newFixedThreadPool(8)
            try {
                pool
                    .invokeAll((1..8).map { i -> Callable { client.post("/anthropic/v1/messages", anthropicBody("p$i")) } })
                    .forEach { assertEquals(200, it.get().statusCode()) }
            } finally {
                pool.shutdown()
            }
            val calls = Json.parseToJsonElement(client.get("/control/calls").body()).jsonArray.map { it.jsonObject }
            val entries = exchanges(client).map { it.jsonObject }
            assertEquals(8, entries.size)
            entries.zip(calls).forEach { (entry, call) ->
                val seq = entry.getValue("seq").jsonPrimitive.long
                assertEquals(call.getValue("seq").jsonPrimitive.long, seq)
                assertContains(entry.obj("request").str("body"), call.str("promptSample").trimEnd())
                assertContains(entry.obj("response").str("body"), "fake-msg-$seq")
            }
        }
    }

    @Test
    fun `an exchange read while its stream is being written is open with the bytes written so far`() {
        val step = Step(text = "a".repeat(40), streamChunkChars = 1, streamInterChunkDelayMs = 50)
        withServer(engineWith(step)) { client, engine ->
            val http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()
            val response =
                http
                    .sendAsync(
                        HttpRequest
                            .newBuilder(URI("http://127.0.0.1:${client.port}/anthropic/v1/messages"))
                            .POST(HttpRequest.BodyPublishers.ofString(anthropicBody("Hi", stream = true)))
                            .build(),
                        HttpResponse.BodyHandlers.ofInputStream(),
                    ).get()
            response.body().use { input ->
                val reader = input.bufferedReader()
                val firstLines = generateSequence { reader.readLine() }.takeWhile { it.isNotEmpty() }.toList()
                assertTrue(firstLines.isNotEmpty())
                val open = exchanges(client).single().jsonObject.obj("response")
                assertEquals("open", open.str("state"))
                val prefix = open.str("body")
                val rest = reader.readText()
                val whole = engine.exchanges().single().response!!
                assertEquals("complete", whole.state)
                assertTrue(prefix.isNotEmpty() && whole.body.startsWith(prefix) && prefix.length < whole.body.length)
                assertEquals(firstLines.joinToString("\n") + "\n\n" + rest, whole.body)
            }
        }
    }

    @Test
    fun `a client that disconnects mid-stream leaves an aborted exchange with the bytes written before the failure`() {
        val step = Step(text = "a".repeat(2000), streamChunkChars = 1, streamInterChunkDelayMs = 5)
        withServer(engineWith(step)) { client, engine ->
            val body = anthropicBody("Hi", stream = true).toByteArray(StandardCharsets.UTF_8)
            Socket("127.0.0.1", client.port).use { socket ->
                socket.getOutputStream().write(
                    (
                        "POST /anthropic/v1/messages HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Type: application/json\r\n" +
                            "Content-Length: ${body.size}\r\n\r\n"
                    ).toByteArray(StandardCharsets.UTF_8) + body,
                )
                socket.getInputStream().read(ByteArray(512))
                socket.setSoLinger(true, 0)
            }
            val deadline = System.nanoTime() + 5_000_000_000
            while (engine
                    .exchanges()
                    .single()
                    .response
                    ?.state != "aborted" && System.nanoTime() < deadline
            ) {
                Thread.sleep(50)
            }
            val response = engine.exchanges().single().response!!
            assertEquals("aborted", response.state)
            assertTrue(response.body.startsWith("event: message_start"))
            assertFalse("message_stop" in response.body)
            assertEquals(1, engine.calls().size)
        }
    }

    @Test
    fun `a client that resets during firstByteDelayMs leaves an aborted exchange`() {
        withServer(engineWith(Step(text = "late", firstByteDelayMs = 500))) { client, engine ->
            val body = anthropicBody("Hi", stream = true).toByteArray(StandardCharsets.UTF_8)
            Socket("127.0.0.1", client.port).use { socket ->
                socket.getOutputStream().write(
                    (
                        "POST /anthropic/v1/messages HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Type: application/json\r\n" +
                            "Content-Length: ${body.size}\r\n\r\n"
                    ).toByteArray(StandardCharsets.UTF_8) + body,
                )
                val called = System.nanoTime() + 5_000_000_000
                while (engine.calls().isEmpty() && System.nanoTime() < called) Thread.sleep(10)
                assertEquals(1, engine.calls().size)
                socket.setSoLinger(true, 0)
            }
            val deadline = System.nanoTime() + 5_000_000_000
            while (engine
                    .exchanges()
                    .single()
                    .response
                    ?.state != "aborted" && System.nanoTime() < deadline
            ) {
                Thread.sleep(50)
            }
            val response = engine.exchanges().single().response!!
            assertEquals("aborted", response.state)
            assertEquals(200, response.status)
            assertEquals("", response.body)
        }
    }

    @Test
    fun `a server stopped mid-stream leaves an aborted exchange`() {
        val engine = engineWith(Step(text = "a".repeat(2000), streamChunkChars = 1, streamInterChunkDelayMs = 5))
        val server = startFakeLlmServer(port = 0, engine = engine, controlToken = null)
        val body = anthropicBody("Hi", stream = true).toByteArray(StandardCharsets.UTF_8)
        Socket("127.0.0.1", server.port).use { socket ->
            socket.getOutputStream().write(
                (
                    "POST /anthropic/v1/messages HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Type: application/json\r\n" +
                        "Content-Length: ${body.size}\r\n\r\n"
                ).toByteArray(StandardCharsets.UTF_8) + body,
            )
            socket.getInputStream().read(ByteArray(512))
            server.close()
        }
        val deadline = System.nanoTime() + 5_000_000_000
        while (engine
                .exchanges()
                .single()
                .response
                ?.state != "aborted" && System.nanoTime() < deadline
        ) {
            Thread.sleep(50)
        }
        val response = engine.exchanges().single().response!!
        assertEquals("aborted", response.state)
        assertTrue(response.body.startsWith("event: message_start"))
        assertFalse("message_stop" in response.body)
    }

    @Test
    fun `a server stopped during firstByteDelayMs leaves an aborted exchange`() {
        val engine = engineWith(Step(text = "late", firstByteDelayMs = 10_000))
        val server = startFakeLlmServer(port = 0, engine = engine, controlToken = null)
        val body = anthropicBody("Hi", stream = true).toByteArray(StandardCharsets.UTF_8)
        Socket("127.0.0.1", server.port).use { socket ->
            socket.getOutputStream().write(
                (
                    "POST /anthropic/v1/messages HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Type: application/json\r\n" +
                        "Content-Length: ${body.size}\r\n\r\n"
                ).toByteArray(StandardCharsets.UTF_8) + body,
            )
            val called = System.nanoTime() + 5_000_000_000
            while (engine.calls().isEmpty() && System.nanoTime() < called) Thread.sleep(10)
            assertEquals(1, engine.calls().size)
            server.close()
        }
        val deadline = System.nanoTime() + 5_000_000_000
        while (engine
                .exchanges()
                .single()
                .response
                ?.state != "aborted" && System.nanoTime() < deadline
        ) {
            Thread.sleep(50)
        }
        val response = engine.exchanges().single().response!!
        assertEquals("aborted", response.state)
        assertEquals(400, response.status)
        assertEquals("", response.body)
    }

    @Test
    fun `a call waiting out firstByteDelayMs is listed with a null response`() {
        withServer(engineWith(Step(text = "x", firstByteDelayMs = 3000))) { client, engine ->
            val http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()
            val future =
                http.sendAsync(
                    HttpRequest
                        .newBuilder(URI("http://127.0.0.1:${client.port}/anthropic/v1/messages"))
                        .POST(HttpRequest.BodyPublishers.ofString(anthropicBody("Hi")))
                        .build(),
                    HttpResponse.BodyHandlers.ofString(),
                )
            val deadline = System.nanoTime() + 900_000_000
            while (engine.calls().isEmpty() && System.nanoTime() < deadline) Thread.sleep(10)
            val waiting = exchanges(client).single().jsonObject
            assertFalse(future.isDone)
            assertEquals(1, waiting.getValue("seq").jsonPrimitive.long)
            assertEquals(anthropicBody("Hi"), waiting.obj("request").str("body"))
            assertEquals(JsonNull, waiting.getValue("response"))
            assertEquals(200, future.get().statusCode())
            val done = engine.exchanges().single().response!!
            assertEquals(200, done.status)
            assertEquals("complete", done.state)
        }
    }

    @Test
    fun `the engine and the endpoint return the same exchanges`() {
        withServer(engineWith(Step(text = "a"), Step(text = "b"))) { client, engine ->
            client.post("/anthropic/v1/messages", anthropicBody("Hi"))
            client.post("/openai/v1/chat/completions", openAiStream)
            assertEquals(serverJson.encodeToString(engine.exchanges()), client.get("/control/exchanges").body())
        }
    }
}
