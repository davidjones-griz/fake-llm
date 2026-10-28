"""An app's tests run against fake-llm as a GitHub Actions service container.

fake-llm-tests.yml starts the server and sets ANTHROPIC_BASE_URL, so the app's own Anthropic
client talks to fake-llm. Each test loads its scenario over the control API, calls the app, and
checks the call log. See "Run in CI" in the fake-llm README.
"""

import json
import os
import pathlib

import anthropic
import httpx
import pytest

SCENARIOS = pathlib.Path(__file__).parent / "scenarios"


def greet(name):
    """The app under test. The client reads ANTHROPIC_BASE_URL and ANTHROPIC_API_KEY."""
    client = anthropic.Anthropic()
    message = client.messages.create(
        model="example-model",
        max_tokens=256,
        messages=[{"role": "user", "content": f"Greet {name}."}],
    )
    return message.content[0].text


@pytest.fixture
def fake_llm():
    """A control API client. Afterwards the server has no scenario and empty logs again."""
    token = os.environ.get("FAKE_LLM_CONTROL_TOKEN")
    headers = {"authorization": f"Bearer {token}"} if token else {}
    with httpx.Client(base_url=os.environ["FAKE_LLM_URL"], headers=headers) as control:
        yield control
        # POST /control/unload needs fake-llm 0.5.0 or later.
        control.post("/control/unload").raise_for_status()


def load(control, body):
    response = control.post("/control/scenario", json=body)
    assert response.status_code == 200, response.text


def calls(control):
    response = control.get("/control/calls")
    response.raise_for_status()
    return response.json()


def test_greets_by_name(fake_llm):
    # By name, from the scenario directory the workflow copied into the container.
    load(fake_llm, {"name": "greeting"})

    assert greet("Ada") == "Hello, Ada."

    [call] = calls(fake_llm)
    assert call["outcome"] == "text"
    assert call["promptSample"] == "Greet Ada.\n"


def test_retries_after_a_rate_limit(fake_llm):
    # Inline, from a file in this repository: no scenario directory needed.
    scenario = json.loads((SCENARIOS / "rate-limited-greeting.json").read_text())
    load(fake_llm, {"scenario": scenario})

    assert greet("Ada") == "Hello, Ada."

    assert [call["outcome"] for call in calls(fake_llm)] == ["error:429", "text"]


def test_reset_starts_the_scenario_over(fake_llm):
    load(fake_llm, {"name": "greeting"})
    assert greet("Ada") == "Hello, Ada."

    fake_llm.post("/control/reset").raise_for_status()

    assert calls(fake_llm) == []
    assert greet("Ada") == "Hello, Ada."
    assert [call["seq"] for call in calls(fake_llm)] == [1]


def test_with_no_scenario_the_call_fails(fake_llm):
    # Each test starts with no scenario loaded, because the fixture unloads after the last one.
    with pytest.raises(anthropic.BadRequestError) as failure:
        greet("Ada")

    assert failure.value.body["fake_llm_error"] == "no scenario loaded"
    assert [call["outcome"] for call in calls(fake_llm)] == ["no-scenario"]
