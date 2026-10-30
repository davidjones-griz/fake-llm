# Malformed JSON output

An app that asks a model for JSON sometimes gets prose around it, or JSON that stops partway. The parse fails, and the app has to decide what to do: ask again, repair the output, or report an error. The bundled scenario `malformed-json` scripts a parse failure followed by a valid answer: call 1 is prose with a broken JSON fragment, and call 2 is a valid JSON object.

```json
--8<-- "src/main/resources/com/davidjonesgriz/fakellm/scenarios/malformed-json.json"
```

## What to check

- The app does not crash or return the broken text when the first reply doesn't parse.
- If it asks again, it returns the second reply's parsed object, and the call log has exactly two calls.

The first reply's `stop_reason` is `end_turn`, not `max_tokens`: this is a model that answered badly, not one that was cut off (for that, see [Truncated output](max-tokens.md)). An app can't tell the two apart from the text alone.

## Kotlin, with the JUnit extension

```kotlin
@FakeLlmTest
class ForecastParserTest {
    @Test
    @FakeLlmScenario("malformed-json")
    fun `asks again when the reply is not valid JSON`(server: FakeLlmServer) {
        val client = ForecastClient(baseUrl = "${server.baseUrl}/anthropic")

        assertEquals(Forecast(city = "Lisbon", conditions = "mild and clear", tempC = 21), client.structuredForecast("Lisbon"))
        assertEquals(2, server.engine.calls().size)
    }
}
```

## Python, with the pytest plugin

```python
import pytest

from myapp.forecast import structured_forecast


@pytest.mark.fake_llm_scenario("malformed-json")
def test_asks_again_after_malformed_json(fake_llm):
    forecast = structured_forecast("Lisbon", base_url=fake_llm.anthropic_base_url)
    assert forecast == {"city": "Lisbon", "conditions": "mild and clear", "tempC": 21}
    assert len(fake_llm.client.calls()) == 2
```
