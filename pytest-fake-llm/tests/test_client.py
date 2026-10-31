"""FakeLlmClient against the session's server, and against a stub for what a real server never sends."""

import pytest

from pytest_fake_llm import CallRecord, FakeLlmClient, FakeLlmClientError, Health, ToolCallRecord, ToolResultRecord
from support import ANTHROPIC_REQUEST, HELLO, OPENAI_REQUEST, post


def test_health_with_no_scenario_loaded(fake_llm):
    assert fake_llm.client.health() == Health(scenario=None, lenient=False, steps=None, unused_steps=[])


def test_health_reports_the_steps_no_call_has_used(fake_llm):
    fake_llm.client.load_inline({"name": "two", "steps": [{"text": "a"}, {"text": "b"}]})
    post(f"{fake_llm.anthropic_base_url}/v1/messages", ANTHROPIC_REQUEST)

    health = fake_llm.client.health()

    assert (health.steps, health.unused_steps) == (2, [2])


def test_health_from_an_older_server_leaves_the_new_fields_none(stub):
    health = FakeLlmClient(stub(200, '{"status":"ok","scenario":null,"lenient":false}').url).health()

    assert (health.steps, health.unused_steps) == (None, None)


def test_scenario_names_are_the_bundled_ones_sorted(fake_llm):
    names = fake_llm.client.scenario_names()

    assert "hello" in names
    assert names == sorted(names)


def test_a_scenario_loaded_by_name_answers_and_its_call_is_logged(fake_llm):
    fake_llm.client.load("hello")

    reply = post(f"{fake_llm.anthropic_base_url}/v1/messages", ANTHROPIC_REQUEST)

    assert reply["content"][0]["text"] == HELLO
    [call] = fake_llm.client.calls()
    assert isinstance(call, CallRecord)
    assert (call.seq, call.step, call.vendor, call.model, call.outcome) == (1, 1, "anthropic", "m", "text")
    assert (call.message_count, call.system_shape, call.prompt_sample) == (1, "absent", "Hi\n")
    assert (call.tool_calls, call.tool_results) == ([], [])
    assert fake_llm.client.health().scenario == "hello"


def test_an_inline_scenario_logs_tool_calls_and_results(fake_llm):
    fake_llm.client.load_inline(
        {
            "name": "inline-tools",
            "steps": [{"toolCalls": [{"id": "call_1", "name": "get_weather", "arguments": {"city": "Paris"}}]}, {"text": "18 degrees."}],
        }
    )
    tool_message = {"role": "assistant", "tool_calls": [{"id": "call_1", "type": "function", "function": {"name": "get_weather", "arguments": "{}"}}]}
    url = f"{fake_llm.openai_base_url}/chat/completions"

    post(url, OPENAI_REQUEST)
    post(url, {**OPENAI_REQUEST, "messages": [*OPENAI_REQUEST["messages"], tool_message, {"role": "tool", "tool_call_id": "call_1", "content": "18"}]})

    first, second = fake_llm.client.calls()
    assert (first.outcome, first.tool_calls) == ("tool-calls", [ToolCallRecord(id="call_1", name="get_weather")])
    assert second.tool_results == [ToolResultRecord(id="call_1", name="get_weather", content="18", is_error=False)]
    assert [e["request"]["path"] for e in fake_llm.client.exchanges()] == ["/openai/v1/chat/completions"] * 2


def test_reset_starts_the_scenario_over_and_unload_removes_it(fake_llm):
    fake_llm.client.load("hello")
    post(f"{fake_llm.anthropic_base_url}/v1/messages", ANTHROPIC_REQUEST)

    fake_llm.client.reset()
    assert (fake_llm.client.calls(), fake_llm.client.health().scenario) == ([], "hello")

    fake_llm.client.unload()
    assert (fake_llm.client.calls(), fake_llm.client.health().scenario) == ([], None)


