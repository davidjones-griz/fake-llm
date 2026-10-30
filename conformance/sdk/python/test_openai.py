"""The official openai SDK against fake-llm's Chat Completions route (buffered and streaming)."""

import json

import openai
import pytest

MESSAGES = [{"role": "user", "content": "Hello"}]
FAST_RETRY = {"retry-after-ms": "10"}
TOOLS = [{"type": "function", "function": {"name": "get_weather", "parameters": {"type": "object", "properties": {"city": {"type": "string"}}}}}]


def create(client, messages=MESSAGES, **extra):
    return client.chat.completions.create(model="example-gpt", messages=messages, **extra)


def stream(client, **extra):
    return client.chat.completions.create(model="example-gpt", messages=MESSAGES, stream=True, **extra)


def tool_calls(message):
    return [(t.id, t.type, t.function.name, t.function.arguments) for t in message.tool_calls]


def call(seq, outcome, step):
    return (seq, "openai", "example-gpt", outcome, step)


def error_step(status, error, headers=None):
    step = {"status": status, "body": json.dumps({"error": error})}
    if headers:
        step["headers"] = headers
    return {"error": step}


def test_text(fake, openai_client):
    fake.load([{"text": "Billing."}])
    completion = create(openai_client)
    assert (completion.id, completion.object, completion.model) == ("fake-openai-1", "chat.completion", "example-gpt")
    (choice,) = completion.choices
    assert choice.index == 0
    assert (choice.message.role, choice.message.content) == ("assistant", "Billing.")
    assert choice.finish_reason == "stop"
    usage = completion.usage
    assert (usage.prompt_tokens, usage.completion_tokens, usage.total_tokens) == (100, 50, 150)
    assert fake.log() == [call(1, "text", 1)]
    record = fake.calls()[0]
    assert (record["messageCount"], record["promptSample"]) == (1, "Hello\n")


def test_content_parts(fake, openai_client):
    fake.load([{"text": "Cat."}])
    parts = [{"type": "text", "text": "What is in this picture?"}, {"type": "image_url", "image_url": {"url": "https://example.com/cat.png"}}]
    messages = [{"role": "developer", "content": "Answer in one word."}, {"role": "user", "content": parts}]
    assert create(openai_client, messages=messages).choices[0].message.content == "Cat."
    record = fake.calls()[0]
    assert (record["systemShape"], record["messageCount"], record["promptSample"]) == ("blocks", 2, "Answer in one word.\nWhat is in this picture?\n")


def test_max_tokens(fake, openai_client):
    fake.load([{"text": "The summary stops in the mid", "stopReason": "max_tokens"}])
    assert create(openai_client).choices[0].finish_reason == "length"
    assert fake.log() == [call(1, "text", 1)]


def test_thinking_ignored(fake, openai_client):
    fake.load([{"text": "Billing.", "thinking": "hidden"}])
    assert create(openai_client).choices[0].message.content == "Billing."
    assert fake.log() == [call(1, "text", 1)]


def test_error_not_retried(fake, openai_client):
    error = {"message": "bad request", "type": "invalid_request_error", "param": None, "code": None}
    fake.load([error_step(400, error), {"text": "ok"}])
    with pytest.raises(openai.BadRequestError) as raised:
        create(openai_client)
    assert raised.value.status_code == 400
    # The SDK unwraps the "error" object.
    assert raised.value.body == error
    assert raised.value.type == "invalid_request_error"
    assert fake.log() == [call(1, "error:400", 1)]


def test_rate_limit_retried(fake, openai_client):
    error = {"message": "slow down", "type": "requests", "param": None, "code": "rate_limit_exceeded"}
    fake.load([error_step(429, error, FAST_RETRY), {"text": "ok"}])
    completion = create(openai_client)
    assert completion.choices[0].message.content == "ok"
    assert completion.id == "fake-openai-2"
    assert fake.log() == [call(1, "error:429", 1), call(2, "text", 2)]


def test_server_error_retries_exhausted(fake, openai_client):
    error = {"message": "server error", "type": "server_error", "param": None, "code": None}
    fake.load([error_step(500, error, FAST_RETRY)] * 3)
    with pytest.raises(openai.InternalServerError) as raised:
        create(openai_client)
    assert raised.value.status_code == 500
    assert fake.log() == [call(1, "error:500", 1), call(2, "error:500", 2), call(3, "error:500", 3)]


