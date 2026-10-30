"""The official anthropic SDK against fake-llm's Anthropic Messages route, buffered and streaming."""

import json
import time

import anthropic
import pytest

MESSAGES = [{"role": "user", "content": "Hello"}]
FAST_RETRY = {"retry-after-ms": "10"}
OVERLOADED = {"type": "error", "error": {"type": "overloaded_error", "message": "Overloaded"}}
TOOLS = [{"name": "get_weather", "description": "Current weather", "input_schema": {"type": "object", "properties": {"city": {"type": "string"}}}}]
RAW = {"toolCalls": [{"name": "get_weather", "rawArguments": "{\"city\": Paris}"}], "streamChunkChars": 8}


def create(client, messages=MESSAGES, **extra):
    return client.messages.create(model="example-model", max_tokens=64, messages=messages, **extra)


def stream(client, **extra):
    return client.messages.stream(model="example-model", max_tokens=64, messages=MESSAGES, **extra)


def blocks(message):
    return [(b.type, b.text) if b.type == "text" else (b.type, b.id, b.name, b.input) for b in message.content]


def tool_loop(client, first, result):
    return create(client, messages=MESSAGES + [{"role": "assistant", "content": first.content}, {"role": "user", "content": [result]}])


def call(seq, outcome, step):
    return (seq, "anthropic", "example-model", outcome, step)


def error_step(status, body, headers=None):
    error = {"status": status, "body": json.dumps(body)}
    if headers:
        error["headers"] = headers
    return {"error": error}


def test_text(fake, anthropic_client):
    fake.load([{"text": "Billing questions go to the billing team."}])
    message = create(anthropic_client)
    assert (message.id, message.type, message.role, message.model) == ("fake-msg-1", "message", "assistant", "example-model")
    assert [(block.type, block.text) for block in message.content] == [("text", "Billing questions go to the billing team.")]
    assert message.stop_reason == "end_turn"
    usage = message.usage
    assert (usage.input_tokens, usage.output_tokens) == (100, 50)
    assert (usage.cache_creation_input_tokens, usage.cache_read_input_tokens) == (0, 0)
    assert fake.log() == [call(1, "text", 1)]
    record = fake.calls()[0]
    assert (record["messageCount"], record["promptSample"]) == (1, "Hello\n")


def test_max_tokens(fake, anthropic_client):
    fake.load([{"text": "The summary stops in the mid", "stopReason": "max_tokens"}])
    message = create(anthropic_client)
    assert message.stop_reason == "max_tokens"
    assert message.content[0].text == "The summary stops in the mid"
    assert fake.log() == [call(1, "text", 1)]


def test_thinking(fake, anthropic_client):
    fake.load([{"text": "Billing.", "thinking": "Check the billing rules first."}])
    message = create(anthropic_client)
    thinking, text = message.content
    assert (thinking.type, thinking.thinking, thinking.signature) == ("thinking", "Check the billing rules first.", "fake-thinking-signature")
    assert (text.type, text.text) == ("text", "Billing.")
    assert fake.log() == [call(1, "text", 1)]


def test_cache_breakpoint(fake, anthropic_client):
    fake.load([{"text": "Cached."}])
    system = [{"type": "text", "text": "You answer briefly.", "cache_control": {"type": "ephemeral"}}]
    message = create(anthropic_client, system=system)
    assert (message.usage.cache_creation_input_tokens, message.usage.cache_read_input_tokens) == (20, 10)
    assert fake.log() == [call(1, "text", 1)]
    record = fake.calls()[0]
    assert (record["systemShape"], record["promptSample"]) == ("blocks", "You answer briefly.\nHello\n")


def test_error_not_retried(fake, anthropic_client):
    body = {"type": "error", "error": {"type": "invalid_request_error", "message": "bad request"}}
    fake.load([error_step(400, body), {"text": "ok"}])
    with pytest.raises(anthropic.BadRequestError) as raised:
        create(anthropic_client)
    assert raised.value.status_code == 400
    assert raised.value.body == body
    assert fake.log() == [call(1, "error:400", 1)]


