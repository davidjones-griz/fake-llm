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
    fun `an invalid step in a scenario file is reported by its 1-based number`() {
        withScenarioDir { dir ->
            writeScenario(dir, "bad", """{"name":"bad","steps":[{"text":"ok"},{"text":"x","error":{"status":500}}]}""")
            val failure = assertFailsWith<IllegalArgumentException> { ScenarioLoader.load("bad", dir) }
            assertTrue(failure.message!!.startsWith("step 2: "), failure.message)
        }
    }

    @Test
    fun `an invalid inline step is reported by its number`() {
        val inline = Json.parseToJsonElement("""{"name":"bad","steps":[{"text":"ok"},{"text":"ok"},{}]}""")
        val failure = assertFailsWith<IllegalArgumentException> { ScenarioLoader.decode(inline) }
        assertTrue(failure.message!!.startsWith("step 3: "), failure.message)
    }

    @Test
    fun `a scenario with no steps, or in the removed rules format, does not load`() {
        withScenarioDir { dir ->
            writeScenario(dir, "empty", """{"name":"empty","steps":[]}""")
            assertFailsWith<IllegalArgumentException> { ScenarioLoader.load("empty", dir) }
            writeScenario(dir, "old", """{"name":"old","rules":[{"role":"r","respond":[{"text":"x"}]}]}""")
            val old = assertFailsWith<IllegalArgumentException> { ScenarioLoader.load("old", dir) }
            assertTrue(old.message!!.contains("rules"), old.message)
        }
    }

    @Test
    fun `a scenario directory is searched before the classpath`() {
        withScenarioDir { dir ->
            writeScenario(dir, "hello", singleReply("hello", text = "Directory scenario wins."))
            writeScenario(dir, "local-only", singleReply("local-only", text = "from the directory"))

            val overridden = ScenarioLoader.load("hello", dir)
            assertEquals("Directory scenario wins.", overridden.onlyText())
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
            writeScenario(dir, "local-only", singleReply("local-only", text = "from the directory"))
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
            writeScenario(dir, "hello", singleReply("not-hello", text = "x"))
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
                "unknown scenario 'missing' — no missing.json in ${dir.toFile().path}, " +
                    "no /scenarios/missing.json on the classpath and no bundled scenario 'missing'",
                withDir.message,
            )
        }
        val classpathOnly = assertFailsWith<IllegalStateException> { ScenarioLoader.load("missing", directory = null) }
        assertEquals(
            "unknown scenario 'missing' — no /scenarios/missing.json on the classpath and no bundled scenario 'missing'",
            classpathOnly.message,
        )
    }

    @Test
    fun `lookup by name tries the directory, then the project's classpath, then the bundled scenarios`() {
        withScenarioDir { dir ->
            withProjectRoots(
                // Each root is one entry on a project's classpath, holding scenarios/<name>.json.
                mapOf(
                    "hello.json" to singleReply("hello", text = "project hello"),
                    "refusal.json" to singleReply("refusal", text = "project refusal"),
                ),
            ) { loader ->
                writeScenario(dir, "refusal", singleReply("refusal", text = "directory refusal"))

                assertEquals("directory refusal", ScenarioLoader.load("refusal", dir, loader).onlyText(), "the directory beats the project")
                assertEquals("project hello", ScenarioLoader.load("hello", dir, loader).onlyText(), "the project beats the bundled file")
                assertEquals("project refusal", ScenarioLoader.load("refusal", null, loader).onlyText())
                assertEquals(BUNDLED_HELLO, ScenarioLoader.load("hello", null).onlyText(), "without the project, the bundled file")
                assertEquals("hello", ScenarioLoader.load("hello", null, loader).name)
                // The project's loader sees no bundled files, which are read through ScenarioLoader itself.
                assertEquals("rate-limit-429", ScenarioLoader.load("rate-limit-429", null, loader).name)
            }
        }
    }

    @Test
    fun `the first project classpath root with the scenario wins`() {
        withProjectRoots(
            mapOf("shared.json" to singleReply("shared", text = "first root")),
            mapOf("shared.json" to singleReply("shared", text = "second root")),
        ) { loader ->
            assertEquals("first root", ScenarioLoader.load("shared", null, loader).onlyText())
        }
    }

    @Test
    fun `the listing merges every project index with the bundled names and the directory`() {
        withScenarioDir { dir ->
            writeScenario(dir, "from-directory", singleReply("from-directory", text = "d"))
            withProjectRoots(
                mapOf("index.txt" to "# one module's scenarios\nfirst-module\n\nshared\n"),
                mapOf("index.txt" to "  second-module  \nshared\nhello\n"),
            ) { loader ->
                val names = ScenarioLoader.availableNames(dir, loader)
                assertEquals(names.sorted(), names)
                assertEquals(names.distinct(), names)
                assertTrue(names.containsAll(BUNDLED_NAMES), "bundled names are always listed: $names")
                assertTrue(names.containsAll(listOf("first-module", "second-module", "shared", "from-directory")), "$names")
                assertEquals(BUNDLED_NAMES.size + 4, names.size, "$names")
            }
        }
    }

    @Test
    fun `names that are not legal scenario names are not listed`() {
        withScenarioDir { dir ->
            dir.resolve("has space.json").writeText("{}")
            withProjectRoots(mapOf("index.txt" to "fine\n../escape\nhas space\n")) { loader ->
                val names = ScenarioLoader.availableNames(dir, loader)
                assertTrue("fine" in names, "$names")
                assertEquals(emptyList(), names.filter { !it.matches(Regex("[A-Za-z0-9_-]+")) })
            }
        }
    }

    @Test
    fun `without a project index the listing is the bundled names`() {
        withProjectRoots(mapOf("hello.json" to singleReply("hello", text = "p"))) { loader ->
            assertEquals(BUNDLED_NAMES.sorted(), ScenarioLoader.availableNames(null, loader))
        }
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
                writeScenario(first, "same", singleReply("same", text = "from the first directory"))
                writeScenario(first, "only-first", singleReply("only-first", text = "x"))
                writeScenario(second, "same", singleReply("same", text = "from the second directory"))
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

    private fun Scenario.onlyText(): String? = steps.single().text

    /**
     * Runs [block] with a classpath of its own: one root per map, each holding `scenarios/<file>`
     * for every entry. The loader has no parent, so it sees nothing else; the bundled scenarios are
     * still read through ScenarioLoader's own class.
     */
    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    private fun withProjectRoots(
        vararg roots: Map<String, String>,
        block: (ClassLoader) -> Unit,
    ) {
        val dirs = roots.map { files -> createTempDirectory("fake-llm-root").also { root -> writeRoot(root, files) } }
        try {
            java.net.URLClassLoader(dirs.map { it.toUri().toURL() }.toTypedArray(), null).use(block)
        } finally {
            dirs.forEach { it.deleteRecursively() }
        }
    }

    private fun writeRoot(
        root: Path,
        files: Map<String, String>,
    ) {
        val scenarios = Files.createDirectories(root.resolve("scenarios"))
        files.forEach { (file, text) -> scenarios.resolve(file).writeText(text) }
    }

    private fun singleReply(
        name: String,
        text: String,
    ): String = """{ "name": "$name", "steps": [ { "text": "$text" } ] }"""

    private companion object {
        const val BUNDLED_HELLO = "Hello. I can help with the weather, a simple recipe, or a short how-to."
        val BUNDLED_NAMES =
            listOf(
                "fail-then-retry",
                "hello",
                "malformed-json",
                "max-tokens-cutoff",
                "overloaded-529",
                "rate-limit-429",
                "refusal",
                "stream-error-midway",
                "thinking-stream",
            )
    }
}
