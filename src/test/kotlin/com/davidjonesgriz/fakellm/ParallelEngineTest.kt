package com.davidjonesgriz.fakellm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** How the engine serves parallel steps and matches; ScenarioEngineTest covers plain steps. */
class ParallelEngineTest {
    private fun user(text: String) = parseAnthropicRequest(anthropicBody(text))

    private fun on(text: String) = Step(text = text.uppercase(), match = Match(lastUser = TextMatch(equalTo = text)))

    private fun engine(vararg steps: Step) = ScenarioEngine().also { it.loadInline(Scenario(name = "p", steps = steps.toList())) }

    private fun section(
        vararg steps: Step,
        overlap: Boolean = false,
    ) = Step(parallel = ParallelSection(steps.toList(), overlap))

    /** The reply text, or the 400's code. */
    private fun ScenarioEngine.call(text: String): String {
        val step = resolve("anthropic", user(text)).step
        return step.text ?: Json
            .parseToJsonElement(step.error!!.body)
            .jsonObject
            .getValue("fake_llm_error")
            .jsonPrimitive.content
    }

    private val issueExample = validParallelScenarios.toMap()

    @Test
    fun `a section is served in any arrival order, and the scenario moves on once it is used up`() {
        listOf("a", "b", "c").permutations().forEach { order ->
            val e = engine(section(on("a"), on("b"), on("c")), Step(text = "merged"))
            assertEquals(order.map { it.uppercase() }, order.map { e.call(it) }, "$order")
            assertEquals("merged", e.call("anything"))
            assertEquals("no-more-steps", e.call("again"))
            assertEquals(emptyList(), e.unusedSteps)
        }
    }

    @Test
    fun `the issue's examples run as written`() {
        val merge = ScenarioEngine().also { it.loadInline(Scenario.fromJson(issueExample.getValue("the issue's fan-out and merge"))) }
        assertEquals("Summary of C.", merge.call("doc-c"))
        assertEquals("Summary of A.", merge.call("doc-a"))
        assertEquals("Summary of B.", merge.call("doc-b"))
        val mergeCall = parseAnthropicRequest("""{"system":"Merge these","messages":[{"role":"user","content":"x"}]}""")
        assertEquals("A, B and C, combined.", merge.resolve("anthropic", mergeCall).step.text)

        val retry = ScenarioEngine().also { it.loadInline(Scenario.fromJson(issueExample.getValue("the issue's 429 and retry"))) }
        assertEquals(
            429,
            retry
                .resolve("anthropic", user("doc-a"))
                .step.error!!
                .status,
        )
        assertEquals("Summary of B.", retry.call("doc-b"))
        assertEquals("Summary of A.", retry.call("doc-a"))
        assertEquals(emptyList(), retry.unusedSteps)
    }

    @Test
    fun `an inner step with no match takes any call, first unused in listed order`() {
        val e = engine(section(on("x"), Step(text = "one"), Step(text = "two")))
        assertEquals("X", e.call("x"))
        assertEquals("one", e.call("y"))
        assertEquals("two", e.call("x"))
        // Listed first, a step with no match takes the call even when a later step's match fits it.
        val first = engine(section(Step(text = "any"), on("x")))
        assertEquals("any", first.call("x"))
        assertEquals("no-match", first.call("y"))
    }

    @Test
    fun `a call that matches nothing takes a number and a log entry, uses nothing, and gets no-match`() {
        val e = engine(section(on("a"), on("b")), on("next"))
        assertEquals("no-match", e.call("next"))
        assertEquals("A", e.call("a"))
        assertEquals("no-match", e.call("a"))
        assertEquals("B", e.call("b"))
        assertEquals("no-match", e.call("other"))
        assertEquals("NEXT", e.call("next"))
        val calls = e.calls()
        assertEquals((1L..6L).toList(), calls.map { it.seq })
        assertEquals(listOf("no-match", "text", "no-match", "text", "no-match", "text"), calls.map { it.outcome })
        assertEquals(listOf(null, 1, null, 1, null, 2), calls.map { it.step })
        assertEquals(listOf(null, 1, null, 2, null, null), calls.map { it.innerStep })
    }

    @Test
    fun `a plain step with a match is taken only by a call that satisfies it, and stays next`() {
        val e = engine(on("first"), Step(text = "any"))
        assertEquals("no-match", e.call("second"))
        assertEquals(listOf(1, 2), e.unusedSteps)
        assertEquals("FIRST", e.call("first"))
        assertEquals("any", e.call("second"))
    }

    @Test
    fun `the no-match message lists the unused steps the call could have taken, with their rules`() {
        val e = engine(Step(text = "intro"), section(on("doc-a"), on("doc-b"), on("doc-c")))
        e.call("hello")
        e.call("doc-a")
        val body =
            Json
                .parseToJsonElement(
                    e
                        .resolve("anthropic", user("doc-z"))
                        .step.error!!
                        .body,
                ).jsonObject
        assertEquals(
            """no match: call 3 matched none of the unused steps it could have taken: step 2.2 (lastUser equals "doc-b"), """ +
                """step 2.3 (lastUser equals "doc-c")""",
            body.getValue("message").jsonPrimitive.content,
        )
        assertEquals(
            listOf("fake_llm_error", "message", "seq", "vendor", "model", "system_shape", "message_count", "prompt_preview"),
            body.keys.toList(),
        )
    }

