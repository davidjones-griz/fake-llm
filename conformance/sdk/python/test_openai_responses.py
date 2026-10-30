"""The official openai SDK against fake-llm's Responses route (buffered and streaming)."""

import json

import openai
import pytest

FAST_RETRY = {"retry-after-ms": "10"}
TOOLS = [{"type": "function", "name": "get_weather", "parameters": {"type": "object", "properties": {"city": {"type": "string"}}}}]


def create(client, input="Hello", **extra):
    return client.responses.create(model="example-gpt", input=input, **extra)


def stream(client, **extra):
    return client.responses.create(model="example-gpt", input="Hello", stream=True, **extra)


def call(seq, outcome, step):
    return (seq, "openai-responses", "example-gpt", outcome, step)


def error_step(status, error, headers=None):
    step = {"status": status, "body": json.dumps({"error": error})}
    if headers:
        step["headers"] = headers
    return {"error": step}


def types(events):
    return [e.type for e in events]


def test_text(fake, openai_client):
    fake.load([{"text": "Billing."}])
    r = create(openai_client)
    assert (r.id, r.object, r.model, r.status) == ("resp_fake_1", "response", "example-gpt", "completed")
    assert r.output_text == "Billing."
    (item,) = r.output
    assert (item.type, item.id, item.role, item.status) == ("message", "msg_fake_1", "assistant", "completed")
    (part,) = item.content
    assert part.type == "output_text" and part.annotations == []
    assert r.incomplete_details is None and r.error is None
    u = r.usage
    assert (u.input_tokens, u.output_tokens, u.total_tokens) == (100, 50, 150)
    assert u.input_tokens_details.cached_tokens == 0 and u.output_tokens_details.reasoning_tokens == 0
    assert r.created_at is None
    assert fake.log() == [call(1, "text", 1)]
    rec = fake.calls()[0]
    assert (rec["messageCount"], rec["promptSample"], rec["systemShape"]) == (1, "Hello\n", "absent")


def test_instructions_and_items(fake, openai_client):
    fake.load([{"text": "Short."}])
    r = create(
        openai_client,
        input=[{"role": "developer", "content": "Use metric units."}, {"role": "user", "content": [{"type": "input_text", "text": "Hello"}]}],
        instructions="Answer briefly.",
    )
    assert r.output_text == "Short."
    assert fake.log() == [call(1, "text", 1)]
    rec = fake.calls()[0]
    assert (rec["systemShape"], rec["messageCount"], rec["promptSample"]) == ("string", 2, "Answer briefly.\nUse metric units.\nHello\n")


def test_max_tokens(fake, openai_client):
    fake.load([{"text": "The summary stops in the mid", "stopReason": "max_tokens"}])
    r = create(openai_client)
    assert r.status == "incomplete"
    assert r.incomplete_details.reason == "max_output_tokens"
    assert r.output_text == "The summary stops in the mid"
    assert r.output[0].status == "incomplete"
    assert fake.log() == [call(1, "text", 1)]


def test_reasoning_item(fake, openai_client):
    fake.load([{"text": "Billing.", "thinking": "The user asks about an invoice."}])
    r = create(openai_client)
    assert [item.type for item in r.output] == ["reasoning", "message"]
    assert r.output[0].id == "rs_fake_1"
    assert [s.text for s in r.output[0].summary] == ["The user asks about an invoice."]
    assert r.output_text == "Billing."
    assert r.usage.output_tokens_details.reasoning_tokens == 0
    assert fake.log() == [call(1, "text", 1)]


def test_reasoning_item_sent_back(fake, openai_client):
    fake.load([{"text": "Billing.", "thinking": "The user asks about an invoice."}, {"text": "Paid."}])
    first = create(openai_client)
    second = create(openai_client, input=[{"role": "user", "content": "Hello"}, *first.output, {"role": "user", "content": "And now?"}])
    assert (second.id, second.output_text) == ("resp_fake_2", "Paid.")
    assert fake.log() == [call(1, "text", 1), call(2, "text", 2)]
    # The reasoning item counts as an input item and adds no prompt text.
    rec = fake.calls()[1]
    assert (rec["messageCount"], rec["promptSample"]) == (4, "Hello\nBilling.\nAnd now?\n")


