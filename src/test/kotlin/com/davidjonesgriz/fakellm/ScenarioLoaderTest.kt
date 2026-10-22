package com.davidjonesgriz.fakellm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScenarioLoaderTest {
    @Test
    fun `a scenario directory is searched before the classpath`() {
        withScenarioDir { dir ->
            writeScenario(dir, "hello", singleReply("hello", role = "override", text = "Directory scenario wins."))
            writeScenario(dir, "local-only", singleReply("local-only", role = "local", text = "from the directory"))

            val overridden = ScenarioLoader.load("hello", dir)
            assertEquals("Directory scenario wins.", overridden.onlyText())
            assertEquals("override", overridden.rules.single().role)
            assertEquals("from the directory", ScenarioLoader.load("local-only", dir).onlyText())

            val names = ScenarioLoader.availableNames(dir)
            assertEquals(names.sorted(), names)
            assertEquals(1, names.count { it == "hello" })
            assertTrue(names.contains("local-only"))
            assertTrue(names.contains("refusal"))

            // The directory belongs to the call, not the process: without one, the bundled file loads.
            assertEquals(BUNDLED_HELLO, ScenarioLoader.load("hello", directory = null).onlyText())
            assertFalse(ScenarioLoader.availableNames(directory = null).contains("local-only"))
        }
    }

    @Test
    fun `a name missing from the scenario directory falls back to the classpath`() {
        withScenarioDir { dir ->
            writeScenario(dir, "local-only", singleReply("local-only", role = "local", text = "from the directory"))
            val classpath = ScenarioLoader.load("refusal", dir)
            assertEquals("refusal", classpath.name)
            assertEquals("I can't help with that request.", classpath.onlyText())
            assertEquals("from the directory", ScenarioLoader.load("local-only", dir).onlyText())
        }
    }

    @Test
    fun `scenario names that leave the directory are rejected`() {
        withScenarioDir { dir ->
            listOf("../hello", "..", ".", "foo/bar", "foo\\bar", "/tmp/hello", "hello.json", "", "has space").forEach { name ->
                val failure =
                    assertFailsWith<IllegalArgumentException>("name=$name") {
                        ScenarioLoader.load(name, dir)
                    }
                assertTrue(failure.message!!.contains("illegal scenario name"), name)
            }
        }
    }

    @Test
    fun `a scenario file whose declared name differs from the file name is rejected`() {
        withScenarioDir { dir ->
            writeScenario(dir, "hello", singleReply("not-hello", role = "r", text = "x"))
            val failure = assertFailsWith<IllegalStateException> { ScenarioLoader.load("hello", dir) }
            assertTrue(failure.message!!.contains("hello"))
            assertTrue(failure.message!!.contains("not-hello"))
            assertTrue(failure.message!!.contains("must match"))
        }
    }

    @Test
    fun `an unknown name names every place that was searched`() {
        withScenarioDir { dir ->
            val withDir = assertFailsWith<IllegalStateException> { ScenarioLoader.load("missing", dir) }
            assertEquals(
                "unknown scenario 'missing' — no missing.json in ${dir.toFile().path} and no /scenarios/missing.json on the classpath",
                withDir.message,
            )
        }
        val classpathOnly = assertFailsWith<IllegalStateException> { ScenarioLoader.load("missing", directory = null) }
        assertEquals("unknown scenario 'missing' — no /scenarios/missing.json on the classpath", classpathOnly.message)
    }

    @Test
    fun `a directory that does not exist lists nothing and falls back to the classpath`() {
        withScenarioDir { parent ->
            val missing = parent.resolve("no-such-scenario-dir")
            assertFalse(Files.exists(missing))
            assertEquals(ScenarioLoader.availableNames(directory = null), ScenarioLoader.availableNames(missing))
            assertEquals(BUNDLED_HELLO, ScenarioLoader.load("hello", missing).onlyText())
        }
    }

    @Test
    fun `servers in one JVM serve and list their own engine's directory`() {
        withScenarioDir { first ->
            withScenarioDir { second ->
                writeScenario(first, "same", singleReply("same", role = "first", text = "from the first directory"))
                writeScenario(first, "only-first", singleReply("only-first", role = "first", text = "x"))
                writeScenario(second, "same", singleReply("same", role = "second", text = "from the second directory"))
                withServer(ScenarioEngine(scenarioDirectory = first)) { firstClient, _ ->
                    withServer(ScenarioEngine(scenarioDirectory = second)) { secondClient, _ ->
                        // Both servers load and answer at the same time, each from its own directory.
                        val pool = Executors.newFixedThreadPool(2)
                        try {
                            val replies =
                                listOf(firstClient, secondClient)
                                    .map { client ->
                                        pool.submit(
                                            Callable {
                                                val loaded = client.post("/control/scenario", """{"name":"same"}""")
                                                assertEquals(200, loaded.statusCode(), loaded.body())
                                                anthropicText(client.post("/anthropic/v1/messages", anthropicBody("hi")).body())
                                            },
                                        )
                                    }.map { it.get(30, TimeUnit.SECONDS) }
                            assertEquals(listOf("from the first directory", "from the second directory"), replies)
                        } finally {
                            pool.shutdownNow()
                        }

                        assertTrue(firstClient.scenarioNames().contains("only-first"))
                        assertFalse(secondClient.scenarioNames().contains("only-first"))
                        assertTrue(secondClient.scenarioNames().contains("hello"))
                    }
                }
            }
        }
    }

    private fun TestClient.scenarioNames(): List<String> =
        Json
            .parseToJsonElement(get("/control/scenarios").body())
            .jsonObject
            .getValue("scenarios")
            .jsonArray
            .map { it.jsonPrimitive.content }

    private fun Scenario.onlyText(): String? =
        rules
            .single()
            .respond
            .single()
            .text

    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    private fun withScenarioDir(block: (Path) -> Unit) {
        val dir = createTempDirectory("fake-llm-scenarios")
        try {
            block(dir)
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun writeScenario(
        dir: Path,
        name: String,
        json: String,
    ) {
        dir.resolve("$name.json").writeText(json)
    }

    private fun singleReply(
        name: String,
        role: String,
        text: String,
    ): String = """{ "name": "$name", "rules": [ { "role": "$role", "respond": [ { "text": "$text" } ] } ] }"""

    private companion object {
        const val BUNDLED_HELLO = "Hello. I can help with the weather, a simple recipe, or a short how-to."
    }
}
