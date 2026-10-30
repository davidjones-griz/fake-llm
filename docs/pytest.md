# pytest plugin

`pytest-fake-llm`, in [pytest-fake-llm/](https://github.com/davidjones-griz/fake-llm/tree/main/pytest-fake-llm/), gives a Python project's pytest tests a fake-llm server: it starts the Docker image for the test session, or uses a server that is already running, and unloads it before each test. It needs Python 3.11 or newer and pytest 8 or newer, and has no other dependencies. It is not on PyPI; install it from this repository at a release tag, 0.6.0 or later:

```bash
pip install "pytest-fake-llm @ git+https://github.com/davidjones-griz/fake-llm@v0.6.0#subdirectory=pytest-fake-llm"
```

```python
import anthropic
import pytest


@pytest.mark.fake_llm_scenario("rate-limit-429")
def test_retries_after_a_rate_limit(fake_llm):
    client = anthropic.Anthropic(api_key="not-used", base_url=fake_llm.anthropic_base_url)
    # ... exercise the code under test ...
    assert [call.outcome for call in fake_llm.client.calls()] == ["error:429", "text"]
```

- **The server.** The session-scoped `fake_llm_server` fixture starts `ghcr.io/davidjones-griz/fake-llm`, tagged with the plugin's own version, on a free port of 127.0.0.1, waits until `GET /health` answers, and removes the container when the session ends. `--fake-llm-image` (or `FAKE_LLM_IMAGE`) starts another image or tag instead. The plugin runs the `docker` command, so Docker must be installed where the tests run.
- **A running server.** With `--fake-llm-url` (or `FAKE_LLM_URL`), no container is started, and the tests use the server at that base URL, such as a service container (see [GitHub Actions](ci.md#github-actions)). If it doesn't answer `GET /health`, every test that uses it errors, naming the URL.
- **A clean server for each test.** The `fake_llm` fixture unloads the server before each test (`POST /control/unload`), so every test starts with no scenario loaded and empty logs, as under [A scenario per test](ci.md#a-scenario-per-test). `@pytest.mark.fake_llm_scenario("name")` on the test, its class or its module then loads that scenario; the nearest one wins. The marker takes effect through the fixture, so the test must request `fake_llm`. The server must be fake-llm 0.5.0 or later.
- **Base URLs.** `fake_llm.base_url` is the server's root. `anthropic_base_url` (`…/anthropic`), `openai_base_url` (`…/openai/v1`) and `gemini_base_url` (`…/gemini/`) are the base URLs the official SDKs take; see [Quickstart](quickstart.md).
- **A scenario directory.** `fake_llm_scenario_dir = tests/scenarios` in the pytest configuration (relative to that file), or `--fake-llm-scenario-dir tests/scenarios`, mounts that directory into the started container, read-only, as its scenario directory, so its scenarios load by name. The files must be readable by the image's user, UID 10001. A running server keeps its own scenario directory. A test can also send its scenario inline, with `fake_llm.client.load_inline({...})`.
- **The control token.** When `FAKE_LLM_CONTROL_TOKEN` is set, the started container requires it, and the client sends it, to a started or a running server.
- **The client.** `fake_llm.client` is a `FakeLlmClient`, which also works on its own: `FakeLlmClient(base_url, control_token=None, timeout=10.0)` from `pytest_fake_llm`. Its methods are those of the Kotlin [`FakeLlmClient`](library.md#control-api-client), in snake_case: `health()`, `scenario_names()`, `load(name)`, `load_inline(scenario)`, `reset()`, `unload()`, `calls()` and `exchanges()`. `calls()` returns `CallRecord`s, with the fields of `GET /control/calls` in snake_case, and `exchanges()` the decoded JSON. `health()` also returns `steps` and `unused_steps` (`None` and `[]` with no scenario loaded), both `None` against a server older than 0.6.0. A failed call raises `FakeLlmClientError`, whose `status_code` is the HTTP status, or None when no complete response arrived, and whose `server_message` is the server's explanation, such as `unknown scenario 'x' — ...`. A 401 says whether the token was missing or rejected. The client uses only the standard library, and ignores the proxy environment variables.
- **One server for the session.** Its tests share it, so they run one at a time. With pytest-xdist, each worker starts a container of its own; with `--fake-llm-url`, the workers would share one server, so run those tests without `-n`.
