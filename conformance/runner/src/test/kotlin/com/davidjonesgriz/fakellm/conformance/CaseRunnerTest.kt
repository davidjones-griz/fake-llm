package com.davidjonesgriz.fakellm.conformance

import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import java.net.InetSocketAddress
import java.nio.file.Path
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** The runner's checks, against a stub server that sends exactly the response a test gives it. */
class CaseRunnerTest {
    private data class Canned(
        val status: Int,
        val body: String,
        val headers: Map<String, String> = mapOf("content-type" to "application/json"),
    )

    private data class Received(
        val method: String,
        val path: String,
        val headers: Map<String, String>,
        val body: String,
    )

    private val received: MutableList<Received> = Collections.synchronizedList(mutableListOf())

    private fun withStub(
        respond: (Received) -> Canned,
        block: (baseUrl: String) -> Unit,
    ) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            val request =
                Received(
                    ex.requestMethod,
                    ex.requestURI.toString(),
                    ex.requestHeaders.mapKeys { it.key.lowercase() }.mapValues { it.value.joinToString(", ") },
                    ex.requestBody.readBytes().decodeToString(),
                )
            received += request
            val canned = respond(request)
            canned.headers.forEach { (k, v) -> ex.responseHeaders.add(k, v) }
            val bytes = canned.body.toByteArray()
            ex.sendResponseHeaders(canned.status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            block("http://127.0.0.1:${server.address.port}")
        } finally {
            server.stop(0)
        }
    }

    private fun json(text: String): JsonElement = Json.parseToJsonElement(text)

    private fun case(
        vararg exchanges: Exchange,
        scenario: JsonElement = JsonNull,
        server: ServerSettings = ServerSettings(fresh = true),
    ) = Case("stub-case", "a case", server, scenario, exchanges.toList(), Path.of("stub-case.json"), "stub-case.json")

    private fun get(
        expected: ExpectedResponse,
        path: String = "/thing",
    ) = Exchange(CaseRequest("GET", path), expected)

    /** Runs a one-exchange case against [canned] and returns the failure message, or null when it passes. */
    private fun outcome(
        expected: ExpectedResponse,
        canned: Canned,
    ): String? {
        var message: String? = null
        withStub({ canned }) { url ->
            message = runCatching { CaseRunner(url).run(case(get(expected))) }.exceptionOrNull()?.message
        }
        return message
    }

    @Test
    fun `a matching response passes`() {
        assertEquals(null, outcome(ExpectedResponse(200, body = json("""{"a":1}""")), Canned(200, """{"a":1}""")))
    }

    @Test
    fun `a wrong status fails, naming the case, the exchange and the request`() {
        val message = outcome(ExpectedResponse(200), Canned(500, "{}"))!!
        assertContains(message, "stub-case, exchange 1 (GET /thing): expected status 200, got 500")
    }

    @Test
    fun `a wrong, extra or missing body field fails with its path`() {
        val expected = ExpectedResponse(200, body = json("""{"a":1,"b":"x"}"""))
        assertContains(outcome(expected, Canned(200, """{"a":2,"b":"x"}"""))!!, "$.a: expected 1, got 2")
        assertContains(outcome(expected, Canned(200, """{"a":1,"b":"x","c":true}"""))!!, "unexpected field 'c'")
        assertContains(outcome(expected, Canned(200, """{"a":1}"""))!!, "missing field 'b'")
        assertContains(outcome(expected, Canned(200, """{"a":1.0,"b":"x"}"""))!!, "$.a: expected 1, got 1.0")
        assertContains(outcome(expected, Canned(200, "not json"))!!, "expected a JSON body")
    }

    @Test
    fun `listed headers are checked, by any name case, and unlisted ones are ignored`() {
        val expected = ExpectedResponse(429, headers = mapOf("Retry-After" to "2", "content-type" to "application/json"))
        val headers = mapOf("retry-after" to "2", "content-type" to "application/json; charset=utf-8", "x-extra" to "1")
        assertEquals(null, outcome(expected, Canned(429, "{}", headers)))
        assertContains(
            outcome(expected, Canned(429, "{}", headers + ("retry-after" to "3")))!!,
            "header 'Retry-After': expected '2', got '3'",
        )
        assertContains(outcome(expected, Canned(429, "{}", mapOf("content-type" to "application/json")))!!, "missing header 'Retry-After'")
    }

    @Test
    fun `a raw body is compared as text`() {
        val expected = ExpectedResponse(200, rawBody = "openapi: 3.1.0\n")
        assertEquals(null, outcome(expected, Canned(200, "openapi: 3.1.0\n", mapOf("content-type" to "application/yaml"))))
        assertContains(outcome(expected, Canned(200, "openapi: 3.0.0\n"))!!, "expected the body text")
    }

    @Test
    fun `SSE events must match in order, with no missing or extra event`() {
        val stream =
            "event: message_start\ndata: {\"n\":1}\n\nevent: content_block_delta\ndata: {\"t\":\"hi\"}\n\nevent: error\ndata: plain\n\n"
        val headers = mapOf("content-type" to "text/event-stream")
        val events =
            listOf(
                ExpectedEvent("message_start", json("""{"n":1}""")),
                ExpectedEvent("content_block_delta", json("""{"t":"hi"}""")),
                ExpectedEvent("error", JsonPrimitive("plain")),
            )
        assertEquals(null, outcome(ExpectedResponse(200, events = events), Canned(200, stream, headers)))
        assertContains(
            outcome(ExpectedResponse(200, events = events + ExpectedEvent("message_stop", json("{}"))), Canned(200, stream, headers))!!,
            "missing event 4",
        )
        assertContains(
            outcome(ExpectedResponse(200, events = events.dropLast(1)), Canned(200, stream, headers))!!,
            "unexpected event 3 ('error')",
        )
        val wrongName = listOf(ExpectedEvent("ping", json("""{"n":1}"""))) + events.drop(1)
        assertContains(outcome(ExpectedResponse(200, events = wrongName), Canned(200, stream, headers))!!, "event 1: expected 'ping'")
        val wrongData = events.take(1) + ExpectedEvent("content_block_delta", json("""{"t":"ho"}""")) + events.drop(2)
        assertContains(
            outcome(ExpectedResponse(200, events = wrongData), Canned(200, stream, headers))!!,
            "event 2 ('content_block_delta') data: $.t",
        )
        val wrongText = events.dropLast(1) + ExpectedEvent("error", JsonPrimitive("other"))
        assertContains(outcome(ExpectedResponse(200, events = wrongText), Canned(200, stream, headers))!!, "expected the text 'other'")
        val jsonForText = events.dropLast(1) + ExpectedEvent("error", json("{}"))
        assertContains(outcome(ExpectedResponse(200, events = jsonForText), Canned(200, stream, headers))!!, "expected JSON, got 'plain'")
    }

    @Test
    fun `requests are sent as written, a raw body byte for byte`() {
        withStub({ Canned(200, "{}") }) { url ->
            val runner = CaseRunner(url)
            runner.run(
                case(
                    Exchange(
                        CaseRequest("POST", "/raw", mapOf("content-type" to "application/json"), rawBody = "{\"a\":"),
                        ExpectedResponse(200),
                    ),
                    Exchange(
                        CaseRequest("POST", "/json?q=1", mapOf("x-case" to "yes"), body = json("""{"b": [1, 2]}""")),
                        ExpectedResponse(200),
                    ),
                ),
            )
        }
        assertEquals("{\"a\":", received[0].body)
        assertEquals("/json?q=1", received[1].path)
        assertEquals("""{"b":[1,2]}""", received[1].body)
        assertEquals("yes", received[1].headers["x-case"])
    }

    @Test
    fun `the scenario is loaded first, by name or inline, with the case's control token`() {
        withStub({ Canned(200, """{"loaded":"x","steps":1}""") }) { url ->
            CaseRunner(
                url,
            ).run(case(get(ExpectedResponse(200)), scenario = JsonPrimitive("hello"), server = ServerSettings(controlToken = "t")))
            CaseRunner(url).run(case(get(ExpectedResponse(200)), scenario = json("""{"name":"s","steps":[{"text":"x"}]}""")))
        }
        assertEquals("/control/scenario", received[0].path)
        assertEquals("""{"name":"hello"}""", received[0].body)
        assertEquals("Bearer t", received[0].headers["authorization"])
        assertEquals("""{"scenario":{"name":"s","steps":[{"text":"x"}]}}""", received[2].body)
        assertEquals(null, received[2].headers["authorization"])
    }

    @Test
    fun `a scenario load that fails stops the case before any exchange`() {
        withStub({ if (it.path == "/control/scenario") Canned(400, """{"fake_llm_error":"exception"}""") else Canned(200, "{}") }) { url ->
            val failure =
                assertFailsWith<CaseFailedException> {
                    CaseRunner(
                        url,
                    ).run(case(get(ExpectedResponse(200)), scenario = JsonPrimitive("nope")))
                }
            assertContains(failure.message!!, "stub-case, scenario load: expected 200, got 400")
        }
        assertEquals(1, received.size)
    }

    @Test
    fun `no response at all fails the case`() {
        val failure = assertFailsWith<CaseFailedException> { CaseRunner("http://127.0.0.1:1").run(case(get(ExpectedResponse(200)))) }
        assertContains(failure.message!!, "GET /thing got no response")
    }
}
