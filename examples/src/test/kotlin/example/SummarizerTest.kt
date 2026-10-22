package example

import com.davidjonesgriz.fakellm.ErrorStep
import com.davidjonesgriz.fakellm.FakeLlmServer
import com.davidjonesgriz.fakellm.Match
import com.davidjonesgriz.fakellm.Rule
import com.davidjonesgriz.fakellm.Scenario
import com.davidjonesgriz.fakellm.ScenarioEngine
import com.davidjonesgriz.fakellm.Step
import com.davidjonesgriz.fakellm.startFakeLlmServer
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
        val call = server.engine.calls().single()
        assertEquals("summarize", call.role)
        assertContains(call.promptSample, "Revenue for the quarter")
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
        server.engine.loadInline(scenario(Step(error = ErrorStep(status = 429, headers = mapOf("retry-after" to "0")))))

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
    fun `a request the scenario does not cover fails loudly`() {
        server.engine.loadInline(
            Scenario(
                name = "other-model-only",
                rules = listOf(Rule(role = "other", match = Match(modelContains = "other-model"), respond = listOf(Step(text = "x")))),
            ),
        )

        val failure = assertFailsWith<IllegalStateException> { summarizer.summarize("doc") }

        assertContains(failure.message!!, "HTTP 400")
        assertContains(failure.message!!, "no rule in scenario 'other-model-only' matches this request")
    }

    private fun scenario(vararg steps: Step) =
        Scenario(name = "inline", rules = listOf(Rule(role = "summarize", respond = steps.toList())))
}