    @Test
    fun `the no-match message names at most ten steps`() {
        val e = engine(section(*(1..12).map { on("doc-$it") }.toTypedArray()))
        val message =
            Json
                .parseToJsonElement(
                    e
                        .resolve("anthropic", user("nope"))
                        .step.error!!
                        .body,
                ).jsonObject
                .getValue("message")
        assertTrue(message.jsonPrimitive.content.endsWith("""step 1.10 (lastUser equals "doc-10") and 2 more"""), "$message")
    }

    @Test
    fun `an overlap group is searched across its sections, and locks at the next step that is not in it`() {
        val e =
            engine(
                section(on("plan a"), on("plan b"), overlap = true),
                section(on("a1"), on("a2"), overlap = true),
                section(on("b1"), overlap = true),
                on("merge"),
                on("after"),
            )
        assertEquals("PLAN A", e.call("plan a"))
        assertEquals("A1", e.call("a1"))
        assertEquals("PLAN B", e.call("plan b"))
        assertEquals("no-match", e.call("merge"))
        assertEquals("B1", e.call("b1"))
        assertEquals(listOf(2, 4, 5), e.unusedSteps)
        assertEquals("A2", e.call("a2"))
        assertEquals(listOf(4, 5), e.unusedSteps)
        assertEquals("MERGE", e.call("merge"))
        assertEquals("AFTER", e.call("after"))
    }

    @Test
    fun `an unmarked section after a group is not part of it`() {
        val e = engine(section(on("a"), overlap = true), section(on("b")))
        assertEquals("no-match", e.call("b"))
        assertEquals("A", e.call("a"))
        assertEquals("B", e.call("b"))
    }

    @Test
    fun `a partly used section is unused, and the unused steps need not be a tail`() {
        val e = engine(section(on("a"), on("b")), on("c"))
        e.call("a")
        assertEquals(listOf(1, 2), e.unusedSteps)
        assertEquals(listOf(1, 2), e.progress.unusedSteps)
        assertEquals(2, e.progress.steps)
    }

    @Test
    fun `the call log records the step and the inner step that served each call`() {
        val e = engine(Step(text = "intro"), section(on("x"), on("y")))
        e.call("hi")
        e.call("y")
        e.call("x")
        assertEquals(listOf(1, 2, 2), e.calls().map { it.step })
        assertEquals(listOf(null, 2, 1), e.calls().map { it.innerStep })
        assertEquals("fake-llm: call 2 anthropic model=\"example-model\" outcome=text step=2.2", callLogLine(e.calls()[1]))
    }

    @Test
    fun `a chosen inner step whose rawArguments the request can't carry is unsupported, named step N dot M, and stays unused`() {
        val raw = Step(toolCalls = listOf(ToolCall(name = "f", rawArguments = "{")), match = Match(lastUser = TextMatch(equalTo = "raw")))
        val e = engine(section(on("a"), raw))
        val failure = assertFailsWith<RequestException> { e.resolve("anthropic", user("raw")) }
        assertEquals(ErrorCode.UNSUPPORTED_STEP, failure.code)
        assertTrue(failure.message!!.startsWith("step 1.2: tool call 1 sets rawArguments"), failure.message)
        assertEquals(emptyList(), e.calls())
        assertEquals("A", e.call("a"))
        assertEquals(listOf(1), e.unusedSteps)
    }

    @Test
    fun `reset and unload clear the section`() {
        val e = engine(section(on("a"), on("b")))
        e.call("a")
        e.reset()
        assertEquals(listOf(1), e.unusedSteps)
        assertEquals("A", e.call("a"))
        assertEquals("B", e.call("b"))
        e.unload()
        assertEquals("no-scenario", e.call("a"))
    }

    @Test
    fun `lenient mode does not change no-match`() {
        val e = ScenarioEngine(lenient = true).also { it.loadInline(Scenario(name = "p", steps = listOf(on("a")))) }
        assertEquals("no-match", e.call("b"))
    }

    @Test
    fun `every valid parallel scenario loads and serves calls without failing`() {
        validParallelScenarios.forEach { (why, body) ->
            val e = ScenarioEngine().also { it.loadInline(Scenario.fromJson(body)) }
            repeat(4) { e.resolve("anthropic", user("u")) }
            assertTrue(e.calls().size == 4, why)
        }
    }

    @Test
    fun `a call log record without an inner step is written without the field`() {
        val e = engine(Step(text = "plain"))
        e.call("x")
        assertNull(Json.parseToJsonElement(json(e.calls().single())).jsonObject["innerStep"])
        val inner = engine(section(on("a")))
        inner.call("a")
        assertEquals(
            "1",
            Json
                .parseToJsonElement(json(inner.calls().single()))
                .jsonObject
                .getValue("innerStep")
                .toString(),
        )
    }

    /** The record as the call log writes it, with defaults. */
    private fun json(record: CallRecord) = logJson.encodeToString(CallRecord.serializer(), record)

    private val logJson = Json { encodeDefaults = true }

    private fun <T> List<T>.permutations(): List<List<T>> =
        if (size <= 1) listOf(this) else flatMap { first -> (this - first).permutations().map { listOf(first) + it } }
}
