package example

import com.davidjonesgriz.fakellm.FakeLlmServer
import com.davidjonesgriz.fakellm.junit.FakeLlmAllowUnservedCalls
import com.davidjonesgriz.fakellm.junit.FakeLlmScenario
import com.davidjonesgriz.fakellm.junit.FakeLlmTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

/**
 * Tests [TicketPipeline] end to end: each test's scenario, from src/test/scenarios, scripts one step
 * per call the pipeline makes, in order, across the three providers' APIs. Each test checks the
 * pipeline's result and the calls it made.
 */
@FakeLlmTest(scenarioDirectory = "src/test/scenarios")
class TicketPipelineTest {
    private fun pipeline(server: FakeLlmServer) =
        TicketPipeline(
            classifier = Provider("${server.baseUrl}/anthropic", "not-used", "example-model"),
            extractor = Provider("${server.baseUrl}/openai", "not-used", "example-gpt"),
            summarizer = Provider("${server.baseUrl}/gemini", "not-used", "example-gemini"),
        )

    private fun outcomes(server: FakeLlmServer) = server.engine.calls().map { it.outcome }

    @Test
    @FakeLlmScenario("pipeline-billing-ticket")
    fun `triages a billing ticket end to end`(server: FakeLlmServer) {
        val triage = pipeline(server).triage(TICKET)

        assertEquals(Triage(Category.BILLING, DETAILS, Summary(SUMMARY, truncated = false)), triage)
        val calls = server.engine.calls()
        assertEquals(listOf("anthropic", "openai", "gemini"), calls.map { it.vendor })
        assertEquals(listOf("text", "tool-calls", "text"), calls.map { it.outcome })
        assertEquals("record_ticket", calls[1].toolCalls.single().name)
        // The summary prompt carries what the extraction stage returned.
        assertContains(calls[2].promptSample, "Order A-1001: charged twice.")
        // The summary arrived as an event stream, in more than one chunk.
        val summaryExchange = server.engine.exchanges().last()
        assertEquals("/gemini/v1beta/models/example-gemini:streamGenerateContent?alt=sse", summaryExchange.request.path)
        val summaryResponse = assertNotNull(summaryExchange.response)
        assertEquals(listOf("text/event-stream"), summaryResponse.headers["content-type"])
        assertEquals(
            6,
            summaryResponse.body
                .lines()
                .count { it.startsWith("data: ") },
        )
    }

    @Test
    @FakeLlmScenario("pipeline-rate-limited")
    fun `retries the classification after a rate limit`(server: FakeLlmServer) {
        val triage = pipeline(server).triage(TICKET)

        assertEquals(Triage(Category.BILLING, DETAILS, Summary(SUMMARY, truncated = false)), triage)
        assertEquals(listOf("error:429", "text", "tool-calls", "text"), outcomes(server))
    }

    @Test
    @FakeLlmScenario("pipeline-malformed-extraction")
    fun `asks again when the tool call's arguments are not valid JSON`(server: FakeLlmServer) {
        val triage = pipeline(server).triage(TICKET)

        assertEquals(DETAILS, triage.details)
        assertEquals(listOf("text", "tool-calls", "tool-calls", "text"), outcomes(server))
    }

    @Test
    @FakeLlmScenario("pipeline-truncated-summary")
    fun `reports a summary cut off at max_tokens`(server: FakeLlmServer) {
        val triage = pipeline(server).triage(TICKET)

        assertEquals(Summary("Refund the duplicate charge", truncated = true), triage.summary)
        assertEquals(listOf("text", "tool-calls", "text"), outcomes(server))
    }

    @Test
    @FakeLlmScenario("pipeline-missing-summary-step")
    @FakeLlmAllowUnservedCalls // The unserved summary call is what this test is about.
    fun `a call after the scenario's last step fails the summarize stage`(server: FakeLlmServer) {
        val failure = assertFailsWith<IllegalStateException> { pipeline(server).triage(TICKET) }

        assertContains(failure.message!!, "summarize failed with HTTP 400")
        assertContains(failure.message!!, "no more steps")
        assertEquals(listOf("text", "tool-calls", "no-more-steps"), outcomes(server))
    }

    @Test
    @FakeLlmScenario("pipeline-spam-ticket")
    fun `stops after classifying a ticket as spam`(server: FakeLlmServer) {
        val triage = pipeline(server).triage("Buy cheap watches at example.com")

        assertEquals(Triage(Category.SPAM), triage)
        assertEquals(listOf("anthropic"), server.engine.calls().map { it.vendor })
        assertEquals(listOf("text"), outcomes(server))
    }

    private companion object {
        const val TICKET = "I was charged twice for order A-1001 this morning. Please refund one of the charges."
        const val SUMMARY = "Refund the duplicate charge on order A-1001."
        val DETAILS = TicketDetails(orderId = "A-1001", problem = "charged twice")
    }
}
