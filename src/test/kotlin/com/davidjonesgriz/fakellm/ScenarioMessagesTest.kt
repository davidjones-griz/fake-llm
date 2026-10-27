package com.davidjonesgriz.fakellm

import kotlinx.serialization.json.Json
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The decoder's messages name the file path of the field at fault, in words a scenario author can
 * act on. Each kind of problem is pinned, so a kotlinx-serialization upgrade cannot change them.
 */
class ScenarioMessagesTest {
    private fun messageFor(scenario: String): String =
        assertFailsWith<IllegalArgumentException> { ScenarioLoader.decode(Json.parseToJsonElement(scenario)) }.message!!

    @Test
    fun `an unknown field is named with its path`() {
        assertEquals(
            "step 2: \$.steps[1]: unknown field 'foo'",
            messageFor("""{"name":"x","steps":[{"text":"a"},{"text":"b","foo":1}]}"""),
        )
        assertEquals("\$: unknown field 'rules'", messageFor("""{"name":"x","rules":[]}"""))
        assertEquals("\$: unknown field 'it\\'s'", messageFor("""{"name":"x","steps":[{"text":"t"}],"it's":1}"""))
        assertEquals(
            "step 1: \$.steps[0].error: unknown field 'extra'",
            messageFor("""{"name":"x","steps":[{"error":{"status":500,"extra":1}}]}"""),
        )
    }

    @Test
    fun `a missing required field is named`() {
        assertEquals("\$: missing field 'name'", messageFor("""{"steps":[{"text":"t"}]}"""))
        assertEquals(
            "step 1: \$.steps[0].error: missing field 'status'",
            messageFor("""{"name":"x","steps":[{"error":{"body":"b"}}]}"""),
        )
    }

    @Test
    fun `a value of the wrong JSON type is named with what was expected`() {
        assertEquals(
            "step 1: \$.steps[0].streamChunkChars: expected an integer, got \"abc\"",
            messageFor("""{"name":"x","steps":[{"text":"t","streamChunkChars":"abc"}]}"""),
        )
        assertEquals("\$.name: expected a string, got 3", messageFor("""{"name":3,"steps":[{"text":"t"}]}"""))
        assertEquals("\$.steps: expected an array, got {}", messageFor("""{"name":"x","steps":{}}"""))
        assertEquals("step 1: \$.steps[0]: expected an object, got \"hi\"", messageFor("""{"name":"x","steps":["hi"]}"""))
        assertEquals("\$: expected an object, got []", messageFor("[]"))
    }

    @Test
    fun `an integer must be a plain JSON integer in range, not quoted, fractional or with an exponent`() {
        listOf("\"24\"", "24.0", "2e1", "2E+1", "1.5e3").forEach { value ->
            assertEquals(
                "step 1: \$.steps[0].streamChunkChars: expected an integer, got $value",
                messageFor("""{"name":"x","steps":[{"text":"t","streamChunkChars":$value}]}"""),
            )
        }
        assertEquals(
            "step 1: \$.steps[0].streamChunkChars: expected an integer from -2147483648 to 2147483647, got 3000000000",
            messageFor("""{"name":"x","steps":[{"text":"t","streamChunkChars":3000000000}]}"""),
        )
        assertEquals(
            "step 1: \$.steps[0].error.status: expected an integer, got \"500\"",
            messageFor("""{"name":"x","steps":[{"error":{"status":"500"}}]}"""),
        )
        // A Long field's shape check takes the whole Long range, and the cap rejects what is past 600000.
        assertEquals(
            "step 1: streamInterChunkDelayMs must be from 0 to 600000 (10 minutes), got 9223372036854775807",
            messageFor("""{"name":"x","steps":[{"text":"t","streamInterChunkDelayMs":9223372036854775807}]}"""),
        )
        val max = """{"name":"x","steps":[{"text":"t","streamInterChunkDelayMs":600000}]}"""
        assertEquals(600_000L, ScenarioLoader.decode(Json.parseToJsonElement(max)).steps[0].streamInterChunkDelayMs)
        assertEquals(
            "step 1: \$.steps[0].streamInterChunkDelayMs: expected an integer from -9223372036854775808 to 9223372036854775807, " +
                "got 9223372036854775808",
            messageFor("""{"name":"x","steps":[{"text":"t","streamInterChunkDelayMs":9223372036854775808}]}"""),
        )
    }

