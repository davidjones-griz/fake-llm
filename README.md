# fake-llm

A local HTTP stand-in for Anthropic Messages, OpenAI chat completions, and Gemini `generateContent`, driven by a JSON scenario. The server is the JDK `com.sun.net.httpserver` plus kotlinx-serialization.

## Why

I got tired of my CI pipeline failing over trivial bugs that should have been caught easily, because there was no way to run each LLM-calling component without deploying it and paying for real model calls. I extracted fake-llm from a personal project so I could exercise each component on its own.

A scenario scripts the reply. The result is determined by which rule matches and how many times that rule has already matched, locally and with no model call. That includes the failure paths: a scripted error status, a cutoff in the middle of a stream, `max_tokens` truncation, malformed output, and a failure followed by a successful retry.

Strict is the default: a request the loaded scenario does not cover returns HTTP 400, so a gap in the scenario fails the call instead of silently passing with a canned reply. `--lenient` or `FAKE_LLM_LENIENT=true` turns that path back into the canned reply.

## Quickstart

Build the image and start it. The entrypoint is `java -jar`, in exec form, so arguments after the image name are passed through to the process:

```bash
docker build -t fake-llm .
docker run -p 8080:8080 fake-llm --scenario hello
```

`GET /health` then returns `{"status":"ok","scenario":"hello","lenient":false}`.

The same process from a checkout:

```bash
./gradlew run --args='--scenario hello'
```

or, after `./gradlew shadowJar` (also produced by `./gradlew build`):

```bash
java -jar build/libs/fake-llm-all.jar --scenario hello
```

The listen port is 8080 unless you set `--port` or `PORT`. The server does not check API keys. Any value satisfies a client that insists on sending one.

Point the Anthropic Python SDK at the server. The SDK appends `/v1/messages`, so the base URL stops at `/anthropic`:

```python
import anthropic

client = anthropic.Anthropic(
    api_key="not-used",
    base_url="http://localhost:8080/anthropic",
    default_headers={"X-Fake-LLM-Caller": "summarizer"},
)
message = client.messages.create(
    model="example-model",
    max_tokens=256,
    messages=[{"role": "user", "content": "Hello"}],
)
```

Point the OpenAI Python SDK at `/openai/v1`. The SDK appends `/chat/completions`:

```python
from openai import OpenAI

client = OpenAI(
    api_key="not-used",
    base_url="http://localhost:8080/openai/v1",
    default_headers={"X-Fake-LLM-Caller": "summarizer"},
)
completion = client.chat.completions.create(
    model="example-gpt",
    messages=[{"role": "user", "content": "Hello"}],
)
```

Gemini `generateContent` is accepted on any `POST` whose path starts with `/gemini/` and contains `:generateContent`. The model id is the path segment between `/models/` and `:`, so both `/gemini/v1beta/models/gemini-2.0-flash:generateContent` and `/gemini/v1/models/example-gemini:generateContent` work. The `google-genai` client appends `/v1beta/models/<model>:generateContent` to `base_url`:

```python
from google import genai

client = genai.Client(
    api_key="not-used",
    http_options={
        "base_url": "http://localhost:8080/gemini/",
        "headers": {"X-Fake-LLM-Caller": "summarizer"},
    },
)
response = client.models.generate_content(
    model="gemini-2.0-flash",
    contents="Hello",
)
```

The same call with curl. `X-Fake-LLM-Caller` is optional. Rules that set `match.caller` use it. This call gets the greeting because the `hello` scenario's match is empty, so every request matches. A request that matches no rule is HTTP 400 unless the process was started with `--lenient`.

```bash
curl -s http://localhost:8080/health

curl -s http://localhost:8080/anthropic/v1/messages \
  -H 'content-type: application/json' \
  -H 'X-Fake-LLM-Caller: summarizer' \
  -d '{"model":"example-model","max_tokens":256,"messages":[{"role":"user","content":"Hello"}]}'
```