def test_error_not_retried(fake, openai_client):
    error = {"message": "bad request", "type": "invalid_request_error", "param": None, "code": None}
    fake.load([error_step(400, error), {"text": "ok"}])
    with pytest.raises(openai.BadRequestError) as raised:
        create(openai_client)
    assert raised.value.status_code == 400
    assert raised.value.body == error
    assert fake.log() == [call(1, "error:400", 1)]


def test_rate_limit_retried(fake, openai_client):
    error = {"message": "slow down", "type": "requests", "param": None, "code": "rate_limit_exceeded"}
    fake.load([error_step(429, error, FAST_RETRY), {"text": "ok"}])
    r = create(openai_client)
    assert r.output_text == "ok" and r.id == "resp_fake_2"
    assert fake.log() == [call(1, "error:429", 1), call(2, "text", 2)]


def test_call_after_last_step(fake, openai_client):
    fake.load([{"text": "once"}])
    assert create(openai_client).output_text == "once"
    with pytest.raises(openai.BadRequestError) as raised:
        create(openai_client)
    assert raised.value.body["fake_llm_error"] == "no more steps"
    assert fake.log() == [call(1, "text", 1), call(2, "no-more-steps", None)]


def test_call_after_unload(fake, openai_client):
    fake.load([{"text": "once"}])
    assert create(openai_client).output_text == "once"
    fake.unload()
    assert fake.log() == []
    with pytest.raises(openai.BadRequestError) as raised:
        create(openai_client)
    assert raised.value.body["fake_llm_error"] == "no scenario loaded"
    assert fake.log() == [call(1, "no-scenario", None)]


def test_previous_response_id_not_resolved(fake, openai_client):
    fake.load([{"text": "Sunny."}, {"text": "Rain."}])
    first = create(openai_client, store=True)
    second = create(openai_client, input="And tomorrow?", previous_response_id=first.id, store=True)
    assert second.id == "resp_fake_2" and second.output_text == "Rain."
    assert fake.log() == [call(1, "text", 1), call(2, "text", 2)]
    rec = fake.calls()[1]
    assert (rec["messageCount"], rec["promptSample"]) == (1, "And tomorrow?\n")


def test_function_calls(fake, openai_client):
    fake.load(
        [{"toolCalls": [{"name": "get_weather", "arguments": {"city": "Paris"}}, {"name": "get_time", "rawArguments": '{"tz": Europe/Paris}', "id": "call_custom"}]}]
    )
    r = create(openai_client, tools=TOOLS)
    assert [(o.type, o.id, o.call_id, o.name, o.arguments, o.status) for o in r.output] == [
        ("function_call", "fc_fake_1_1", "call_fake_1_1", "get_weather", '{"city":"Paris"}', "completed"),
        ("function_call", "fc_fake_1_2", "call_custom", "get_time", '{"tz": Europe/Paris}', "completed"),
    ]
    assert r.output_text == "" and r.status == "completed"
    assert fake.log() == [call(1, "tool-calls", 1)]
    assert fake.calls()[0]["toolCalls"] == [{"id": "call_fake_1_1", "name": "get_weather"}, {"id": "call_custom", "name": "get_time"}]


def test_tool_loop(fake, openai_client):
    fake.load([{"toolCalls": [{"name": "get_weather", "arguments": {"city": "Paris"}}]}, {"text": "It is 18 degrees in Paris."}])
    first = create(openai_client, tools=TOOLS)
    second = create(
        openai_client,
        input=[{"role": "user", "content": "Hello"}, *first.output, {"type": "function_call_output", "call_id": first.output[0].call_id, "output": "18 degrees"}],
        tools=TOOLS,
    )
    assert second.output_text == "It is 18 degrees in Paris." and second.id == "resp_fake_2"
    assert fake.log() == [call(1, "tool-calls", 1), call(2, "text", 2)]
    rec = fake.calls()[1]
    assert (rec["messageCount"], rec["promptSample"]) == (3, "Hello\n18 degrees\n")
    assert rec["toolResults"] == [{"id": "call_fake_1_1", "name": "get_weather", "content": "18 degrees", "isError": False}]


