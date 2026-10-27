"""The exchange log as each official SDK's traffic produces it: requests as sent, auth redacted, responses byte for byte."""

import json

import openai
import pytest

HELLO = [{"role": "user", "content": "Hello"}]
STREAM_HEADERS = {"cache-control": ["no-cache"], "connection": ["keep-alive"], "content-type": ["text/event-stream"]}
JSON_HEADERS = {"content-type": ["application/json"]}


def no_secret(fake):
    assert "not-used" not in json.dumps(fake.exchanges())


def test_anthropic_exchange(fake, anthropic_client):
    fake.load([{"text": "Billing."}])
    message = anthropic_client.messages.create(model="example-model", max_tokens=64, messages=HELLO)
    (entry,) = fake.exchanges()
    request, response = entry["request"], entry["response"]
    assert (entry["seq"], request["method"], request["path"]) == (1, "POST", "/anthropic/v1/messages")
    headers = request["headers"]
    assert headers["authorization"] == ["[redacted]"]
    assert "x-api-key" not in headers
    assert len(headers["anthropic-version"]) == 1
    assert json.loads(request["body"]) == {"model": "example-model", "max_tokens": 64, "messages": HELLO}
    assert (response["status"], response["headers"], response["state"]) == (200, JSON_HEADERS, "complete")
    assert json.loads(response["body"])["content"][0]["text"] == message.content[0].text == "Billing."
    assert fake.log() == [(1, "anthropic", "example-model", "text", 1)]
    no_secret(fake)


def test_openai_stream_exchange(fake, openai_client):
    fake.load([{"text": "Streaming text.", "streamChunkChars": 8}])
    chunks = list(openai_client.chat.completions.create(model="example-gpt", messages=HELLO, stream=True))
    sdk = "".join(c.choices[0].delta.content or "" for c in chunks if c.choices)
    (entry,) = fake.exchanges()
    assert entry["request"]["headers"]["authorization"] == ["[redacted]"]
    assert json.loads(entry["request"]["body"])["stream"] is True
    response = entry["response"]
    assert response["headers"] == STREAM_HEADERS
    assert response["body"].endswith("data: [DONE]\n\n")
    datas = [f[len("data: "):] for f in response["body"].split("\n\n") if f]
    assert datas[-1] == "[DONE]"
    joined = "".join(json.loads(d)["choices"][0]["delta"].get("content") or "" for d in datas[:-1])
    assert joined == sdk == "Streaming text."
    assert response["state"] == "complete"
    assert fake.log() == [(1, "openai", "example-gpt", "text", 1)]
    no_secret(fake)


def test_gemini_exchange(fake, gemini_client):
    fake.load([{"text": "Billing."}])
    response = gemini_client.models.generate_content(model="example-gemini", contents="Hello")
    (entry,) = fake.exchanges()
    assert entry["request"]["path"] == "/gemini/v1beta/models/example-gemini:generateContent"
    assert entry["request"]["headers"]["x-goog-api-key"] == ["[redacted]"]
    assert json.loads(entry["response"]["body"])["candidates"][0]["content"]["parts"][0]["text"] == response.text
    assert fake.log() == [(1, "gemini", "example-gemini", "text", 1)]
    no_secret(fake)


def test_retry_exchanges(fake, openai_client):
    error = {"message": "slow down", "type": "requests", "param": None, "code": "rate_limit_exceeded"}
    body = json.dumps({"error": error})
    fake.load([{"error": {"status": 429, "body": body, "headers": {"retry-after-ms": "10"}}}, {"text": "ok"}])
    completion = openai_client.chat.completions.create(model="example-gpt", messages=HELLO)
    assert completion.choices[0].message.content == "ok"
    first, second = fake.exchanges()
    assert first["response"]["status"] == 429
    assert first["response"]["headers"] == {"content-type": ["application/json"], "retry-after-ms": ["10"]}
    assert first["response"]["body"] == body
    assert second["response"]["status"] == 200
    assert first["request"]["body"] == second["request"]["body"]
    assert [e["request"]["headers"]["x-stainless-retry-count"] for e in (first, second)] == [["0"], ["1"]]
    assert fake.log() == [(1, "openai", "example-gpt", "error:429", 1), (2, "openai", "example-gpt", "text", 2)]
    no_secret(fake)


def test_responses_stream_error_exchange(fake, openai_client):
    error = {"message": "server error", "type": "server_error", "param": None, "code": None}
    body = json.dumps({"error": error})
    fake.load([{"error": {"status": 500, "body": body}, "streamErrorAfterChunks": 2, "streamChunkChars": 8}])
    deltas = []
    with pytest.raises(openai.APIError):
        for e in openai_client.responses.create(model="example-gpt", input="Hello", stream=True):
            if e.type == "response.output_text.delta":
                deltas.append(e.delta)
    assert deltas == ["Thinking", " this th"]
    (entry,) = fake.exchanges()
    assert (entry["response"]["status"], entry["response"]["state"]) == (200, "complete")
    assert entry["response"]["body"].endswith("event: error\ndata: " + body + "\n\n")
    assert fake.log() == [(1, "openai-responses", "example-gpt", "error:500", 1)]
    no_secret(fake)