def test_rate_limit_retried(fake, anthropic_client):
    body = {"type": "error", "error": {"type": "rate_limit_error", "message": "slow down"}}
    fake.load([error_step(429, body, FAST_RETRY), {"text": "ok"}])
    message = create(anthropic_client)
    assert message.content[0].text == "ok"
    assert message.id == "fake-msg-2"
    assert fake.log() == [call(1, "error:429", 1), call(2, "text", 2)]


def test_overloaded_retries_exhausted(fake, anthropic_client):
    fake.load([error_step(529, OVERLOADED, FAST_RETRY)] * 3)
    with pytest.raises(anthropic.OverloadedError) as raised:
        create(anthropic_client)
    assert raised.value.status_code == 529
    assert fake.log() == [call(1, "error:529", 1), call(2, "error:529", 2), call(3, "error:529", 3)]


def test_call_after_last_step(fake, anthropic_client):
    fake.load([{"text": "once"}])
    assert create(anthropic_client).content[0].text == "once"
    with pytest.raises(anthropic.BadRequestError) as raised:
        create(anthropic_client)
    assert raised.value.body["fake_llm_error"] == "no more steps"
    assert fake.log() == [call(1, "text", 1), call(2, "no-more-steps", None)]


def test_call_after_unload(fake, anthropic_client):
    fake.load([{"text": "once"}])
    assert create(anthropic_client).content[0].text == "once"
    fake.unload()
    assert fake.log() == []
    with pytest.raises(anthropic.BadRequestError) as raised:
        create(anthropic_client)
    assert raised.value.body["fake_llm_error"] == "no scenario loaded"
    assert fake.log() == [call(1, "no-scenario", None)]


def test_stream_text(fake, anthropic_client):
    fake.load([{"text": "Streaming text that is long enough.", "streamChunkChars": 8}])
    with stream(anthropic_client) as events:
        chunks = list(events.text_stream)
        message = events.get_final_message()
    assert chunks == ["Streamin", "g text t", "hat is l", "ong enou", "gh."]
    assert (message.id, message.model, message.stop_reason) == ("fake-msg-1", "example-model", "end_turn")
    assert [(block.type, block.text) for block in message.content] == [("text", "Streaming text that is long enough.")]
    assert (message.usage.input_tokens, message.usage.output_tokens) == (100, 50)
    assert fake.log() == [call(1, "text", 1)]


def test_stream_events(fake, anthropic_client):
    fake.load([{"text": "abcdefghij", "streamChunkChars": 4}])
    types = [event.type for event in create(anthropic_client, stream=True)]
    assert types == [
        "message_start",
        "content_block_start",
        "content_block_delta",
        "content_block_delta",
        "content_block_delta",
        "content_block_stop",
        "message_delta",
        "message_stop",
    ]
    assert fake.log() == [call(1, "text", 1)]


def test_stream_max_tokens(fake, anthropic_client):
    fake.load([{"text": "cut off", "stopReason": "max_tokens"}])
    with stream(anthropic_client) as events:
        message = events.get_final_message()
    assert message.stop_reason == "max_tokens"
    assert fake.log() == [call(1, "text", 1)]


def test_stream_thinking(fake, anthropic_client):
    fake.load([{"text": "Billing.", "thinking": "Check the billing rules first.", "streamChunkChars": 8}])
    with stream(anthropic_client) as events:
        deltas = [event.delta.type for event in events if event.type == "content_block_delta"]
        message = events.get_final_message()
    last_thinking = max(i for i, delta in enumerate(deltas) if delta == "thinking_delta")
    assert last_thinking < deltas.index("text_delta")
    thinking, text = message.content
    assert (thinking.type, thinking.thinking, thinking.signature) == ("thinking", "Check the billing rules first.", "fake-thinking-signature")
    assert (text.type, text.text) == ("text", "Billing.")
    assert fake.log() == [call(1, "text", 1)]