def test_call_after_last_step(fake, openai_client):
    fake.load([{"text": "once"}])
    assert create(openai_client).choices[0].message.content == "once"
    with pytest.raises(openai.BadRequestError) as raised:
        create(openai_client)
    assert raised.value.body["fake_llm_error"] == "no more steps"
    assert fake.log() == [call(1, "text", 1), call(2, "no-more-steps", None)]


def test_call_after_unload(fake, openai_client):
    fake.load([{"text": "once"}])
    assert create(openai_client).choices[0].message.content == "once"
    fake.unload()
    assert fake.log() == []
    with pytest.raises(openai.BadRequestError) as raised:
        create(openai_client)
    assert raised.value.body["fake_llm_error"] == "no scenario loaded"
    assert fake.log() == [call(1, "no-scenario", None)]


def content(chunks):
    return [chunk.choices[0].delta.content for chunk in chunks]


def test_stream_text(fake, openai_client):
    fake.load([{"text": "Streaming text that is long enough.", "streamChunkChars": 8}])
    chunks = list(stream(openai_client))
    assert content(chunks) == ["", "Streamin", "g text t", "hat is l", "ong enou", "gh.", None]
    for chunk in chunks:
        assert (chunk.id, chunk.object, chunk.model, chunk.usage) == ("fake-openai-1", "chat.completion.chunk", "example-gpt", None)
    assert chunks[0].choices[0].delta.role == "assistant"
    assert chunks[-1].choices[0].finish_reason == "stop"
    assert fake.log() == [call(1, "text", 1)]
    record = fake.calls()[0]
    assert (record["messageCount"], record["promptSample"]) == (1, "Hello\n")


def test_stream_final_completion(fake, openai_client):
    fake.load([{"text": "Billing."}])
    with openai_client.chat.completions.stream(model="example-gpt", messages=MESSAGES) as events:
        for _ in events:
            pass
        completion = events.get_final_completion()
    assert completion.id == "fake-openai-1"
    (choice,) = completion.choices
    assert (choice.message.role, choice.message.content) == ("assistant", "Billing.")
    assert choice.finish_reason == "stop"
    assert completion.usage is None
    assert fake.log() == [call(1, "text", 1)]


def test_stream_include_usage(fake, openai_client):
    fake.load([{"text": "Billing."}])
    *earlier, last = list(stream(openai_client, stream_options={"include_usage": True}))
    assert last.choices == []
    assert (last.usage.prompt_tokens, last.usage.completion_tokens, last.usage.total_tokens) == (100, 50, 150)
    assert [chunk.usage for chunk in earlier] == [None] * len(earlier)
    assert fake.log() == [call(1, "text", 1)]


def test_stream_max_tokens(fake, openai_client):
    fake.load([{"text": "cut off", "stopReason": "max_tokens"}])
    assert list(stream(openai_client))[-1].choices[0].finish_reason == "length"
    assert fake.log() == [call(1, "text", 1)]


def test_stream_thinking_ignored(fake, openai_client):
    fake.load([{"text": "Billing.", "thinking": "hidden"}])
    assert "".join(piece or "" for piece in content(stream(openai_client))) == "Billing."
    assert fake.log() == [call(1, "text", 1)]


def test_stream_error_after_chunks(fake, openai_client):
    error = {"message": "server error", "type": "server_error", "param": None, "code": None}
    fake.load([{"error": {"status": 500, "body": json.dumps({"error": error})}, "streamErrorAfterChunks": 2, "streamChunkChars": 8}])
    received = []
    with pytest.raises(openai.APIError) as raised:
        for chunk in stream(openai_client):
            received.append(chunk.choices[0].delta.content)
    assert received == ["", "Thinking", " this th"]
    # The error arrives inside an HTTP 200 stream, so the SDK raises the base APIError.
    assert not isinstance(raised.value, openai.APIStatusError)
    assert (raised.value.body, raised.value.message) == (error, "server error")
    assert fake.log() == [call(1, "error:500", 1)]