def test_an_unknown_scenario_raises_with_the_servers_message(fake_llm):
    with pytest.raises(FakeLlmClientError) as raised:
        fake_llm.client.load("no-such-scenario")

    assert raised.value.status_code == 400
    assert raised.value.server_message.startswith("unknown scenario 'no-such-scenario'")
    assert raised.value.error_code == "unknown-scenario"
    assert str(raised.value).startswith("POST /control/scenario returned HTTP 400: unknown scenario 'no-such-scenario'")


def test_a_rejected_inline_scenario_raises_with_the_servers_message(fake_llm):
    with pytest.raises(FakeLlmClientError) as raised:
        fake_llm.client.load_inline({"name": "misspelt", "steps": [{"txt": "a"}]})

    assert (raised.value.status_code, raised.value.server_message) == (400, "step 1: $.steps[0]: unknown field 'txt'")
    assert raised.value.error_code == "invalid-scenario"


def test_the_token_is_sent_as_a_bearer_header(stub):
    server = stub(200, '{"scenarios": []}')

    assert FakeLlmClient(server.url, control_token="t0ken").scenario_names() == []
    FakeLlmClient(server.url, control_token="  ").scenario_names()

    assert server.headers[0]["Authorization"] == "Bearer t0ken"
    assert "Authorization" not in server.headers[1]


def test_a_401_says_whether_the_token_was_missing_or_rejected(stub):
    server = stub(401, '{"fake_llm_error":"unauthorized: missing or invalid Authorization: Bearer <token>"}')

    with pytest.raises(FakeLlmClientError, match="requires a control token") as missing:
        FakeLlmClient(server.url).reset()
    with pytest.raises(FakeLlmClientError, match="rejected the control token") as rejected:
        FakeLlmClient(server.url, control_token="wrong").reset()

    assert missing.value.status_code == rejected.value.status_code == 401
    assert missing.value.server_message == "unauthorized: missing or invalid Authorization: Bearer <token>"
    assert missing.value.error_code is None


def test_an_older_servers_400_keeps_its_detail_and_value(stub):
    server = stub(400, '{"fake_llm_error":"exception","detail":"unknown scenario \'x\'"}')

    with pytest.raises(FakeLlmClientError) as raised:
        FakeLlmClient(server.url).load("x")

    assert (raised.value.server_message, raised.value.error_code) == ("unknown scenario 'x'", "exception")


def test_a_non_json_error_and_an_unreadable_reply_keep_the_body(stub):
    with pytest.raises(FakeLlmClientError) as proxy:
        FakeLlmClient(stub(502, "Bad gateway", "text/plain").url).reset()
    with pytest.raises(FakeLlmClientError, match="cannot read") as unreadable:
        FakeLlmClient(stub(200, "<html>", "text/html").url).calls()

    assert (proxy.value.status_code, proxy.value.server_message) == (502, "Bad gateway")
    assert proxy.value.error_code is None
    assert (unreadable.value.status_code, unreadable.value.server_message) == (200, None)


def test_no_server_raises_with_no_status(stub):
    server = stub(200, "{}")
    server.close()

    with pytest.raises(FakeLlmClientError, match="GET /health failed") as raised:
        FakeLlmClient(server.url, timeout=1).health()

    assert raised.value.status_code is None


@pytest.mark.parametrize(
    "base_url", ["localhost:8080", "ftp://localhost", "http://", "http://localhost:99999", "http://localhost:8080/?a=1", "http://localhost#x"]
)
def test_a_base_url_the_paths_cannot_be_appended_to_is_rejected(base_url):
    with pytest.raises(ValueError, match="base_url"):
        FakeLlmClient(base_url)


def test_trailing_slashes_are_dropped_and_the_timeout_must_be_positive():
    assert FakeLlmClient("http://localhost:8080//").base_url == "http://localhost:8080"
    with pytest.raises(ValueError, match="timeout"):
        FakeLlmClient("http://localhost:8080", timeout=0)
