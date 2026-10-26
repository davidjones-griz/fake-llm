"""The official anthropic SDK against fake-llm's Anthropic Messages route, buffered and streaming."""

import json

import anthropic
import pytest

MESSAGES = [{"role": "user", "content": "Hello"}]
FAST_RETRY = {"retry-after-ms": "10"}
OVERLOADED = {"type": "error", "error": {"type": "overloaded_error", "message": "Overloaded"}}


def create(client, **extra):
    return client.messages.create(model="example-model", max_tokens=64, messages=MESSAGES, **extra)


def stream(client):
    return client.messages.stream(model="example-model", max_tokens=64, messages=MESSAGES)


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
    assert (message.id, message.type, message.role) == ("fake-msg-1", "message", "assistant")
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