## Use as a library

Kotlin and Java tests can start the server in the same JVM instead of running a separate process. The coordinates are `com.davidjonesgriz:fake-llm:0.1.0`. Classes are in the `com.davidjonesgriz.fakellm` package. The artifact is not on Maven Central yet, so for now either publish it to your local Maven repository:

```bash
./gradlew publishToMavenLocal
```

```kotlin
repositories { mavenLocal() }
dependencies { testImplementation("com.davidjonesgriz:fake-llm:0.1.0") }
```

or include a checkout of this repository as a composite build. Gradle then substitutes the dependency with the checkout's source, with no publishing step:

```kotlin
// settings.gradle.kts
includeBuild("../fake-llm")
```

`startFakeLlmServer` returns a `FakeLlmServer`. Pass port `0` for an ephemeral port. `baseUrl` is `http://127.0.0.1:<port>`, `engine` loads scenarios and reads the call log without going through HTTP, and `close()` stops the server and its threads:

```kotlin
startFakeLlmServer(port = 0, engine = ScenarioEngine(), controlToken = null).use { server ->
    server.engine.load("hello")
    val client = MyClient(baseUrl = "${server.baseUrl}/anthropic")
    // ... exercise the code under test ...
    assertEquals("text", server.engine.calls().single().outcome)
}
```

