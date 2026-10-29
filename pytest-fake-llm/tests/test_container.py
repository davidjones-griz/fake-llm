"""The plugin starting the image itself. Each test starts a container of its own, in a pytest
process of its own (pytester), from the image the session started."""

import pytest

from pytest_fake_llm import FakeLlmClient, FakeLlmClientError
import support

pytestmark = pytest.mark.docker

GREETING = '{"name": "greeting", "steps": [{"text": "Hello from the directory."}]}'
CALL_GREETING = """
import json, urllib.request
import pytest

@pytest.mark.fake_llm_scenario("greeting")
def test_it(fake_llm):
    body = {"model": "m", "max_tokens": 64, "messages": [{"role": "user", "content": "Hi"}]}
    request = urllib.request.Request(f"{fake_llm.anthropic_base_url}/v1/messages", data=json.dumps(body).encode(),
                                     headers={"content-type": "application/json"})
    with urllib.request.build_opener(urllib.request.ProxyHandler({})).open(request, timeout=10) as response:
        assert json.loads(response.read())["content"][0]["text"] == "Hello from the directory."
"""


@pytest.fixture(autouse=True)
def no_server_url(monkeypatch):
    monkeypatch.delenv("FAKE_LLM_URL", raising=False)


@pytest.fixture(autouse=True)
def docker_config(monkeypatch):
    # pytester points HOME at its temp directory, so the inner docker command would lose ~/.docker.
    monkeypatch.setenv("DOCKER_CONFIG", support.DOCKER_CONFIG)


def scenarios(pytester):
    """A scenario directory with greeting.json, readable by the image's non-root user."""
    directory = pytester.mkdir("scenarios")
    directory.chmod(0o755)
    (directory / "greeting.json").write_text(GREETING)
    (directory / "greeting.json").chmod(0o644)


def test_the_container_is_started_for_the_session_and_removed_after_it(pytester, image):
    pytester.makepyfile(
        """
        import pathlib

        def test_it(fake_llm):
            assert fake_llm.client.health().scenario is None
            pathlib.Path("url.txt").write_text(fake_llm.base_url)
        """
    )

    pytester.runpytest_subprocess("--fake-llm-image", image).assert_outcomes(passed=1)

    with pytest.raises(FakeLlmClientError) as raised:
        FakeLlmClient((pytester.path / "url.txt").read_text(), timeout=1).health()
    assert raised.value.status_code is None


def test_the_ini_scenario_directory_is_mounted(pytester, image):
    scenarios(pytester)
    pytester.makeini("[pytest]\nfake_llm_scenario_dir = scenarios\n")
    pytester.makepyfile(CALL_GREETING)

    pytester.runpytest_subprocess("--fake-llm-image", image).assert_outcomes(passed=1)


def test_the_option_scenario_directory_wins_over_the_ini(pytester, image):
    scenarios(pytester)
    pytester.makeini("[pytest]\nfake_llm_scenario_dir = missing\n")
    pytester.makepyfile(CALL_GREETING)

    pytester.runpytest_subprocess("--fake-llm-image", image, "--fake-llm-scenario-dir", "scenarios").assert_outcomes(passed=1)


def test_the_image_and_the_control_token_can_come_from_the_environment(pytester, image, monkeypatch):
    monkeypatch.setenv("FAKE_LLM_IMAGE", image)
    monkeypatch.setenv("FAKE_LLM_CONTROL_TOKEN", "t0ken")
    pytester.makepyfile(
        """
        import pytest
        from pytest_fake_llm import FakeLlmClient, FakeLlmClientError

        @pytest.mark.fake_llm_scenario("hello")
        def test_it(fake_llm):
            assert fake_llm.client.health().scenario == "hello"
            with pytest.raises(FakeLlmClientError, match="requires a control token"):
                FakeLlmClient(fake_llm.base_url).load("hello")
        """
    )

    pytester.runpytest_subprocess().assert_outcomes(passed=1)
