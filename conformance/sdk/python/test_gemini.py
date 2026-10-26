"""The official google-genai SDK against fake-llm's Gemini generateContent route (buffered)."""

import json

import pytest
from google.genai import errors, types

FAST_RETRY = {"retry-after-ms": "10"}


def generate(client):
    return client.models.generate_content(model="example-gemini", contents="Hello")


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
    (candidate,) = response.candidates
    assert candidate.content.role == "model"
    assert candidate.finish_reason == types.FinishReason.STOP
    usage = response.usage_metadata
    assert (usage.prompt_token_count, usage.candidates_token_count, usage.total_token_count) == (100, 50, 150)
    assert fake.log() == [call(1, "text", 1)]
    record = fake.calls()[0]
    assert (record["messageCount"], record["promptSample"]) == (1, "Hello\n")


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
    assert raised.value.details["fake_llm_error"] == "no more steps"
    assert fake.log() == [call(1, "text", 1), call(2, "no-more-steps", None)]