def test_stream_error_after_chunks(fake, anthropic_client):
    fake.load([{"error": {"status": 529, "body": json.dumps(OVERLOADED)}, "streamErrorAfterChunks": 2, "streamChunkChars": 8}])
    chunks = []
    with pytest.raises(anthropic.APIStatusError) as raised:
        with stream(anthropic_client) as events:
            for chunk in events.text_stream:
                chunks.append(chunk)
    assert chunks == ["Thinking", " this th"]
    assert raised.value.body == OVERLOADED
    assert fake.log() == [call(1, "error:529", 1)]


def test_stream_error_before_stream_retried(fake, anthropic_client):
    fake.load([error_step(529, OVERLOADED, FAST_RETRY), {"text": "after"}])
    with stream(anthropic_client) as events:
        message = events.get_final_message()
    assert message.content[0].text == "after"
    assert message.id == "fake-msg-2"
    assert fake.log() == [call(1, "error:529", 1), call(2, "text", 2)]


def test_tool_use(fake, anthropic_client):
    fake.load([{"text": "Checking.", "toolCalls": [{"name": "get_weather", "arguments": {"city": "Paris"}}]}])
    message = create(anthropic_client, tools=TOOLS)
    assert blocks(message) == [("text", "Checking."), ("tool_use", "toolu_fake_1_1", "get_weather", {"city": "Paris"})]
    assert message.stop_reason == "tool_use"
    assert fake.log() == [call(1, "tool-calls", 1)]
    record = fake.calls()[0]
    assert (record["toolCalls"], record["toolResults"]) == ([{"id": "toolu_fake_1_1", "name": "get_weather"}], [])


def test_tool_loop(fake, anthropic_client):
    fake.load([{"toolCalls": [{"name": "get_weather", "arguments": {"city": "Paris"}}]}, {"text": "It is 18 degrees in Paris."}])
    first = create(anthropic_client, tools=TOOLS)
    result = {"type": "tool_result", "tool_use_id": first.content[0].id, "content": "18 degrees"}
    second = tool_loop(anthropic_client, first, result)
    assert blocks(second) == [("text", "It is 18 degrees in Paris.")]
    assert (second.stop_reason, second.id) == ("end_turn", "fake-msg-2")
    assert fake.log() == [call(1, "tool-calls", 1), call(2, "text", 2)]
    record = fake.calls()[1]
    assert (record["messageCount"], record["promptSample"]) == (3, "Hello\n18 degrees\n")
    assert record["toolResults"] == [{"id": "toolu_fake_1_1", "name": "get_weather", "content": "18 degrees", "isError": False}]


def test_tool_error_result(fake, anthropic_client):
    fake.load([{"toolCalls": [{"name": "get_time", "id": "toolu_custom"}]}, {"text": "I could not get the time."}])
    first = create(anthropic_client, tools=TOOLS)
    assert blocks(first) == [("tool_use", "toolu_custom", "get_time", {})]
    content = [{"type": "text", "text": "unknown timezone"}]
    result = {"type": "tool_result", "tool_use_id": "toolu_custom", "content": content, "is_error": True}
    assert tool_loop(anthropic_client, first, result).content[0].text == "I could not get the time."
    assert fake.log() == [call(1, "tool-calls", 1), call(2, "text", 2)]
    assert fake.calls()[1]["toolResults"] == [{"id": "toolu_custom", "name": "get_time", "content": content, "isError": True}]


