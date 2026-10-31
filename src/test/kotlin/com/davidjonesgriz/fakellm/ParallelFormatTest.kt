package com.davidjonesgriz.fakellm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * The scenario format for parallel steps and matches: what the decoder and `fake-llm validate`
 * accept, and the messages they give. ScenarioSchemaTest and OpenApiConformanceTest push the same
 * lists through the schemas.
 */
class ParallelFormatTest {
    private fun decode(scenario: String): Scenario = ScenarioLoader.decode(Json.parseToJsonElement(scenario))

    private fun messageFor(scenario: String): String = assertFailsWith<IllegalArgumentException> { decode(scenario) }.message!!

    @Test
    fun `the decoder and validate accept every valid parallel scenario`() {
        validParallelScenarios.forEach { (why, body) ->
            val scenario = decode(body)
            withScenarioDir { dir ->
                val file = dir.resolve("${scenario.name}.json").also { it.writeText(body) }
                val out = ByteArrayOutputStream()
                val err = ByteArrayOutputStream()
                assertEquals(0, validateScenarios(listOf(file.toString()), PrintStream(out, true), PrintStream(err, true)), "$why: $err")
            }
        }
    }

    @Test
    fun `explicit nulls read as absent`() {
        val steps = decode(validParallelScenarios.toMap().getValue("explicit nulls the decoder reads as absent")).steps
        assertEquals(TextMatch(contains = "a"), steps[0].match!!.lastUser)
        assertNull(steps[0].match!!.model)
        assertEquals(Step(text = "a"), steps[1].parallel!!.steps.single())
        assertEquals(JsonNull, steps[4].match!!.body!!.equalTo)
        assertEquals(Step(text = "t"), steps[5])
    }

    @Test
    fun `a parallel step is read into a section, and a match into its types`() {
        val s = decode(validParallelScenarios.toMap().getValue("the issue's fan-out and merge"))
        val section = s.steps[0].parallel!!
        assertEquals(false, section.overlap)
        assertEquals(listOf("Summary of A.", "Summary of B.", "Summary of C."), section.steps.map { it.text })
        assertEquals(Match(lastUser = TextMatch(contains = "doc-b")), section.steps[1].match)
        assertEquals(Match(system = TextMatch(contains = "Merge")), s.steps[1].match)
        val keys = decode(validParallelScenarios.toMap().getValue("every match key")).steps.single().match!!
        assertEquals(MessageMatch(index = -1, contains = "u"), keys.message)
        assertEquals(ToolsMatch(declares = "f", choice = "tool", choiceTool = "f"), keys.tools)
        assertEquals("$.metadata['user \\'id'][-1].a", keys.body!!.path)
    }

    @Test
    fun `a nested parallel step and a parallel step with a sibling are named with their step and path`() {
        assertEquals(
            "step 2.1: \$.steps[1].parallel.steps[0].parallel: a parallel step cannot contain another parallel step",
            messageFor("""{"name":"x","steps":[{"text":"a"},{"parallel":{"steps":[{"parallel":{"steps":[{"text":"b"}]}}]}}]}"""),
        )
        assertEquals(
            "step 1: \$.steps[0]: a parallel step has only the key 'parallel', got 'match'",
            messageFor("""{"name":"x","steps":[{"parallel":{"steps":[{"text":"a"}]},"match":{"model":"m"}}]}"""),
        )
    }

    @Test
    fun `an inner step's problem is named step N dot M`() {
        assertEquals(
            "step 2.3: \$.steps[1].parallel.steps[2].match.lastUser.regex: expected a string, got 1",
            messageFor(
                """{"name":"x","steps":[{"text":"a"},{"parallel":{"steps":[{"text":"b"},{"text":"c"},
                {"text":"d","match":{"lastUser":{"regex":1}}}]}}]}""",
            ),
        )
        assertEquals(
            "step 1.2: a step must set either error or a reply: text, toolCalls or both",
            messageFor("""{"name":"x","steps":[{"parallel":{"steps":[{"text":"a"},{}]}}]}"""),
        )
        assertEquals(
            "step 1: a parallel step's steps must not be empty",
            messageFor("""{"name":"x","steps":[{"parallel":{"steps":[]}}]}"""),
        )
    }

    @Test
    fun `match rules are checked when the scenario loads`() {
        fun matchMessage(match: String) = messageFor("""{"name":"x","steps":[{"match":$match,"text":"t"}]}""")
        assertEquals("step 1: a match must set at least one key", matchMessage("{}"))
        assertEquals("step 1: a text match sets exactly one of contains, equals and regex", matchMessage("""{"prompt":{}}"""))
        assertEquals(
            "step 1: a message match sets exactly one of index and role",
            matchMessage("""{"message":{"index":0,"role":"user","contains":"a"}}"""),
        )
        assertEquals(
            "step 1: route must be anthropic, openai, openai-responses or gemini, got 'bedrock'",
            matchMessage("""{"route":"bedrock"}"""),
        )
        assertEquals("step 1: messageCount must not be negative, got -1", matchMessage("""{"messageCount":-1}"""))
        assertEquals(
            "step 1: body path '$.a[*]' is not supported: use \$ followed by .name, ['name'] or [n]",
            matchMessage("""{"body":{"path":"$.a[*]","equals":1}}"""),
        )
        assertEquals(
            "step 1: tools choice must be auto, none, required, tool or other, got 'maybe'",
            matchMessage("""{"tools":{"choice":"maybe"}}"""),
        )
        assertEquals(
            "step 1: tools choiceTool requires choice tool, got 'auto'",
            matchMessage("""{"tools":{"choice":"auto","choiceTool":"f"}}"""),
        )
        assertEquals("step 1: header name 'x trace' is not a header name", matchMessage("""{"header":{"name":"x trace","contains":"a"}}"""))
    }

    /**
     * Whether a regex compiles is the one rule the JSON Schema can't check: its regex dialect is
     * ECMA-262 and the engine's is Java's, so the schema carries no `format` and accepts any string.
     * The decoder and `fake-llm validate` reject it, so nothing they accept fails when it runs.
     */
    @Test
    fun `a regex that does not compile is rejected by the decoder`() {
        assertContains(
            messageFor("""{"name":"x","steps":[{"match":{"lastUser":{"regex":"(unclosed"}},"text":"t"}]}"""),
            "step 1: regex '(unclosed' does not compile: Unclosed group",
        )
        decode("""{"name":"x","steps":[{"match":{"lastUser":{"regex":"(?i)a++"}},"text":"t"}]}""")
    }

    @Test
    fun `the Kotlin model enforces the same rules`() {
        assertFailsWith<IllegalArgumentException> {
            ParallelSection(
                steps = listOf(Step(parallel = ParallelSection(listOf(Step(text = "a"))))),
            )
        }
        assertFailsWith<IllegalArgumentException> { Step(text = "a", parallel = ParallelSection(listOf(Step(text = "b")))) }
        assertFailsWith<IllegalArgumentException> { Step(streamChunkChars = 5, parallel = ParallelSection(listOf(Step(text = "b")))) }
        assertFailsWith<IllegalArgumentException> { Match() }
        assertFailsWith<IllegalArgumentException> { TextMatch(contains = "a", regex = "b") }
        assertFailsWith<IllegalArgumentException> { BodyMatch(path = "$", equalTo = JsonNull) }
        Step(parallel = ParallelSection(listOf(Step(text = "a", match = Match(model = "m")))))
    }
}
