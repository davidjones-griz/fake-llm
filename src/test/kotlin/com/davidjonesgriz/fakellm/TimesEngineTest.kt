package com.davidjonesgriz.fakellm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** How the engine serves steps with `times`: from `min` to `max` calls each. */
class TimesEngineTest {
    private fun user(text: String) = parseAnthropicRequest(anthropicBody(text))

    /** A step that replies with [text] in capitals to a call whose last user message is [text]. */
    private fun on(
        text: String,
        times: Times? = null,
    ) = Step(text = text.uppercase(), match = Match(lastUser = TextMatch(equalTo = text)), times = times)

    private fun engine(vararg steps: Step) = ScenarioEngine().also { it.loadInline(Scenario(name = "times", steps = steps.toList())) }

    private fun section(
        vararg steps: Step,
        overlap: Boolean = false,
    ) = Step(parallel = ParallelSection(steps.toList(), overlap))

    private fun errorBody(step: Step) = Json.parseToJsonElement(step.error!!.body).jsonObject

    /** The reply text, or the 400's code. */
    private fun ScenarioEngine.call(text: String): String {
        val step = resolve("anthropic", user(text)).step
        return step.text ?: errorBody(step).getValue("fake_llm_error").jsonPrimitive.content
    }

    @Test
    fun `a poll step takes calls until one it does not match, which reaches the next step`() {
        val e = engine(on("poll", Times(min = 1)), on("final"))
        assertEquals(listOf("POLL", "POLL", "POLL"), List(3) { e.call("poll") })
        assertEquals(listOf(2), e.unusedSteps)
        assertEquals("FINAL", e.call("final"))
        assertEquals(emptyList(), e.unusedSteps)
        assertEquals("no-more-steps", e.call("poll"))
        assertEquals(listOf(1, 2, 3, null, null), e.calls().map { it.useNumber })
        assertEquals(listOf(1, 1, 1, 2, null), e.calls().map { it.step })
    }

    @Test
    fun `a fan-out of unknown width takes one inner step, and the merge call reaches the step after the section`() {
        listOf(1, 4, 9).forEach { width ->
            val fanOut = Step(text = "summary", match = Match(lastUser = TextMatch(contains = "doc-")), times = Times(min = 1))
            val merge = Step(text = "merged", match = Match(lastUser = TextMatch(equalTo = "merge")))
            val e = engine(section(fanOut), merge)
            assertEquals(List(width) { "summary" }, List(width) { e.call("doc-$it") })
            assertEquals("merged", e.call("merge"))
            assertEquals(emptyList(), e.unusedSteps)
            assertEquals((1..width).toList() + listOf(null), e.calls().map { it.useNumber })
            assertEquals(List(width) { 1 } + listOf(null), e.calls().map { it.innerStep })
        }
    }

    @Test
    fun `a step with min 0 can be skipped on the first call, and several in a row are skipped together`() {
        val e = engine(on("retry", Times(min = 0, max = 2)), on("ok"))
        assertEquals("OK", e.call("ok"))
        assertEquals(emptyList(), e.unusedSteps)
        val chain = engine(on("r1", Times(min = 0)), on("r2", Times(min = 0)), on("go"))
        assertEquals(emptyList(), chain.unusedSteps.filter { it < 3 })
        assertEquals("GO", chain.call("go"))
        assertEquals("no-more-steps", chain.call("r1"))
    }

    @Test
    fun `the reach stops at a step whose minimum is not met`() {
        val e = engine(on("x", Times(min = 0)), on("y", Times(min = 1)), on("z"))
        assertEquals("no-match", e.call("z"))
        assertEquals("Y", e.call("y"))
        assertEquals("Z", e.call("z"))
    }

    @Test
    fun `a step below its minimum holds the scenario, and a no-match moves nothing`() {
        val e = engine(on("a", Times(min = 2)), on("b"))
        assertEquals("A", e.call("a"))
        assertEquals("no-match", e.call("b"))
        assertEquals(listOf(1, 2), e.unusedSteps)
        assertEquals("A", e.call("a"))
        assertEquals("B", e.call("b"))
        assertEquals(listOf(1, null, 2, null), e.calls().map { it.useNumber })
    }

    @Test
    fun `a step at its max is passed, and the next call goes to the step after it`() {
        val e = engine(on("a", Times(max = 2)), Step(text = "next"))
        assertEquals(listOf("A", "A", "next"), listOf(e.call("a"), e.call("a"), e.call("a")))
    }

