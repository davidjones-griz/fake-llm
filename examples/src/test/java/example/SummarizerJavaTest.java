package example;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.davidjonesgriz.fakellm.CallRecord;
import com.davidjonesgriz.fakellm.FakeLlm;
import com.davidjonesgriz.fakellm.FakeLlmClient;
import com.davidjonesgriz.fakellm.FakeLlmServer;
import com.davidjonesgriz.fakellm.Scenario;
import com.davidjonesgriz.fakellm.ScenarioEngine;
import java.net.http.HttpClient;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Tests {@link Summarizer} from Java, against fake-llm running in this JVM, one server per test. */
class SummarizerJavaTest {
    private FakeLlmServer server;

    @BeforeEach
    void start() {
        server = FakeLlm.startFakeLlmServer(0, new ScenarioEngine(), null);
    }

    @AfterEach
    void stop() {
        server.close();
    }

    private Summarizer summarizer() {
        // Summarizer is this project's Kotlin code, without @JvmOverloads, so Java passes every argument.
        return new Summarizer(server.getBaseUrl() + "/anthropic", "not-used", "example-model", 2, HttpClient.newHttpClient());
    }

    @Test
    void returnsTheScriptedSummary() {
        server.getEngine().load("summarizer");

        Summary summary = summarizer().summarize("Revenue for the quarter was ...");

        assertEquals("Quarterly revenue rose 12% on subscription growth.", summary.getText());
        List<CallRecord> calls = server.getEngine().calls();
        assertEquals(1, calls.size());
        assertTrue(calls.get(0).getPromptSample().contains("Revenue for the quarter"));
    }

    @Test
    void retriesOnceAfterARateLimit() {
        server.getEngine().loadInline(Scenario.fromJson("""
                {
                  "name": "rate-limited-once",
                  "steps": [
                    {"error": {"status": 429, "headers": {"retry-after": "0"}}},
                    {"text": "Recovered."}
                  ]
                }
                """));

        assertEquals("Recovered.", summarizer().summarize("doc").getText());
        assertEquals(List.of("error:429", "text"), server.getEngine().calls().stream().map(CallRecord::getOutcome).toList());
    }

    @Test
    void drivesTheServerOverHttpWithTheControlApiClient() {
        try (FakeLlmClient client = new FakeLlmClient(server.getBaseUrl())) {
            client.load("summarizer");
            summarizer().summarize("doc");
            assertEquals("text", client.calls().get(0).getOutcome());

            client.unload();
            assertNull(client.health().getScenario());
        }
    }
}
