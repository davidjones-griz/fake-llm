# Run in CI

In CI, fake-llm runs as a separate server beside an app's tests. The app can be in any language: point its LLM base URL at fake-llm, and have its tests load scenarios and read the call log over the [control API](control-api.md). The server prints one line per call to its log, so a failed job's service log shows which calls reached fake-llm and what each got.

## GitHub Actions

[examples/github-actions/fake-llm-tests.yml](https://github.com/davidjones-griz/fake-llm/blob/main/examples/github-actions/fake-llm-tests.yml) is a complete workflow. It runs fake-llm as a service container, and runs a small Python app's tests, [examples/github-actions/test_greeting.py](https://github.com/davidjones-griz/fake-llm/blob/main/examples/github-actions/test_greeting.py), against it. This repository's CI runs it against the image built from each commit. To use it, copy it to `.github/workflows/` in your repository, and change the `examples/github-actions/` paths to where your tests and scenarios are.

The service:

```yaml
services:
  fake-llm:
    image: ghcr.io/davidjones-griz/fake-llm:0.6.0
    ports:
      - 8080:8080
    env:
      FAKE_LLM_SCENARIO_DIR: /scenarios
      FAKE_LLM_CONTROL_TOKEN: fake-llm-ci-token
    options: >-
      --health-cmd "curl -fsS http://127.0.0.1:8080/health"
      --health-interval 2s
      --health-timeout 5s
      --health-retries 30
```

- **Pin a release tag**, such as `0.6.0`, not `latest` or `0.6`. Those two tags move with each release, so a new release could change your tests' results with no change in your repository. The recipe needs 0.5.0 or later, for `POST /control/unload`.
- **Configure it with environment variables.** A service container takes no command-line arguments, so use the variables under [Configuration](https://github.com/davidjones-griz/fake-llm#configuration): `FAKE_LLM_SCENARIO` (a scenario loaded at startup), `FAKE_LLM_SCENARIO_DIR`, `FAKE_LLM_LENIENT` and `FAKE_LLM_CONTROL_TOKEN`. Keep the container's port at 8080, and choose the runner's port in `ports`.
- **The health check** runs inside the container, so it needs a program in the image. The image has `curl`, from its eclipse-temurin base. `GET /health` needs no token. The runner starts the job's steps once the check passes, and fails the job if it never does. Without a health check, the steps can start before the server listens. From 0.6.0 the image declares its own health check on `GET /health` (every 5 s; failures in the first 30 s don't count), so a service container waits for it with no `options`. The recipe keeps its `--health-cmd` so that it also works with 0.5.0. A service's `--health-cmd` replaces the image's command, and any timing flag it leaves unset comes from the image.
- **The control token is optional.** When it is set, every `/control/` request must send `Authorization: Bearer <token>`. The ports of a GitHub-hosted runner are not reachable from outside the job, so the token can be written in the workflow.

The job's `env` points the app at the service. The steps run on the runner, so the address is `localhost` and the port from `ports`:

```yaml
env:
  ANTHROPIC_BASE_URL: http://localhost:8080/anthropic
  ANTHROPIC_API_KEY: not-used
  FAKE_LLM_URL: http://localhost:8080
  FAKE_LLM_CONTROL_TOKEN: fake-llm-ci-token
```

The official Python SDKs read their base URL from the environment, so the app needs no code change: `ANTHROPIC_BASE_URL` (`http://localhost:8080/anthropic`), `OPENAI_BASE_URL` (`http://localhost:8080/openai/v1`) and `GOOGLE_GEMINI_BASE_URL` (`http://localhost:8080/gemini/`). Each still needs its API key variable (`ANTHROPIC_API_KEY`, `OPENAI_API_KEY` or `GEMINI_API_KEY`) set, to any value. Other clients take the same base URLs; see [Quickstart](quickstart.md). When the job itself runs in a container (`jobs.<job_id>.container`), the service is at `http://fake-llm:8080` instead.

A service's volumes are mounted when the job starts, before `actions/checkout` runs, so a volume can't show the repository's scenario files. Copy them into the running container after the checkout instead. `FAKE_LLM_SCENARIO_DIR` is read at each load by name, so the directory doesn't have to exist when the server starts:

```yaml
- name: Copy the scenarios into fake-llm
  env:
    FAKE_LLM_CONTAINER: ${{ job.services.fake-llm.id }}
  run: docker cp examples/github-actions/scenarios/. "$FAKE_LLM_CONTAINER:/scenarios"
```

A test can also send its scenario inline in `POST /control/scenario` (see [Loading a scenario](scenarios.md#loading-a-scenario)). That needs no copy and no scenario directory.

## A scenario per test

Tests that share one server should each start from a known state. Load the test's scenario at the start of the test: a load replaces the previous scenario, empties the call log and the exchange log, and starts numbering at call 1. After the test, `POST /control/unload` returns the server to the state it has when started without a scenario, so a test that loads nothing gets the `no scenario loaded` 400, not whatever the previous test left. Unload needs fake-llm 0.5.0 or later; an older server answers it with HTTP 404. Within a test, `POST /control/reset` starts the loaded scenario over.

The example's pytest fixture and one of its tests:

```python
@pytest.fixture
def fake_llm():
    token = os.environ.get("FAKE_LLM_CONTROL_TOKEN")
    headers = {"authorization": f"Bearer {token}"} if token else {}
    with httpx.Client(base_url=os.environ["FAKE_LLM_URL"], headers=headers) as control:
        yield control
        control.post("/control/unload").raise_for_status()


def test_greets_by_name(fake_llm):
    load(fake_llm, {"name": "greeting"})

    assert greet("Ada") == "Hello, Ada."

    [call] = calls(fake_llm)
    assert call["outcome"] == "text"
    assert call["promptSample"] == "Greet Ada.\n"
```

`load` posts the body to `POST /control/scenario` and checks for HTTP 200, and `calls` returns `GET /control/calls`. The same requests work from any language with an HTTP client; from Kotlin or Java, [`FakeLlmClient`](library.md#control-api-client) makes them. One server has one scenario and one call log, so tests that run in parallel against it interleave their calls: run them one at a time, or give each parallel worker its own server.

## Other CI systems

Where the CI system can run `docker`, start the image in a step after the checkout. Arguments and volumes then work as in any `docker run`. The files must be readable by the image's user, UID 10001:

```bash
docker run -d --name fake-llm -p 8080:8080 \
  -v "$PWD/tests/scenarios:/scenarios:ro" \
  ghcr.io/davidjones-griz/fake-llm:0.6.0 --scenario-dir /scenarios
for _ in $(seq 30); do
  curl -fsS http://127.0.0.1:8080/health > /dev/null && break
  sleep 1
done
curl -fsS http://127.0.0.1:8080/health
```

For Python tests, the [pytest plugin](pytest.md) starts the server for a test session, or uses one that is already running, such as a service container.
