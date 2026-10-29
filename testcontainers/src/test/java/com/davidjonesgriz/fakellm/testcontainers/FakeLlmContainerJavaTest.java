package com.davidjonesgriz.fakellm.testcontainers;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.davidjonesgriz.fakellm.FakeLlmClient;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/** The container from Java with Testcontainers' JUnit Jupiter extension: one container for the class. */
@Tag("docker")
@Testcontainers
class FakeLlmContainerJavaTest {
    @Container
    static final FakeLlmContainer FAKE_LLM = new FakeLlmContainer(
            DockerImageName.parse(System.getProperty("fakellm.image")).asCompatibleSubstituteFor("ghcr.io/davidjones-griz/fake-llm"));

    @Test
    void answersFromTheLoadedScenario() throws Exception {
        try (FakeLlmClient client = FAKE_LLM.client()) {
            client.load("hello");

            HttpRequest request = HttpRequest.newBuilder(URI.create(FAKE_LLM.getAnthropicBaseUrl() + "/v1/messages"))
                    .header("content-type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            "{\"model\": \"m\", \"max_tokens\": 64, \"messages\": [{\"role\": \"user\", \"content\": \"Hi\"}]}"))
                    .build();
            HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());

            assertEquals(200, response.statusCode());
            assertEquals("text", client.calls().get(0).getOutcome());
        }
    }
}
