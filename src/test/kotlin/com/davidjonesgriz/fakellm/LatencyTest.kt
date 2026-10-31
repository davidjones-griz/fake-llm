package com.davidjonesgriz.fakellm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.net.Socket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.nio.charset.StandardCharsets
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LatencyTest {
    private fun engineWith(vararg steps: Step) = ScenarioEngine().also { it.loadInline(Scenario(name = "latency", steps = steps.toList())) }

    private fun timedMs(block: () -> Unit): Long {
        val started = System.nanoTime()
        block()
        return (System.nanoTime() - started) / 1_000_000
    }

    private fun ScenarioEngine.awaitCalls(count: Int) {
        val deadline = System.nanoTime() + 5_000_000_000
        while (calls().size < count && System.nanoTime() < deadline) Thread.sleep(10)
        assertEquals(count, calls().size)
    }

    /** The element at [path] (object keys and array indexes), or null when any part is missing. */
    private fun JsonElement.at(vararg path: Any): JsonElement? =
        path.fold(this as JsonElement?) { e, k -> if (k is Int) (e as? JsonArray)?.getOrNull(k) else (e as? JsonObject)?.get(k as String) }

    private fun JsonElement?.text(): String = (this as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()

    /** The reply text of a buffered response from [path]. */
    private fun bufferedText(
        path: String,
        body: String,
    ): String {
        val json = Json.parseToJsonElement(body)
        return when {
            path.startsWith("/anthropic") -> anthropicText(body)
            path.endsWith("/chat/completions") -> json.at("choices", 0, "message", "content").text()
            path.endsWith("/responses") -> json.at("output", 0, "content", 0, "text").text()
            else -> json.at("candidates", 0, "content", "parts", 0, "text").text()
        }
    }

    /** The text pieces of a stream from [path], joined. */
    private fun streamedText(
        path: String,
        body: String,
    ): String =
        when {
            path.startsWith("/anthropic") -> {
                namedFrames(body).filter { it.first == "content_block_delta" }.joinToString("") {
                    Json.parseToJsonElement(it.second).at("delta", "text").text()
                }
            }

            path.endsWith("/chat/completions") -> {
                dataFrames(body).filter { it != "[DONE]" }.joinToString("") {
                    Json.parseToJsonElement(it).at("choices", 0, "delta", "content").text()
                }
            }

            path.endsWith("/responses") -> {
                namedFrames(body).filter { it.first == "response.output_text.delta" }.joinToString("") {
                    Json.parseToJsonElement(it.second).at("delta").text()
                }
            }

            else -> {
                dataFrames(body).joinToString("") { Json.parseToJsonElement(it).at("candidates", 0, "content", "parts", 0, "text").text() }
            }
        }

    private val openAi = """{"model":"example-gpt","messages":[{"role":"user","content":"Hi"}]}"""
    private val openAiStream = """{"model":"example-gpt","stream":true,"messages":[{"role":"user","content":"Hi"}]}"""

    @Test
    fun `firstByteDelayMs delays a buffered reply on every vendor route`() {
        withServer(engineWith(*Array(4) { Step(text = "late", firstByteDelayMs = 200) })) { client, _ ->
            listOf(
                "/anthropic/v1/messages" to anthropicBody("Hi"),
                "/openai/v1/chat/completions" to openAi,
                "/openai/v1/responses" to responsesBody(),
                "/gemini/v1beta/models/m:generateContent" to """{"contents":[{"role":"user","parts":[{"text":"Hi"}]}]}""",
            ).forEach { (path, body) ->
                lateinit var response: HttpResponse<String>
                val ms = timedMs { response = client.post(path, body) }
                assertEquals(200, response.statusCode())
                assertEquals("late", bufferedText(path, response.body()), path)
                assertTrue(ms >= 200, "$path took $ms ms")
            }
        }
    }

    @Test
    fun `a call is numbered and logged when it arrives, before its first-byte wait`() {
        withServer(engineWith(Step(text = "x", firstByteDelayMs = 2000))) { client, engine ->
            val http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()
            val future =
                http.sendAsync(
                    HttpRequest
                        .newBuilder(URI("http://127.0.0.1:${client.port}/anthropic/v1/messages"))
                        .POST(HttpRequest.BodyPublishers.ofString(anthropicBody("Hi")))
                        .build(),
                    HttpResponse.BodyHandlers.ofString(),
                )
            val deadline = System.nanoTime() + 1_500_000_000
            while (engine.calls().isEmpty() && System.nanoTime() < deadline) Thread.sleep(10)
            assertEquals(1, engine.calls().size)
            assertFalse(future.isDone)
            assertEquals(1L, engine.calls().single().seq)
            assertEquals(2000L, engine.calls().single().firstByteDelayMs)
            assertEquals(200, future.get().statusCode())
        }
    }

    @Test
    fun `a client that times out during firstByteDelayMs leaves the server serving the next call`() {
        withServer(
            engineWith(
                Step(text = "slow", firstByteDelayMs = 1500),
                Step(text = "slow", firstByteDelayMs = 1500, streamInterChunkDelayMs = 10),
                Step(text = "fast"),
            ),
        ) { client, engine ->
            assertFailsWith<HttpTimeoutException> {
                client.post("/anthropic/v1/messages", anthropicBody("Hi"), timeout = Duration.ofMillis(200))
            }
            engine.awaitCalls(1)
            assertFailsWith<HttpTimeoutException> {
                client.post("/anthropic/v1/messages", anthropicBody("Hi", stream = true), timeout = Duration.ofMillis(200))
            }
            engine.awaitCalls(2)
            val third = client.post("/anthropic/v1/messages", anthropicBody("Hi"))
            assertEquals(200, third.statusCode())
            assertEquals("fast", anthropicText(third.body()))
            Thread.sleep(1800)
            assertEquals(listOf(1L, 2L, 3L), engine.calls().map { it.seq })
            assertEquals(
                listOf(1500L to 0L, 1500L to 10L, 0L to 0L),
                engine.calls().map { it.firstByteDelayMs to it.streamInterChunkDelayMs },
            )
            assertEquals(200, client.get("/health").statusCode())
        }
    }

    @Test
    fun `a client that disconnects between chunks leaves the server serving the next call`() {
        withServer(
            engineWith(Step(text = "abcdef", streamChunkChars = 1, streamInterChunkDelayMs = 200), Step(text = "fast")),
        ) { client, engine ->
            Socket("127.0.0.1", client.port).use { socket ->
                val body = openAiStream.toByteArray(StandardCharsets.UTF_8)
                val head =
                    "POST /openai/v1/chat/completions HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Type: application/json\r\n" +
                        "Content-Length: ${body.size}\r\n\r\n"
                socket.getOutputStream().apply {
                    write(head.toByteArray(StandardCharsets.US_ASCII))
                    write(body)
                    flush()
                }
                assertTrue(socket.getInputStream().read() >= 0)
            }
            Thread.sleep(1500)
            val next = client.post("/openai/v1/chat/completions", openAi)
            assertEquals(200, next.statusCode())
            assertTrue(next.body().contains("\"fast\""))
            assertEquals(2, engine.calls().size)
        }
    }

    @Test
    fun `a malformed chunked request body still gets the exception 400`() {
        withServer(engineWith(Step(text = "unused"))) { client, engine ->
            val reply =
                Socket("127.0.0.1", client.port).use { socket ->
                    val head =
                        "POST /openai/v1/chat/completions HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Type: application/json\r\n" +
                            "Transfer-Encoding: chunked\r\n\r\nzz\r\nabc\r\n0\r\n\r\n"
                    socket.getOutputStream().apply {
                        write(head.toByteArray(StandardCharsets.US_ASCII))
                        flush()
                    }
                    socket.soTimeout = 5000
                    // The server keeps the connection open, so read the one response: head, then its body.
                    val input = socket.getInputStream()
                    val text = StringBuilder()
                    while (!text.endsWith("\r\n\r\n")) text.append(input.read().toChar())
                    val length = Regex("(?i)content-length: (\\d+)").find(text)!!.groupValues[1].toInt()
                    text.append(input.readNBytes(length).decodeToString()).toString()
                }
            assertTrue(reply.startsWith("HTTP/1.1 400"), reply)
            assertTrue(reply.endsWith("""{"fake_llm_error":"exception","message":"invalid chunk length"}"""), reply)
            assertEquals(0, engine.calls().size)
        }
    }

    @Test
    fun `firstByteDelayMs delays the start of a stream on every vendor route`() {
        withServer(engineWith(*Array(4) { Step(text = "late", firstByteDelayMs = 200) })) { client, _ ->
            listOf(
                "/anthropic/v1/messages" to anthropicBody("Hi", stream = true),
                "/openai/v1/chat/completions" to openAiStream,
                "/openai/v1/responses" to responsesBody(stream = true),
                "/gemini/v1beta/models/m:streamGenerateContent?alt=sse" to """{"contents":[{"role":"user","parts":[{"text":"Hi"}]}]}""",
            ).forEach { (path, body) ->
                lateinit var response: HttpResponse<String>
                val ms = timedMs { response = client.post(path, body) }
                assertEquals(200, response.statusCode())
                assertTrue(
                    response
                        .headers()
                        .firstValue("content-type")
                        .get()
                        .startsWith("text/event-stream"),
                )
                assertEquals("late", streamedText(path, response.body()), path)
                assertTrue(ms >= 200, "$path took $ms ms")
            }
        }
    }

    @Test
    fun `firstByteDelayMs delays an error step, buffered, streamed and in-stream`() {
        val e429 = ErrorStep(429, """{"error":{"message":"slow down"}}""", mapOf("retry-after" to "1"))
        withServer(
            engineWith(
                Step(error = e429, firstByteDelayMs = 200),
                Step(error = e429, firstByteDelayMs = 200),
                Step(error = ErrorStep(500, """{"error":{"message":"boom"}}"""), streamErrorAfterChunks = 1, firstByteDelayMs = 200),
            ),
        ) { client, _ ->
            lateinit var r: HttpResponse<String>
            assertTrue(timedMs { r = client.post("/openai/v1/chat/completions", openAi) } >= 200)
            assertEquals(429, r.statusCode())
            assertEquals("1", r.headers().firstValue("retry-after").get())
            assertTrue(timedMs { r = client.post("/anthropic/v1/messages", anthropicBody("Hi", stream = true)) } >= 200)
            assertEquals(429, r.statusCode())
            assertEquals("application/json", r.headers().firstValue("content-type").get())
            assertTrue(timedMs { r = client.post("/openai/v1/responses", responsesBody(stream = true)) } >= 200)
            assertEquals(200, r.statusCode())
            assertEquals("error", namedFrames(r.body()).last().first)
        }
    }

    @Test
    fun `other calls are served while one waits`() {
        withServer(engineWith(Step(text = "slow", firstByteDelayMs = 1500), Step(text = "fast"))) { client, engine ->
            val http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()
            val future =
                http.sendAsync(
                    HttpRequest
                        .newBuilder(URI("http://127.0.0.1:${client.port}/anthropic/v1/messages"))
                        .POST(HttpRequest.BodyPublishers.ofString(anthropicBody("Hi")))
                        .build(),
                    HttpResponse.BodyHandlers.ofString(),
                )
            engine.awaitCalls(1)
            assertEquals("fast", anthropicText(client.post("/anthropic/v1/messages", anthropicBody("Hi")).body()))
            assertFalse(future.isDone)
            assertEquals("slow", anthropicText(future.get().body()))
        }
    }

    @Test
    fun `streamInterChunkDelayMs waits before each anthropic text delta`() {
        withServer(engineWith(Step(text = "abcdefghi", streamChunkChars = 3, streamInterChunkDelayMs = 100))) { client, _ ->
            var body = ""
            val ms = timedMs { body = client.post("/anthropic/v1/messages", anthropicBody("Hi", stream = true)).body() }
            assertEquals(3, namedFrames(body).count { it.second.contains("\"text_delta\"") })
            assertTrue(ms >= 300, "took $ms ms")
        }
    }
}
