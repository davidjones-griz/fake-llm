# fake-llm

A local HTTP stand-in for Anthropic Messages, OpenAI chat completions and Responses, and Gemini `generateContent` and `streamGenerateContent`, driven by a JSON scenario, plus OpenAI and Gemini embeddings, Anthropic token counting and the three model lists, which answer from the request alone. The server is the JDK `com.sun.net.httpserver` plus kotlinx-serialization.

Documentation: <https://davidjones-griz.github.io/fake-llm/> (quickstart, scenario reference, control API, recipes).

## Why

I got tired of my CI pipeline failing over trivial bugs that should have been caught easily, because there was no way to run each LLM-calling component without deploying it and paying for real model calls. I extracted fake-llm from a personal project so I could exercise each component on its own.

The code that calls an LLM is deterministic; the model is the only part you cannot predict. A scenario takes that part away: it is an ordered list of steps, and call N gets step N, locally and with no model call. That includes the failure paths: a scripted error status, a cutoff in the middle of a stream, `max_tokens` truncation, malformed output, a tool call with valid or malformed arguments, and a failure followed by a successful retry.

A call the scenario has no step for fails: a call after the last step returns HTTP 400 (`no more steps`), and so does a call with no scenario loaded, unless the server runs with `--lenient` or `FAKE_LLM_LENIENT=true`, which answers that case with a canned reply.

## Quickstart

Start the image with the bundled scenario `hello`. Arguments after the image name are passed to fake-llm:

```bash
docker run --rm -p 8080:8080 ghcr.io/davidjones-griz/fake-llm:0.5.0 --scenario hello
```

Check that it is up:

```bash
curl -s http://localhost:8080/health
```

```json
{"status":"ok","scenario":"hello","lenient":false,"steps":1,"unusedSteps":[1],"version":"0.5.0"}
```

Point an SDK at the server; the server does not check API keys:

```python
import anthropic

client = anthropic.Anthropic(api_key="not-used", base_url="http://localhost:8080/anthropic")
message = client.messages.create(
    model="example-model",
    max_tokens=256,
    messages=[{"role": "user", "content": "Hello"}],
)
print(message.content[0].text)  # the scenario's one step
```

`curl -s http://localhost:8080/control/calls` then lists that call: its vendor route, model, prompt and the step that answered it. `hello` has one step, so a second call gets HTTP 400 (`no more steps`) until `POST /control/reset` starts the scenario over.

The SDKs' base URLs:

| SDK | Base URL |
| --- | --- |
| Anthropic | `http://localhost:8080/anthropic` |
| OpenAI (Chat Completions and Responses) | `http://localhost:8080/openai/v1` |
| Gemini (`google-genai`) | `http://localhost:8080/gemini/` |

The [Quickstart](docs/quickstart.md) page has the full walk-through, with the response to each step.

## Install

The image is on GHCR, for linux/amd64 and linux/arm64:

```bash
docker run -p 8080:8080 ghcr.io/davidjones-griz/fake-llm:0.5.0 --scenario hello
```

From 0.6.0 the image reports `healthy` once `GET /health` answers. Set the port with `-e PORT=<n>`, not `--port`, or the health check probes 8080. Compose can wait for it with `depends_on: {fake-llm: {condition: service_healthy}}`.

To run it beside an app's tests in CI, see [Run in CI](#run-in-ci). For Python tests, the [pytest plugin](docs/pytest.md) starts it for each test session.

The libraries are on Maven Central. With Gradle:

```kotlin
repositories { mavenCentral() }
dependencies {
    testImplementation("com.davidjonesgriz:fake-llm:0.5.0")
    // Optional: the JUnit Jupiter extension.
    testImplementation("com.davidjonesgriz:fake-llm-junit-jupiter:0.5.0")
    // Optional: the Testcontainers container.
    testImplementation("com.davidjonesgriz:fake-llm-testcontainers:0.5.0")
}
```

With Maven:

```xml
<dependency>
  <groupId>com.davidjonesgriz</groupId>
  <artifactId>fake-llm</artifactId>
  <version>0.5.0</version>
  <scope>test</scope>
</dependency>
<!-- Optional: the JUnit Jupiter extension. -->
<dependency>
  <groupId>com.davidjonesgriz</groupId>
  <artifactId>fake-llm-junit-jupiter</artifactId>
  <version>0.5.0</version>
  <scope>test</scope>
</dependency>
<!-- Optional: the Testcontainers container. -->
<dependency>
  <groupId>com.davidjonesgriz</groupId>
  <artifactId>fake-llm-testcontainers</artifactId>
  <version>0.5.0</version>
  <scope>test</scope>
</dependency>
```