    @Test
    fun `an unbounded step with no match takes every call, and its minimum of 0 leaves nothing unused`() {
        val e = engine(Step(text = "u", times = Times(min = 0)))
        assertEquals(emptyList(), e.unusedSteps)
        assertEquals(List(10) { "u" }, List(10) { e.call("anything $it") })
        assertEquals((1..10).toList(), e.calls().map { it.useNumber })
    }

    @Test
    fun `a trailing repeatable step whose minimum is met gives no-match, listing it, until it reaches its max`() {
        val e = engine(on("a", Times(min = 1, max = 2)))
        assertEquals("A", e.call("a"))
        val miss = e.resolve("anthropic", user("b")).step
        assertEquals("no-match", errorBody(miss).getValue("fake_llm_error").jsonPrimitive.content)
        assertTrue("step 1 (lastUser equals \"a\")" in errorBody(miss).getValue("message").jsonPrimitive.content)
        assertEquals("A", e.call("a"))
        val past = errorBody(e.resolve("anthropic", user("a")).step)
        assertEquals("no-more-steps", past.getValue("fake_llm_error").jsonPrimitive.content)
        assertTrue(
            past
                .getValue("message")
                .jsonPrimitive.content
                .endsWith("The last step can't take more calls."),
            "$past",
        )
        val plain = errorBody(engine(Step(text = "once")).also { it.call("x") }.resolve("anthropic", user("y")).step)
        assertTrue(
            plain
                .getValue("message")
                .jsonPrimitive.content
                .endsWith("The last step does not repeat."),
            "$plain",
        )
    }

    @Test
    fun `inner steps with times work in a section and across an overlap group`() {
        val e =
            engine(
                section(on("plan", Times(min = 1)), overlap = true),
                section(on("sub", Times(min = 0)), overlap = true),
                on("merge"),
            )
        assertEquals(listOf("PLAN", "SUB", "PLAN", "SUB", "SUB"), listOf("plan", "sub", "plan", "sub", "sub").map { e.call(it) })
        assertEquals("MERGE", e.call("merge"))
        assertEquals(listOf(1, 2, 1, 2, 2, 3), e.calls().map { it.step })
        assertEquals(listOf(1, 1, 2, 2, 3, null), e.calls().map { it.useNumber })
    }

    @Test
    fun `a section is unused until every inner step has met its minimum`() {
        val e = engine(section(on("a", Times(min = 2)), on("b", Times(min = 0))))
        e.call("a")
        assertEquals(listOf(1), e.unusedSteps)
        e.call("a")
        assertEquals(emptyList(), e.unusedSteps)
    }

    @Test
    fun `a repeated step whose rawArguments the request can't carry is unsupported and keeps its count`() {
        val raw =
            Step(
                toolCalls = listOf(ToolCall(name = "f", rawArguments = "{")),
                match = Match(route = "gemini"),
                times = Times(min = 1, max = 3),
            )
        val e = engine(raw, Step(text = "after"))
        val gemini = parseGeminiRequest("""{"contents":[{"parts":[{"text":"hi"}]}]}""", "m")
        val failure = assertFailsWith<RequestException> { e.resolve("gemini", gemini) }
        assertTrue(failure.message!!.startsWith("step 1: tool call 1 sets rawArguments"), failure.message)
        assertEquals(emptyList(), e.calls())
        assertEquals(listOf(1, 2), e.unusedSteps)
    }

    @Test
    fun `reset clears the counts`() {
        val e = engine(on("a", Times(min = 2)), on("b"))
        e.call("a")
        e.reset()
        assertEquals("no-match", e.call("b"))
        assertEquals(listOf(1L), e.calls().map { it.seq })
    }

    @Test
    fun `the call log writes useNumber only for a step with times, and the CLI line shows it`() {
        val plain = engine(Step(text = "p"))
        plain.call("x")
        assertNull(Json.parseToJsonElement(logJson.encodeToString(CallRecord.serializer(), plain.calls().single())).jsonObject["useNumber"])
        val repeated = engine(on("a", Times(min = 1)))
        repeated.call("a")
        repeated.call("a")
        val record = repeated.calls()[1]
        assertEquals(
            "2",
            Json
                .parseToJsonElement(logJson.encodeToString(CallRecord.serializer(), record))
                .jsonObject
                .getValue("useNumber")
                .toString(),
        )
        assertEquals("fake-llm: call 2 anthropic model=\"example-model\" outcome=text step=1 use=2", callLogLine(record))
    }
}
