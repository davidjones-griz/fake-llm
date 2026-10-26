package com.davidjonesgriz.fakellm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.nio.file.Files
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScenarioEngineTest {
    private fun req(
        model: String = "example-model",
        systemShape: String = "blocks",
        promptText: String = "",
        messageCount: Int = 1,
        stream: Boolean = false,
        hasCacheBreakpoint: Boolean = false,
    ) = ParsedRequest(
        model = model,
        systemShape = systemShape,
        promptText = promptText,
        messageCount = messageCount,
        stream = stream,
        hasCacheBreakpoint = hasCacheBreakpoint,
    )

    private fun textScenario(
        vararg texts: String,
        name: String = "inline",
    ): Scenario = Scenario(name = name, steps = texts.map { Step(text = it) })

    private fun engineWith(
        scenario: Scenario,
        lenient: Boolean = false,
    ): ScenarioEngine = ScenarioEngine(lenient = lenient).also { it.loadInline(scenario) }

    private fun ScenarioEngine.text(vendor: String = "anthropic"): String = anthropicText(handle(vendor, req()).body)

    private fun json(body: String): JsonObject = Json.parseToJsonElement(body).jsonObject

    @Test
    fun `call N gets step N, and a call after the last step gets the no-more-steps 400`() {
        val engine = engineWith(textScenario("one", "two", "three"))
        assertEquals(listOf("one", "two", "three"), (1..3).map { engine.text() })

        val prompt = "line\n\"quoted\" " + "p".repeat(250)
        val fourth =
            engine.handle(
                "anthropic",
                req(model = "example-model", systemShape = "string", promptText = prompt, messageCount = 2),
            )
        assertEquals(400, fourth.status)
        val body = json(fourth.body)
        assertEquals("no more steps", body.getValue("fake_llm_error").jsonPrimitive.content)
        assertEquals("inline", body.getValue("scenario").jsonPrimitive.content)
        assertEquals(
            3,
            body
                .getValue("steps")
                .jsonPrimitive.content
                .toInt(),
        )
        assertEquals(4L, body.getValue("seq").jsonPrimitive.long)
        assertEquals("anthropic", body.getValue("vendor").jsonPrimitive.content)
        assertEquals("example-model", body.getValue("model").jsonPrimitive.content)
        assertEquals("string", body.getValue("system_shape").jsonPrimitive.content)
        assertEquals(
            2,
            body
                .getValue("message_count")
                .jsonPrimitive.content
                .toInt(),
        )
        assertEquals(prompt.take(200), body.getValue("prompt_preview").jsonPrimitive.content)

        val calls = engine.calls()
        assertEquals(listOf(1L, 2L, 3L, 4L), calls.map { it.seq })
        assertEquals(listOf(1, 2, 3, null), calls.map { it.step })
        assertEquals(listOf("text", "text", "text", "no-more-steps"), calls.map { it.outcome })
    }

    @Test
    fun `the last step does not repeat`() {
        val engine = engineWith(textScenario("only"))
        assertEquals("only", engine.text())
        assertEquals(400, engine.handle("anthropic", req()).status)
    }

    @Test
    fun `the vendor routes share one sequence of steps`() {
        val engine = engineWith(textScenario("first", "second", "third"))
        val openai = engine.handle("openai", req()).body
        assertTrue(openai.contains("\"first\""), openai)
        assertEquals("second", engine.text("anthropic"))
        val gemini = engine.handle("gemini", req()).body
        assertTrue(gemini.contains("\"third\""), gemini)
        assertEquals(listOf("openai", "anthropic", "gemini"), engine.calls().map { it.vendor })
        assertEquals(listOf(1, 2, 3), engine.calls().map { it.step })
    }

    @Test
    fun `an error step returns its status, body and headers and takes its place in the order`() {
        val engine =
            engineWith(
                Scenario(
                    name = "inline",
                    steps =
                        listOf(
                            Step(
                                error =
                                    ErrorStep(
                                        status = 429,
                                        body = """{"type":"error","error":{"type":"rate_limit_error"}}""",
                                        headers = mapOf("retry-after" to "2"),
                                    ),
                            ),
                            Step(text = "sunny"),
                        ),
                ),
            )
        val first = engine.handle("anthropic", req())
        assertEquals(429, first.status)
        assertEquals("2", first.headers["retry-after"])
        assertEquals("""{"type":"error","error":{"type":"rate_limit_error"}}""", first.body)
        assertEquals("sunny", engine.text())
        assertEquals(listOf("error:429", "text"), engine.calls().map { it.outcome })
        assertEquals(listOf(1, 2), engine.calls().map { it.step })
    }

    @Test
    fun `reset starts the scenario over, numbering included`() {
        val engine = engineWith(textScenario("one", "two"))
        engine.text()
        engine.text()

        engine.reset()
        assertEquals("inline", engine.currentScenarioName())
        assertTrue(engine.calls().isEmpty())
        val again = engine.handle("anthropic", req())
        assertEquals("one", anthropicText(again.body))
        assertEquals("fake-msg-1", json(again.body).getValue("id").jsonPrimitive.content)
        val call = engine.calls().single()
        assertEquals(1L, call.seq)
        assertEquals(1, call.step)
    }

    @Test
    fun `loading starts over, numbering included`() {
        val engine = engineWith(textScenario("one", "two"))
        engine.text()
        engine.text()

        engine.loadInline(textScenario("fresh", name = "reloaded"))
        assertEquals("reloaded", engine.currentScenarioName())
        assertTrue(engine.calls().isEmpty())
        val reply = engine.handle("openai", req()).body
        assertEquals("fake-openai-1", json(reply).getValue("id").jsonPrimitive.content)
        assertEquals(1L, engine.calls().single().seq)

        engine.load("hello")
        assertTrue(engine.calls().isEmpty())
        val hello = engine.handle("gemini", req()).body
        assertEquals(1L, json(hello).getValue("fake_llm_seq").jsonPrimitive.long)
    }

    @Test
    fun `unload removes the scenario and starts numbering over`() {
        val engine = engineWith(textScenario("one", "two"), lenient = true)
        engine.text()
        engine.text()

        engine.unload()
        assertNull(engine.currentScenarioName())
        assertTrue(engine.calls().isEmpty())
        val canned = engine.handle("anthropic", req())
        assertEquals(CANNED_ASSISTANT_TEXT, anthropicText(canned.body))
        assertEquals("fake-msg-1", json(canned.body).getValue("id").jsonPrimitive.content)
    }

    @Test
    fun `strict mode with no scenario loaded answers the no-scenario 400 and logs the call`() {
        val engine = ScenarioEngine()
        assertFalse(engine.lenient)
        val prompt = "start \"quote\"\n" + "p".repeat(250)
        val response =
            engine.handle("anthropic", req(model = "example-model", systemShape = "string", promptText = prompt, messageCount = 2))
        assertEquals(400, response.status)
        val body = json(response.body)
        assertEquals(
            setOf("fake_llm_error", "seq", "vendor", "model", "system_shape", "message_count", "prompt_preview"),
            body.keys,
        )
        assertEquals("no scenario loaded", body.getValue("fake_llm_error").jsonPrimitive.content)
        assertEquals(1L, body.getValue("seq").jsonPrimitive.long)
        assertEquals("anthropic", body.getValue("vendor").jsonPrimitive.content)
        assertEquals("example-model", body.getValue("model").jsonPrimitive.content)
        assertEquals("string", body.getValue("system_shape").jsonPrimitive.content)
        assertFalse(body.getValue("message_count").jsonPrimitive.isString)
        assertEquals(prompt.take(200), body.getValue("prompt_preview").jsonPrimitive.content)

        val gemini = engine.handle("gemini", req(model = "example-gemini"))
        assertEquals(2L, json(gemini.body).getValue("seq").jsonPrimitive.long)

        val calls = engine.calls()
        assertEquals(listOf("no-scenario", "no-scenario"), calls.map { it.outcome })
        assertEquals(listOf(null, null), calls.map { it.step })
        assertEquals(listOf(1L, 2L), calls.map { it.seq })
        assertEquals(prompt.takeLast(500), calls[0].promptSample)
    }

    @Test
    fun `lenient mode with no scenario loaded serves the canned reply in each dialect`() {
        assertEquals("This is a canned response from fake-llm.", CANNED_ASSISTANT_TEXT)
        val engine = ScenarioEngine(lenient = true)
        assertEquals(CANNED_ASSISTANT_TEXT, engine.text())
        val openai = engine.handle("openai", req())
        assertEquals(200, openai.status)
        assertTrue(openai.body.contains(CANNED_ASSISTANT_TEXT))
        assertTrue(openai.body.contains("chat.completion"))
        assertEquals(listOf("default", "default"), engine.calls().map { it.outcome })
        assertEquals(listOf(null, null), engine.calls().map { it.step })
    }

    @Test
    fun `lenient mode does not cover a call after the last step`() {
        val engine = engineWith(textScenario("only"), lenient = true)
        engine.text()
        val extra = engine.handle("anthropic", req())
        assertEquals(400, extra.status)
        assertEquals("no more steps", json(extra.body).getValue("fake_llm_error").jsonPrimitive.content)
        assertEquals("no-more-steps", engine.calls()[1].outcome)
    }

    @Test
    fun `the call log records the request`() {
        val engine = engineWith(textScenario("a", "b"))
        val before = System.currentTimeMillis()
        engine.handle("anthropic", req(model = "example-model", systemShape = "blocks", promptText = "alpha", messageCount = 2))
        engine.handle("gemini", req(model = "other-model", systemShape = "absent", promptText = "beta", messageCount = 4))
        val after = System.currentTimeMillis()
        val (first, second) = engine.calls()

        assertEquals(1L, first.seq)
        assertEquals(1, first.step)
        assertEquals("anthropic", first.vendor)
        assertEquals("example-model", first.model)
        assertEquals("blocks", first.systemShape)
        assertEquals(2, first.messageCount)
        assertEquals("text", first.outcome)
        assertEquals("alpha", first.promptSample)
        assertTrue(first.timestampMs in before..after)

        assertEquals(2L, second.seq)
        assertEquals(2, second.step)
        assertEquals("gemini", second.vendor)
        assertEquals("other-model", second.model)
        assertEquals("absent", second.systemShape)
        assertEquals(4, second.messageCount)
        assertEquals("beta", second.promptSample)
        assertTrue(second.timestampMs in before..after)
    }

    @Test
    fun `prompt sample keeps the last 500 characters`() {
        val engine = ScenarioEngine()
        val tail = "T".repeat(500)
        engine.handle("anthropic", req(promptText = "H".repeat(20) + tail))
        assertEquals(tail, engine.calls().single().promptSample)
    }

    @Test
    fun `concurrent calls each take a different step, and the log is in call order`() {
        val size = 200
        val engine = engineWith(textScenario(*Array(size) { "step-${it + 1}" }))
        val pool = Executors.newFixedThreadPool(16)
        val start = CountDownLatch(1)
        try {
            val futures =
                (1..size).map {
                    pool.submit<Resolution> {
                        start.await()
                        engine.resolve("anthropic", req())
                    }
                }
            start.countDown()
            val resolutions = futures.map { it.get(10, TimeUnit.SECONDS) }
            // Each call got the step matching its own number, not just some unused step.
            resolutions.forEach { assertEquals("step-${it.seq}", it.step.text) }
            assertEquals((1L..size).toSet(), resolutions.map { it.seq }.toSet())
        } finally {
            pool.shutdownNow()
        }
        val calls = engine.calls()
        assertEquals((1L..size).toList(), calls.map { it.seq })
        assertEquals((1..size).toList(), calls.map { it.step })
    }

    @Test
    fun `a reset during concurrent calls leaves a log that belongs to one run, in order`() {
        // Probabilistic: racy code can pass a run, but most races leave gaps, stale entries, or a
        // call whose step does not match its number.
        val engine = engineWith(textScenario(*Array(1000) { "step-${it + 1}" }))
        val mismatched = ConcurrentLinkedQueue<Resolution>()
        val pool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        try {
            val callers =
                (1..400).map {
                    pool.submit<Unit> {
                        start.await()
                        val r = engine.resolve("anthropic", req())
                        if (r.step.text != "step-${r.seq}") mismatched += r
                    }
                }
            val resetter =
                pool.submit<Unit> {
                    start.await()
                    repeat(20) { engine.reset() }
                }
            start.countDown()
            (callers + resetter).forEach { it.get(10, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
        assertEquals(emptyList(), mismatched.toList())
        val calls = engine.calls()
        assertEquals((1L..calls.size).toList(), calls.map { it.seq })
        assertTrue(calls.all { it.step?.toLong() == it.seq })
    }

    @Test
    fun `an unknown scenario name is rejected and leaves the engine unchanged`() {
        val engine = engineWith(textScenario("kept"))
        val failure = assertFailsWith<IllegalStateException> { engine.load("missing-scenario") }
        assertTrue(failure.message!!.contains("unknown scenario"))
        assertEquals("inline", engine.currentScenarioName())
        assertEquals("kept", engine.text())
    }

    @Test
    fun `a step is an error or a reply of text, tool calls or both`() {
        val call = listOf(ToolCall(name = "f"))
        assertFailsWith<IllegalArgumentException> { Scenario(name = "empty", steps = emptyList()) }
        assertFailsWith<IllegalArgumentException> { Step(text = "x", error = ErrorStep(500)) }
        assertFailsWith<IllegalArgumentException> { Step() }
        Step(text = "x")
        Step(error = ErrorStep(500))
        Step(toolCalls = call)
        Step(text = "x", toolCalls = call)
        Step(thinking = "t", toolCalls = call)
        Step(toolCalls = call, stopReason = "tool_use")
        val failures =
            listOf(
                assertFailsWith<IllegalArgumentException> { Step(toolCalls = call, error = ErrorStep(500)) },
                assertFailsWith<IllegalArgumentException> { Step(toolCalls = emptyList()) },
                assertFailsWith<IllegalArgumentException> { Step(toolCalls = call, stopReason = "max_tokens") },
            )
        assertEquals(
            listOf(
                "a step must set either error or a reply: text, toolCalls or both",
                "toolCalls must not be empty",
                "stopReason must be end_turn or tool_use on a step with toolCalls, got 'max_tokens'",
            ),
            failures.map { it.message },
        )
    }

    @Test
    fun `a tool call needs a name, a non-empty id and at most one of arguments and rawArguments`() {
        ToolCall(name = "f", id = "x", arguments = JsonObject(emptyMap()))
        ToolCall(name = "f", rawArguments = "not json")
        val failures =
            listOf(
                assertFailsWith<IllegalArgumentException> { ToolCall(name = "") },
                assertFailsWith<IllegalArgumentException> { ToolCall(name = "f", id = "") },
                assertFailsWith<IllegalArgumentException> {
                    ToolCall(name = "f", arguments = JsonObject(emptyMap()), rawArguments = "{}")
                },
            )
        assertEquals(
            listOf(
                "a tool call's name must not be empty",
                "a tool call's id must not be empty",
                "a tool call sets at most one of arguments and rawArguments",
            ),
            failures.map { it.message },
        )
    }

    @Test
    fun `an error step's status must be an HTTP status`() {
        ErrorStep(100)
        ErrorStep(599)
        listOf(42, 99, 600, -1).forEach { status ->
            assertFailsWith<IllegalArgumentException>("$status") { ErrorStep(status) }
        }
    }

    @Test
    fun `thinking and streaming fields are constrained`() {
        assertFailsWith<IllegalArgumentException> { Step(error = ErrorStep(500), thinking = "x") }
        assertFailsWith<IllegalArgumentException> { Step(text = "x", streamErrorAfterChunks = 3) }
        assertFailsWith<IllegalArgumentException> { Step(error = ErrorStep(500), streamErrorAfterChunks = 0) }
        assertFailsWith<IllegalArgumentException> { Step(text = "x", streamChunkChars = 0) }
        assertFailsWith<IllegalArgumentException> { Step(text = "x", streamInterChunkDelayMs = -1) }
        Step(text = "x", thinking = "t", streamInterChunkDelayMs = 0)
        Step(error = ErrorStep(500), streamErrorAfterChunks = 1)
    }

    @Test
    fun `a thinking step is rendered ahead of the text`() {
        val engine = engineWith(Scenario(name = "inline", steps = listOf(Step(text = "the reply", thinking = "the thinking"))))
        val body = engine.handle("anthropic", req()).body
        val content = json(body).getValue("content").jsonArray.map { it.jsonObject }
        assertEquals(listOf("thinking", "text"), content.map { it.getValue("type").jsonPrimitive.content })
        assertEquals("the thinking", content[0].getValue("thinking").jsonPrimitive.content)
        assertTrue(
            content[0]
                .getValue("signature")
                .jsonPrimitive.content
                .isNotBlank(),
        )
        assertEquals("the reply", content[1].getValue("text").jsonPrimitive.content)
        assertEquals("end_turn", anthropicStopReason(body))
    }

    @Test
    fun `engines in one JVM load same-named scenarios from their own directories`() {
        val first = Files.createTempDirectory("fake-llm-first")
        val second = Files.createTempDirectory("fake-llm-second")
        try {
            first.resolve("same.json").writeText(sameScenario("from the first directory"))
            second.resolve("same.json").writeText(sameScenario("from the second directory"))
            val a = ScenarioEngine(scenarioDirectory = first)
            val b = ScenarioEngine(scenarioDirectory = second)
            val classpathOnly = ScenarioEngine()

            assertEquals(
                "from the first directory",
                a
                    .load("same")
                    .steps
                    .single()
                    .text,
            )
            assertEquals(
                "from the second directory",
                b
                    .load("same")
                    .steps
                    .single()
                    .text,
            )
            assertEquals(
                "from the first directory",
                a
                    .load("same")
                    .steps
                    .single()
                    .text,
            )
            assertFailsWith<IllegalStateException> { classpathOnly.load("same") }
            assertNull(classpathOnly.scenarioDirectory)
        } finally {
            first.toFile().deleteRecursively()
            second.toFile().deleteRecursively()
        }
    }

    private fun sameScenario(text: String): String = """{"name":"same","steps":[{"text":"$text"}]}"""
}