    @Test
    fun `null is named for a field that does not take it`() {
        assertEquals("\$.description: must not be null", messageFor("""{"name":"x","description":null,"steps":[{"text":"t"}]}"""))
        assertEquals("\$.steps: must not be null", messageFor("""{"name":"x","steps":null}"""))
    }

    @Test
    fun `a map value is named by its key`() {
        assertEquals(
            "step 1: \$.steps[0].error.headers['a-b']: expected a string, got 1",
            messageFor("""{"name":"x","steps":[{"error":{"status":500,"headers":{"a-b":1}}}]}"""),
        )
        assertEquals(
            "step 1: \$.steps[0].error.headers['it\\'s']: expected a string, got 1",
            messageFor("""{"name":"x","steps":[{"error":{"status":500,"headers":{"it's":1}}}]}"""),
        )
    }

    @Test
    fun `a rule the model enforces keeps its own message`() {
        assertEquals(
            "step 1: status must be an HTTP status (100 to 599), got 700",
            messageFor("""{"name":"x","steps":[{"error":{"status":700}}]}"""),
        )
        assertEquals(
            "step 2: a step must set either error or a reply: text, toolCalls or both",
            messageFor("""{"name":"x","steps":[{"text":"a"},{}]}"""),
        )
        assertEquals("scenario 'x' has no steps", messageFor("""{"name":"x","steps":[]}"""))
    }

    @Test
    fun `tool call arguments must be a JSON object, named with its path`() {
        assertEquals(
            "step 1: \$.steps[0].toolCalls[0].arguments: expected an object, got [1]",
            messageFor("""{"name":"x","steps":[{"toolCalls":[{"name":"f","arguments":[1]}]}]}"""),
        )
    }

    @Test
    fun `any JSON is accepted inside arguments`() {
        val scenario =
            ScenarioLoader.decode(
                Json.parseToJsonElement(
                    """{"name":"x","steps":[{"toolCalls":[{"name":"f","arguments":{"a":null,"b":[1,{"c":true}]}}]}]}""",
                ),
            )
        assertEquals("""{"a":null,"b":[1,{"c":true}]}""", scenario.steps[0].toolCalls!![0].argumentText())
    }

    @Test
    fun `a tool call rule keeps its own message`() {
        assertEquals(
            "step 1: a tool call sets at most one of arguments and rawArguments",
            messageFor("""{"name":"x","steps":[{"toolCalls":[{"name":"f","arguments":{},"rawArguments":"{}"}]}]}"""),
        )
    }

    @Test
    fun `malformed JSON in a scenario file is reported by line and column, without the file`() {
        withScenarioDir { dir ->
            dir.resolve("broken.json").writeText("{\n  \"name\": \"broken\",\n  \"steps\": [}\n")
            val message = assertFailsWith<IllegalArgumentException> { ScenarioLoader.load("broken", dir) }.message!!
            assertEquals(
                "invalid JSON at line 3, column 13: Unexpected JSON token: Expected end of the array ']', but had '}' instead",
                message.substringBefore(" at path"),
            )
            assertEquals(1, message.lines().size, message)
        }
    }

    @Test
    fun `a top-level string $schema is accepted and ignored, anything else is not`() {
        val withSchema = """{"${'$'}schema":"https://example.invalid/s.json","name":"x","steps":[{"text":"t"}]}"""
        assertEquals("x", ScenarioLoader.decode(Json.parseToJsonElement(withSchema)).name)
        assertEquals(
            "\$.\$schema: must not be null",
            messageFor("""{"${'$'}schema":null,"name":"x","steps":[{"text":"t"}]}"""),
        )
        assertEquals(
            "\$.\$schema: expected a string, got 1",
            messageFor("""{"${'$'}schema":1,"name":"x","steps":[{"text":"t"}]}"""),
        )
        assertEquals(
            "step 1: \$.steps[0]: unknown field '\$schema'",
            messageFor("""{"name":"x","steps":[{"${'$'}schema":"s","text":"t"}]}"""),
        )
    }

    @Test
    fun `a scenario file with $schema loads by name`() {
        withScenarioDir { dir ->
            writeScenario(
                dir,
                "schema-ok",
                """{"${'$'}schema":"../schema/scenario.schema.json","name":"schema-ok","steps":[{"text":"t"}]}""",
            )
            assertEquals("schema-ok", ScenarioLoader.load("schema-ok", dir).name)
        }
    }
}
