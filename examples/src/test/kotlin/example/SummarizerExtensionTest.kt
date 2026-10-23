package example

import com.davidjonesgriz.fakellm.FakeLlmServer
import com.davidjonesgriz.fakellm.junit.FakeLlmScenario
import com.davidjonesgriz.fakellm.junit.FakeLlmTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

/**
 * Tests [Summarizer] with the JUnit Jupiter extension: one server for the class, the scenario
 * loaded (and the call log cleared) before each test, and the server injected as a parameter.
 */
@FakeLlmTest
@FakeLlmScenario("summarizer")
class SummarizerExtensionTest {
    private fun summarizer(server: FakeLlmServer) =
        Summarizer(baseUrl = "${server.baseUrl}/anthropic", apiKey = "not-used", model = "example-model")

    @Test
    fun `returns the scripted summary`(server: FakeLlmServer) {
        val summary = summarizer(server).summarize("Revenue for the quarter was ...")

        assertEquals("Quarterly revenue rose 12% on subscription growth.", summary.text)
        assertEquals(
            "summarize",
            server.engine
                .calls()
                .single()
                .role,
        )
    }

    @Test
    fun `sees only its own calls`(server: FakeLlmServer) {
        // Whichever of these two tests runs second still finds exactly one call: its own.
        summarizer(server).summarize("Churn for the quarter ...")

        assertContains(
            server.engine
                .calls()
                .single()
                .promptSample,
            "Churn for the quarter",
        )
    }
}
