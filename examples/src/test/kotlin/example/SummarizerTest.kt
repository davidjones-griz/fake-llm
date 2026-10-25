package example

import com.davidjonesgriz.fakellm.ErrorStep
import com.davidjonesgriz.fakellm.FakeLlmClient
import com.davidjonesgriz.fakellm.FakeLlmServer
import com.davidjonesgriz.fakellm.Scenario
import com.davidjonesgriz.fakellm.ScenarioEngine
import com.davidjonesgriz.fakellm.Step
import com.davidjonesgriz.fakellm.startFakeLlmServer
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Tests [Summarizer] against fake-llm running in this JVM, one server per test. */
class SummarizerTest {
    private lateinit var server: FakeLlmServer

    private val summarizer
        get() = Summarizer(baseUrl = "${server.baseUrl}/anthropic", apiKey = "not-used", model = "example-model")

    @BeforeTest
    fun start() {
        server = startFakeLlmServer(port = 0, engine = ScenarioEngine(), controlToken = null)
    }

    @AfterTest
    fun stop() {
        server.close()
    }

    @Test
    fun `returns the scripted summary`() {
        server.engine.load("summarizer")

        val summary = summarizer.summarize("Revenue for the quarter was ...")

        assertEquals("Quarterly revenue rose 12% on subscription growth.", summary.text)
        assertFalse(summary.truncated)
        assertContains(
            server.engine
                .calls()
                .single()
                .promptSample,
            "Revenue for the quarter",
        )
    }

    @Test
    fun `drives the server over HTTP with the control API client`() {
        // FakeLlmClient talks to a server by URL, as a test would with the Docker image or java -jar.
        FakeLlmClient(server.baseUrl).use { client ->
            client.loadInline(scenario(Step(text = "Loaded over HTTP.")))

            assertEquals("Loaded over HTTP.", summarizer.summarize("doc").text)

            assertEquals("text", client.calls().single().outcome)
        }
    }

    @Test
    fun `loads a scenario from this project's own directory`() {
        // Gradle runs tests with the project directory as the working directory, so this relative
        // path is examples/src/test/scenarios. The engine reads <name>.json there before the classpath.
        val engine = ScenarioEngine(scenarioDirectory = Path.of("src/test/scenarios"))
        startFakeLlmServer(port = 0, engine = engine, controlToken = null).use { directoryServer ->
            engine.load("summarizer-from-directory")
            val fromDirectory = Summarizer(baseUrl = "${directoryServer.baseUrl}/anthropic", apiKey = "not-used", model = "example-model")

            assertEquals("Churn fell to 2% after the onboarding redesign.", fromDirectory.summarize("Churn for the quarter ...").text)
        }
    }

    @Test
    fun `retries once after a rate limit`() {
        server.engine.loadInline(
            scenario(
                Step(error = ErrorStep(status = 429, headers = mapOf("retry-after" to "0"))),
                Step(text = "Recovered."),
            ),
        )

        assertEquals("Recovered.", summarizer.summarize("doc").text)
        assertEquals(listOf("error:429", "text"), server.engine.calls().map { it.outcome })
    }

    @Test
    fun `gives up when the rate limit persists`() {
        val limited = Step(error = ErrorStep(status = 429, headers = mapOf("retry-after" to "0")))
        server.engine.loadInline(scenario(limited, limited))

        val failure = assertFailsWith<IllegalStateException> { summarizer.summarize("doc") }

        assertContains(failure.message!!, "HTTP 429")
        assertEquals(2, server.engine.calls().size)
    }

    @Test
    fun `reports a truncated summary`() {
        server.engine.loadInline(scenario(Step(text = "Quarterly revenue rose", stopReason = "max_tokens")))

        assertTrue(summarizer.summarize("doc").truncated)
    }

    @Test
    fun `a call the scenario has no step for fails loudly`() {
        server.engine.loadInline(scenario(Step(text = "The only summary.")))
        summarizer.summarize("doc")

        val failure = assertFailsWith<IllegalStateException> { summarizer.summarize("another doc") }

        assertContains(failure.message!!, "HTTP 400")
        assertContains(failure.message!!, "no more steps")
    }

    private fun scenario(vararg steps: Step) = Scenario(name = "inline", steps = steps.toList())
}