def test_stream_tool_use(fake, anthropic_client):
    fake.load([{"text": "Checking.", "toolCalls": [{"name": "get_weather", "arguments": {"city": "Paris"}}], "streamChunkChars": 8}])
    with stream(anthropic_client, tools=TOOLS) as events:
        pieces = [event.partial_json for event in events if event.type == "input_json"]
        message = events.get_final_message()
    assert pieces == ['{"city":', '"Paris"}']
    assert blocks(message) == [("text", "Checking."), ("tool_use", "toolu_fake_1_1", "get_weather", {"city": "Paris"})]
    assert message.stop_reason == "tool_use"
    assert fake.log() == [call(1, "tool-calls", 1)]


def test_stream_tool_use_raw_arguments(fake, anthropic_client):
    fake.load([RAW, RAW])
    events = list(create(anthropic_client, stream=True))
    deltas = [e.delta for e in events if e.type == "content_block_delta"]
    assert [d.partial_json for d in deltas if d.type == "input_json_delta"] == ['{"city":', " Paris}"]
    assert [e.delta.stop_reason for e in events if e.type == "message_delta"] == ["tool_use"]
    # The stream helper parses the arguments as it accumulates them, so invalid JSON raises.
    with pytest.raises(ValueError):
        with stream(anthropic_client) as helper:
            for _ in helper:
                pass
    assert fake.log() == [call(1, "tool-calls", 1), call(2, "tool-calls", 2)]


def test_raw_arguments_buffered_not_a_call(fake, anthropic_client):
    fake.load([RAW])
    with pytest.raises(anthropic.BadRequestError) as raised:
        create(anthropic_client)
    assert raised.value.body["fake_llm_error"] == "exception"
    assert fake.log() == []
    starts = [e.content_block for e in create(anthropic_client, stream=True) if e.type == "content_block_start"]
    assert [(block.type, block.id) for block in starts] == [("tool_use", "toolu_fake_1_1")]
    assert fake.log() == [call(1, "tool-calls", 1)]


def test_first_byte_delay(fake, anthropic_client):
    fake.load([{"text": "Late reply.", "firstByteDelayMs": 300}])
    started = time.monotonic()
    message = create(anthropic_client)
    assert time.monotonic() - started >= 0.3
    assert message.content[0].text == "Late reply."
    assert fake.log() == [call(1, "text", 1)]
    assert fake.delays() == [(300, 0)]


def test_first_byte_delay_timeout(fake, server):
    fake.load([{"text": "slow", "firstByteDelayMs": 2000}, {"text": "fast"}])
    with anthropic.Anthropic(api_key="not-used", base_url=f"{server}/anthropic", timeout=0.5, max_retries=0) as client:
        with pytest.raises(anthropic.APITimeoutError):
            create(client)
        fake.wait_for_calls(1)
        assert create(client).content[0].text == "fast"
    assert fake.log() == [call(1, "text", 1), call(2, "text", 2)]


def test_stream_error_step_first_byte_delay(fake, anthropic_client):
    fake.load([{**error_step(529, OVERLOADED), "firstByteDelayMs": 300, "streamInterChunkDelayMs": 50}])
    started = time.monotonic()
    with pytest.raises(anthropic.APIStatusError) as raised:
        with anthropic_client.with_options(max_retries=0).messages.stream(model="example-model", max_tokens=64, messages=MESSAGES) as events:
            for _ in events:
                pass
    assert raised.value.status_code == 529
    assert time.monotonic() - started >= 0.3
    assert raised.value.body == OVERLOADED
    assert fake.log() == [call(1, "error:529", 1)]
    assert fake.delays() == [(300, 0)]


def test_scripted_usage(fake, anthropic_client):
    usage = {"inputTokens": 1200, "outputTokens": 340}
    fake.load([{"text": "Counted.", "usage": usage}, {"text": "Counted, streamed.", "usage": usage}])
    message = create(anthropic_client)
    with stream(anthropic_client) as events:
        streamed = events.get_final_message()
    assert (message.usage.input_tokens, message.usage.output_tokens) == (1200, 340)
    assert (streamed.usage.input_tokens, streamed.usage.output_tokens) == (1200, 340)
