package example

import com.davidjonesgriz.fakellm.FakeLlmClient
import com.davidjonesgriz.fakellm.ScenarioEngine
import com.davidjonesgriz.fakellm.startFakeLlmServer
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * This project keeps its own scenarios in src/test/resources/scenarios/, with an index.txt listing
 * them. Its test resources come before the fake-llm jar on the classpath, so the listing must merge
 * both indexes rather than let this one hide the bundled scenarios.
 */
class ScenarioListingTest {
    @Test
    fun `the scenario listing has the bundled names and this project's own`() {
        startFakeLlmServer(port = 0, engine = ScenarioEngine(), controlToken = null).use { server ->
            FakeLlmClient(server.baseUrl).use { client ->
                assertEquals(BUNDLED + "summarizer", client.scenarioNames().toSet())
            }
        }
    }

    private companion object {
        val BUNDLED =
            setOf(
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
