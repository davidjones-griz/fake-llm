package com.davidjonesgriz.fakellm

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.SerializationException
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.http.HttpTimeoutException
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FakeLlmClientTest {
    @Test
    fun `loads, reads typed calls and resets over HTTP`() {
        withClient { client, server ->
            assertEquals(FakeLlmHealth(scenario = null, lenient = false), client.health())

            client.load("hello")
            assertEquals("hello", client.health().scenario)

            TestClient(server.port).post("/anthropic/v1/messages", anthropicBody("hi"))
            val call = client.calls().single()
            assertEquals("anthropic", call.vendor)
            assertEquals("assistant", call.role)
            assertEquals("text", call.outcome)
            assertEquals(1, call.occurrence)

            client.reset()
            assertTrue(client.calls().isEmpty())
            assertEquals("hello", client.health().scenario)
        }
    }

    @Test
    fun `loadInline sends only the fields that are set`() {
        val scenario = Scenario(name = "inline", rules = listOf(Rule(role = "r", respond = listOf(Step(text = "from the client")))))
        // No "description", "match", "stopReason" or other defaults that a server without those fields would reject.
        assertEquals(
            """{"scenario":{"name":"inline","rules":[{"role":"r","respond":[{"text":"from the client"}]}]}}""",
            encodeLoadInline(scenario),
        )
    }

    @Test
    fun `loadInline round-trips matches, error steps, headers and stop reasons`() {
        val scenario =
            Scenario(
                name = "rich",
                rules =
                    listOf(
                        Rule(
                            role = "special",
                            match = Match(modelContains = "special", promptContainsAll = listOf("hi")),
                            respond =
                                listOf(
                                    Step(error = ErrorStep(status = 429, body = "slow down", headers = mapOf("retry-after" to "3"))),
                                    Step(text = "cut short", stopReason = "max_tokens"),
                                ),
                        ),
                    ),
            )
        withClient { client, server ->
            client.loadInline(scenario)
            val http = TestClient(server.port)

            val limited = http.post("/anthropic/v1/messages", anthropicBody("hi", model = "special-model"))
            assertEquals(429, limited.statusCode())
            assertEquals("3", limited.headers().firstValue("retry-after").orElse(null))
            assertEquals("slow down", limited.body())

            val reply = http.post("/anthropic/v1/messages", anthropicBody("hi", model = "special-model")).body()
            assertEquals("cut short", anthropicText(reply))
            assertEquals("max_tokens", anthropicStopReason(reply))

            assertEquals(400, http.post("/anthropic/v1/messages", anthropicBody("hi", model = "other")).statusCode(), "the match was sent")
        }
    }

    @Test
    fun `scenarioNames matches the server's listing`() {
        withClient { client, _ ->
            assertEquals(ScenarioLoader.availableNames(directory = null), client.scenarioNames())
        }
    }

    @Test
    fun `a configured control token is sent, and a missing or wrong one is a clear 401`() {
        withServerToken("t") { server ->
            FakeLlmClient(server.baseUrl, controlToken = "t").use { client ->
                client.load("hello")
                assertEquals("hello", client.health().scenario)
            }
            for (missingToken in listOf(null, "", "   ")) {
                FakeLlmClient(server.baseUrl, controlToken = missingToken).use { client ->
                    val missing = assertFailsWith<FakeLlmClientException> { client.calls() }
                    assertEquals(401, missing.statusCode)
                    assertContains(missing.message!!, "requires a control token", message = "token '$missingToken'")
                    assertContains(missing.serverMessage!!, "unauthorized")
                    assertEquals("hello", client.health().scenario, "health is never gated")
                }
            }
            FakeLlmClient(server.baseUrl, controlToken = "wrong").use { client ->
                val wrong = assertFailsWith<FakeLlmClientException> { client.reset() }
                assertEquals(401, wrong.statusCode)
                assertContains(wrong.message!!, "rejected the control token")
                assertContains(wrong.serverMessage!!, "unauthorized")
            }
        }
    }

    @Test
    fun `the server's error message is surfaced verbatim`() {
        withClient { client, _ ->
            val unknown = assertFailsWith<FakeLlmClientException> { client.load("no-such-scenario") }
            assertEquals(400, unknown.statusCode)
            assertContains(unknown.serverMessage!!, "unknown scenario 'no-such-scenario'")
            assertContains(unknown.message!!, "POST /control/scenario returned HTTP 400: unknown scenario")

            val invalid =
                assertFailsWith<FakeLlmClientException> {
                    client.send(
                        "POST",
                        "/control/scenario",
                        """{"scenario":{"name":"x","rules":[]}}""",
                    )
                }
            assertEquals(400, invalid.statusCode)
            assertContains(invalid.serverMessage!!, "scenario 'x' has no rules")

            val noRoute = assertFailsWith<FakeLlmClientException> { client.send("GET", "/nope") }
            assertEquals(404, noRoute.statusCode)
            assertEquals("no route: GET /nope", noRoute.serverMessage)
        }
    }

    @Test
    fun `an error body's message falls back from detail to fake_llm_error to the raw text`() {
        assertEquals("why", serverMessageOf("""{"fake_llm_error":"exception","detail":"why"}"""))
        assertEquals("exception", serverMessageOf("""{"fake_llm_error":"exception","detail":null}"""))
        assertEquals("<html>Bad Gateway</html>", serverMessageOf("<html>Bad Gateway</html>"))
        assertEquals("[1,2]", serverMessageOf("[1,2]"))
        assertEquals(500, serverMessageOf("x".repeat(2000))!!.length)
        assertNull(serverMessageOf(""))
        assertEquals(
            "GET /x returned HTTP 502: (empty response body)",
            httpFailure("GET", "/x", 502, "", tokenConfigured = false).message,
        )
    }

    @Test
    fun `an invalid base URL or timeout is rejected when the client is created`() {
        for (url in listOf(
            "localhost:8080",
            "http://local host",
            "ftp://example.com",
            "http://",
            "http://h:1/?x=1",
            "http://h:1#f",
            "",
            "http://h:99999",
            "http://fake_llm:8080",
        )) {
            assertFailsWith<IllegalArgumentException>("baseUrl '$url'") { FakeLlmClient(url) }
        }
        assertFailsWith<IllegalArgumentException> { FakeLlmClient("http://localhost:1", timeout = Duration.ZERO) }
        assertFailsWith<IllegalArgumentException> { FakeLlmClient("http://localhost:1", timeout = Duration.ofSeconds(-1)) }
        for (url in listOf("https://example.com/base/", "HTTP://LOCALHOST:8080", "http://[::1]:8080", "http://localhost")) {
            FakeLlmClient(url).close()
        }
    }

    @Test
    fun `a server that is not running is a transport failure with no status`() {
        val port = ServerSocket(0).use { it.localPort }
        FakeLlmClient("http://127.0.0.1:$port").use { client ->
            val failure = assertFailsWith<FakeLlmClientException> { client.health() }
            assertNull(failure.statusCode)
            assertIs<IOException>(failure.cause)
        }
    }

    @Test
    fun `a request that outlasts the timeout fails with the timeout as the cause`() {
        val release = CountDownLatch(1)
        withStub({ ex ->
            release.await(10, TimeUnit.SECONDS)
            respondRaw(ex, 200, "{}")
        }) { url ->
            try {
                FakeLlmClient(url, timeout = Duration.ofMillis(200)).use { client ->
                    val failure = assertFailsWith<FakeLlmClientException> { client.health() }
                    assertNull(failure.statusCode)
                    assertIs<HttpTimeoutException>(failure.cause)
                }
            } finally {
                release.countDown()
            }
        }
    }

    @Test
    fun `a 2xx response that does not decode keeps its status and the decoding error`() {
        withStub({ ex -> respondRaw(ex, 200, "not json") }) { url ->
            FakeLlmClient(url).use { client ->
                val failure = assertFailsWith<FakeLlmClientException> { client.calls() }
                assertEquals(200, failure.statusCode)
                assertNull(failure.serverMessage)
                assertContains(failure.message!!, "not json")
                assertIs<SerializationException>(failure.cause)
            }
        }
    }

    @Test
    fun `call records with fields this client does not know still decode`() {
        val record =
            """[{"seq":1,"vendor":"openai","model":"m","systemShape":"absent","messageCount":1,"role":"r",""" +
                """"occurrence":1,"outcome":"text","timestampMs":5,"promptSample":"p","addedLater":true}]"""
        withStub({ ex -> respondRaw(ex, 200, record) }) { url ->
            FakeLlmClient(url).use { client -> assertEquals("openai", client.calls().single().vendor) }
        }
    }

    @Test
    fun `an interrupt before the call throws and leaves the thread interrupted`() {
        withClient { client, _ ->
            Thread.currentThread().interrupt()
            val failure =
                try {
                    assertFailsWith<FakeLlmClientException> { client.health() }
                } finally {
                    // Clear the flag before the client closes: close() waits for requests in flight.
                    assertTrue(Thread.interrupted(), "the interrupt flag is restored")
                }
            assertIs<InterruptedException>(failure.cause)
            assertNull(failure.statusCode)
        }
    }

    @Test
    fun `an interrupt during a blocked call throws and leaves the thread interrupted`() {
        val requestArrived = CountDownLatch(1)
        val release = CountDownLatch(1)
        withStub({ ex ->
            requestArrived.countDown()
            release.await(10, TimeUnit.SECONDS)
            respondRaw(ex, 200, "{}")
        }) { url ->
            FakeLlmClient(url).use { client ->
                val caller = Thread.currentThread()
                val interrupter = Executors.newSingleThreadExecutor()
                try {
                    interrupter.submit {
                        if (requestArrived.await(10, TimeUnit.SECONDS)) caller.interrupt()
                    }
                    val failure =
                        try {
                            assertFailsWith<FakeLlmClientException> { client.health() }
                        } finally {
                            assertTrue(Thread.interrupted(), "the interrupt flag is restored")
                        }
                    assertIs<InterruptedException>(failure.cause)
                } finally {
                    release.countDown()
                    interrupter.shutdownNow()
                    interrupter.awaitTermination(10, TimeUnit.SECONDS)
                    Thread.interrupted() // a late interrupt must not leak into the next test
                }
            }
        }
    }

    @Test
    fun `trailing slashes on the base URL are ignored`() {
        startFakeLlmServer(port = 0, engine = ScenarioEngine(), controlToken = null).use { server ->
            FakeLlmClient("${server.baseUrl}//").use { client ->
                client.load("hello")
                assertEquals("hello", client.health().scenario)
                assertFalse(client.scenarioNames().isEmpty())
            }
        }
    }

    private fun withClient(block: (FakeLlmClient, FakeLlmServer) -> Unit) {
        startFakeLlmServer(port = 0, engine = ScenarioEngine(), controlToken = null).use { server ->
            FakeLlmClient(server.baseUrl).use { client -> block(client, server) }
        }
    }

    private fun withServerToken(
        token: String,
        block: (FakeLlmServer) -> Unit,
    ) {
        startFakeLlmServer(port = 0, engine = ScenarioEngine(), controlToken = token).use(block)
    }

    /** A stand-in server whose every response comes from [handler], for responses the real server never sends. */
    private fun withStub(
        handler: (HttpExchange) -> Unit,
        block: (url: String) -> Unit,
    ) {
        val stub = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        stub.createContext("/") { ex -> handler(ex) }
        val executor = Executors.newCachedThreadPool()
        stub.executor = executor
        stub.start()
        try {
            block("http://127.0.0.1:${stub.address.port}")
        } finally {
            stub.stop(0)
            executor.shutdownNow()
        }
    }

    private fun respondRaw(
        ex: HttpExchange,
        status: Int,
        body: String,
    ) {
        val bytes = body.toByteArray()
        ex.sendResponseHeaders(status, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }
}