The fat jar is attached to each [GitHub Release](https://github.com/davidjones-griz/fake-llm/releases), with its SHA-256. It needs Java 21 or newer:

```bash
java -jar fake-llm-all.jar --scenario hello
```

## Use as a library

Kotlin and Java tests can start the server in the same JVM. `startFakeLlmServer` returns a `FakeLlmServer` with a `baseUrl` and the `ScenarioEngine` behind it; `FakeLlmClient` drives a server in another process, such as a container, over the control API:

```kotlin
startFakeLlmServer(port = 0, engine = ScenarioEngine(), controlToken = null).use { server ->
    server.engine.load("hello")
    val client = MyClient(baseUrl = "${server.baseUrl}/anthropic")
    // ... exercise the code under test ...
    assertEquals("text", server.engine.calls().single().outcome)
}

// The same against a server in another process, such as the Docker image:
FakeLlmClient("http://localhost:8080").use { control ->
    control.load("hello")
    // ... exercise the code under test against http://localhost:8080 ...
    assertEquals("text", control.calls().single().outcome)
}
```

The JUnit Jupiter extension (`com.davidjonesgriz:fake-llm-junit-jupiter`) runs a server for each test class, loads each test's scenario, and fails a test whose calls and steps don't match:

```kotlin
@FakeLlmTest
class MyClientTest {
    @Test
    @FakeLlmScenario("rate-limit-429")
    fun `retries after a rate limit`(server: FakeLlmServer) {
        val client = MyClient(baseUrl = "${server.baseUrl}/anthropic")
        // ... exercise the code under test ...
        assertEquals(2, server.engine.calls().size)
    }
}
```

[Use as a library](docs/library.md) covers Java, the control API client, the extension's options, the Testcontainers module (`com.davidjonesgriz:fake-llm-testcontainers`) and API stability. For Python, the [pytest plugin](docs/pytest.md) starts the image for a test session.

## Run in CI

In CI, fake-llm runs as a separate server beside an app's tests, in any language: point the app's LLM base URL at fake-llm, and have its tests load scenarios and read the call log over the [control API](docs/control-api.md). [examples/github-actions/fake-llm-tests.yml](examples/github-actions/fake-llm-tests.yml) is a complete GitHub Actions workflow that runs the image as a service container, and this repository's CI runs it against the image built from each commit. [Run in CI](docs/ci.md) explains the service definition, a scenario per test, and other CI systems.

## Scenarios

A scenario is an ordered list of steps. Each step is a reply (`text`, tool calls, or both) or a scripted error, with optional streaming, latency and token-count settings:

```json
{
  "name": "example",
  "steps": [
    { "error": { "status": 429, "headers": { "retry-after": "1" } } },
    { "text": "Mild and dry." }
  ]
}
```

Call N, on any generation route, gets step N. The last step does not repeat: a call after it gets HTTP 400. A scenario loads by name from a scenario directory, the bundled scenarios (`hello`, `rate-limit-429`, `fail-then-retry` and others) or, in process, the test classpath; or inline over the control API. The [scenario reference](docs/scenarios.md) has every field, the bundled scenarios, and `validate`, which checks scenario files; the [recipes](docs/recipes/index.md) test retries, truncated and malformed output, and mid-stream failures.

## Endpoints

| Method and path | What it does |
| --- | --- |
| `POST /anthropic/v1/messages` | Anthropic Messages, buffered or streamed: the next step. |
| `POST /anthropic/v1/messages/count_tokens` | `{"input_tokens":100}`. Not a call. |
| `/anthropic/v1/messages/batches` | Not implemented: HTTP 501. |
| `POST /openai/v1/chat/completions` | OpenAI Chat Completions, buffered or streamed: the next step. |
| `POST /openai/v1/responses` | OpenAI Responses, buffered or streamed: the next step. |
| `POST /gemini/{version}/models/{model}:generateContent` | Gemini, buffered: the next step. |
| `POST /gemini/{version}/models/{model}:streamGenerateContent?alt=sse` | Gemini, streamed: the next step. |
| `POST /openai/v1/embeddings`, `POST /gemini/…:embedContent`, `…:batchEmbedContents` | Deterministic fake embeddings from the text. Not calls. |
| `GET /anthropic/v1/models`, `/openai/v1/models`, `/gemini/{version}/models`, and `…/models/{id}` | One model, `fake-llm`; a lookup echoes the id. Not calls. |
| `GET /health` | Liveness, the loaded scenario, its unused steps, and the version. |
| `GET /openapi` | The OpenAPI document for this version. |
| `/control/…` | Load, reset and unload scenarios; read the call and exchange logs. |

Every generation reply names the request's model. Anything else is HTTP 404. [Endpoints](docs/endpoints.md) describes each vendor route's bodies and streams, and [Control API](docs/control-api.md) the `/health`, `/openapi` and `/control/` routes and the control token. The OpenAPI document, [openapi/fake-llm.yaml](openapi/fake-llm.yaml), is the contract.

## Configuration

For the port, the scenario, and the scenario directory, a flag overrides the environment variable, and a blank environment variable is ignored. `--lenient` takes no value and turns lenient mode on regardless of `FAKE_LLM_LENIENT`, and `--no-call-log` takes no value and turns the call log line off regardless of `FAKE_LLM_CALL_LOG`. An unknown flag or argument, a port outside 0–65535, a scenario directory that is not a valid path, or a `FAKE_LLM_LENIENT` or `FAKE_LLM_CALL_LOG` value other than `true` or `false` (any case), prints a usage line to stderr and exits 1.

The process binds the IPv6 wildcard `::` when the host can, and otherwise the IPv4 wildcard `0.0.0.0`.

| Flag | Environment variable | Default | Effect |
| --- | --- | --- | --- |
| `--port <n>` | `PORT` | `8080` | Listen port. |
| `--scenario <name>` | `FAKE_LLM_SCENARIO` | none | Scenario loaded at startup. |
| `--scenario-dir <path>` | `FAKE_LLM_SCENARIO_DIR` | bundled scenarios only | Directory searched before the bundled scenarios. |
| `--lenient` | `FAKE_LLM_LENIENT` | off (`false`) | Calls made while no scenario is loaded. Off returns HTTP 400. On returns the canned assistant reply. A call after the last step is HTTP 400 either way. The flag takes no value and turns lenient mode on. The environment variable accepts `true` or `false`, case-insensitively. |
| `--no-call-log` | `FAKE_LLM_CALL_LOG` | on (`true`) | One line per call on stdout: seq, vendor route, model, outcome and step, such as `fake-llm: call 2 openai model="example-gpt" outcome=error:429 step=2`. Prompts, arguments, headers and bodies are not printed. The flag takes no value and turns it off. The environment variable accepts `true` or `false`, case-insensitively. |
| none | `FAKE_LLM_CONTROL_TOKEN` | unset, control routes open | Bearer token for `/control/*`. A JVM system property of the same name is the fallback when the environment variable is absent. |

`fake-llm --version` (or `docker run --rm ghcr.io/davidjones-griz/fake-llm:0.5.0 --version`) prints `fake-llm <version>` and exits 0. It works only as the sole argument: after server flags, `--version` is an unknown flag.

## Limits

Anthropic Messages, OpenAI Chat Completions and Responses, and Gemini all stream. On `POST /anthropic/v1/messages`, `POST /openai/v1/chat/completions` and `POST /openai/v1/responses` with `"stream": true`, and on `POST /gemini/…:streamGenerateContent?alt=sse`, the response is server-sent events for a reply step, and for an error step that sets `streamErrorAfterChunks`. An error step that leaves that field unset is a plain HTTP error instead.

A call after the scenario's last step, and in strict mode (the default) a call with no scenario loaded, gets HTTP 400 and a JSON body on every generation route. On `POST /anthropic/v1/messages`, `POST /openai/v1/chat/completions` and `POST /openai/v1/responses` with `"stream": true`, and on `POST /gemini/…:streamGenerateContent`, the response is `application/json`, and no server-sent events are written. With no scenario loaded, lenient mode (`--lenient` or `FAKE_LLM_LENIENT=true`) returns the canned assistant reply instead. For a streamed Anthropic, OpenAI (either route) or Gemini request, that reply is a normal text stream.

The Anthropic Batches API is not implemented. Every request under `/anthropic/v1/messages/batches` returns HTTP 501.

Embeddings are a hash of the text: equal texts get equal vectors, and there is no semantic similarity between different texts. The value cap bounds the vectors in one response (about 0.5 GB of memory at peak for large vectors; many small vectors, such as millions of size-1 Gemini requests, carry more per-value overhead), and request bodies are not capped, as on every other route. With 16 request threads and no `-Xmx` set in the Docker image, a small container can still run out of memory.

Delays are fixed per step: there is no jitter. Each waiting call holds one of the server's 16 request threads until its wait ends, even after the client has gone, and more requests queue when all 16 are busy.

On the generation routes, token counts are 100 input and 50 output unless the step sets `usage`; they are not a count of the prompt or the reply. The embeddings routes report an estimate instead (see [Embeddings](docs/endpoints.md#embeddings)). `POST /anthropic/v1/messages/count_tokens` reports the same constant, `input_tokens` 100, for any prompt. Anthropic reports the input count as `input_tokens` and the output count as `output_tokens`. `cache_creation_input_tokens` is 20 and `cache_read_input_tokens` is 10 when the request carries a `cache_control` breakpoint, and both are 0 otherwise. A breakpoint is a `cache_control` object on a block in the `system` array or in a message `content` array. `type` must be `ephemeral`, and `ttl`, if present, must be `5m` or `1h`. On a stream, the input and cache figures are on `message_start`, and `output_tokens` is on `message_delta`. OpenAI reports the input count as `prompt_tokens`, the output count as `completion_tokens`, and their sum as `total_tokens`. On an OpenAI stream, usage is sent only when `stream_options.include_usage` is true, in a final chunk with no choices. Gemini reports the input count as `promptTokenCount`, the output count as `candidatesTokenCount`, and their sum as `totalTokenCount`. On a Gemini stream, `usageMetadata` is only on the last chunk. OpenAI Responses reports the input count as `input_tokens`, the output count as `output_tokens` and their sum as `total_tokens`, with `cached_tokens` and `reasoning_tokens` 0; on a stream, usage is on `response.completed` (or `response.incomplete`).

Tool calls are scripted per step; request fields such as `tools`, `tool_choice` and Gemini `tools` and `toolConfig` are ignored, so a step's tool calls are sent whatever tools the request declares.

The OpenAI Responses route keeps no state. `previous_response_id`, `store` and `conversation` are accepted and ignored, and `GET /openai/v1/responses/{id}` and the other Responses subpaths are not routes. An app that sends only the new turn with `previous_response_id` gets the next step, and the call log shows only that turn's input.

Each process holds one scenario, one call log and one exchange log in memory. Exchange bodies are not capped, so a run with very large requests or streams holds them until the next load, reset or unload. Nothing is written to disk. Another process does not share that state.

## Development

`./gradlew build` compiles the server, runs the Kotlin tests, and writes `build/libs/fake-llm-all.jar`.

The example project tests the published artifact, not the source, so publish it first. `check` runs its tests and its quality gates, as CI does:

```bash
./gradlew publishAllPublicationsToBuildLocalRepository
./gradlew -p examples check
```

`./gradlew -p examples test --include-build ..` runs the same tests against the source instead, through a composite build.

Reference documentation lives in `docs/` and is published to the [site](https://davidjones-griz.github.io/fake-llm/); update the page there, not the README.

### Public API

Kotlin explicit API mode is on, so a new public declaration in `src/main` (or `junit-jupiter/src/main` or `testcontainers/src/main`) needs an explicit `public` modifier. `./gradlew check`, and so `./gradlew build` and CI, compares the compiled public API with `api/fake-llm.api`, `junit-jupiter/api/fake-llm-junit-jupiter.api` and `testcontainers/api/fake-llm-testcontainers.api` and fails on any difference. For an intended change, run `./gradlew apiDump`, commit the updated `.api` file with the change, and add a CHANGELOG entry. A public top-level function goes in `FakeLlmServer.kt`, whose `@file:JvmName("FakeLlm")` makes it a static method of `FakeLlm` for Java, or in a file with its own `@file:JvmName`: a file without one gives Java a class named after the file, such as `FooKt`. Give a public function with default arguments `@JvmOverloads` when Java callers would otherwise have to pass the defaults. The scenario model classes don't have it, because Java builds scenarios with `Scenario.fromJson`. Add new fields to the scenario model classes last, with a default value, so existing Kotlin call sites still compile. A scenario field also goes in the spec's `Scenario`, `Step`, `ToolCall`, `ErrorStep` or `TokenUsage` schema (a test fails until it does); then run `./gradlew updateScenarioSchema` and commit the regenerated `schema/scenario.schema.json`. A Kotlin or kotlinx-serialization upgrade can change the generated members in the dump, so run `apiDump` after one and review the diff.

### Quality gates

Compiler warnings are errors. A warning in the main or test code fails `./gradlew build`, and one in `examples/` fails `./gradlew -p examples check`; CI runs both.

`./gradlew check`, and so `./gradlew build` and CI, measures the test coverage of `src/main` with [Kover](https://github.com/Kotlin/kotlinx-kover) and fails when line coverage drops below 95% or branch coverage below 65%. The JUnit extension in `junit-jupiter/` and the conformance runner in `conformance/runner/` are each measured on their own against the same floors, so no module's coverage can hide a drop in another's. The conformance cases run against the fat jar from another module, so they add nothing to the root module's coverage. The Testcontainers module in `testcontainers/` is not measured: most of its code runs only with a started container, in the `dockerTest` task described below. The floors are set in the root `build.gradle.kts`, which is the source of truth; update the numbers here when they change. Raise a floor when coverage rises, and never lower one without a reason in the pull request. To see the current numbers, run `./gradlew koverHtmlReport` and open `build/reports/kover/html/index.html` (the extension's is `junit-jupiter/build/reports/kover/html/index.html`, the runner's `conformance/runner/build/reports/kover/html/index.html`), which shows line and branch coverage; `./gradlew koverLog` prints line coverage only. CI attaches the reports as the `coverage-report` artifact on every run where the tests pass. Nothing is excluded from the measurement, so a new `@Serializable` class usually adds one uncovered generated line.

`./gradlew check` also runs [ktlint](https://ktlint.github.io/) over the Kotlin sources and build scripts, and [detekt](https://detekt.dev/) over `src/main` and `src/test`, in the root build and in `examples/` (`./gradlew -p examples check`); CI runs both builds.

- **Formatting** follows ktlint's `ktlint_official` code style, set in `.editorconfig`. IntelliJ's own formatter does not produce that style, so before committing run `./gradlew ktlintFormat` and `./gradlew -p examples ktlintFormat`, which fix most findings in place; the ktlint IntelliJ plugin can apply the same rules as you type. The ktlint version is set once, as `ktlintVersion` in `gradle.properties`.
- **Static analysis** uses detekt's default rules plus the overrides in `config/detekt/detekt.yml`, each with its reason; the reports are written to `build/reports/detekt/` in each build (`examples/build/reports/detekt/` for `examples/`). Fix a finding rather than suppress it. When a suppression is the right call, put it on the narrowest declaration and give the reason in a comment beside it, as the existing ones do; reviewers check that every suppression has one. There is no baseline file: the code has no unexplained findings.
- **The rest of the repository** is linted by CI's `lint` job on every pull request: [actionlint](https://github.com/rhysd/actionlint) checks the workflows, including their `run:` scripts with shellcheck; [hadolint](https://github.com/hadolint/hadolint) checks the `Dockerfile` against `.hadolint.yaml`; and [lychee](https://lychee.cli.rs/) checks every link from the Markdown files to files and `#anchor`s in the repository, as configured in `lychee.toml`. hadolint fails on every finding, and an exception goes in `.hadolint.yaml` with its reason; `# hadolint ignore=` comments in the `Dockerfile` are switched off. Links to other sites are not checked on pull requests, so an outage elsewhere cannot fail one; the weekly `Links` workflow (`.github/workflows/links.yml`, also runnable from the Actions tab) checks them. A failed weekly run is emailed to whoever last changed its `cron` line, and GitHub disables scheduled workflows in a public repository after 60 days without activity. Each tool runs from the image pinned in `.github/linters/`. To run them locally with Docker, from the repository root:

  ```bash
  img() { sed -n 's/^FROM //p' ".github/linters/$1.Dockerfile"; }
  docker run --rm -v "$PWD:/repo" -w /repo "$(img actionlint)" -color
  docker run --rm -v "$PWD:/repo" -w /repo "$(img hadolint)" hadolint --config .hadolint.yaml Dockerfile
  docker run --rm -v "$PWD:/repo" -w /repo "$(img lychee)" --config lychee.toml --offline .
  ```

  Drop `--offline` to check links to other sites too.
- **The HTTP API is spec-first.** [openapi/fake-llm.yaml](openapi/fake-llm.yaml) is the hand-written contract, and a change to the control API starts there. `OpenApiConformanceTest` runs every control route, `/health` and `/openapi` against a real server, and fails when a status, header or body differs from the spec, when a response has a field the spec doesn't document, or when a status the spec documents is never exercised. It also checks every scenario file in the repository against the spec's scenario format. CI's `lint` job validates the spec itself with [Redocly](https://redocly.com/docs/cli/) (`recommended-strict`, configured in `redocly.yaml`; a rule exempted for one location is listed in `.redocly.lint-ignore.yaml` with its reason). Locally, from the repository root: `docker run --rm -v "$PWD:/repo" -w /repo "$(sed -n 's/^FROM //p' .github/linters/redocly.Dockerfile)" lint openapi/fake-llm.yaml`. lychee checks only the Markdown files, so the provider links in the spec are not link-checked.
- **The conformance suite** in [conformance/](conformance/README.md) defines the server's HTTP behaviour as JSON cases, for this server and for any other implementation. CI's `conformance` job runs it against the fat jar and the Docker image built from the same commit; locally, `./gradlew shadowJar :fake-llm-conformance:test -Pfakellm.command="java -jar build/libs/fake-llm-all.jar"`. A change to HTTP behaviour comes with a case. CI's `conformance` job also runs the official Python and Node SDKs against the same jar and image (see [conformance/README.md](conformance/README.md#sdk-suite)), and the `SDK latest` workflow runs both daily against the SDKs' newest releases.
- **The CI recipe** in [examples/github-actions/fake-llm-tests.yml](examples/github-actions/fake-llm-tests.yml) (see [Run in CI](#run-in-ci)) is run by CI's `recipe` job against the image built from the same commit. A service container's image is pulled when the job starts, before any step could build it, so the job starts the image with `docker run`, with the example's service `env`, `ports` and `options`, and waits for its health check as the runner does. It then runs the example's `Copy the scenarios into fake-llm` and `Run the tests` steps with the example job's `env`. It checks that the example pins an `X.Y.Z` tag, and it installs the Python SDK suite's pinned lock instead of running the example's unpinned install step. CI's `lint` job runs actionlint on the example too.
- **The Testcontainers module** in `testcontainers/` has two kinds of test. Its `test` task, part of `./gradlew build`, checks the container's configuration without Docker. The tests tagged `docker` start containers, so they run only in the `dockerTest` task, against the image named by `-Pfakellm.image`. CI's `docker` job runs them against the image built from the same commit; locally, `docker build -t fake-llm:dev . && ./gradlew :fake-llm-testcontainers:dockerTest -Pfakellm.image=fake-llm:dev`.
- **The pytest plugin** in [pytest-fake-llm/](pytest-fake-llm/) is tested with itself: CI's `python` job builds the image from the same commit and runs the plugin's tests on Python 3.11, 3.12, 3.13, 3.14 and 3.15, with the session's server started from that image. Tests marked `docker` start containers of their own. Each version installs the hash-pinned `pytest-fake-llm/requirements-test.txt`, then the plugin. A test checks that the plugin's `version` in `pytest-fake-llm/pyproject.toml` is the one in `gradle.properties`, since the plugin starts the image with that tag. Locally, with Docker: `docker build -t fake-llm:dev .`, `python -m pip install --require-hashes -r pytest-fake-llm/requirements-test.txt`, `python -m pip install --no-build-isolation --no-deps ./pytest-fake-llm`, then `python -m pytest pytest-fake-llm/tests --fake-llm-image=fake-llm:dev`. Without Docker, `--fake-llm-url=http://localhost:8080` against `java -jar build/libs/fake-llm-all.jar` runs all but the `docker` tests.
- Commits that only reformat code are listed in `.git-blame-ignore-revs`, which GitHub's blame view honours; locally, run `git config blame.ignoreRevsFile .git-blame-ignore-revs`.

The documentation site in `docs/` is built with [Zensical](https://zensical.org/) from `mkdocs.yml` by the [Docs](.github/workflows/docs.yml) workflow, which also runs the quickstart script against an image built from the commit, and deploys the site from `main`. `DocsTest` checks that the scenario reference has a row for every scenario field, the control API page a heading for `/health`, `/openapi` and every control route, the endpoints page a heading for every vendor operation, each recipe a bundled scenario the tests load, and every image tag and version in `docs/` the current version. `ReadmeTest` keeps this README under 400 lines, free of scenario field tables, and with the headings that workflow comments cite. To build it locally: `python -m pip install --require-hashes -r docs/requirements.txt && zensical serve`.

### Dependency updates

[Dependabot](.github/dependabot.yml) opens version-update pull requests every Monday for the Gradle builds (the root build and `examples/`, including the Gradle wrapper), the GitHub Actions in `.github/workflows/`, the Dockerfile base images, and the linter images in `.github/linters/`. Minor and patch updates are grouped into one pull request per ecosystem, and the two base-image digests are updated together. Kotlin, its Gradle plugins and the kotlinx-serialization runtime move together in one group across both builds, majors included, and the detekt and ktlint plugins have a group of their own, because a new rule release can bring new findings. Every other major update arrives on its own, except the linter images, the SDK conformance suites' Python and Node packages (`@types/node` majors aside, see below) and the pytest plugin's test dependencies, which are grouped whatever the update type. Each update must pass the same required checks as any other pull request.

- Dependabot also updates the SDK conformance suites' locks (`conformance/sdk/python/requirements.txt` and `conformance/sdk/node/package-lock.json`) and the pytest plugin's test lock (`pytest-fake-llm/requirements-test.txt`) weekly, each in one pull request for all its packages, majors included. The exception is `@types/node`, whose major version follows the Node line in `conformance/sdk/node/.node-version`: Dependabot ignores its majors, and both move by hand to a new Node LTS.
- A Kotlin or kotlinx-serialization bump can change the generated members in `api/fake-llm.api`; see [Public API](#public-api) for the `apiDump` step. A bump that adds a compiler warning or lowers coverage is fixed on its pull request; the [quality gates](#quality-gates) are not lowered to let it through.
- `main` only accepts branches that are up to date with it, so merge Dependabot pull requests one at a time. After each merge, comment `@dependabot rebase` on the next one; otherwise Dependabot only rebases it on its next weekly run. Once anyone pushes a commit to a Dependabot branch, such as an `apiDump`, Dependabot stops rebasing it: bring it up to date with "Update branch" instead. `@dependabot recreate` rebuilds the pull request from scratch and discards pushed commits.
- Actions are pinned to a full commit SHA with the release in a trailing comment (`uses: actions/checkout@<sha> # v4.4.0`), and base images to a digest (`eclipse-temurin:21-jdk@sha256:...`). Keep that form when editing by hand; Dependabot updates both. Java major versions of the base images are ignored on purpose: the images stay on Java 21, the supported minimum ([#39](https://github.com/davidjones-griz/fake-llm/issues/39)), and newer Java versions are tested by CI's `java25` job instead. When a newer LTS becomes the one to test, rename that job and its required check.
- The CI recipe example, `examples/github-actions/fake-llm-tests.yml`, names its actions by major tag (`actions/checkout@v7`), not by SHA, because it is written for copying and Dependabot updates only the workflows in `.github/workflows/`. Move its majors by hand with the ones in `ci.yml`. actionlint, which CI runs on it, fails on an action whose runtime GitHub no longer supports.
- The linter images (actionlint, hadolint, lychee and Redocly) are updated in a group of their own, tag and digest together. A new release can bring new findings; fix them on that pull request rather than adding exceptions to get it through.
- The ktlint version itself (`ktlintVersion` in `gradle.properties`) is not visible to Dependabot. Raise it by hand when a new ktlint release comes out, run `./gradlew ktlintFormat` and `./gradlew -p examples ktlintFormat`, and commit any reformatting separately, adding that commit to `.git-blame-ignore-revs`.
- CI's `gradle/actions/setup-gradle` and the dependency submission workflow's `gradle/actions/dependency-submission` use `cache-provider: basic`, the MIT-licensed cache built on GitHub's `actions/cache`. From v6 the action defaults to "enhanced" caching, a proprietary component under separate Gradle terms; keeping CI entirely open-source matters more here than its speed-up on a small build. Keep the setting in both when Dependabot updates the actions.
- The Gradle wrapper pins the distribution's SHA-256 (`distributionSha256Sum` in `gradle/wrapper/gradle-wrapper.properties`), so a download that does not match fails instead of running. Dependabot updates the checksum with the version.
- To change the Gradle version by hand, run `./gradlew wrapper --gradle-version <version> --gradle-distribution-sha256-sum <sum>`, with the sum from `https://services.gradle.org/distributions/gradle-<version>-bin.zip.sha256`. Run it twice: the first run uses the old Gradle, and the second lets the new version regenerate `gradlew`, `gradlew.bat` and the wrapper jar.
- JUnit versions are ignored by Dependabot. The extension compiles against the oldest JUnit Jupiter it supports (5.10.5, `minimumJupiter` in `junit-jupiter/build.gradle.kts`) and its tests run again on newer releases (the `testJupiter514` and `testJupiter6` tasks, both part of `check`). Update the tested releases by hand; raise the minimum only as a deliberate, documented change.
- Dependabot security alerts cover the Gradle dependencies. GitHub cannot read them from the build files, so the [Dependency submission](.github/workflows/dependency-submission.yml) workflow resolves the root build (`junit-jupiter/` and `testcontainers/` included) on every push to `main`, weekly, and on demand, and submits every resolved dependency, transitive ones included. The libraries' runtime classpaths (`fake-llm`'s is also what the fat jar and the Docker image bundle) are marked as runtime dependencies; build plugins, linters, test libraries and Gradle itself are marked as development, so alerts can be filtered by scope. `examples/` is not submitted: its third-party dependencies are currently the same coordinates as the root build's, so a dependency added only to `examples/` would not be covered. An alert usually needs a hand-made fix here: transitive versions are not written in any build file (add a constraint or a BOM), the JUnit versions are set by hand (see the JUnit bullet above), and `ktlintVersion` is a property Dependabot cannot follow. The alerts to dismiss rather than fix are on the JUnit release at the extension's minimum (Jupiter 5.10.5, Platform 1.10.5): it is `compileOnly`, never shipped, and it is the release the extension's tests exist to check. The JUnit the root tests get through `kotlin("test")` (5.10.1) is incidental; fix an alert on it by adding a JUnit BOM to the root `testImplementation`, as `examples/` does.

### Releasing

A release is published from a `vX.Y.Z` tag on `main` by the [Release](.github/workflows/release.yml) workflow.

1. In a pull request, set `version` in `gradle.properties`, `info.version` in `openapi/fake-llm.yaml` and `version` in `pytest-fake-llm/pyproject.toml` to `X.Y.Z` (tests check that they match), change the CHANGELOG heading from `## [Unreleased]` to `## [X.Y.Z] - YYYY-MM-DD`, and move every `ghcr.io/davidjones-griz/fake-llm:` tag in `README.md` and `docs/`, the service image in `examples/github-actions/fake-llm-tests.yml` and the `@vX.Y.Z` in the pytest plugin's install line to `X.Y.Z`.
2. After it merges, tag the merge commit and push the tag: `git tag vX.Y.Z <merge commit> && git push origin vX.Y.Z`.

The workflow then runs four jobs:

- `verify` fails unless the tag is on `main`, matches `version` in `gradle.properties`, and has a dated CHANGELOG heading, and unless `README.md` and the CI recipe name the image only as `X.Y.Z`. It extracts that CHANGELOG section as the release notes, then builds and tests as CI does: the build, `examples/`, and the HTTP conformance suite against the fat jar.
- `image` builds the image for linux/amd64 and linux/arm64, pushes it to `ghcr.io/davidjones-griz/fake-llm` by digest only, and checks `GET /health` on each platform. Only then does it tag it `X.Y.Z`, `X.Y` and `latest`.
- `maven` runs after `image`, because a Maven Central publish cannot be undone. It signs the three artifacts, checks that every file in the bundle is signed, uploads the bundle to the Central Portal, and waits up to 30 minutes for it to publish. It needs four repository secrets: `MAVEN_CENTRAL_USERNAME` and `MAVEN_CENTRAL_PASSWORD` (a Central Portal user token), `SIGNING_KEY` (the ASCII-armored private key) and `SIGNING_KEY_PASSWORD`. If any is missing, the job passes with a "Maven Central skipped" notice in its log and summary, and the image and GitHub Release are still published.
- `release` creates the GitHub Release `vX.Y.Z` with the release notes, the fat jar and its `.sha256`. It runs only when the other three jobs succeeded.

Because `maven` runs after `image`, a failed `maven` job leaves the image tags pushed with no GitHub Release yet; the Release is created when the re-run succeeds. If a job fails for a reason outside the code, such as a missing secret, a key that hasn't reached the keyservers, or an outage, fix the cause and re-run the failed jobs. A problem in the code, the POM or the signatures needs a new tag, because a re-run uses the same commit. Central does not accept the same version twice: if Central published after the `maven` job gave up waiting, re-running that job fails, so check the deployment on central.sonatype.com and create the Release by hand. After the first release, set the `fake-llm` package on GHCR to public.

## Roadmap

Tracked in https://github.com/davidjones-griz/fake-llm/issues

## License

Apache-2.0. See [LICENSE](LICENSE). Copyright 2026 David Jones.
