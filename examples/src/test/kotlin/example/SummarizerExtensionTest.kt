package example

import com.davidjonesgriz.fakellm.FakeLlmServer
import com.davidjonesgriz.fakellm.junit.FakeLlmScenario
import com.davidjonesgriz.fakellm.junit.FakeLlmTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

/**
 * Tests [Summarizer] with the JUnit Jupiter extension: one server for the class, each test's own
 * scenario (from src/test/scenarios) loaded before it, which also clears the call log, and the
 * server injected as a parameter.
 */
@FakeLlmTest(scenarioDirectory = "src/test/scenarios")
class SummarizerExtensionTest {
    private fun summarizer(server: FakeLlmServer) =
        Summarizer(baseUrl = "${server.baseUrl}/anthropic", apiKey = "not-used", model = "example-model")

    @Test
    @FakeLlmScenario("extension-returns-summary")
    fun `returns the scripted summary`(server: FakeLlmServer) {
        val summary = summarizer(server).summarize("Revenue for the quarter was ...")

        assertEquals("Quarterly revenue rose 12% on subscription growth.", summary.text)
    }

    @Test
    @FakeLlmScenario("extension-sees-own-calls")
    fun `sees only its own calls`(server: FakeLlmServer) {
        // Whichever of these two tests runs second still finds exactly one call: its own.
        val summary = summarizer(server).summarize("Churn for the quarter ...")

        assertEquals("Churn fell to 2% after the onboarding redesign.", summary.text)

        assertContains(
            server.engine
                .calls()
                .single()
                .promptSample,
            "Churn for the quarter",
        )
    }
}
