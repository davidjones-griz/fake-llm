package example;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.davidjonesgriz.fakellm.FakeLlmServer;
import com.davidjonesgriz.fakellm.junit.FakeLlmScenario;
import com.davidjonesgriz.fakellm.junit.FakeLlmTest;
import java.net.http.HttpClient;
import org.junit.jupiter.api.Test;

/** The JUnit Jupiter extension from Java: one server for the class, and each test's scenario loaded before it. */
@FakeLlmTest(scenarioDirectory = "src/test/scenarios")
class SummarizerExtensionJavaTest {
    @Test
    @FakeLlmScenario("extension-returns-summary")
    void returnsTheScriptedSummary(FakeLlmServer server) {
        Summarizer summarizer = new Summarizer(server.getBaseUrl() + "/anthropic", "not-used", "example-model", 2, HttpClient.newHttpClient());

        assertEquals("Quarterly revenue rose 12% on subscription growth.", summarizer.summarize("doc").getText());
        assertEquals(1, server.getEngine().calls().size());
    }
}
