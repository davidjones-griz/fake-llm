package com.davidjonesgriz.fakellm

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Path
import kotlin.io.path.createDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** `fake-llm validate <file|dir>...`: checks scenario files as a load by name would, without a server. */
class ScenarioValidationTest {
    private data class Outcome(
        val code: Int,
        val out: String,
        val err: String,
    )

    private fun validate(vararg args: String): Outcome {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val code = validateScenarios(args.toList(), PrintStream(out, true), PrintStream(err, true))
        return Outcome(code, out.toString(), err.toString())
    }

    private fun Path.write(
        file: String,
        text: String,
    ): String = resolve(file).also { it.writeText(text) }.toString()

    @Test
    fun `valid files exit 0 and are each reported ok`() {
        withScenarioDir { dir ->
            val a = dir.write("a.json", """{"name":"a","steps":[{"text":"t"}]}""")
            val b = dir.write("b.json", """{"${'$'}schema":"../schema/scenario.schema.json","name":"b","steps":[{"text":"t"}]}""")
            val outcome = validate(a, b)
            assertEquals(0, outcome.code)
            assertContains(outcome.out, "ok $a\n")
            assertContains(outcome.out, "ok $b\n")
            assertContains(outcome.out, "2 files: 2 valid, 0 invalid")
            assertEquals("", outcome.err)
        }
    }

    @Test
    fun `an invalid file exits 1, naming the file, the step and the field`() {
        withScenarioDir { dir ->
            val bad = dir.write("bad.json", """{"name":"bad","steps":[{"text":"a"},{"text":"b","stopReasn":"max_tokens"}]}""")
            val outcome = validate(bad)
            assertEquals(1, outcome.code)
            assertEquals("$bad: step 2: \$.steps[1]: unknown field 'stopReasn'\n", outcome.err)
            assertContains(outcome.out, "1 file: 0 valid, 1 invalid")
        }
    }

    @Test
    fun `a directory means its json files, and every file is checked`() {
        withScenarioDir { dir ->
            val good = dir.write("good.json", """{"name":"good","steps":[{"text":"t"}]}""")
            val renamed = dir.write("renamed.json", """{"name":"other","steps":[{"text":"t"}]}""")
            val illegal = dir.write("has space.json", """{"name":"has space","steps":[{"text":"t"}]}""")
            val broken = dir.write("broken.json", "{\"name\": \"broken\",\n\"steps\": [")
            val notObject = dir.write("list.json", "[]")
            dir.write("notes.txt", "not a scenario")
            dir.resolve("nested").createDirectory().write("deeper.json", "{}")
            val outcome = validate(dir.toString())
            assertEquals(1, outcome.code)
            assertContains(outcome.out, "ok $good\n")
            assertContains(outcome.err, "$renamed: scenario file renamed.json declares name 'other'")
            assertContains(outcome.err, "$illegal: 'has space' is not a legal scenario name")
            assertContains(outcome.err, "$broken: invalid JSON at line 2")
            assertContains(outcome.err, "$notObject: \$: expected an object, got []")
            assertFalse("deeper" in outcome.out + outcome.err, "a directory is not searched recursively")
            assertFalse("notes" in outcome.out + outcome.err)
            assertContains(outcome.out, "5 files: 1 valid, 4 invalid")
        }
    }

    @Test
    fun `usage errors exit 2 before any file is checked`() {
        withScenarioDir { dir ->
            val good = dir.write("good.json", """{"name":"good","steps":[{"text":"t"}]}""")
            val empty = dir.resolve("empty").createDirectory().toString()
            listOf(
                emptyList<String>() to "no files or directories given",
                listOf(good, dir.resolve("missing.json").toString()) to "no such file or directory: ${dir.resolve("missing.json")}",
                listOf(empty) to "no .json files in $empty",
                listOf(good, "--port") to "unknown flag '--port'",
                listOf("a\u0000b") to "invalid path 'a\u0000b': Nul character not allowed",
            ).forEach { (args, detail) ->
                val outcome = validate(*args.toTypedArray())
                assertEquals(2, outcome.code, detail)
                assertEquals("fake-llm validate: $detail\nUsage: fake-llm validate <file|dir>...\n", outcome.err)
                assertEquals("", outcome.out, "nothing is checked after a usage error")
            }
        }
    }

    @Test
    fun `main runs validate without starting a server`() {
        withScenarioDir { dir ->
            val good = dir.write("good.json", """{"name":"good","steps":[{"text":"t"}]}""")
            val captured = ByteArrayOutputStream()
            val original = System.out
            System.setOut(PrintStream(captured, true))
            try {
                main(arrayOf("validate", good))
            } finally {
                System.setOut(original)
            }
            assertContains(captured.toString(), "ok $good")
            assertFalse("listening" in captured.toString(), captured.toString())
        }
    }
}