def test_stream_error_before_stream_retried(fake, openai_client):
    error = {"message": "slow down", "type": "requests", "param": None, "code": "rate_limit_exceeded"}
    fake.load([error_step(429, error, FAST_RETRY), {"text": "after"}])
    chunks = list(stream(openai_client))
    assert "".join(piece or "" for piece in content(chunks)) == "after"
    assert {chunk.id for chunk in chunks} == {"fake-openai-2"}
    assert fake.log() == [call(1, "error:429", 1), call(2, "text", 2)]


def test_stream_call_after_last_step(fake, openai_client):
    fake.load([{"text": "once"}])
    list(stream(openai_client))
    with pytest.raises(openai.BadRequestError) as raised:
        stream(openai_client)
    assert raised.value.body["fake_llm_error"] == "no more steps"
    assert fake.log() == [call(1, "text", 1), call(2, "no-more-steps", None)]


def test_tool_calls(fake, openai_client):
    fake.load(
        [{"toolCalls": [{"name": "get_weather", "arguments": {"city": "Paris"}}, {"name": "get_time", "rawArguments": "{\"tz\": Europe/Paris}", "id": "call_custom"}]}]
    )
    (choice,) = create(openai_client, tools=TOOLS).choices
    assert choice.message.content is None
    assert tool_calls(choice.message) == [
        ("call_fake_1_1", "function", "get_weather", '{"city":"Paris"}'),
        ("call_custom", "function", "get_time", '{"tz": Europe/Paris}'),
    ]
    assert choice.finish_reason == "tool_calls"
    assert fake.log() == [call(1, "tool-calls", 1)]
    assert fake.calls()[0]["toolCalls"] == [{"id": "call_fake_1_1", "name": "get_weather"}, {"id": "call_custom", "name": "get_time"}]


def test_tool_loop(fake, openai_client):
    fake.load([{"toolCalls": [{"name": "get_weather", "arguments": {"city": "Paris"}}]}, {"text": "It is 18 degrees in Paris."}])
    message = create(openai_client, tools=TOOLS).choices[0].message
    result = {"role": "tool", "tool_call_id": message.tool_calls[0].id, "content": "18 degrees"}
    second = create(openai_client, messages=MESSAGES + [message, result])
    assert (second.choices[0].message.content, second.choices[0].finish_reason, second.id) == ("It is 18 degrees in Paris.", "stop", "fake-openai-2")
    assert fake.log() == [call(1, "tool-calls", 1), call(2, "text", 2)]
    record = fake.calls()[1]
    # The SDK sends the assistant message back with "content": null, which adds no prompt text.
    assert (record["messageCount"], record["promptSample"]) == (3, "Hello\n18 degrees\n")
    assert record["toolResults"] == [{"id": "call_fake_1_1", "name": "get_weather", "content": "18 degrees", "isError": False}]


def test_stream_tool_calls(fake, openai_client):
    fake.load([{"text": "Checking.", "toolCalls": [{"name": "get_weather", "arguments": {"city": "Paris"}}], "streamChunkChars": 8}])
    # No tools: the stream() helper accepts only strict function tools, and the server ignores tools anyway.
    with openai_client.chat.completions.stream(model="example-gpt", messages=MESSAGES) as events:
        deltas = [event.arguments_delta for event in events if event.type == "tool_calls.function.arguments.delta"]
        completion = events.get_final_completion()
    assert deltas == ["", '{"city":', '"Paris"}']
    (choice,) = completion.choices
    assert choice.message.content == "Checking."
    assert tool_calls(choice.message) == [("call_fake_1_1", "function", "get_weather", '{"city":"Paris"}')]
    assert choice.finish_reason == "tool_calls"
    assert fake.log() == [call(1, "tool-calls", 1)]


def test_stream_raw_arguments(fake, openai_client):
    fake.load([{"toolCalls": [{"name": "get_weather", "rawArguments": "{\"city\": Paris}"}], "streamChunkChars": 8}])
    chunks = list(stream(openai_client))
    pieces = [c.choices[0].delta.tool_calls[0].function.arguments for c in chunks if c.choices[0].delta.tool_calls]
    assert pieces == ["", '{"city":', " Paris}"]
    assert chunks[0].choices[0].delta.content is None
    assert chunks[-1].choices[0].finish_reason == "tool_calls"
    assert fake.log() == [call(1, "tool-calls", 1)]


