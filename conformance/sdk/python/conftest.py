"""Fixtures for the SDK conformance suite: one fake-llm server per session, control helpers, and
the official SDK clients pointed at it.

The server is either started from FAKE_LLM_COMMAND (run through `sh -c`, with PORT and the
FAKE_LLM_* settings in its environment) or reached at FAKE_LLM_BASE_URL. Exactly one must be set.
"""

import os
import signal
import socket
import subprocess
import tempfile
import time

import anthropic
import httpx
import openai
import pytest
from google import genai
from google.genai import types

STARTUP_SECONDS = 30
STOP_GRACE_SECONDS = 5
POLL_SECONDS = 0.1
HEALTH_REQUEST_SECONDS = 1.0


@pytest.fixture(scope="session")
def server():
    """The server's base URL, such as http://127.0.0.1:41234."""
    command = os.environ.get("FAKE_LLM_COMMAND", "")
    base_url = os.environ.get("FAKE_LLM_BASE_URL", "").rstrip("/")
    if bool(command) == bool(base_url):
        pytest.fail("set exactly one of FAKE_LLM_COMMAND (a command that starts the server) or FAKE_LLM_BASE_URL (a running server)")
    if base_url:
        _check_running(base_url)
        yield base_url
    else:
        yield from _started(command)


def _check_running(base_url):
    """A running server must be strict and have open control routes."""
    try:
        health = httpx.get(f"{base_url}/health")
        lenient = health.json().get("lenient") if health.status_code == 200 else None
        calls = httpx.get(f"{base_url}/control/calls")
    except (httpx.TransportError, ValueError) as e:
        pytest.fail(f"cannot check the server at {base_url}: {e!r}")
    if lenient is not False:
        pytest.fail(f"GET {base_url}/health must answer 200 with \"lenient\": false, got {health.status_code}: {health.text}")
    if calls.status_code != 200:
        pytest.fail(f"GET {base_url}/control/calls must answer without a control token, got {calls.status_code}: {calls.text}")


def _started(command):
    """Starts the command on a free port in its own process group, and stops the group afterwards."""
    with socket.socket() as probe:
        probe.bind(("127.0.0.1", 0))
        port = probe.getsockname()[1]
    env = {name: value for name, value in os.environ.items() if not name.startswith("FAKE_LLM_")}
    env.update(PORT=str(port), FAKE_LLM_LENIENT="false", FAKE_LLM_CONTROL_TOKEN="", FAKE_LLM_SCENARIO="", FAKE_LLM_SCENARIO_DIR="")
    # Output goes to a file, not a pipe that could fill up and stall the server.
    with tempfile.TemporaryFile() as output:
        process = subprocess.Popen(["sh", "-c", command], env=env, stdout=output, stderr=subprocess.STDOUT, start_new_session=True)
        try:
            base_url = f"http://127.0.0.1:{port}"
            _await_healthy(base_url, process, output)
            yield base_url
        finally:
            _stop(process)


def _await_healthy(base_url, process, output):
    """Polls GET /health. Any transport error counts as not ready yet: Docker accepts connections early."""
    deadline = time.monotonic() + STARTUP_SECONDS
    while time.monotonic() < deadline and process.poll() is None:
        try:
            if httpx.get(f"{base_url}/health", timeout=HEALTH_REQUEST_SECONDS).status_code == 200:
                return
        except httpx.TransportError:
            pass
        time.sleep(POLL_SECONDS)
    output.seek(0)
    printed = output.read().decode(errors="replace")
    if process.poll() is not None:
        pytest.fail(f"the server exited with code {process.returncode} before answering GET /health:\n{printed}")
    pytest.fail(f"no answer from GET {base_url}/health within {STARTUP_SECONDS} s:\n{printed}")


def _stop(process):
    """SIGTERM to the process group, then SIGKILL after the grace period."""
    try:
        os.killpg(process.pid, signal.SIGTERM)
        try:
            process.wait(STOP_GRACE_SECONDS)
        except subprocess.TimeoutExpired:
            os.killpg(process.pid, signal.SIGKILL)
            process.wait()
    except ProcessLookupError:
        pass


class Fake:
    """Control helpers for one test. The scenario is named after the test function."""

    def __init__(self, base_url, name):
        self.base_url = base_url
        self.name = name

    def load(self, steps):
        """Loads the steps as an inline scenario, which starts it over and empties the call log."""
        response = httpx.post(f"{self.base_url}/control/scenario", json={"scenario": {"name": self.name, "steps": steps}})
        assert response.status_code == 200, response.text
        assert response.json() == {"loaded": self.name, "steps": len(steps)}

    def calls(self):
        return httpx.get(f"{self.base_url}/control/calls").json()

    def wait_for_calls(self, count, timeout=5.0):
        """Waits until the call log holds count records, so a timed-out call is logged before the next is sent."""
        deadline = time.monotonic() + timeout
        while len(self.calls()) < count and time.monotonic() < deadline:
            time.sleep(0.01)
        assert len(self.calls()) == count

    def delays(self):
        """Each call's (firstByteDelayMs, streamInterChunkDelayMs), in call order."""
        return [(c["firstByteDelayMs"], c["streamInterChunkDelayMs"]) for c in self.calls()]

    def log(self):
        return [(c["seq"], c["vendor"], c["model"], c["outcome"], c["step"]) for c in self.calls()]


@pytest.fixture
def fake(server, request):
    return Fake(server, request.function.__name__.replace("_", "-"))


@pytest.fixture
def anthropic_client(server):
    with anthropic.Anthropic(api_key="not-used", base_url=f"{server}/anthropic", timeout=10.0) as client:
        yield client


@pytest.fixture
def openai_client(server):
    with openai.OpenAI(api_key="not-used", base_url=f"{server}/openai/v1", timeout=10.0) as client:
        yield client


@pytest.fixture
def gemini_client(server):
    client = genai.Client(api_key="not-used", http_options=types.HttpOptions(base_url=f"{server}/gemini/", timeout=10_000))
    yield client
    client.close()


@pytest.fixture
def gemini_retry_client(server):
    retry = types.HttpRetryOptions(attempts=3, initial_delay=0.01, jitter=0)
    client = genai.Client(
        api_key="not-used",
        http_options=types.HttpOptions(base_url=f"{server}/gemini/", timeout=10_000, retry_options=retry),
    )
    yield client
    client.close()