def test_stream_text(fake, openai_client):
    fake.load([{"text": "Streaming text that is long enough.", "streamChunkChars": 8}])
    events = list(stream(openai_client))
    p = "response."
    assert types(events) == [p + t for t in ["created", "in_progress", "output_item.added", "content_part.added"]] + [p + "output_text.delta"] * 5 + [
        p + t for t in ["output_text.done", "content_part.done", "output_item.done", "completed"]
    ]
    assert [e.sequence_number for e in events] == list(range(13))
    assert [e.delta for e in events if e.type == "response.output_text.delta"] == ["Streamin", "g text t", "hat is l", "ong enou", "gh."]
    first = events[0].response
    assert first.status == "in_progress" and first.output == [] and first.usage is None
    last = events[-1].response
    assert last.output_text == "Streaming text that is long enough." and last.id == "resp_fake_1" and last.usage.total_tokens == 150
    assert fake.log() == [call(1, "text", 1)]


def test_stream_helper_final_response(fake, openai_client):
    fake.load([{"text": "Billing.", "streamChunkChars": 4}])
    with openai_client.responses.stream(model="example-gpt", input="Hello") as s:
        deltas = [e.delta for e in s if e.type == "response.output_text.delta"]
        final = s.get_final_response()
    assert deltas == ["Bill", "ing."]
    assert (final.id, final.status, final.output_text) == ("resp_fake_1", "completed", "Billing.")
    assert fake.log() == [call(1, "text", 1)]


def test_stream_reasoning_summary(fake, openai_client):
    fake.load([{"text": "Billing.", "thinking": "The user asks about an invoice.", "streamChunkChars": 8}])
    with openai_client.responses.stream(model="example-gpt", input="Hello") as s:
        events = list(s)
        final = s.get_final_response()
    deltas = [e.delta for e in events if e.type == "response.reasoning_summary_text.delta"]
    assert "".join(deltas) == "The user asks about an invoice." and len(deltas) > 1
    assert [item.type for item in final.output] == ["reasoning", "message"]
    assert [s.text for s in final.output[0].summary] == ["The user asks about an invoice."]
    assert final.output_text == "Billing."
    assert fake.log() == [call(1, "text", 1)]


def test_stream_max_tokens(fake, openai_client):
    fake.load([{"text": "cut off", "stopReason": "max_tokens"}] * 2)
    events = list(stream(openai_client))
    last = events[-1]
    assert last.type == "response.incomplete"
    assert last.response.status == "incomplete" and last.response.incomplete_details.reason == "max_output_tokens"
    assert last.response.output_text == "cut off"
    with openai_client.responses.stream(model="example-gpt", input="Hello") as s:
        with pytest.raises(RuntimeError):
            s.get_final_response()
    assert fake.log() == [call(1, "text", 1), call(2, "text", 2)]


def test_stream_function_calls(fake, openai_client):
    fake.load([{"text": "Checking.", "toolCalls": [{"name": "get_weather", "arguments": {"city": "Paris"}}], "streamChunkChars": 8}])
    with openai_client.responses.stream(model="example-gpt", input="Hello") as s:
        events = list(s)
        final = s.get_final_response()
    assert [e.delta for e in events if e.type == "response.output_text.delta"] == ["Checking", "."]
    assert [e.delta for e in events if e.type == "response.function_call_arguments.delta"] == ['{"city":', '"Paris"}']
    done = [(e.item_id, e.output_index, e.arguments) for e in events if e.type == "response.function_call_arguments.done"]
    assert done == [("fc_fake_1_1", 1, '{"city":"Paris"}')]
    assert final.output_text == "Checking."
    o = final.output[1]
    assert (o.type, o.call_id, o.name, o.arguments) == ("function_call", "call_fake_1_1", "get_weather", '{"city":"Paris"}')
    assert fake.log() == [call(1, "tool-calls", 1)]