def short_timeout_client(server, max_retries=0):
    return openai.OpenAI(api_key="not-used", base_url=f"{server}/openai/v1", timeout=0.5, max_retries=max_retries)


def test_first_byte_delay_timeout(fake, server):
    fake.load([{"text": "slow", "firstByteDelayMs": 2000}, {"text": "fast"}])
    with short_timeout_client(server) as client:
        with pytest.raises(openai.APITimeoutError):
            create(client)
        fake.wait_for_calls(1)
        assert create(client).choices[0].message.content == "fast"
    assert fake.log() == [call(1, "text", 1), call(2, "text", 2)]
    assert fake.delays() == [(2000, 0), (0, 0)]


def test_stream_first_byte_delay_timeout(fake, server):
    fake.load([{"text": "slow", "firstByteDelayMs": 2000, "streamInterChunkDelayMs": 5}, {"text": "fast"}])
    with short_timeout_client(server) as client:
        with pytest.raises(openai.APITimeoutError):
            stream(client)
        fake.wait_for_calls(1)
        assert content(list(stream(client))) == ["", "fast", None]
    assert fake.log() == [call(1, "text", 1), call(2, "text", 2)]
    assert fake.delays() == [(2000, 5), (0, 0)]


def test_timeout_retried_takes_next_step(fake, server):
    fake.load([{"text": "slow", "firstByteDelayMs": 2000}, {"text": "fast"}])
    with short_timeout_client(server, max_retries=1) as client:
        assert create(client).choices[0].message.content == "fast"
    assert fake.log() == [call(1, "text", 1), call(2, "text", 2)]


def test_scripted_usage(fake, openai_client):
    fake.load([{"text": "Counted.", "usage": {"inputTokens": 1200, "outputTokens": 340}}])
    usage = create(openai_client).usage
    assert (usage.prompt_tokens, usage.completion_tokens, usage.total_tokens) == (1200, 340, 1540)


def test_embeddings_default_call(fake, openai_client):
    fake.load([{"text": "unused"}])
    # The SDK asks for base64 when encoding_format is not set, and decodes it to float32 values.
    r = openai_client.embeddings.create(model="example-embedding", input="Hello")
    (item,) = r.data
    assert (item.index, len(item.embedding), r.model) == (0, 1536, "example-embedding")
    assert abs(sum(x * x for x in item.embedding) - 1) < 1e-5
    assert (r.usage.prompt_tokens, r.usage.total_tokens) == (2, 2)
    assert fake.log() == []


def test_embeddings_float_equals_default(fake, openai_client):
    fake.load([{"text": "unused"}])
    default = openai_client.embeddings.create(model="example-embedding", input=["a", "b"])
    floats = openai_client.embeddings.create(model="example-embedding", input=["a", "b"], encoding_format="float")
    assert [d.embedding for d in floats.data] == [d.embedding for d in default.data]
    assert fake.log() == []


def test_embeddings_dimensions(fake, openai_client):
    fake.load([{"text": "unused"}])
    r = openai_client.embeddings.create(model="example-embedding", input=["same", "same", "other"], dimensions=8)
    vectors = [d.embedding for d in r.data]
    assert [len(v) for v in vectors] == [8, 8, 8]
    assert vectors[0] == vectors[1] and vectors[0] != vectors[2]
    assert fake.log() == []


def test_models(fake, openai_client):
    # A request that took a step would be logged; these take none.
    fake.load([{"text": "unused"}])
    assert [m.id for m in openai_client.models.list()] == ["fake-llm"]
    model = openai_client.models.retrieve("example-gpt")
    assert (model.id, model.object, model.owned_by) == ("example-gpt", "model", "fake-llm")
    # The SDK sends the slash as %2F; the lookup echoes the id it decodes.
    assert openai_client.models.retrieve("meta-llama/Llama-3.1-8B").id == "meta-llama/Llama-3.1-8B"
    assert fake.log() == []
