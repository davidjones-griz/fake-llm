package com.davidjonesgriz.fakellm.testcontainers

import com.davidjonesgriz.fakellm.FakeLlmClient
import com.davidjonesgriz.fakellm.FakeLlmClientException
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.io.TempDir
import org.testcontainers.utility.DockerImageName
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Starts the image under test (-Pfakellm.image) and calls it as an app and its tests would. */
@Tag("docker")
class FakeLlmContainerTest {
    private val http = HttpClient.newHttpClient()

    @Test
    fun `it is ready when started, with no scenario loaded`() {
        container().use { container ->
            container.start()

            container.client().use { client -> assertNull(client.health().scenario) }
        }
    }

    @Test
    fun `each vendor's base URL reaches the scenario`() {
        container().withScenario("hello").use { container ->
            container.start()

            container.client().use { client ->
                // hello has one step, so each vendor's call starts the scenario over.
                fun call(
                    vendor: String,
                    url: String,
                    request: String,
                ) {
                    client.reset()
                    val body = post(url, request)
                    assertTrue(body.contains(HELLO), body)
                    assertEquals(listOf(vendor), client.calls().map { it.vendor })
                }

                call("anthropic", "${container.anthropicBaseUrl}/v1/messages", """{"model": "m", "max_tokens": 64, "messages": [$USER]}""")
                call("openai", "${container.openAiBaseUrl}/chat/completions", """{"model": "m", "messages": [$USER]}""")
                call(
                    "gemini",
                    "${container.geminiBaseUrl}v1beta/models/m:generateContent",
                    """{"contents": [{"parts": [{"text": "Hi"}]}]}""",
                )
            }
        }
    }

    @Test
    fun `a host scenario directory loads by name`() {
        container().withScenarioDirectory(Path.of("src/test/scenarios")).use { container ->
            container.start()

            container.client().use { client ->
                client.load("from-host-directory")
                val body = post("${container.anthropicBaseUrl}/v1/messages", """{"model": "m", "max_tokens": 64, "messages": [$USER]}""")

                assertTrue(body.contains("Loaded from the host."), body)
                assertEquals("from-host-directory", client.health().scenario)
            }
        }
    }

    @Test
    fun `a directory only its owner can read still loads`(
        @TempDir directory: Path,
    ) {
        val scenario = directory.resolve("private.json")
        Files.writeString(scenario, """{"name": "private", "steps": [{"text": "Read anyway."}]}""")
        Files.setPosixFilePermissions(scenario, PosixFilePermissions.fromString("rw-------"))
        Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"))

        container().withScenarioDirectory(directory).use { container ->
            container.start()

            container.client().use { client ->
                client.load("private")
                val body = post("${container.anthropicBaseUrl}/v1/messages", """{"model": "m", "max_tokens": 64, "messages": [$USER]}""")

                assertTrue(body.contains("Read anyway."), body)
            }
        }
    }

    @Test
    fun `the client sends the control token`() {
        container().withControlToken("t0ken").use { container ->
            container.start()

            container.client().use { client -> client.load("hello") }
            FakeLlmClient(container.baseUrl).use { client ->
                assertEquals(401, assertFailsWith<FakeLlmClientException> { client.load("hello") }.statusCode)
            }
        }
    }

    private fun post(
        url: String,
        body: String,
    ): String {
        val request =
            HttpRequest
                .newBuilder(URI.create(url))
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        assertEquals(200, response.statusCode(), response.body())
        return response.body()
    }

    private companion object {
        const val USER = """{"role": "user", "content": "Hi"}"""
        const val HELLO = "Hello. I can help with the weather, a simple recipe, or a short how-to."

        fun container(): FakeLlmContainer =
            FakeLlmContainer(
                DockerImageName.parse(System.getProperty("fakellm.image")).asCompatibleSubstituteFor("ghcr.io/davidjones-griz/fake-llm"),
            )
    }
}
