# Truncated output (max_tokens)

When a reply reaches the request's `max_tokens`, the provider stops it where it is and says so: Anthropic sets `stop_reason` to `max_tokens`, OpenAI `finish_reason` to `length`, and Gemini `finishReason` to `MAX_TOKENS`. The text is a valid string, but it is not a complete answer. An app that parses it, stores it, or shows it as final has a bug that only appears with long answers. The bundled scenario `max-tokens-cutoff` is one reply cut off inside a JSON value:

```json
--8<-- "src/main/resources/com/davidjonesgriz/fakellm/scenarios/max-tokens-cutoff.json"
```

## What to check

- The app reads the stop reason, not only the text.
- It does not treat the truncated text as a result: it reports the cutoff, asks again with a higher limit, or continues the answer, whichever your app means to do.

`stopReason` `max_tokens` is reported in each API's own way on every route, buffered or streamed (OpenAI Responses as `status` `incomplete` with `incomplete_details.reason` `max_output_tokens`), so the same scenario tests an app on any provider's API.

## Kotlin, with the JUnit extension

```kotlin
@FakeLlmTest
class RecipeExtractorTest {
    @Test
    @FakeLlmScenario("max-tokens-cutoff")
    fun `reports a truncated recipe instead of parsing it`(server: FakeLlmServer) {
        val extractor = RecipeExtractor(baseUrl = "${server.baseUrl}/anthropic")

        val failure = assertFailsWith<TruncatedReplyException> { extractor.extract("lentil soup") }
        assertEquals("max_tokens", failure.stopReason)
    }
}
```

## Python, with the pytest plugin

```python
import pytest

from myapp.recipes import TruncatedReply, extract_recipe


@pytest.mark.fake_llm_scenario("max-tokens-cutoff")
def test_reports_a_truncated_recipe(fake_llm):
    with pytest.raises(TruncatedReply):
        extract_recipe("lentil soup", base_url=fake_llm.anthropic_base_url)
    [call] = fake_llm.client.calls()
    assert call.outcome == "text"
```