`engine.load(name)` finds `/scenarios/<name>.json` on the classpath, so a test project can keep its scenarios in `src/test/resources/scenarios/`. `engine.loadInline(Scenario(...))` takes a scenario built in code. Each `ScenarioEngine` has its own scenario, counters, and call log. A scenario directory is set only by the command-line server (`--scenario-dir` or `FAKE_LLM_SCENARIO_DIR`), which `main` applies process-wide; per-engine scenario sources are tracked in [#28](https://github.com/davidjones-griz/fake-llm/issues/28). The library depends on `kotlinx-serialization-json` as an API dependency, so `Json.decodeFromString<Scenario>(text)` also works for scenario files kept elsewhere.

The library is built with Kotlin 2.4. A Kotlin project that uses it needs Kotlin 2.4 (2.3 may work, on a best-effort basis). A Java project needs Java 21, and gets `kotlin-stdlib` 2.4 and `kotlinx-serialization-json` 1.12.0 as dependencies.

### API stability

The public API is exactly what [api/fake-llm.api](api/fake-llm.api) lists, and the build fails if the code drifts from it. The one exception is `main`, the command-line entry point, which is listed only because the JVM requires it to be public. Before 1.0, a minor version may change that API, and every change is listed in [CHANGELOG.md](CHANGELOG.md). The scenario model classes (`Scenario`, `Rule`, `Match`, `Step`, `ErrorStep`) are data classes, so a release that adds a field changes their constructors: recompile against a new version rather than swapping the jar. `CallRecord` is read-only, with an internal constructor, so new fields added with default values do not break callers, including code that decodes `GET /control/calls` with `CallRecord.serializer()`. Exception messages from scenario validation are diagnostics, not API.

[examples/](examples/) is a complete consumer: a small client with retry logic, tested against the published artifact.

## Scenarios

A scenario file is JSON. Unknown keys are rejected. `name` is required, `description` defaults to an empty string, and `rules` must be non-empty. For a load by name, the file is `<dir>/<name>.json` or the bundled resource `/scenarios/<name>.json`, the declared `name` must equal the file name, and the name must match `[A-Za-z0-9_-]+`.

```json
{
  "name": "example",
  "description": "Fail once for the weather caller, then answer.",
  "rules": [
    {
      "role": "weather",
      "match": {
        "modelContains": "haiku",
        "promptContainsAll": ["forecast"],
        "promptContainsAny": ["tomorrow", "weekend"],
        "systemShape": "string",
        "caller": "weather"
      },
      "respond": [
        {
          "error": {
            "status": 429,
            "body": "{\"type\":\"error\",\"error\":{\"type\":\"rate_limit_error\",\"message\":\"slow down\"}}",
            "headers": { "retry-after": "1" }
          }
        },
        {
          "text": "Mild and dry.",
          "stopReason": "end_turn"
        }
      ]
    }
  ]
}
```

`role` is a label stored on the call log. `match` may be omitted; an empty match matches every request, so put specific rules first. `respond` is a non-empty list of steps.

Every predicate that is present must hold. `modelContains` is a case-sensitive substring of the request model. `promptContainsAll` requires every entry to appear in the combined prompt text. `promptContainsAny`, when the list is non-empty, requires one entry to appear. `systemShape` is an exact match on the request's system-field shape: `blocks`, `string`, or `absent` (Anthropic also records `unknown` when `system` is a JSON object). `caller` is an exact match on the `X-Fake-LLM-Caller` header. A missing header does not match a rule that sets `caller`. Omitting `caller` skips that predicate.

The combined prompt text depends on the dialect:

- Anthropic: the `system` field, when it is a string or an array of text blocks, then each message `content` that is a string or an array of text blocks. Non-text blocks contribute nothing.
- OpenAI: every message whose `content` is a JSON string, including a system message. Array content is skipped. A message with role `system` sets `systemShape` to `blocks`; otherwise it is `absent`. OpenAI requests never record `string`.
- Gemini: each `contents[].parts[].text`. `systemInstruction` is not read, and `systemShape` is always `absent`. The model id comes from the URL, not the body.

Each step sets exactly one of `text` or `error`.

| Field | Meaning |
| --- | --- |
| `text` | Assistant text copied into the vendor response. |
| `thinking` | Optional thinking trace. Legal only on a text step. Anthropic includes it as a thinking block (buffered) or as thinking deltas before the text deltas (streaming), with signature `fake-thinking-signature`. OpenAI and Gemini ignore it and return `text` only. |
| `stopReason` | Stop reason for a text step. Default `end_turn`. Anthropic copies it to `stop_reason`. `"max_tokens"` means the reply was truncated: OpenAI then reports `finish_reason` `length`, and Gemini reports `finishReason` `MAX_TOKENS`. Any other value becomes OpenAI `stop` and Gemini `STOP`. |
| `error` | A scripted HTTP error instead of a text reply. `status` is the HTTP status, `body` is sent as given (default empty), and `headers` are added as given (for example `retry-after`). The server also sets `Content-Type: application/json`. |
| `streamChunkChars` | SSE delta size in Unicode code points, not UTF-16 chars. Default 24. Must be positive. Used only for an Anthropic request with `"stream": true`. A buffered request ignores it. |
| `streamInterChunkDelayMs` | Milliseconds to wait between SSE deltas. Default 0. Must be zero or positive. Anthropic streaming only. |
| `streamErrorAfterChunks` | Legal only on an error step, and must be positive. Anthropic streaming only. Null, the default, means the error is a plain HTTP response and no SSE bytes are written, including when the client asked for a stream. Setting it writes HTTP 200 and an event stream: `message_start`, a text block start, this many filler deltas, then `event: error` whose data is `error.body` (or a generic `api_error` body when `body` is blank). `content_block_stop`, `message_delta`, and `message_stop` are skipped. `error.status` and `error.headers` are not used on that path. A non-streaming client still receives `error.status` and `error.body`. The filler is fixed text, not the error body. |

The first matching rule wins. Each rule has its own occurrence counter, starting at 1 the first time that rule matches. Call number `n` for that rule uses `respond[n - 1]`, and once the list runs out the last step repeats. The counters are in memory and shared by every request in the process, so a sequence of different steps is well-defined when the caller sends those requests one at a time. A rule that concurrent requests can hit together should have a single step.

When no scenario is loaded, or no rule matches, strict mode (the default) returns HTTP 400. The body is JSON:

```json
{
  "fake_llm_error": "unmatched request",
  "why": "no scenario loaded; POST /control/scenario first (or start with --lenient)",
  "vendor": "anthropic",
  "model": "example-model",
  "system_shape": "absent",
  "message_count": 1,
  "caller": null,
  "prompt_preview": "Hello\n"
}
```

`why` is `no scenario loaded; POST /control/scenario first (or start with --lenient)` when nothing is loaded, and `no rule in scenario '<name>' matches this request` when a scenario is loaded but no rule matches. `caller` is the `X-Fake-LLM-Caller` header value, or JSON `null` when the header is absent. `prompt_preview` is the first 200 characters of the combined prompt text. The call log records role `UNMATCHED`, occurrence `0`, and outcome `unmatched`. Occurrence stays 0 for every unmatched call. An Anthropic request with `"stream": true` gets this same status and JSON body, and the server writes no SSE bytes.

Lenient mode (`--lenient` or `FAKE_LLM_LENIENT=true`) returns one canned assistant reply on that path: `This is a canned response from fake-llm.` The call log records role `DEFAULT` and outcome `default`. That path has its own occurrence counter. A text step from a rule is logged as `text`. An error step is logged as `error:<status>`. In lenient mode the canned reply is a normal text step, so an Anthropic request with `"stream": true` streams it.

### Bundled scenarios

| Name | What it does |
| --- | --- |
| `hello` | Empty match. Every request gets one short greeting. |
| `rate-limit-429` | The first call is HTTP 429 with a `retry-after: 2` header. The next call is a weather sentence, and that step repeats. |
| `overloaded-529` | The first call is HTTP 529. The next call is a short support reply, and that step repeats. A streaming Anthropic request still gets HTTP 529, because the error step leaves `streamErrorAfterChunks` unset. |
| `refusal` | One text reply with `stopReason` `refusal`. |
| `max-tokens-cutoff` | One text reply cut off inside a JSON value, with `stopReason` `max_tokens`. |
| `malformed-json` | Matches prompts that contain `Respond in JSON`. The first reply is prose with a broken JSON fragment. The next reply is a valid JSON object, and that step repeats. Other prompts do not match, so strict mode returns HTTP 400. |
| `stream-error-midway` | Anthropic streaming writes three 8-code-point filler deltas, then an `overloaded_error` event. A non-streaming client, including OpenAI and Gemini, receives that error as HTTP 529. |
| `thinking-stream` | A text reply with a thinking trace. Anthropic streaming splits both into 8-code-point chunks and waits 20 ms between chunks. |
| `fail-then-retry` | `X-Fake-LLM-Caller: summarizer` gets a short summary. That rule is first, so it wins. Every other caller gets HTTP 500 once, then a normal text reply that repeats. |

### Loading a scenario

At startup, `--scenario <name>` overrides `FAKE_LLM_SCENARIO`. With neither set, the process starts with no scenario. A vendor request then returns HTTP 400 until a scenario is loaded, unless the process is lenient, in which case it serves the canned reply.

`--scenario-dir <path>` overrides `FAKE_LLM_SCENARIO_DIR`. A name is read from `<dir>/<name>.json` when that file exists, and otherwise from the bundled classpath resource. `GET /control/scenarios` lists `*.json` stems in that directory plus the names in the bundled `/scenarios/index.txt` (one name per line; blank lines and `#` comments are skipped). The combined list is deduplicated and sorted.

`POST /control/scenario` loads a scenario in the running process and clears the call log and the occurrence counters. The body is a JSON object with exactly one of `name` or `scenario`:

```bash
curl -s -X POST http://localhost:8080/control/scenario \
  -H 'content-type: application/json' \
  -d '{"name":"hello"}'
```

```json
{"loaded":"hello","rules":1}
```

An inline scenario is decoded with the same strict rules as a file. The name does not have to match a file:

```bash
curl -s -X POST http://localhost:8080/control/scenario \
  -H 'content-type: application/json' \
  -d '{"scenario":{"name":"inline","rules":[{"role":"assistant","respond":[{"text":"ok"}]}]}}'
```

```json
{"loaded":"inline","rules":1}
```

Sending both `name` and `scenario`, or neither, is HTTP 400. A bad name or a body the decoder rejects is HTTP 400 with `{"fake_llm_error":"exception","detail":"..."}`.

## Endpoints

Anything that matches none of the routes below is HTTP 404:

```json
{"fake_llm_error":"no route: GET /nope"}
```

A handler that throws, including a body that is not valid JSON, is HTTP 400:

```json
{"fake_llm_error":"exception","detail":"..."}
```

An unmatched vendor request in strict mode is also HTTP 400. Its `fake_llm_error` value is `unmatched request`, not `exception`. The fields are described under Scenarios.

### `GET /health`

Always open, including when a control token is set. Reports liveness, the loaded scenario name or `null` when none is loaded, and whether lenient mode is on.

```json
{"status":"ok","scenario":"hello","lenient":false}
```

### `POST /anthropic/v1/messages`

Anthropic Messages JSON. A text step with `"stream": true` is answered as `text/event-stream`: `message_start`, content blocks and deltas, `message_delta`, `message_stop`. A thinking step adds a thinking block before the text block. An error step with `streamErrorAfterChunks` set uses the shorter stream described above. Any other request, including an error step that leaves `streamErrorAfterChunks` unset, is one buffered response: the text step as JSON, or the error step as that HTTP status, even when the client set `"stream": true`.

The buffered message carries `id` (`fake-msg-<seq>`), `type`, `role`, `content` (an optional thinking block, then one text block), `stop_reason`, and `usage`. It does not include a `model` field. `message_start` on the stream does, and it echoes the request model.

An unmatched request is HTTP 400 with the JSON body described under Scenarios, including when `"stream": true`. That response is `application/json`, and no SSE bytes are written. In lenient mode the canned reply is served instead, and `"stream": true` streams it as a text step.

### `/anthropic/v1/messages/batches`

Any method on a path that starts with `/anthropic/v1/messages/batches` returns HTTP 501:

```json
{"fake_llm_error":"the Anthropic Batches API is not implemented"}
```

### `POST /openai/v1/chat/completions`

One JSON chat completion: `id` `fake-openai-<seq>`, `object` `chat.completion`, `model` `fake-openai`, one choice with `message.role` `assistant` and `message.content` set to the step text, `finish_reason` as mapped above, and `usage`. `"stream": true` is parsed and ignored. The response is still this single JSON object, with `Content-Type: application/json`. An unmatched request in strict mode is HTTP 400 with the JSON body described under Scenarios, not a chat completion. Lenient mode returns the canned reply as a completion.

### `POST /gemini/…:generateContent`

One JSON `generateContent` body: one candidate whose part text is the step text, `finishReason` `STOP` or `MAX_TOKENS`, `usageMetadata`, and `fake_llm_seq`. The parser forces the call to be non-streaming. `:streamGenerateContent` does not contain `:generateContent`, so that path is a 404. An unmatched request in strict mode is HTTP 400 with the JSON body described under Scenarios, not a `generateContent` body. Lenient mode returns the canned reply as one candidate.

### Control API

When `FAKE_LLM_CONTROL_TOKEN` is set to a non-blank value, every path under `/control/` requires an `Authorization` header whose value is exactly `Bearer <token>`. A missing or different header is HTTP 401:

```json
{"fake_llm_error":"unauthorized: missing or invalid Authorization: Bearer <token>"}
```

Vendor routes and `/health` stay open. With no token configured, the control routes are open. The environment variable is read first. A JVM system property of the same name is used only when the environment variable is absent. A blank environment variable counts as unset and does not fall through to the system property.

`POST /control/scenario` is described above. It replaces the loaded scenario and clears the call log and occurrence counters.

`GET /control/scenarios` returns the names the loader can see:

```json
{"scenarios":["fail-then-retry","hello","malformed-json","max-tokens-cutoff","overloaded-529","rate-limit-429","refusal","stream-error-midway","thinking-stream"]}
```

With a scenario directory set, `*.json` names from that directory are merged in, and the list is sorted again.

`POST /control/reset` clears the call log and the occurrence counters and keeps the loaded scenario. The body is ignored. Sequence numbers are not restarted.

```json
{"reset":true}
```

`GET /control/calls` returns the call log as a JSON array, oldest first. `promptSample` is the last 500 characters of the combined prompt text. `occurrence` is 1-based for a matched rule, and for the canned reply in lenient mode. A strict-mode unmatched request records occurrence `0`, role `UNMATCHED`, and outcome `unmatched`. `timestampMs` is the wall-clock time when the call was recorded.

```json
[
  {
    "seq": 1,
    "vendor": "anthropic",
    "model": "example-model",
    "systemShape": "absent",
    "messageCount": 1,
    "role": "assistant",
    "occurrence": 1,
    "outcome": "text",
    "timestampMs": 1710000000000,
    "promptSample": "Hello\n"
  }
]
```

Pass the bearer token on each control call when one is configured:

```bash
curl -s http://localhost:8080/control/calls \
  -H "authorization: Bearer $FAKE_LLM_CONTROL_TOKEN"
```

## Configuration

For the port, the scenario, and the scenario directory, a flag overrides the environment variable, and a blank environment variable is ignored. `--lenient` takes no value and turns lenient mode on regardless of `FAKE_LLM_LENIENT`. An unknown flag or argument, a port outside 0–65535, or a `FAKE_LLM_LENIENT` value other than `true` or `false` (any case), prints a usage line to stderr and exits 1.

The process binds the IPv6 wildcard `::` when the host can, and otherwise the IPv4 wildcard `0.0.0.0`.

| Flag | Environment variable | Default | Effect |
| --- | --- | --- | --- |
| `--port <n>` | `PORT` | `8080` | Listen port. |
| `--scenario <name>` | `FAKE_LLM_SCENARIO` | none | Scenario loaded at startup. |
| `--scenario-dir <path>` | `FAKE_LLM_SCENARIO_DIR` | bundled scenarios only | Directory searched before the classpath. |
| `--lenient` | `FAKE_LLM_LENIENT` | off (`false`) | Unmatched requests. Off returns HTTP 400. On returns the canned assistant reply. The flag takes no value and turns lenient mode on. The environment variable accepts `true` or `false`, case-insensitively. |
| none | `FAKE_LLM_CONTROL_TOKEN` | unset, control routes open | Bearer token for `/control/*`. A JVM system property of the same name is the fallback when the environment variable is absent. |

## Limits

Anthropic Messages is the only route that streams. On `POST /anthropic/v1/messages`, `"stream": true` is a server-sent event response for a text step, and for an error step that sets `streamErrorAfterChunks`. An error step that leaves that field unset is a plain HTTP error instead. OpenAI always returns one JSON chat completion; `"stream": true` is accepted and then ignored. Gemini always returns one JSON `generateContent` body. The Gemini parser sets `stream` to false, and `:streamGenerateContent` is not a route.

Strict mode (the default) answers an unmatched request with HTTP 400 and a JSON body on every vendor route. On `POST /anthropic/v1/messages` that includes `"stream": true`: the response is `application/json`, and no server-sent events are written. Lenient mode (`--lenient` or `FAKE_LLM_LENIENT=true`) returns the canned assistant reply on that path. For an Anthropic request with `"stream": true`, that reply is a normal text stream.

The Anthropic Batches API is not implemented. Every request under `/anthropic/v1/messages/batches` returns HTTP 501.

Token counts are constants, not a count of the prompt or the reply. Anthropic reports `input_tokens` 100 and `output_tokens` 50. `cache_creation_input_tokens` is 20 and `cache_read_input_tokens` is 10 when the request carries a `cache_control` breakpoint, and both are 0 otherwise. A breakpoint is a `cache_control` object on a block in the `system` array or in a message `content` array. `type` must be `ephemeral`, and `ttl`, if present, must be `5m` or `1h`. On a stream, the input and cache figures are on `message_start`, and `output_tokens` 50 is on `message_delta`. OpenAI reports `prompt_tokens` 100, `completion_tokens` 50, and `total_tokens` 150. Gemini reports `promptTokenCount` 100, `candidatesTokenCount` 50, and `totalTokenCount` 150.

The server does not simulate tool use or function calling. Responses are text, plus an Anthropic thinking block when the step sets `thinking`. Request fields such as `tools`, `tool_choice`, and Gemini `tools` are ignored, and the response never contains `tool_use`, `tool_calls`, or `functionCall` parts.

Each process holds one scenario, one call log, and one set of occurrence counters in memory. Nothing is written to disk. Another process does not share that state.

## Development

`./gradlew build` compiles the server, runs the Kotlin tests, and writes `build/libs/fake-llm-all.jar`.

The example project tests the published artifact, not the source, so publish it first. `check` runs its tests and its quality gates, as CI does:

```bash
./gradlew publishAllPublicationsToBuildLocalRepository
./gradlew -p examples check
```

`./gradlew -p examples test --include-build ..` runs the same tests against the source instead, through a composite build.

### Public API

Kotlin explicit API mode is on, so a new public declaration in `src/main` needs an explicit `public` modifier. `./gradlew check`, and so `./gradlew build` and CI, compares the compiled public API with `api/fake-llm.api` and fails on any difference. For an intended change, run `./gradlew apiDump`, commit the updated `api/fake-llm.api` with the change, and add a CHANGELOG entry. Add new fields to the scenario model classes last, with a default value, so existing Kotlin call sites still compile. A Kotlin or kotlinx-serialization upgrade can change the generated members in the dump, so run `apiDump` after one and review the diff.

### Quality gates

Compiler warnings are errors. A warning in the main or test code fails `./gradlew build`, and one in `examples/` fails `./gradlew -p examples check`; CI runs both.

`./gradlew check`, and so `./gradlew build` and CI, measures the test coverage of `src/main` with [Kover](https://github.com/Kotlin/kotlinx-kover) and fails when line coverage drops below 95% or branch coverage below 65%. The floors are set in `build.gradle.kts`, which is the source of truth; update the numbers here when they change. Raise a floor when coverage rises, and never lower one without a reason in the pull request. To see the current numbers, run `./gradlew koverHtmlReport` and open `build/reports/kover/html/index.html`, which shows line and branch coverage; `./gradlew koverLog` prints line coverage only. CI attaches the same report as the `coverage-report` artifact on every run where the tests pass. Nothing is excluded from the measurement, so a new `@Serializable` class usually adds one uncovered generated line.

`./gradlew check` also runs [ktlint](https://pinterest.github.io/ktlint/) over the Kotlin sources and build scripts, and [detekt](https://detekt.dev/) over `src/main` and `src/test`, in the root build and in `examples/` (`./gradlew -p examples check`); CI runs both builds.

- **Formatting** follows ktlint's `ktlint_official` code style, set in `.editorconfig`. IntelliJ's own formatter does not produce that style, so before committing run `./gradlew ktlintFormat` and `./gradlew -p examples ktlintFormat`, which fix most findings in place; the ktlint IntelliJ plugin can apply the same rules as you type. The ktlint version is set once, as `ktlintVersion` in `gradle.properties`.
- **Static analysis** uses detekt's default rules plus the overrides in `config/detekt/detekt.yml`, each with its reason; the reports are written to `build/reports/detekt/` in each build (`examples/build/reports/detekt/` for `examples/`). Fix a finding rather than suppress it. When a suppression is the right call, put it on the narrowest declaration and give the reason in a comment beside it, as the existing ones do; reviewers check that every suppression has one. There is no baseline file: the code has no unexplained findings.
- Commits that only reformat code are listed in `.git-blame-ignore-revs`, which GitHub's blame view honours; locally, run `git config blame.ignoreRevsFile .git-blame-ignore-revs`.

### Dependency updates

[Dependabot](.github/dependabot.yml) opens version-update pull requests every Monday for the Gradle builds (the root build and `examples/`, including the Gradle wrapper), the GitHub Actions in `.github/workflows/`, and the Dockerfile base images. Minor and patch updates are grouped into one pull request per ecosystem, and the two base-image digests are updated together. Kotlin, its Gradle plugins and the kotlinx-serialization runtime move together in one group across both builds, majors included, and the detekt and ktlint plugins have a group of their own, because a new rule release can bring new findings. Every other major update arrives on its own. Each update must pass the same required checks as any other pull request.

- A Kotlin or kotlinx-serialization bump can change the generated members in `api/fake-llm.api`; see [Public API](#public-api) for the `apiDump` step. A bump that adds a compiler warning or lowers coverage is fixed on its pull request; the [quality gates](#quality-gates) are not lowered to let it through.
- `main` only accepts branches that are up to date with it, so merge Dependabot pull requests one at a time. After each merge, comment `@dependabot rebase` on the next one; otherwise Dependabot only rebases it on its next weekly run. Once anyone pushes a commit to a Dependabot branch, such as an `apiDump`, Dependabot stops rebasing it: bring it up to date with "Update branch" instead. `@dependabot recreate` rebuilds the pull request from scratch and discards pushed commits.
- Actions are pinned to a full commit SHA with the release in a trailing comment (`uses: actions/checkout@<sha> # v4.4.0`), and base images to a digest (`eclipse-temurin:21-jdk@sha256:...`). Keep that form when editing by hand; Dependabot updates both. Java major versions of the base images are ignored on purpose, because the supported Java version is a separate decision ([#39](https://github.com/davidjones-griz/fake-llm/issues/39)).
- The ktlint version itself (`ktlintVersion` in `gradle.properties`) is not visible to Dependabot. Raise it by hand when a new ktlint release comes out, run `./gradlew ktlintFormat` and `./gradlew -p examples ktlintFormat`, and commit any reformatting separately, adding that commit to `.git-blame-ignore-revs`.
- CI's `gradle/actions/setup-gradle` uses `cache-provider: basic`, the MIT-licensed cache built on GitHub's `actions/cache`. From v6 the action defaults to "enhanced" caching, a proprietary component under separate Gradle terms; keeping CI entirely open-source matters more here than its speed-up on a small build. Keep the setting when Dependabot updates the action.
- The Gradle wrapper pins the distribution's SHA-256 (`distributionSha256Sum` in `gradle/wrapper/gradle-wrapper.properties`), so a download that does not match fails instead of running. Dependabot updates the checksum with the version.
- To change the Gradle version by hand, run `./gradlew wrapper --gradle-version <version> --gradle-distribution-sha256-sum <sum>`, with the sum from `https://services.gradle.org/distributions/gradle-<version>-bin.zip.sha256`. Run it twice: the first run uses the old Gradle, and the second lets the new version regenerate `gradlew`, `gradlew.bat` and the wrapper jar.
- Dependabot security alerts do not cover the Gradle dependencies yet. Gradle needs its dependency graph submitted from CI first, which is tracked in [#41](https://github.com/davidjones-griz/fake-llm/issues/41).

## Roadmap

Tracked in https://github.com/davidjones-griz/fake-llm/issues

## License

Apache-2.0. See [LICENSE](LICENSE). Copyright 2026 David Jones.
