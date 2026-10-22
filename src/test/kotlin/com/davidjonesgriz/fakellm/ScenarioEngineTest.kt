package com.davidjonesgriz.fakellm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
        caller: String? = null,
        hasCacheBreakpoint: Boolean = false,
    ) = ParsedRequest(
        model = model,
        systemShape = systemShape,
        promptText = promptText,
        messageCount = messageCount,
        stream = stream,
        caller = caller,
        hasCacheBreakpoint = hasCacheBreakpoint,
    )

    private fun textRule(
        role: String,
        match: Match = Match(),
        texts: List<String>,
    ): Rule = Rule(role = role, match = match, respond = texts.map { Step(text = it) })

    private fun engineWith(
        vararg rules: Rule,
        lenient: Boolean = false,
    ): ScenarioEngine =
        ScenarioEngine(lenient = lenient).also {
            it.loadInline(Scenario(name = "inline", rules = rules.toList()))
        }

    @Test
    fun `the first matching rule wins`() {
        val engine =
            engineWith(
                textRule("specific", Match(caller = "planner", promptContainsAll = listOf("forecast")), listOf("specific-hit")),
                textRule("general", Match(promptContainsAll = listOf("forecast")), listOf("general-hit")),
            )
        val response = engine.handle("anthropic", req(caller = "planner", promptText = "weekly forecast"))
        assertEquals(200, response.status)
        assertEquals("specific-hit", anthropicText(response.body))
        assertEquals("specific", engine.calls().single().role)
        assertEquals("text", engine.calls().single().outcome)
    }

    @Test
    fun `a later rule runs when earlier rules do not match`() {
        val engine =
            engineWith(
                textRule("specific", Match(caller = "planner"), listOf("specific-hit")),
                textRule("general", Match(promptContainsAll = listOf("forecast")), listOf("general-hit")),
            )
        val response = engine.handle("anthropic", req(caller = "other", promptText = "weekly forecast"))
        assertEquals("general-hit", anthropicText(response.body))
        assertEquals("general", engine.calls().single().role)
    }

    @Test
    fun `occurrence counters advance and the last step repeats`() {
        val engine = engineWith(textRule("counter", texts = listOf("one", "two", "three")))
        val texts = (1..5).map { anthropicText(engine.handle("anthropic", req()).body) }
        assertEquals(listOf("one", "two", "three", "three", "three"), texts)
        assertEquals(listOf(1, 2, 3, 4, 5), engine.calls().map { it.occurrence })
        assertTrue(engine.calls().all { it.role == "counter" && it.outcome == "text" })
    }

    @Test
    fun `occurrence counters are tracked per rule`() {
        val engine =
            engineWith(
                textRule("alpha", Match(caller = "alpha"), listOf("a1", "a2")),
                textRule("beta", Match(caller = "beta"), listOf("b1", "b2")),
            )
        assertEquals("a1", anthropicText(engine.handle("anthropic", req(caller = "alpha")).body))
        assertEquals("b1", anthropicText(engine.handle("anthropic", req(caller = "beta")).body))
        assertEquals("a2", anthropicText(engine.handle("anthropic", req(caller = "alpha")).body))
        assertEquals("a2", anthropicText(engine.handle("anthropic", req(caller = "alpha")).body))
        assertEquals("b2", anthropicText(engine.handle("anthropic", req(caller = "beta")).body))
        assertEquals(listOf(1, 1, 2, 3, 2), engine.calls().map { it.occurrence })
        assertEquals(listOf("alpha", "beta", "alpha", "alpha", "beta"), engine.calls().map { it.role })
    }

    @Test
    fun `reset and reload clear counters and the call log`() {
        val engine = engineWith(textRule("special", Match(caller = "special"), listOf("one", "two")), lenient = true)
        engine.handle("anthropic", req())
        engine.handle("anthropic", req(caller = "special"))
        engine.handle("anthropic", req(caller = "special"))

        engine.reset()
        assertEquals("inline", engine.currentScenarioName())
        assertTrue(engine.calls().isEmpty())

        val defaultAgain = engine.handle("anthropic", req(promptText = "after reset"))
        assertEquals(CANNED_ASSISTANT_TEXT, anthropicText(defaultAgain.body))
        assertEquals(1, engine.calls().single().occurrence)
        assertEquals("DEFAULT", engine.calls().single().role)

        val specialAgain = engine.handle("anthropic", req(caller = "special"))
        assertEquals("one", anthropicText(specialAgain.body))
        assertEquals(1, engine.calls()[1].occurrence)
        assertEquals("special", engine.calls()[1].role)

        engine.loadInline(Scenario(name = "reloaded", rules = listOf(textRule("fresh", texts = listOf("fresh")))))
        assertEquals("reloaded", engine.currentScenarioName())
        assertTrue(engine.calls().isEmpty())
        assertEquals("fresh", anthropicText(engine.handle("anthropic", req()).body))
        assertEquals(1, engine.calls().single().occurrence)
    }

    @Test
    fun `the call log records the request and the outcome`() {
        val engine = engineWith(textRule("assistant", texts = listOf("reply")))
        val before = System.currentTimeMillis()
        engine.handle(
            "anthropic",
            req(model = "example-model", systemShape = "blocks", promptText = "alpha", messageCount = 2),
        )
        engine.handle(
            "gemini",
            req(model = "other-model", systemShape = "absent", promptText = "beta", messageCount = 4),
        )
        val after = System.currentTimeMillis()
        val calls = engine.calls()

        assertEquals(2, calls.size)
        val first = calls[0]
        assertEquals(1L, first.seq)
        assertEquals("anthropic", first.vendor)
        assertEquals("example-model", first.model)
        assertEquals("blocks", first.systemShape)
        assertEquals(2, first.messageCount)
        assertEquals("assistant", first.role)
        assertEquals(1, first.occurrence)
        assertEquals("text", first.outcome)
        assertEquals("alpha", first.promptSample)
        assertTrue(first.timestampMs in before..after)

        val second = calls[1]
        assertEquals(2L, second.seq)
        assertEquals("gemini", second.vendor)
        assertEquals("other-model", second.model)
        assertEquals("absent", second.systemShape)
        assertEquals(4, second.messageCount)
        assertEquals("assistant", second.role)
        assertEquals(2, second.occurrence)
        assertEquals("text", second.outcome)
        assertEquals("beta", second.promptSample)
        assertTrue(second.timestampMs in before..after)
    }

    @Test
    fun `prompt sample keeps the last 500 characters`() {
        val engine = ScenarioEngine()
        val head = "H".repeat(20)
        val tail = "T".repeat(500)
        engine.handle("anthropic", req(promptText = head + tail))
        val sample = engine.calls().single().promptSample
        assertEquals(500, sample.length)
        assertEquals(tail, sample)
    }

    @Test
    fun `prompt sample keeps a short prompt whole`() {
        val engine = ScenarioEngine(lenient = true)
        val prompt = "A short prompt about the weather."
        engine.handle("anthropic", req(promptText = prompt))
        assertEquals(prompt, engine.calls().single().promptSample)
        assertEquals("DEFAULT", engine.calls().single().role)
    }

    @Test
    fun `caller match is exact`() {
        val engine =
            engineWith(
                textRule("summarizer", Match(caller = "summarizer"), listOf("summary")),
                textRule("assistant", texts = listOf("general")),
            )
        assertEquals("summary", anthropicText(engine.handle("anthropic", req(caller = "summarizer")).body))
        assertEquals("general", anthropicText(engine.handle("anthropic", req(caller = "Summarizer")).body))
        assertEquals("general", anthropicText(engine.handle("anthropic", req(caller = "summarizer-2")).body))
        assertEquals("general", anthropicText(engine.handle("anthropic", req(caller = "")).body))
        assertEquals("general", anthropicText(engine.handle("anthropic", req(caller = null)).body))
        assertEquals(
            listOf("summarizer", "assistant", "assistant", "assistant", "assistant"),
            engine.calls().map { it.role },
        )
    }

    @Test
    fun `promptContainsAll requires every fragment`() {
        val engine =
            engineWith(
                textRule("finder", Match(promptContainsAll = listOf("city", "forecast")), listOf("found")),
                textRule("fallback", texts = listOf("missed")),
            )
        assertEquals("found", anthropicText(engine.handle("anthropic", req(promptText = "city forecast tonight")).body))
        assertEquals("missed", anthropicText(engine.handle("anthropic", req(promptText = "city tonight")).body))
        assertEquals("missed", anthropicText(engine.handle("anthropic", req(promptText = "forecast tonight")).body))
    }

    @Test
    fun `promptContainsAny matches when any fragment is present`() {
        val engine =
            engineWith(
                textRule("season", Match(promptContainsAny = listOf("rain", "snow")), listOf("wet")),
                textRule("fallback", texts = listOf("dry")),
            )
        assertEquals("wet", anthropicText(engine.handle("anthropic", req(promptText = "heavy rain")).body))
        assertEquals("wet", anthropicText(engine.handle("anthropic", req(promptText = "light snow")).body))
        assertEquals("dry", anthropicText(engine.handle("anthropic", req(promptText = "clear skies")).body))
    }

    @Test
    fun `modelContains matches a substring of the model`() {
        val engine =
            engineWith(
                textRule("weather", Match(modelContains = "haiku"), listOf("haiku-hit")),
                textRule("other", texts = listOf("other-hit")),
            )
        assertEquals("haiku-hit", anthropicText(engine.handle("anthropic", req(model = "vendor-haiku-small")).body))
        assertEquals("other-hit", anthropicText(engine.handle("anthropic", req(model = "vendor-sonnet")).body))
        assertEquals("other-hit", anthropicText(engine.handle("anthropic", req(model = "vendor-HAIKU")).body))
    }

    @Test
    fun `systemShape matches only that shape`() {
        val engine =
            engineWith(
                textRule("blocks-rule", Match(systemShape = "blocks"), listOf("blocks-hit")),
                textRule("any-shape", texts = listOf("any-hit")),
            )
        assertEquals("blocks-hit", anthropicText(engine.handle("anthropic", req(systemShape = "blocks")).body))
        assertEquals("any-hit", anthropicText(engine.handle("anthropic", req(systemShape = "string")).body))
        assertEquals("any-hit", anthropicText(engine.handle("anthropic", req(systemShape = "absent")).body))
    }

    @Test
    fun `every present match predicate must hold`() {
        val match =
            Match(
                modelContains = "weather",
                systemShape = "blocks",
                caller = "planner",
                promptContainsAll = listOf("city", "forecast"),
                promptContainsAny = listOf("tomorrow", "weekend"),
            )
        val engine = engineWith(textRule("strict", match, listOf("strict-hit")), lenient = true)
        val accepted =
            req(
                model = "weather-v1",
                systemShape = "blocks",
                caller = "planner",
                promptText = "city forecast for tomorrow",
            )
        assertEquals("strict-hit", anthropicText(engine.handle("anthropic", accepted).body))

        val rejected =
            listOf(
                accepted.copy(model = "other-v1"),
                accepted.copy(systemShape = "string"),
                accepted.copy(caller = "other"),
                accepted.copy(caller = null),
                accepted.copy(promptText = "forecast for tomorrow"),
                accepted.copy(promptText = "city forecast for tonight"),
            )
        rejected.forEach { request ->
            val response = engine.handle("anthropic", request)
            assertEquals(CANNED_ASSISTANT_TEXT, anthropicText(response.body), request.toString())
        }
        assertEquals(1, engine.calls().first().occurrence)
        assertEquals("strict", engine.calls().first().role)
        assertTrue(engine.calls().drop(1).all { it.role == "DEFAULT" && it.outcome == "default" })
    }

    @Test
    fun `an error step returns its status and headers and the next step repeats`() {
        val engine =
            engineWith(
                Rule(
                    role = "weather",
                    respond =
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
        assertTrue(first.body.contains("rate_limit_error"))

        val second = engine.handle("anthropic", req())
        assertEquals(200, second.status)
        assertEquals("sunny", anthropicText(second.body))
        val third = engine.handle("anthropic", req())
        assertEquals(200, third.status)
        assertEquals("sunny", anthropicText(third.body))

        assertEquals(listOf("error:429", "text", "text"), engine.calls().map { it.outcome })
        assertEquals(listOf(1, 2, 3), engine.calls().map { it.occurrence })
        assertTrue(engine.calls().all { it.role == "weather" })
    }

    @Test
    fun `lenient mode with no scenario loaded serves the canned reply and logs it`() {
        assertEquals("This is a canned response from fake-llm.", CANNED_ASSISTANT_TEXT)
        val engine = ScenarioEngine(lenient = true)
        assertNull(engine.currentScenarioName())

        val resolution = engine.resolve("anthropic", req(promptText = "hello"))
        assertEquals(CANNED_ASSISTANT_TEXT, resolution.step.text)
        assertNull(resolution.step.error)
        assertEquals("DEFAULT", resolution.role)
        assertEquals(1, resolution.occurrence)
        assertEquals(1, engine.calls().size)
        assertEquals("default", engine.calls().single().outcome)
        assertEquals("hello", engine.calls().single().promptSample)

        val response = engine.handle("openai", req(promptText = "hello again"))
        assertEquals(200, response.status)
        assertTrue(response.body.contains(CANNED_ASSISTANT_TEXT))
        assertTrue(response.body.contains("chat.completion"))
        assertEquals(2, engine.calls().size)
        assertEquals(listOf(1, 2), engine.calls().map { it.occurrence })
        assertEquals(listOf("DEFAULT", "DEFAULT"), engine.calls().map { it.role })
        assertEquals("openai", engine.calls()[1].vendor)
        assertEquals("default", engine.calls()[1].outcome)
    }

    @Test
    fun `lenient mode serves the canned reply when no rule matches`() {
        val engine = engineWith(textRule("special", Match(caller = "special"), listOf("special-text")), lenient = true)
        val missed = engine.handle("anthropic", req(caller = "other", promptText = "ordinary"))
        assertEquals(200, missed.status)
        assertEquals(CANNED_ASSISTANT_TEXT, anthropicText(missed.body))
        assertEquals("DEFAULT", engine.calls().single().role)
        assertEquals("default", engine.calls().single().outcome)
        assertEquals(1, engine.calls().single().occurrence)

        val hit = engine.handle("anthropic", req(caller = "special"))
        assertEquals("special-text", anthropicText(hit.body))
        assertEquals(1, engine.calls()[1].occurrence)
        assertEquals("special", engine.calls()[1].role)

        val missedAgain = engine.handle("anthropic", req())
        assertEquals(CANNED_ASSISTANT_TEXT, anthropicText(missedAgain.body))
        assertEquals(2, engine.calls()[2].occurrence)
        assertEquals("DEFAULT", engine.calls()[2].role)
    }

    @Test
    fun `strict mode returns 400 and logs an unmatched call when no scenario is loaded`() {
        val engine = ScenarioEngine()
        assertFalse(engine.lenient)
        val prompt = "start \"quote\"\n" + "p".repeat(250)
        val response =
            engine.handle(
                "anthropic",
                req(model = "example-model", systemShape = "string", promptText = prompt, messageCount = 2, caller = "planner"),
            )
        assertEquals(400, response.status)
        val body = Json.parseToJsonElement(response.body).jsonObject
        assertEquals("unmatched request", body.getValue("fake_llm_error").jsonPrimitive.content)
        assertEquals(
            "no scenario loaded; POST /control/scenario first (or start with --lenient)",
            body.getValue("why").jsonPrimitive.content,
        )
        assertEquals("anthropic", body.getValue("vendor").jsonPrimitive.content)
        assertEquals("example-model", body.getValue("model").jsonPrimitive.content)
        assertEquals("string", body.getValue("system_shape").jsonPrimitive.content)
        assertFalse(body.getValue("message_count").jsonPrimitive.isString)
        assertEquals(2, body.getValue("message_count").jsonPrimitive.content.toInt())
        assertEquals("planner", body.getValue("caller").jsonPrimitive.content)
        assertEquals(prompt.take(200), body.getValue("prompt_preview").jsonPrimitive.content)

        val openai = engine.handle("openai", req(model = "example-gpt", caller = null, promptText = "second"))
        assertEquals(400, openai.status)
        val openaiBody = Json.parseToJsonElement(openai.body).jsonObject
        assertEquals("openai", openaiBody.getValue("vendor").jsonPrimitive.content)
        assertEquals(JsonNull, openaiBody.getValue("caller"))
        assertEquals("second", openaiBody.getValue("prompt_preview").jsonPrimitive.content)

        val gemini = engine.handle("gemini", req(model = "example-gemini", systemShape = "absent", messageCount = 3))
        assertEquals(400, gemini.status)
        val geminiBody = Json.parseToJsonElement(gemini.body).jsonObject
        assertEquals("gemini", geminiBody.getValue("vendor").jsonPrimitive.content)
        assertEquals("example-gemini", geminiBody.getValue("model").jsonPrimitive.content)
        assertEquals("absent", geminiBody.getValue("system_shape").jsonPrimitive.content)
        assertEquals(3, geminiBody.getValue("message_count").jsonPrimitive.content.toInt())

        val calls = engine.calls()
        assertEquals(listOf("UNMATCHED", "UNMATCHED", "UNMATCHED"), calls.map { it.role })
        assertEquals(listOf(0, 0, 0), calls.map { it.occurrence })
        assertEquals(listOf("unmatched", "unmatched", "unmatched"), calls.map { it.outcome })
        assertEquals(listOf(1L, 2L, 3L), calls.map { it.seq })
        assertEquals("anthropic", calls[0].vendor)
        assertEquals(prompt.takeLast(500), calls[0].promptSample)
    }

    @Test
    fun `strict mode names the scenario when no rule matches and does not consume its counter`() {
        val engine = engineWith(textRule("special", Match(caller = "special"), listOf("hit")))
        val missed = engine.handle("gemini", req(model = "example-gemini", caller = "other", promptText = "nope"))
        assertEquals(400, missed.status)
        val missedBody = Json.parseToJsonElement(missed.body).jsonObject
        assertEquals("no rule in scenario 'inline' matches this request", missedBody.getValue("why").jsonPrimitive.content)
        assertEquals("gemini", missedBody.getValue("vendor").jsonPrimitive.content)
        assertEquals("UNMATCHED", engine.calls().single().role)
        assertEquals(0, engine.calls().single().occurrence)
        assertEquals("unmatched", engine.calls().single().outcome)

        val hit = engine.handle("anthropic", req(caller = "special"))
        assertEquals(200, hit.status)
        assertEquals("hit", anthropicText(hit.body))
        assertEquals(1, engine.calls()[1].occurrence)
        assertEquals("special", engine.calls()[1].role)
        assertEquals("text", engine.calls()[1].outcome)
    }

    @Test
    fun `the unmatched body escapes the reason and the prompt preview`() {
        val engine =
            ScenarioEngine().also {
                it.loadInline(
                    Scenario(
                        name = "say \"no\"",
                        rules = listOf(textRule("only", Match(caller = "keep"), listOf("kept"))),
                    ),
                )
            }
        val prompt = "line\n\"quoted\" \\ slash"
        val response = engine.handle("anthropic", req(promptText = prompt, caller = null))
        val parsed = Json.parseToJsonElement(response.body).jsonObject
        assertEquals(
            "no rule in scenario 'say \"no\"' matches this request",
            parsed.getValue("why").jsonPrimitive.content,
        )
        assertEquals(prompt, parsed.getValue("prompt_preview").jsonPrimitive.content)
        assertEquals(JsonNull, parsed.getValue("caller"))
        assertTrue(response.body.contains("\\n"))
        assertTrue(response.body.contains("\\\""))
    }

    @Test
    fun `an unknown scenario name is rejected`() {
        ScenarioLoader.setScenarioDirectory(null)
        val failure = assertFailsWith<IllegalStateException> { ScenarioEngine().load("missing-scenario") }
        assertTrue(failure.message!!.contains("unknown scenario"))
    }

    @Test
    fun `rules require a non-empty respond list and exactly one of text or error`() {
        assertFailsWith<IllegalArgumentException> {
            Rule(role = "both", respond = listOf(Step(text = "x", error = ErrorStep(500))))
        }
        assertFailsWith<IllegalArgumentException> {
            Rule(role = "neither", respond = listOf(Step()))
        }
        assertFailsWith<IllegalArgumentException> { Rule(role = "empty", respond = emptyList()) }
        assertFailsWith<IllegalArgumentException> { Scenario(name = "no-rules", rules = emptyList()) }
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
    fun `resolve records one call for the matched step`() {
        val engine = engineWith(textRule("assistant", texts = listOf("reply")))
        val resolution = engine.resolve("anthropic", req(model = "example-model"))
        assertEquals("assistant", resolution.role)
        assertEquals(1, resolution.occurrence)
        assertEquals("reply", resolution.step.text)
        assertNull(resolution.step.error)
        val record = engine.calls().single()
        assertEquals(1L, record.seq)
        assertEquals("assistant", record.role)
        assertEquals("text", record.outcome)
        assertEquals("example-model", record.model)
    }

    @Test
    fun `a thinking step is rendered ahead of the text`() {
        val engine =
            engineWith(
                Rule(
                    role = "assistant",
                    respond = listOf(Step(text = "the reply", thinking = "the thinking")),
                ),
            )
        val body = engine.handle("anthropic", req()).body
        val content = Json.parseToJsonElement(body).jsonObject.getValue("content").jsonArray
        assertEquals(2, content.size)
        assertEquals("thinking", content[0].jsonObject.getValue("type").jsonPrimitive.content)
        assertEquals("the thinking", content[0].jsonObject.getValue("thinking").jsonPrimitive.content)
        assertTrue(content[0].jsonObject.getValue("signature").jsonPrimitive.content.isNotBlank())
        assertEquals("text", content[1].jsonObject.getValue("type").jsonPrimitive.content)
        assertEquals("the reply", content[1].jsonObject.getValue("text").jsonPrimitive.content)
        assertEquals("end_turn", anthropicStopReason(body))
    }
}
