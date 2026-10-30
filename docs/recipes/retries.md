# Retries after an error

A provider sometimes answers with a server error that a second attempt would get past. An app, or the SDK it uses, should retry once and use the second reply. The bundled scenario `fail-then-retry` scripts exactly that: call 1 is HTTP 500 with an Anthropic `api_error` body, and call 2 is a normal reply.

```json
--8<-- "src/main/resources/com/davidjonesgriz/fakellm/scenarios/fail-then-retry.json"
```

## What to check

- The app returns the second reply's text.
- The call log has exactly two calls, `error:500` then `text`. One call means the app gave up; three means it retried after succeeding, or the scenario ran out and the third got `no more steps`.

The `anthropic` and `openai` Python SDKs retry a 500 on their own, twice by default. A test of your own retry logic should turn the SDK's off, so the call log shows the app's retries. To test a rate limit instead, the bundled `rate-limit-429` scenario answers call 1 with HTTP 429 and a `retry-after` header.

## Kotlin, with the JUnit extension

```kotlin
@FakeLlmTest
class ForecastClientTest {
    @Test
    @FakeLlmScenario("fail-then-retry")
    fun `retries once after a server error`(server: FakeLlmServer) {
        val client = ForecastClient(baseUrl = "${server.baseUrl}/anthropic")

        assertTrue(client.forecast("Lisbon").startsWith("Tomorrow looks mild"))
        assertEquals(listOf("error:500", "text"), server.engine.calls().map { it.outcome })
    }
}
```

If the client gives up after the 500, the first assertion fails. The extension also fails a test that passes with a step unused, such as a retry that never happened.

## Python, with the pytest plugin

Here the `anthropic` SDK's own retry is the behaviour under test:

```python
import anthropic
import pytest


@pytest.mark.fake_llm_scenario("fail-then-retry")
def test_retries_once_after_a_server_error(fake_llm):
    client = anthropic.Anthropic(api_key="not-used", base_url=fake_llm.anthropic_base_url)
    message = client.messages.create(
        model="example-model",
        max_tokens=256,
        messages=[{"role": "user", "content": "What is the weather tomorrow?"}],
    )
    assert message.content[0].text.startswith("Tomorrow looks mild")
    assert [call.outcome for call in fake_llm.client.calls()] == ["error:500", "text"]
```
