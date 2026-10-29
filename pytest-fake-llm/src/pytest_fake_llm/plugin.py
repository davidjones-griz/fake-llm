"""The pytest plugin: a fake-llm server for the session, and a clean state for each test."""

from __future__ import annotations

import importlib.metadata
import os
import subprocess
import time
from collections.abc import Iterator
from dataclasses import dataclass
from pathlib import Path

import pytest

from pytest_fake_llm.client import FakeLlmClient, FakeLlmClientError

IMAGE_REPOSITORY = "ghcr.io/davidjones-griz/fake-llm"
CONTAINER_PORT = 8080
CONTAINER_SCENARIO_DIR = "/scenarios"
STARTUP_SECONDS = 60.0
_POLL_SECONDS = 0.2
_MARKER = "fake_llm_scenario"


def default_image() -> str:
    """The image started when none is given: fake-llm's release with this plugin's version."""
    return f"{IMAGE_REPOSITORY}:{importlib.metadata.version('pytest-fake-llm')}"


@dataclass(frozen=True)
class FakeLlmServer:
    """A running server: its address, the base URLs for each SDK, and a control API client."""

    base_url: str
    client: FakeLlmClient

    @property
    def anthropic_base_url(self) -> str:
        """For ``anthropic.Anthropic(base_url=...)``, which appends ``/v1/messages``."""
        return f"{self.base_url}/anthropic"

    @property
    def openai_base_url(self) -> str:
        """For ``openai.OpenAI(base_url=...)``, which appends ``/chat/completions`` or ``/responses``."""
        return f"{self.base_url}/openai/v1"

    @property
    def gemini_base_url(self) -> str:
        """For ``google-genai``'s ``HttpOptions(base_url=...)``, which appends ``v1beta/models/...``."""
        return f"{self.base_url}/gemini/"


def pytest_addoption(parser: pytest.Parser) -> None:
    group = parser.getgroup("fake-llm")
    group.addoption(
        "--fake-llm-url",
        help="use the fake-llm server at this base URL instead of starting a container (default: $FAKE_LLM_URL)",
    )
    group.addoption(
        "--fake-llm-image",
        help=f"the image to start (default: $FAKE_LLM_IMAGE, else {IMAGE_REPOSITORY}:<the plugin's version>)",
    )
    group.addoption(
        "--fake-llm-scenario-dir",
        help="a directory mounted into the started container as its scenario directory (default: the fake_llm_scenario_dir ini value)",
    )
    parser.addini(
        "fake_llm_scenario_dir",
        "a directory, relative to the ini file, mounted into the started container as its scenario directory",
        type="paths",
    )


def pytest_configure(config: pytest.Config) -> None:
    config.addinivalue_line("markers", f'{_MARKER}(name): load the named scenario before the test, through the fake_llm fixture')


@pytest.fixture(scope="session")
def fake_llm_server(pytestconfig: pytest.Config) -> Iterator[FakeLlmServer]:
    """The server for the session: the one at --fake-llm-url or $FAKE_LLM_URL, or else a container it starts and removes."""
    token = os.environ.get("FAKE_LLM_CONTROL_TOKEN") or None
    url = pytestconfig.getoption("fake_llm_url") or os.environ.get("FAKE_LLM_URL")
    if url:
        client = FakeLlmClient(url, token)
        try:
            client.health()
        except FakeLlmClientError as e:
            pytest.fail(f"fake-llm: no server answered GET /health at {client.base_url}: {e}", pytrace=False)
        yield FakeLlmServer(client.base_url, client)
    else:
        yield from _container(pytestconfig, token)


@pytest.fixture
def fake_llm(fake_llm_server: FakeLlmServer, request: pytest.FixtureRequest) -> FakeLlmServer:
    """The session's server, with no scenario and empty logs, or with the test's fake_llm_scenario loaded."""
    marker = request.node.get_closest_marker(_MARKER)
    if marker is not None and (len(marker.args) != 1 or not isinstance(marker.args[0], str) or marker.kwargs):
        pytest.fail(f'fake-llm: use @pytest.mark.{_MARKER}("<scenario name>"), with one name', pytrace=False)
    fake_llm_server.client.unload()
    if marker is not None:
        fake_llm_server.client.load(marker.args[0])
    return fake_llm_server


def _container(config: pytest.Config, token: str | None) -> Iterator[FakeLlmServer]:
    image = config.getoption("fake_llm_image") or os.environ.get("FAKE_LLM_IMAGE") or default_image()
    args = ["run", "--detach", "--publish", f"127.0.0.1::{CONTAINER_PORT}"]
    scenario_dir = _scenario_dir(config)
    if scenario_dir is not None:
        args += ["--volume", f"{scenario_dir}:{CONTAINER_SCENARIO_DIR}:ro", "--env", f"FAKE_LLM_SCENARIO_DIR={CONTAINER_SCENARIO_DIR}"]
    if token is not None:
        # Passed by name, so the value is not on the docker command line.
        args += ["--env", "FAKE_LLM_CONTROL_TOKEN"]
    container = _docker(*args, image).strip()
    try:
        address = _docker("port", container, f"{CONTAINER_PORT}/tcp").splitlines()[0].strip()
        base_url = f"http://127.0.0.1:{address.rsplit(':', 1)[1]}"
        _await_healthy(base_url, container)
        yield FakeLlmServer(base_url, FakeLlmClient(base_url, token))
    finally:
        _docker("rm", "--force", container, check=False)


def _scenario_dir(config: pytest.Config) -> Path | None:
    option = config.getoption("fake_llm_scenario_dir")
    if option:
        directory = config.invocation_params.dir / option
    else:
        paths = config.getini("fake_llm_scenario_dir")
        if len(paths) > 1:
            pytest.fail("fake-llm: fake_llm_scenario_dir takes one directory", pytrace=False)
        if not paths:
            return None
        directory = Path(paths[0])
    directory = directory.resolve()
    if not directory.is_dir():
        pytest.fail(f"fake-llm: the scenario directory {directory} does not exist", pytrace=False)
    return directory


def _await_healthy(base_url: str, container: str) -> None:
    """Polls GET /health until it answers. Docker accepts connections before the server listens."""
    probe = FakeLlmClient(base_url, timeout=1.0)
    deadline = time.monotonic() + STARTUP_SECONDS
    while True:
        try:
            probe.health()
            return
        except FakeLlmClientError:
            pass
        running = _docker("inspect", "--format", "{{.State.Running}}", container).strip() == "true"
        if not running or time.monotonic() > deadline:
            state = "exited" if not running else f"did not answer GET /health within {STARTUP_SECONDS:.0f} s"
            pytest.fail(f"fake-llm: the container {state}. Its log:\n{_docker('logs', container, check=False)}", pytrace=False)
        time.sleep(_POLL_SECONDS)


def _docker(*args: str, check: bool = True) -> str:
    try:
        result = subprocess.run(["docker", *args], capture_output=True, text=True, check=False)
    except FileNotFoundError:
        pytest.fail("fake-llm: the docker command was not found. Install Docker, or pass --fake-llm-url (or set FAKE_LLM_URL)", pytrace=False)
    if check and result.returncode != 0:
        pytest.fail(f"fake-llm: docker {args[0]} failed with exit code {result.returncode}:\n{result.stderr}", pytrace=False)
    return result.stdout + (result.stderr if not check else "")
