package com.davidjonesgriz.fakellm

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** `allOf` and `anyOf` in a text rule: one level of plain rules, on every key that takes a text rule. */
class TextCombinatorTest {
    private val req =
        parseAnthropicRequest(
            """{"model":"m","system":"Merge the summaries, urgent.",
            "messages":[{"role":"user","content":"Summarize doc-a asap"},
              {"role":"assistant","content":[{"type":"tool_use","id":"t1","name":"f","input":{}}]},
              {"role":"user","content":[{"type":"tool_result","tool_use_id":"t1","content":"sunny and warm"}]},
              {"role":"user","content":"plan b"}]}""",
        ).withHeaders(mapOf("X-Tenant" to listOf("acme-prod")))

    private fun holds(match: Match) = match.holds("anthropic", req)

    private fun all(vararg rules: TextMatch) = TextMatch(allOf = rules.toList())

    private fun any(vararg rules: TextMatch) = TextMatch(anyOf = rules.toList())

    private fun messageFor(scenario: String) =
        assertFailsWith<IllegalArgumentException> { ScenarioLoader.decode(Json.parseToJsonElement(scenario)) }.message!!

    private fun withMatch(match: String) = """{"steps":[{"match":$match,"text":"t"}]}"""

    @Test
    fun `allOf holds when every rule holds, and anyOf when at least one does`() {
        val doc = TextMatch(contains = "plan")
        val urgent = TextMatch(regex = "urgent|asap")
        val nope = TextMatch(equalTo = "nope")
        assertTrue(all(doc, TextMatch(regex = "b$")).matches("plan b"))
        assertFalse(all(doc, urgent).matches("plan b"), "one rule fails")
        assertTrue(any(nope, doc).matches("plan b"))
        assertFalse(any(nope, urgent).matches("plan b"), "no rule holds")
        assertTrue(all(doc).matches("plan b") && any(doc).matches("plan b"), "a single rule works in either")
    }

    @Test
    fun `they work on system, lastUser, prompt, message, header and toolResult content`() {
        assertTrue(holds(Match(system = all(TextMatch(contains = "Merge"), TextMatch(regex = "urgent|asap")))))
        assertFalse(holds(Match(system = all(TextMatch(contains = "Merge"), TextMatch(contains = "calm")))))
        assertTrue(holds(Match(lastUser = any(TextMatch(contains = "doc-a"), TextMatch(equalTo = "plan b")))))
        assertFalse(holds(Match(lastUser = all(TextMatch(contains = "doc-a"), TextMatch(equalTo = "plan b")))))
        assertTrue(holds(Match(prompt = all(TextMatch(contains = "doc-a"), TextMatch(contains = "plan b")))))
        assertTrue(
            holds(Match(message = MessageMatch(index = 0, allOf = listOf(TextMatch(contains = "doc-a"), TextMatch(regex = "asap$"))))),
        )
        assertFalse(holds(Match(message = MessageMatch(index = 0, anyOf = listOf(TextMatch(contains = "x"), TextMatch(contains = "y"))))))
        assertTrue(
            holds(Match(message = MessageMatch(role = "user", anyOf = listOf(TextMatch(contains = "x"), TextMatch(contains = "plan"))))),
        )
        assertTrue(
            holds(
                Match(header = HeaderMatch(name = "x-tenant", allOf = listOf(TextMatch(contains = "acme"), TextMatch(regex = "-prod$")))),
            ),
        )
        assertFalse(
            holds(Match(header = HeaderMatch(name = "x-tenant", allOf = listOf(TextMatch(contains = "acme"), TextMatch(regex = "-dev$"))))),
        )
        assertTrue(holds(Match(toolResult = ToolResultMatch(content = all(TextMatch(contains = "sunny"), TextMatch(contains = "warm"))))))
        assertFalse(holds(Match(toolResult = ToolResultMatch(content = any(TextMatch(contains = "rain"), TextMatch(contains = "snow"))))))
    }

    @Test
    fun `the decoder reads them in every place a text rule goes`() {
        val s =
            ScenarioLoader.decode(
                Json.parseToJsonElement(
                    """{"steps":[{"match":{
                      "system":{"anyOf":[{"contains":"a"},{"equals":"b"}]},
                      "lastUser":{"allOf":[{"contains":"doc-a"},{"regex":"urgent|asap"}]},
                      "prompt":{"anyOf":[{"regex":"x"}]},
                      "message":{"role":"user","anyOf":[{"contains":"x"},{"contains":"y"}]},
                      "header":{"name":"x-tenant","allOf":[{"contains":"acme"},{"regex":"-prod$"}]},
                      "toolResult":{"content":{"allOf":[{"contains":"ok"}]}}},"text":"t"}]}""",
                ),
            )
        val match = s.steps.single().match!!
        assertEquals(all(TextMatch(contains = "doc-a"), TextMatch(regex = "urgent|asap")), match.lastUser)
        assertEquals(MessageMatch(role = "user", anyOf = listOf(TextMatch(contains = "x"), TextMatch(contains = "y"))), match.message)
        assertEquals(
            HeaderMatch(name = "x-tenant", allOf = listOf(TextMatch(contains = "acme"), TextMatch(regex = "-prod$"))),
            match.header,
        )
    }

