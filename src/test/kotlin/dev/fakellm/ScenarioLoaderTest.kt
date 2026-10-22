package dev.fakellm

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ScenarioLoaderTest {
    @BeforeTest
    fun clearDirectory() {
        ScenarioLoader.setScenarioDirectory(null)
    }

    @AfterTest
    fun clearDirectoryAfter() {
        ScenarioLoader.setScenarioDirectory(null)
    }

    @Test
    fun `a scenario directory is searched before the classpath`() {
        val dir = createScenarioDir()
        try {
            writeScenario(
                dir,
                "hello",
                """
                {
                  "name": "hello",
                  "description": "override",
                  "rules": [
                    { "role": "override", "respond": [ { "text": "Directory scenario wins." } ] }
                  ]
                }
                """.trimIndent(),
            )
            writeScenario(
                dir,
                "local-only",
                """
                {
                  "name": "local-only",
                  "rules": [
                    { "role": "local", "respond": [ { "text": "from the directory" } ] }
                  ]
                }
                """.trimIndent(),
            )
            ScenarioLoader.setScenarioDirectory(dir)

            val overridden = ScenarioLoader.load("hello")
            assertEquals("Directory scenario wins.", overridden.rules.single().respond.single().text)
            assertEquals("override", overridden.rules.single().role)

            val local = ScenarioLoader.load("local-only")
            assertEquals("from the directory", local.rules.single().respond.single().text)

            val names = ScenarioLoader.availableNames()
            assertEquals(names.sorted(), names)
            assertEquals(1, names.count { it == "hello" })
            assertTrue(names.contains("local-only"))
            assertTrue(names.contains("refusal"))

            ScenarioLoader.setScenarioDirectory(null)
            val restored = ScenarioLoader.load("hello")
            assertEquals(
                "Hello. I can help with the weather, a simple recipe, or a short how-to.",
                restored.rules.single().respond.single().text,
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `a name missing from the scenario directory falls back to the classpath`() {
        val dir = createScenarioDir()
        try {
            writeScenario(
                dir,
                "local-only",
                """
                {
                  "name": "local-only",
                  "rules": [
                    { "role": "local", "respond": [ { "text": "from the directory" } ] }
                  ]
                }
                """.trimIndent(),
            )
            ScenarioLoader.setScenarioDirectory(dir)
            val classpath = ScenarioLoader.load("refusal")
            assertEquals("refusal", classpath.name)
            assertEquals("I can't help with that request.", classpath.rules.single().respond.single().text)
            assertEquals("from the directory", ScenarioLoader.load("local-only").rules.single().respond.single().text)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `scenario names that leave the directory are rejected`() {
        val dir = createScenarioDir()
        try {
            ScenarioLoader.setScenarioDirectory(dir)
            listOf("../hello", "..", ".", "foo/bar", "foo\\bar", "/tmp/hello", "hello.json", "", "has space").forEach { name ->
                val failure =
                    assertFailsWith<IllegalArgumentException>("name=$name") {
                        ScenarioLoader.load(name)
                    }
                assertTrue(failure.message!!.contains("illegal scenario name"), name)
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `a scenario file whose declared name differs from the file name is rejected`() {
        val dir = createScenarioDir()
        try {
            writeScenario(
                dir,
                "hello",
                """
                {
                  "name": "not-hello",
                  "rules": [
                    { "role": "r", "respond": [ { "text": "x" } ] }
                  ]
                }
                """.trimIndent(),
            )
            ScenarioLoader.setScenarioDirectory(dir)
            val failure = assertFailsWith<IllegalStateException> { ScenarioLoader.load("hello") }
            assertTrue(failure.message!!.contains("hello"))
            assertTrue(failure.message!!.contains("not-hello"))
            assertTrue(failure.message!!.contains("must match"))
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun createScenarioDir(): File = Files.createTempDirectory("fake-llm-scenarios").toFile()

    private fun writeScenario(
        dir: File,
        name: String,
        json: String,
    ) {
        File(dir, "$name.json").writeText(json)
    }
}
