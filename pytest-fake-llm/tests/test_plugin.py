"""The fixtures, the marker and the options. Most run in a pytest process of their own (pytester),
pointed at the session's server with --fake-llm-url."""

import pytest

from support import ANTHROPIC_REQUEST, GEMINI_REQUEST, HELLO, OPENAI_REQUEST, post

# The inner test files' own helper: one Anthropic call.
CALL = """
import json, urllib.request

def call(fake_llm):
    body = {"model": "m", "max_tokens": 64, "messages": [{"role": "user", "content": "Hi"}]}
    request = urllib.request.Request(f"{fake_llm.anthropic_base_url}/v1/messages", data=json.dumps(body).encode(),
                                     headers={"content-type": "application/json"})
    with urllib.request.build_opener(urllib.request.ProxyHandler({})).open(request, timeout=10) as response:
        return json.loads(response.read())["content"][0]["text"]
"""


@pytest.mark.fake_llm_scenario("hello")
def test_each_sdk_base_url_reaches_the_scenario(fake_llm):
    assert fake_llm.anthropic_base_url == f"{fake_llm.base_url}/anthropic"
    assert fake_llm.openai_base_url == f"{fake_llm.base_url}/openai/v1"
    assert fake_llm.gemini_base_url == f"{fake_llm.base_url}/gemini/"
    # hello has one step, so the scenario starts over before each vendor's call.
    replies = {}
    for vendor, url, body in [
        ("anthropic", f"{fake_llm.anthropic_base_url}/v1/messages", ANTHROPIC_REQUEST),
        ("openai", f"{fake_llm.openai_base_url}/chat/completions", OPENAI_REQUEST),
        ("gemini", f"{fake_llm.gemini_base_url}v1beta/models/m:generateContent", GEMINI_REQUEST),
    ]:
        fake_llm.client.reset()
        replies[vendor] = str(post(url, body))
        assert [c.vendor for c in fake_llm.client.calls()] == [vendor]

    assert all(HELLO in reply for reply in replies.values()), replies


def test_the_marker_loads_the_nearest_scenario_before_the_test(pytester, fake_llm_server):
    pytester.makepyfile(
        """
        import pytest

        @pytest.mark.fake_llm_scenario("hello")
        def test_on_the_function(fake_llm):
            assert fake_llm.client.health().scenario == "hello"
            assert fake_llm.client.calls() == []

        @pytest.mark.fake_llm_scenario("refusal")
        class TestOnTheClass:
            def test_from_the_class(self, fake_llm):
                assert fake_llm.client.health().scenario == "refusal"

            @pytest.mark.fake_llm_scenario("hello")
            def test_the_functions_wins(self, fake_llm):
                assert fake_llm.client.health().scenario == "hello"
        """
    )

    pytester.runpytest_subprocess("--fake-llm-url", fake_llm_server.base_url, "--strict-markers").assert_outcomes(passed=3)


def test_each_test_starts_with_no_scenario_and_empty_logs(pytester, fake_llm_server):
    pytester.makepyfile(
        CALL
        + """
import pytest

@pytest.mark.fake_llm_scenario("hello")
def test_first(fake_llm):
    assert call(fake_llm).startswith("Hello.")
    assert len(fake_llm.client.calls()) == 1

def test_second(fake_llm):
    assert fake_llm.client.health().scenario is None
    assert fake_llm.client.calls() == []
"""
    )

    pytester.runpytest_subprocess("--fake-llm-url", fake_llm_server.base_url).assert_outcomes(passed=2)


def test_the_server_url_can_come_from_the_environment(pytester, fake_llm_server, monkeypatch):
    monkeypatch.setenv("FAKE_LLM_URL", fake_llm_server.base_url + "/")
    pytester.makepyfile(
        f"""
        def test_it(fake_llm):
            assert fake_llm.base_url == {fake_llm_server.base_url!r}
            assert fake_llm.client.health().scenario is None
        """
    )

    pytester.runpytest_subprocess().assert_outcomes(passed=1)


def test_a_url_with_no_server_fails_the_tests_that_use_it(pytester, stub):
    closed = stub(200, "{}")
    closed.close()
    pytester.makepyfile("def test_it(fake_llm): pass\ndef test_other(): pass\n")

    result = pytester.runpytest_subprocess("--fake-llm-url", closed.url)

    result.assert_outcomes(passed=1, errors=1)
    result.stdout.fnmatch_lines([f"*fake-llm: no server answered GET /health at {closed.url}: GET /health failed*"])


def test_a_marker_without_one_name_is_an_error(pytester, fake_llm_server):
    pytester.makepyfile(
        """
        import pytest

        @pytest.mark.fake_llm_scenario()
        def test_it(fake_llm):
            pass
        """
    )

    result = pytester.runpytest_subprocess("--fake-llm-url", fake_llm_server.base_url)

    result.assert_outcomes(errors=1)
    result.stdout.fnmatch_lines(['*fake-llm: use @pytest.mark.fake_llm_scenario("<scenario name>"), with one name*'])


def test_without_docker_the_error_says_how_to_use_a_running_server(pytester, monkeypatch):
    monkeypatch.delenv("FAKE_LLM_URL", raising=False)
    monkeypatch.setenv("PATH", str(pytester.mkdir("empty")))
    pytester.makepyfile("def test_it(fake_llm): pass\n")

    result = pytester.runpytest_subprocess()

    result.assert_outcomes(errors=1)
    result.stdout.fnmatch_lines(["*fake-llm: the docker command was not found. Install Docker, or pass --fake-llm-url (or set FAKE_LLM_URL)*"])


def test_the_plugin_is_installed_with_its_options_and_marker(pytester):
    help_text = pytester.runpytest_subprocess("--help").stdout.str()
    markers = pytester.runpytest_subprocess("--markers").stdout.str()

    for option in ("--fake-llm-url", "--fake-llm-image", "--fake-llm-scenario-dir", "fake_llm_scenario_dir"):
        assert option in help_text
    assert "@pytest.mark.fake_llm_scenario(name)" in markers
