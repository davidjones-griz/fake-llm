"""The official google-genai SDK against fake-llm's Gemini generateContent and streamGenerateContent routes (buffered and streaming)."""

import json

import httpx
import pytest
from google import genai
from google.genai import errors, types

FAST_RETRY = {"retry-after-ms": "10"}


def generate(client, contents="Hello"):
    return client.models.generate_content(model="example-gemini", contents=contents)


def stream(client, contents="Hello"):
    return client.models.generate_content_stream(model="example-gemini", contents=contents)


def texts_until_raised(chunks, received):
    for chunk in chunks:
        received.append(chunk.text)


def call(seq, outcome, step):
    return (seq, "gemini", "example-gemini", outcome, step)


def error_step(code, message, status, headers=None):
    error = {"status": code, "body": json.dumps({"error": {"code": code, "message": message, "status": status}})}
    if headers:
        error["headers"] = headers
    return {"error": error}


def test_text(fake, gemini_client):
    fake.load([{"text": "Billing."}])
    response = generate(gemini_client)
    assert response.text == "Billing."
    assert response.model_version == "example-gemini"
    (candidate,) = response.candidates
    assert candidate.content.role == "model"
    assert candidate.finish_reason == types.FinishReason.STOP
    usage = response.usage_metadata
    assert (usage.prompt_token_count, usage.candidates_token_count, usage.total_token_count) == (100, 50, 150)
    assert fake.log() == [call(1, "text", 1)]
    record = fake.calls()[0]
    assert (record["messageCount"], record["promptSample"]) == (1, "Hello\n")


def test_system_instruction(fake, gemini_client):
    fake.load([{"text": "Hi."}])
    config = types.GenerateContentConfig(system_instruction="Answer in one word.")
    assert gemini_client.models.generate_content(model="example-gemini", contents="Hello", config=config).text == "Hi."
    record = fake.calls()[0]
    assert (record["systemShape"], record["messageCount"], record["promptSample"]) == ("blocks", 1, "Answer in one word.\nHello\n")


def test_max_tokens(fake, gemini_client):
    fake.load([{"text": "The summary stops in the mid", "stopReason": "max_tokens"}])
    assert generate(gemini_client).candidates[0].finish_reason == types.FinishReason.MAX_TOKENS
    assert fake.log() == [call(1, "text", 1)]


def test_thinking_ignored(fake, gemini_client):
    fake.load([{"text": "Billing.", "thinking": "hidden"}])
    response = generate(gemini_client)
    assert response.text == "Billing."
    assert len(response.candidates[0].content.parts) == 1
    assert fake.log() == [call(1, "text", 1)]


def test_default_client_does_not_retry(fake, gemini_client):
    fake.load([error_step(503, "unavailable", "UNAVAILABLE", FAST_RETRY), {"text": "ok"}])
    with pytest.raises(errors.ServerError) as raised:
        generate(gemini_client)
    assert (raised.value.code, raised.value.status, raised.value.message) == (503, "UNAVAILABLE", "unavailable")
    assert raised.value.details == {"error": {"code": 503, "message": "unavailable", "status": "UNAVAILABLE"}}
    assert fake.log() == [call(1, "error:503", 1)]


def test_rate_limit_retried(fake, gemini_retry_client):
    fake.load([error_step(429, "slow down", "RESOURCE_EXHAUSTED", FAST_RETRY), {"text": "ok"}])
    assert generate(gemini_retry_client).text == "ok"
    assert fake.log() == [call(1, "error:429", 1), call(2, "text", 2)]


def test_unavailable_retries_exhausted(fake, gemini_retry_client):
    fake.load([error_step(503, "unavailable", "UNAVAILABLE", FAST_RETRY)] * 3)
    with pytest.raises(errors.ServerError) as raised:
        generate(gemini_retry_client)
    assert raised.value.code == 503
    assert fake.log() == [call(1, "error:503", 1), call(2, "error:503", 2), call(3, "error:503", 3)]


def test_bad_request_not_retried(fake, gemini_retry_client):
    fake.load([error_step(400, "bad request", "INVALID_ARGUMENT"), {"text": "ok"}])
    with pytest.raises(errors.ClientError) as raised:
        generate(gemini_retry_client)
    assert raised.value.code == 400
    assert fake.log() == [call(1, "error:400", 1)]


def test_call_after_last_step(fake, gemini_client):
    fake.load([{"text": "once"}])
    assert generate(gemini_client).text == "once"
    with pytest.raises(errors.ClientError) as raised:
        generate(gemini_client)
    assert raised.value.code == 400
    assert raised.value.details["fake_llm_error"] == "no-more-steps" and raised.value.details["message"].startswith("no more steps:")
    assert fake.log() == [call(1, "text", 1), call(2, "no-more-steps", None)]


