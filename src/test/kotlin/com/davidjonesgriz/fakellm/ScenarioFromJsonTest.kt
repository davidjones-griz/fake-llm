package com.davidjonesgriz.fakellm

import kotlinx.serialization.json.Json
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** [Scenario.fromJson]: a scenario file's text, decoded with the checks and messages of an inline load. */
class ScenarioFromJsonTest {
    @Test
    fun `decodes a scenario file as it is, with its schema key`() {
        val text = Path.of("examples/src/test/scenarios/summarizer-from-directory.json").readText()

        val scenario = Scenario.fromJson(text)

        assertEquals(null, scenario.metadata?.name, "a file is named by its file name, which fromJson does not see")
        assertEquals(listOf(Step(text = "Churn fell to 2% after the onboarding redesign.")), scenario.steps)
    }

    @Test
    fun `the name need not match a file`() {
        val scenario =
            Scenario.fromJson(
                """{"metadata": {"name": "built-in-code"}, "steps": [{"error": {"status": 429}}, {"text": "ok"}]}""",
            )

        assertEquals(
            Scenario(metadata = ScenarioMetadata(name = "built-in-code"), steps = listOf(Step(error = ErrorStep(429)), Step(text = "ok"))),
            scenario,
        )
    }

    @Test
    fun `round-trips the scenario model's own JSON`() {
        val scenario =
            Scenario(listOf(Step(text = "t", thinking = "th", streamChunkChars = 3)), ScenarioMetadata("round-trip", "d", listOf("tag")))

        assertEquals(scenario, Scenario.fromJson(Json.encodeToString(Scenario.serializer(), scenario)))
    }

    @Test
    fun `malformed JSON names its line and column`() {
        val failure =
            assertFailsWith<IllegalArgumentException> { Scenario.fromJson("{\n  \"metadata\": {\"name\": \"x\"},\n  \"steps\": [\n}") }

        assertTrue(failure.message!!.startsWith("invalid JSON at line 4, column "), failure.message)
    }

    @Test
    fun `an invalid scenario fails with the inline load's message`() {
        val cases =
            mapOf(
                """[]""" to "$: expected an object, got []",
                """{"metadata": {"name": "x"}, "steps": [{"text": "a"}], "extra": true}""" to "$: unknown field 'extra'",
                """{"metadata": {"name": "x"}, "steps": []}""" to "a scenario must have at least one step",
                """{"metadata": {"name": "x"}, "steps": [{"text": "a"}, {"text": "b", "stopReason": 7}]}""" to
                    "step 2: $.steps[1].stopReason: expected a string, got 7",
                """{"metadata": {"name": "x"}, "steps": [{"text": "a"}, {"text": "b", "error": {"status": 500}}]}""" to
                    "step 2: a step must set either error or a reply: text, toolCalls or both",
            )
        for ((text, message) in cases) {
            val inline = assertFailsWith<IllegalArgumentException> { ScenarioLoader.decode(Json.parseToJsonElement(text)) }
            val failure = assertFailsWith<IllegalArgumentException> { Scenario.fromJson(text) }

            assertEquals(message, failure.message, text)
            assertEquals(inline.message, failure.message, text)
        }
    }
}
