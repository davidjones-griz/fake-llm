package com.davidjonesgriz.fakellm.testcontainers

import org.testcontainers.utility.DockerImageName
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The container's configuration, checked without starting it, so these tests need no Docker. */
class FakeLlmContainerConfigTest {
    @Test
    fun `the default image is the release with this library's version`() {
        assertEquals(
            "ghcr.io/davidjones-griz/fake-llm:${System.getProperty("fakellm.version")}",
            FakeLlmContainer.DEFAULT_IMAGE.asCanonicalNameString(),
        )
    }

    @Test
    fun `it exposes the server's port`() {
        FakeLlmContainer().use { assertEquals(listOf(8080), it.exposedPorts) }
    }

    @Test
    fun `the with methods set the server's environment variables`() {
        FakeLlmContainer()
            .withScenario("hello")
            .withLenient()
            .withControlToken("t0ken")
            .withScenarioDirectory(Path.of("src/test/scenarios"))
            .use { container ->
                assertEquals(
                    mapOf(
                        "FAKE_LLM_SCENARIO" to "hello",
                        "FAKE_LLM_LENIENT" to "true",
                        "FAKE_LLM_CONTROL_TOKEN" to "t0ken",
                        "FAKE_LLM_SCENARIO_DIR" to "/scenarios",
                    ),
                    container.envMap,
                )
            }
    }

    @Test
    fun `any tag of the release repository is accepted`() {
        FakeLlmContainer("ghcr.io/davidjones-griz/fake-llm:0.5.0").close()
    }

    @Test
    fun `another image must be declared a substitute`() {
        val failure = assertFailsWith<IllegalStateException> { FakeLlmContainer("fake-llm:ci") }
        assertTrue(failure.message!!.contains("asCompatibleSubstituteFor"), failure.message)

        val substitute = DockerImageName.parse("fake-llm:ci").asCompatibleSubstituteFor("ghcr.io/davidjones-griz/fake-llm")
        FakeLlmContainer(substitute).close()
    }
}