def test_call_after_unload(fake, gemini_client):
    fake.load([{"text": "once"}])
    assert generate(gemini_client).text == "once"
    fake.unload()
    assert fake.log() == []
    with pytest.raises(errors.ClientError) as raised:
        generate(gemini_client)
    assert raised.value.code == 400
    assert raised.value.details["fake_llm_error"] == "no-scenario" and raised.value.details["message"].startswith("no scenario loaded:")
    assert fake.log() == [call(1, "no-scenario", None)]


def test_function_call(fake, gemini_client):
    fake.load([{"toolCalls": [{"name": "get_weather", "arguments": {"city": "Paris"}}, {"name": "get_time", "id": "t2"}]}])
    response = generate(gemini_client)
    assert [(fc.id, fc.name, fc.args) for fc in response.function_calls] == [(None, "get_weather", {"city": "Paris"}), ("t2", "get_time", {})]
    assert response.candidates[0].finish_reason == types.FinishReason.STOP
    assert response.text is None
    assert fake.log() == [call(1, "tool-calls", 1)]
    assert fake.calls()[0]["toolCalls"] == [{"id": None, "name": "get_weather"}, {"id": "t2", "name": "get_time"}]


def test_function_loop(fake, gemini_client):
    fake.load([{"toolCalls": [{"name": "get_weather", "arguments": {"city": "Paris"}}]}, {"text": "It is 18 degrees in Paris."}])
    first = generate(gemini_client)
    contents = [
        types.Content(role="user", parts=[types.Part.from_text(text="Hello")]),
        first.candidates[0].content,
        types.Content(role="user", parts=[types.Part.from_function_response(name="get_weather", response={"temperature": 18})]),
    ]
    assert generate(gemini_client, contents).text == "It is 18 degrees in Paris."
    assert fake.log() == [call(1, "tool-calls", 1), call(2, "text", 2)]
    record = fake.calls()[1]
    assert (record["messageCount"], record["promptSample"]) == (3, "Hello\n")
    assert record["toolResults"] == [{"id": None, "name": "get_weather", "content": {"temperature": 18}, "isError": False}]


def test_raw_arguments_not_a_call(fake, gemini_client):
    fake.load([{"toolCalls": [{"name": "get_weather", "rawArguments": "{}"}]}])
    with pytest.raises(errors.ClientError) as raised:
        generate(gemini_client)
    assert raised.value.code == 400
    assert raised.value.details["fake_llm_error"] == "unsupported-step"
    assert fake.log() == []


def test_stream_text(fake, gemini_client):
    fake.load([{"text": "Streaming text that is long enough.", "streamChunkChars": 8}])
    chunks = list(stream(gemini_client))
    assert [c.text for c in chunks] == ["Streamin", "g text t", "hat is l", "ong enou", "gh."]
    assert all(c.candidates[0].content.role == "model" for c in chunks)
    assert [c.candidates[0].finish_reason for c in chunks] == [None] * 4 + [types.FinishReason.STOP]
    assert [c.usage_metadata for c in chunks[:-1]] == [None] * 4
    usage = chunks[-1].usage_metadata
    assert (usage.prompt_token_count, usage.candidates_token_count, usage.total_token_count) == (100, 50, 150)
    assert fake.log() == [call(1, "text", 1)]
    record = fake.calls()[0]
    assert (record["messageCount"], record["promptSample"]) == (1, "Hello\n")


def test_stream_max_tokens(fake, gemini_client):
    fake.load([{"text": "The summary stops in the mid", "stopReason": "max_tokens", "streamChunkChars": 8}])
    reasons = [c.candidates[0].finish_reason for c in stream(gemini_client)]
    assert reasons == [None, None, None, types.FinishReason.MAX_TOKENS]
    assert fake.log() == [call(1, "text", 1)]


def test_stream_thinking_ignored(fake, gemini_client):
    fake.load([{"text": "Billing.", "thinking": "hidden"}])
    (chunk,) = list(stream(gemini_client))
    assert len(chunk.candidates[0].content.parts) == 1
    assert chunk.text == "Billing."
    assert fake.log() == [call(1, "text", 1)]


def test_stream_function_call(fake, gemini_client):
    fake.load(
        [
            {
                "text": "Checking.",
                "toolCalls": [{"name": "get_weather", "arguments": {"city": "Paris"}}, {"name": "get_time", "id": "t2"}],
                "streamChunkChars": 8,
            }
        ]
    )
    chunks = list(stream(gemini_client))
    assert [c.text for c in chunks] == ["Checking", ".", None, None]
    calls = [[(fc.id, fc.name, fc.args) for fc in c.function_calls or []] for c in chunks]
    assert calls == [[], [], [(None, "get_weather", {"city": "Paris"})], [("t2", "get_time", {})]]
    assert chunks[-1].candidates[0].finish_reason == types.FinishReason.STOP
    assert fake.log() == [call(1, "tool-calls", 1)]
    assert fake.calls()[0]["toolCalls"] == [{"id": None, "name": "get_weather"}, {"id": "t2", "name": "get_time"}]


