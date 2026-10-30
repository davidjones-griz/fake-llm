package com.davidjonesgriz.fakellm

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StartupConfigTest {
    @Test
    fun `command flags set port scenario and scenario directory`() {
        val config =
            parseStartupConfig(
                arrayOf(
                    "--scenario-dir",
                    "/tmp/fake-llm-scenarios",
                    "--port",
                    "9090",
                    "--scenario",
                    "hello",
                ),
            )
        assertEquals(9090, config.port)
        assertEquals("hello", config.scenario)
        assertEquals(Path.of("/tmp/fake-llm-scenarios"), config.scenarioDir)
    }

    @Test
    fun `the served engine gets the configured scenario directory and lenient mode`() {
        val withDir = parseStartupConfig(arrayOf("--scenario-dir", "/tmp/fake-llm-scenarios", "--lenient"), env = emptyMap()).engine()
        assertEquals(Path.of("/tmp/fake-llm-scenarios"), withDir.scenarioDirectory)
        assertTrue(withDir.lenient)

        val defaults = parseStartupConfig(emptyArray(), env = emptyMap()).engine()
        assertNull(defaults.scenarioDirectory)
        assertFalse(defaults.lenient)
    }

    @Test
    fun `FAKE_LLM_SCENARIO_DIR reaches the served engine's scenario listing`() {
        val dir = Files.createTempDirectory("fake-llm-env-scenarios")
        try {
            dir.resolve("from-env.json").writeText("""{"name":"from-env","steps":[{"text":"x"}]}""")
            val engine = parseStartupConfig(emptyArray(), env = mapOf("FAKE_LLM_SCENARIO_DIR" to dir.toString())).engine()
            withServer(engine) { client, _ ->
                val listing = client.get("/control/scenarios").body()
                assertTrue(listing.contains("\"from-env\""), listing)
                assertTrue(listing.contains("\"hello\""), listing)
                assertEquals(200, client.post("/control/scenario", """{"name":"from-env"}""").statusCode())
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `a scenario directory the platform cannot represent is a usage error`() {
        val failure =
            assertFailsWith<StartupUsageException> {
                parseStartupConfig(arrayOf("--scenario-dir", "bad\u0000dir"), env = emptyMap())
            }
        assertTrue(failure.message!!.contains("invalid scenario directory"), failure.message)
    }

    @Test
    fun `omitted flags fall back to the environment and then to built-in defaults`() {
        val config = parseStartupConfig(emptyArray())
        val expectedPort = System.getenv("PORT")?.takeIf { it.isNotBlank() }?.toInt() ?: 8080
        assertEquals(expectedPort, config.port)
        assertEquals(System.getenv("FAKE_LLM_SCENARIO")?.takeIf { it.isNotBlank() }, config.scenario)
        val dir = System.getenv("FAKE_LLM_SCENARIO_DIR")?.takeIf { it.isNotBlank() }
        assertEquals(dir?.let { Path.of(it) }, config.scenarioDir)
        if (System.getenv("PORT").isNullOrBlank()) {
            assertEquals(8080, config.port)
        }
        if (System.getenv("FAKE_LLM_SCENARIO").isNullOrBlank()) {
            assertNull(config.scenario)
        }
        if (System.getenv("FAKE_LLM_SCENARIO_DIR").isNullOrBlank()) {
            assertNull(config.scenarioDir)
        }
        val lenientEnv = System.getenv("FAKE_LLM_LENIENT")?.takeIf { it.isNotBlank() }
        if (lenientEnv == null) {
            assertEquals(false, config.lenient)
        } else {
            assertEquals(lenientEnv.equals("true", ignoreCase = true), config.lenient)
        }
    }

    @Test
    fun `--version with other arguments is an unknown flag`() {
        val e = assertFailsWith<StartupUsageException> { parseStartupConfig(arrayOf("--port", "8080", "--version"), env = emptyMap()) }
        assertTrue(e.message!!.contains("unknown flag '--version'"))
        assertTrue(e.message!!.contains("fake-llm --version"))
    }

    @Test
    fun `an unknown flag is rejected`() {
        val failure =
            assertFailsWith<StartupUsageException> {
                parseStartupConfig(arrayOf("--not-a-flag"))
            }
        assertTrue(failure.message!!.contains("unknown flag"))
        assertTrue(failure.message!!.contains("--not-a-flag"))
        assertTrue(failure.message!!.contains("Usage:"))
        assertTrue(failure.message!!.contains("--lenient"))
    }

    @Test
    fun `an unexpected positional argument is rejected`() {
        val failure =
            assertFailsWith<StartupUsageException> {
                parseStartupConfig(arrayOf("--port", "8080", "extra"))
            }
        assertTrue(failure.message!!.contains("unknown argument"))
        assertTrue(failure.message!!.contains("extra"))
    }

    @Test
    fun `a port outside 0 to 65535 is rejected and the endpoints are accepted`() {
        assertFailsWith<StartupUsageException> { parseStartupConfig(arrayOf("--port", "-1")) }
        assertFailsWith<StartupUsageException> { parseStartupConfig(arrayOf("--port", "65536")) }
        assertFailsWith<StartupUsageException> { parseStartupConfig(arrayOf("--port", "nope")) }
        assertEquals(0, parseStartupConfig(arrayOf("--port", "0")).port)
        assertEquals(65535, parseStartupConfig(arrayOf("--port", "65535")).port)
    }

    @Test
    fun `a flag without a value is rejected`() {
        assertFailsWith<StartupUsageException> { parseStartupConfig(arrayOf("--port")) }
        assertFailsWith<StartupUsageException> { parseStartupConfig(arrayOf("--scenario")) }
        assertFailsWith<StartupUsageException> { parseStartupConfig(arrayOf("--scenario-dir")) }
        val failure =
            assertFailsWith<StartupUsageException> {
                parseStartupConfig(arrayOf("--port", "--scenario"))
            }
        assertTrue(failure.message!!.contains("missing value"))
        assertTrue(failure.message!!.contains("--port"))
    }

    @Test
    fun `lenient defaults to false, reads the environment, and the bare flag turns it on`() {
        assertEquals(false, parseStartupConfig(emptyArray(), env = emptyMap()).lenient)
        assertEquals(true, parseStartupConfig(emptyArray(), env = mapOf("FAKE_LLM_LENIENT" to "true")).lenient)
        assertEquals(true, parseStartupConfig(emptyArray(), env = mapOf("FAKE_LLM_LENIENT" to "TRUE")).lenient)
        assertEquals(false, parseStartupConfig(emptyArray(), env = mapOf("FAKE_LLM_LENIENT" to "false")).lenient)
        assertEquals(false, parseStartupConfig(emptyArray(), env = mapOf("FAKE_LLM_LENIENT" to "False")).lenient)
        assertEquals(false, parseStartupConfig(emptyArray(), env = mapOf("FAKE_LLM_LENIENT" to "   ")).lenient)

        assertEquals(true, parseStartupConfig(arrayOf("--lenient"), env = emptyMap()).lenient)
        assertEquals(
            true,
            parseStartupConfig(arrayOf("--lenient"), env = mapOf("FAKE_LLM_LENIENT" to "false")).lenient,
        )

        val combined =
            parseStartupConfig(
                arrayOf("--port", "9090", "--lenient", "--scenario", "hello"),
                env = mapOf("FAKE_LLM_LENIENT" to "false", "PORT" to "1", "FAKE_LLM_SCENARIO" to "other"),
            )
        assertEquals(9090, combined.port)
        assertEquals(true, combined.lenient)
        assertEquals("hello", combined.scenario)

        val valueAfterFlag =
            assertFailsWith<StartupUsageException> {
                parseStartupConfig(arrayOf("--lenient", "true"), env = emptyMap())
            }
        assertTrue(valueAfterFlag.message!!.contains("unknown argument 'true'"))

        val badEnv =
            assertFailsWith<StartupUsageException> {
                parseStartupConfig(emptyArray(), env = mapOf("FAKE_LLM_LENIENT" to "1"))
            }
        assertTrue(badEnv.message!!.contains("1"))
        assertTrue(badEnv.message!!.contains("expected true or false"))
    }
}
