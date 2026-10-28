package com.davidjonesgriz.fakellm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;
import org.junit.jupiter.api.Test;

/** Starts a server and builds a scenario the way Java test code would, through the FakeLlm facade. */
class FakeLlmJavaTest {
    @Test
    void startsWithoutPassingTheToken() {
        ScenarioEngine engine = new ScenarioEngine();
        try (FakeLlmServer server = FakeLlm.startFakeLlmServer(0, engine)) {
            engine.load("hello");
            assertEquals("http://127.0.0.1:" + server.getPort(), server.getBaseUrl());
            assertEquals("hello", server.getEngine().currentScenarioName());
        }
    }

    @Test
    void decodesAScenarioFromJsonText() {
        Scenario scenario = Scenario.fromJson("""
                {
                  "name": "from-java",
                  "steps": [
                    {"error": {"status": 429, "headers": {"retry-after": "0"}}},
                    {"text": "Recovered."}
                  ]
                }
                """);
        assertEquals(new ErrorStep(429, "", Map.of("retry-after", "0")), scenario.getSteps().get(0).getError());
        assertEquals("Recovered.", scenario.getSteps().get(1).getText());

        ScenarioEngine engine = new ScenarioEngine();
        engine.loadInline(scenario);
        assertEquals("from-java", engine.currentScenarioName());
    }

    @Test
    void rejectsAnInvalidScenario() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class, () -> Scenario.fromJson("{\"name\": \"x\", \"steps\": []}"));
        assertEquals("scenario 'x' has no steps", failure.getMessage());
    }
}