def test_stream_error_after_chunks(fake, gemini_retry_client):
    body = {"error": {"code": 500, "message": "server error", "status": "INTERNAL"}}
    fake.load([{"error": {"status": 500, "body": json.dumps(body)}, "streamErrorAfterChunks": 2, "streamChunkChars": 8}, {"text": "never"}])
    received = []
    with pytest.raises(errors.ServerError) as raised:
        texts_until_raised(stream(gemini_retry_client), received)
    assert received == ["Thinking", " this th"]
    assert (raised.value.code, raised.value.status, raised.value.message) == (500, "INTERNAL", "server error")
    assert raised.value.details == body
    # An in-stream error is raised to the caller, not retried.
    assert fake.log() == [call(1, "error:500", 1)]


def test_stream_error_before_stream_retried(fake, gemini_retry_client):
    fake.load([error_step(429, "slow down", "RESOURCE_EXHAUSTED", FAST_RETRY), {"text": "after"}])
    assert "".join(c.text for c in stream(gemini_retry_client)) == "after"
    assert fake.log() == [call(1, "error:429", 1), call(2, "text", 2)]


def test_stream_call_after_last_step(fake, gemini_client):
    fake.load([{"text": "once"}])
    assert [c.text for c in stream(gemini_client)] == ["once"]
    with pytest.raises(errors.ClientError) as raised:
        list(stream(gemini_client))
    assert raised.value.code == 400
    assert raised.value.details["fake_llm_error"] == "no-more-steps" and raised.value.details["message"].startswith("no more steps:")
    assert fake.log() == [call(1, "text", 1), call(2, "no-more-steps", None)]


def test_stream_raw_arguments_not_a_call(fake, gemini_client):
    fake.load([{"toolCalls": [{"name": "get_weather", "rawArguments": "{}"}]}])
    with pytest.raises(errors.ClientError) as raised:
        list(stream(gemini_client))
    assert raised.value.code == 400
    assert raised.value.details["fake_llm_error"] == "unsupported-step"
    assert fake.log() == []


def test_first_byte_delay_timeout(fake, server):
    fake.load([{"text": "slow", "firstByteDelayMs": 2000}, {"text": "slow", "firstByteDelayMs": 2000}, {"text": "fast"}])
    client = genai.Client(api_key="not-used", http_options=types.HttpOptions(base_url=f"{server}/gemini/", timeout=500))
    try:
        with pytest.raises(httpx.ReadTimeout):
            generate(client)
        fake.wait_for_calls(1)
        with pytest.raises(httpx.ReadTimeout):
            list(stream(client))
        fake.wait_for_calls(2)
        assert generate(client).text == "fast"
    finally:
        client.close()
    assert fake.log() == [call(1, "text", 1), call(2, "text", 2), call(3, "text", 3)]
    assert fake.delays() == [(2000, 0), (2000, 0), (0, 0)]


def test_scripted_usage(fake, gemini_client):
    fake.load([{"text": "Counted.", "usage": {"inputTokens": 1200, "outputTokens": 340}}])
    usage = generate(gemini_client).usage_metadata
    assert (usage.prompt_token_count, usage.candidates_token_count, usage.total_token_count) == (1200, 340, 1540)


def test_embed_content_matches_openai(fake, gemini_client, openai_client):
    fake.load([{"text": "unused"}])
    config = types.EmbedContentConfig(output_dimensionality=8)
    r = gemini_client.models.embed_content(model="example-gemini", contents=["a", "b"], config=config)
    vectors = [e.values for e in r.embeddings]
    assert [len(v) for v in vectors] == [8, 8]
    # The same function on both vendors: the OpenAI default (base64) call gives the same values.
    openai = openai_client.embeddings.create(model="example-embedding", input=["a", "b"], dimensions=8)
    assert vectors == [d.embedding for d in openai.data]
    (default,) = gemini_client.models.embed_content(model="example-gemini", contents="a").embeddings
    assert len(default.values) == 1536
    assert fake.log() == []


def test_models(fake, gemini_client):
    # A request that took a step would be logged; these take none.
    fake.load([{"text": "unused"}])
    assert [m.name for m in gemini_client.models.list()] == ["models/fake-llm"]
    model = gemini_client.models.get(model="example-gemini")
    assert (model.name, model.display_name) == ("models/example-gemini", "example-gemini")
    assert "generateContent" in model.supported_actions
    assert fake.log() == []