def test_stream_raw_arguments(fake, openai_client):
    fake.load([{"toolCalls": [{"name": "get_weather", "rawArguments": '{"city": Paris}'}], "streamChunkChars": 8}])
    events = list(stream(openai_client))
    assert [e.delta for e in events if e.type == "response.function_call_arguments.delta"] == ['{"city":', " Paris}"]
    third = events[2]
    assert third.type == "response.output_item.added" and third.output_index == 0
    assert third.item.type == "function_call" and third.item.arguments == ""
    assert events[-1].type == "response.completed" and events[-1].response.output[0].arguments == '{"city": Paris}'
    assert fake.log() == [call(1, "tool-calls", 1)]


def test_stream_error_after_chunks(fake, openai_client):
    error = {"message": "server error", "type": "server_error", "param": None, "code": None}
    fake.load([{**error_step(500, error), "streamErrorAfterChunks": 2, "streamChunkChars": 8}, {"text": "never"}])
    deltas = []
    with pytest.raises(openai.APIError) as raised:
        for e in stream(openai_client):
            if e.type == "response.output_text.delta":
                deltas.append(e.delta)
    assert deltas == ["Thinking", " this th"]
    assert not isinstance(raised.value, openai.APIStatusError)
    assert raised.value.body == error and raised.value.message == "server error"
    assert fake.log() == [call(1, "error:500", 1)]


def test_stream_error_generic_body(fake, openai_client):
    fake.load([{"error": {"status": 503, "body": ""}, "streamErrorAfterChunks": 1, "streamChunkChars": 8}] * 2)
    events = list(stream(openai_client))
    p = "response."
    assert types(events) == [p + "created", p + "in_progress", p + "output_item.added", p + "content_part.added", p + "output_text.delta", "error"]
    last = events[-1]
    assert (last.type, last.code, last.message, last.param, last.sequence_number) == ("error", "server_error", "scripted stream error", None, 5)
    with openai_client.responses.stream(model="example-gpt", input="Hello") as s:
        helper_events = list(s)
        assert helper_events[-1].type == "error"
        with pytest.raises(RuntimeError):
            s.get_final_response()
    assert fake.log() == [call(1, "error:503", 1), call(2, "error:503", 2)]


def test_stream_error_before_stream_retried(fake, openai_client):
    error = {"message": "slow down", "type": "requests", "param": None, "code": "rate_limit_exceeded"}
    fake.load([error_step(429, error, FAST_RETRY), {"text": "after"}])
    events = list(stream(openai_client))
    assert "".join(e.delta for e in events if e.type == "response.output_text.delta") == "after"
    assert events[-1].response.id == "resp_fake_2"
    assert fake.log() == [call(1, "error:429", 1), call(2, "text", 2)]


def test_stream_call_after_last_step(fake, openai_client):
    fake.load([{"text": "once"}])
    assert list(stream(openai_client))[-1].type == "response.completed"
    with pytest.raises(openai.BadRequestError) as raised:
        list(stream(openai_client))
    assert raised.value.body["fake_llm_error"] == "no more steps"
    assert fake.log() == [call(1, "text", 1), call(2, "no-more-steps", None)]


def test_first_byte_delay_timeout(fake, server):
    fake.load([{"text": "slow", "firstByteDelayMs": 2000}, {"text": "fast"}])
    with openai.OpenAI(api_key="not-used", base_url=f"{server}/openai/v1", timeout=0.5, max_retries=0) as client:
        with pytest.raises(openai.APITimeoutError):
            create(client)
        fake.wait_for_calls(1)
        assert create(client).output_text == "fast"
    assert fake.log() == [call(1, "text", 1), call(2, "text", 2)]


def test_scripted_usage(fake, openai_client):
    fake.load([{"text": "Counted.", "usage": {"inputTokens": 1200, "outputTokens": 340}}])
    usage = create(openai_client).usage
    assert (usage.input_tokens, usage.output_tokens, usage.total_tokens) == (1200, 340, 1540)
