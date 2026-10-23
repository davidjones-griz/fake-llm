package com.davidjonesgriz.fakellm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Uses the client the way Java test code would, to keep its API usable from Java. */
class FakeLlmClientJavaTest {
    @Test
    void loadsReadsAndResetsFromJava() {
        try (FakeLlmServer server = FakeLlmAppKt.startFakeLlmServer(0, new ScenarioEngine(), null);
                FakeLlmClient client = new FakeLlmClient(server.getBaseUrl())) {
            client.load("hello");
            assertEquals("hello", client.health().getScenario());

            List<CallRecord> calls = client.calls();
            assertTrue(calls.isEmpty());

            client.reset();
            FakeLlmClientException failure = assertThrows(FakeLlmClientException.class, () -> client.load("no-such-scenario"));
            assertEquals(400, failure.getStatusCode());
        }
    }
}
