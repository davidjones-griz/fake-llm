"""The official openai SDK against fake-llm's Chat Completions route (buffered)."""

import json

import openai
import pytest

MESSAGES = [{"role": "user", "content": "Hello"}]
FAST_RETRY = {"retry-after-ms": "10"}


def create(client):
    return client.chat.completions.create(model="example-gpt", messages=MESSAGES)


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
    assert (completion.id, completion.object, completion.model) == ("fake-openai-1", "chat.completion", "fake-openai")
    (choice,) = completion.choices
    assert choice.index == 0
    assert (choice.message.role, choice.message.content) == ("assistant", "Billing.")
    assert choice.finish_reason == "stop"
    usage = completion.usage
    assert (usage.prompt_tokens, usage.completion_tokens, usage.total_tokens) == (100, 50, 150)
    assert fake.log() == [call(1, "text", 1)]
    record = fake.calls()[0]
    assert (record["messageCount"], record["promptSample"]) == (1, "Hello\n")


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
