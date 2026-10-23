package com.davidjonesgriz.fakellm.junit.fixtures;

import com.davidjonesgriz.fakellm.FakeLlmServer;
import com.davidjonesgriz.fakellm.junit.FakeLlmScenario;
import com.davidjonesgriz.fakellm.junit.FakeLlmTest;
import org.junit.jupiter.api.Test;

/** The annotations as Java code uses them. */
@Fixture
@FakeLlmTest
@FakeLlmScenario("hello")
public class JavaFixture {
    @Test
    public void injected(FakeLlmServer server) {
        Sink.add("java", server.getEngine().currentScenarioName());
        Sink.add("java:status", FixtureSupportKt.post(server, "example-model"));
    }
}