    @Test
    fun `nesting, empty arrays and mixing with plain keys are rejected, in every place`() {
        val nested = "step 1: allOf and anyOf take plain text rules (contains, equals or regex), not another allOf or anyOf"
        assertEquals(nested, messageFor(withMatch("""{"lastUser":{"allOf":[{"anyOf":[{"contains":"a"}]}]}}""")))
        assertEquals(nested, messageFor(withMatch("""{"system":{"anyOf":[{"contains":"a"},{"allOf":[{"contains":"b"}]}]}}""")))
        assertEquals(nested, messageFor(withMatch("""{"message":{"index":0,"anyOf":[{"anyOf":[{"contains":"a"}]}]}}""")))
        assertEquals(nested, messageFor(withMatch("""{"header":{"name":"h","allOf":[{"allOf":[{"contains":"a"}]}]}}""")))
        assertEquals(nested, messageFor(withMatch("""{"toolResult":{"content":{"allOf":[{"anyOf":[{"contains":"a"}]}]}}}""")))

        assertEquals("step 1: allOf must not be empty", messageFor(withMatch("""{"prompt":{"allOf":[]}}""")))
        assertEquals("step 1: anyOf must not be empty", messageFor(withMatch("""{"message":{"role":"user","anyOf":[]}}""")))
        assertEquals("step 1: anyOf must not be empty", messageFor(withMatch("""{"header":{"name":"h","anyOf":[]}}""")))

        val oneKey = "step 1: a text match sets exactly one of contains, equals, regex, allOf and anyOf"
        assertEquals(oneKey, messageFor(withMatch("""{"lastUser":{"contains":"a","allOf":[{"contains":"b"}]}}""")))
        assertEquals(oneKey, messageFor(withMatch("""{"lastUser":{"allOf":[{"contains":"a"}],"anyOf":[{"contains":"b"}]}}""")))
        assertEquals(oneKey, messageFor(withMatch("""{"message":{"index":0,"regex":"a","anyOf":[{"contains":"b"}]}}""")))
        assertEquals(oneKey, messageFor(withMatch("""{"header":{"name":"h","equals":"a","allOf":[{"contains":"b"}]}}""")))
        assertEquals(oneKey, messageFor(withMatch("""{"system":{}}""")), "still exactly one")
        assertEquals(
            "step 1: a text match sets exactly one of contains, equals, regex, allOf and anyOf",
            messageFor(withMatch("""{"lastUser":{"allOf":[{}]}}""")),
            "an item is itself a text rule with exactly one key",
        )
        assertEquals(
            "step 1: \$.steps[0].match.lastUser.allOf: expected an array, got {\"contains\":\"a\"}",
            messageFor(withMatch("""{"lastUser":{"allOf":{"contains":"a"}}}""")),
        )
        assertEquals(
            "step 1: \$.steps[0].match.lastUser.allOf[1]: unknown field 'name'",
            messageFor(withMatch("""{"lastUser":{"allOf":[{"contains":"a"},{"name":"h","contains":"b"}]}}""")),
        )
        assertTrue(messageFor(withMatch("""{"lastUser":{"anyOf":[{"regex":"(unclosed"}]}}""")).contains("does not compile"))
    }

    @Test
    fun `the Kotlin model enforces the same rules`() {
        assertFailsWith<IllegalArgumentException> { TextMatch(allOf = emptyList()) }
        assertFailsWith<IllegalArgumentException> { TextMatch(anyOf = listOf(TextMatch(allOf = listOf(TextMatch(contains = "a"))))) }
        assertFailsWith<IllegalArgumentException> { TextMatch(contains = "a", anyOf = listOf(TextMatch(contains = "b"))) }
        assertFailsWith<IllegalArgumentException> { MessageMatch(index = 0, allOf = emptyList()) }
        assertFailsWith<IllegalArgumentException> { HeaderMatch(name = "h", equalTo = "a", anyOf = listOf(TextMatch(contains = "b"))) }
    }

    @Test
    fun `a no-match message describes them readably`() {
        assertEquals(
            """lastUser allOf(contains "doc-a", regex "urgent|asap")""",
            Match(lastUser = all(TextMatch(contains = "doc-a"), TextMatch(regex = "urgent|asap"))).describe(),
        )
        assertEquals(
            """message(role "user") anyOf(contains "x", equals "y")""",
            Match(message = MessageMatch(role = "user", anyOf = listOf(TextMatch(contains = "x"), TextMatch(equalTo = "y")))).describe(),
        )
        assertEquals(
            """header x-tenant allOf(contains "acme", regex "-prod$")""",
            Match(
                header = HeaderMatch(name = "x-tenant", allOf = listOf(TextMatch(contains = "acme"), TextMatch(regex = "-prod$"))),
            ).describe(),
        )
        val engine =
            ScenarioEngine().also {
                it.loadInline(
                    Scenario(
                        steps =
                            listOf(
                                Step(text = "t", match = Match(lastUser = any(TextMatch(contains = "x"), TextMatch(equalTo = "y")))),
                            ),
                    ),
                )
            }
        val body = engine.handle("anthropic", parseAnthropicRequest(anthropicBody("z"))).body
        assertTrue(body.contains("""step 1 (lastUser anyOf(contains \"x\", equals \"y\"))"""), body)
    }
}
