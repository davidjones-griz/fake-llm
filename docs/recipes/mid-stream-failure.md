# Mid-stream failures

A streamed reply can fail after it has started: the response is HTTP 200, some text has arrived, and then the stream ends with an error event instead of a stop. An app that shows text as it arrives has already displayed part of an answer; an app that collects it may keep a fragment as if it were the whole reply. The bundled scenario `stream-error-midway` streams three 8-code-point chunks of filler text, then an Anthropic `overloaded_error` event:

```json
--8<-- "src/main/resources/com/davidjonesgriz/fakellm/scenarios/stream-error-midway.json"
```

`streamErrorAfterChunks` makes the error arrive inside the stream. A request that doesn't stream gets the same error as a plain HTTP 529, so the scenario also covers a client that falls back to a buffered request.

## What to check

- The app notices the error even though the response status was 200.
- It does not keep or return the partial text as the answer.
- What it does next (retry, report, or show an error) is what you expect. A retry needs a second step in the scenario.

On OpenAI Chat Completions and Responses streams, the same scenario ends with an error frame that the `openai` SDK raises as `APIError`. On a Gemini stream, the `google-genai` SDK raises only when the body's first key is `error`, which this scenario's body doesn't have; see [Step fields](../scenarios.md#step-fields), `streamErrorAfterChunks`.

## Kotlin, with the JUnit extension

```kotlin
@FakeLlmTest
class StreamingAnswerTest {
    @Test
    @FakeLlmScenario("stream-error-midway")
    fun `a stream that fails partway is reported, not kept`(server: FakeLlmServer) {
        val client = StreamingClient(baseUrl = "${server.baseUrl}/anthropic")

        val result = client.answer("Summarize this.")
        assertTrue(result is AnswerResult.Failed)
        assertEquals(listOf("error:529"), server.engine.calls().map { it.outcome })
    }
}
```

## Python, with the pytest plugin

Here the `anthropic` SDK's streaming helper is the code under test: it raises when the error event arrives, after yielding the first chunks.

```python
import anthropic
import pytest


@pytest.mark.fake_llm_scenario("stream-error-midway")
def test_stream_error_after_three_chunks(fake_llm):
    client = anthropic.Anthropic(api_key="not-used", base_url=fake_llm.anthropic_base_url)
    chunks = []
    with pytest.raises(anthropic.APIStatusError) as raised:
        with client.messages.stream(
            model="example-model",
            max_tokens=256,
            messages=[{"role": "user", "content": "Summarize this."}],
        ) as stream:
            for text in stream.text_stream:
                chunks.append(text)
    assert len(chunks) == 3
    assert raised.value.body["error"]["type"] == "overloaded_error"
```
