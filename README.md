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

or include a checkout of this repository as a composite build. Gradle then substitutes the dependency with the checkout's source, with no publishing step. The included build runs on your project's Gradle, which must be 9.2 or newer (and so run on JDK 17 or newer); it compiles with a JDK 21 toolchain, and your tests must run on Java 21 or newer:

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

`engine.load(name)` finds `/scenarios/<name>.json` on the classpath, so a test project can keep its scenarios in `src/test/resources/scenarios/`. `engine.loadInline(Scenario(...))` takes a scenario built in code. Each `ScenarioEngine` has its own scenario, counters, call log, and optional scenario directory. An engine created with `ScenarioEngine(scenarioDirectory = Path.of("src/test/scenarios"))` (from Java, `new ScenarioEngine(false, dir)`) reads `<directory>/<name>.json` first and falls back to the classpath, as the command-line server does with `--scenario-dir`; a relative path resolves against the working directory, which in a Gradle test is the project directory. `GET /control/scenarios` lists that directory's `*.json` names plus the names in `/scenarios/index.txt`, the bundled list. Scenarios in your own `src/test/resources/scenarios/` load by name but are not listed; do not add an `index.txt` there, because the first one on the classpath replaces the bundled list. The library depends on `kotlinx-serialization-json` as an API dependency, so `Json.decodeFromString<Scenario>(text)` also works for scenario files kept elsewhere.

The library is built with Kotlin 2.4. A Kotlin project that uses it needs Kotlin 2.4 (2.3 may work, on a best-effort basis), and gets `kotlin-stdlib` 2.4 and `kotlinx-serialization-json` 1.12.0 as dependencies, as does a Java project. Both libraries, `fake-llm` and `fake-llm-junit-jupiter`, need Java 21 or newer, and CI tests them on Java 21 and 25. A Gradle build that targets an older Java fails at dependency resolution (the library is published as compatible with Java 21). A Maven or plain-classpath build fails to compile under an older JDK (`class file has wrong version 65.0`), or, if compiled with a newer JDK, fails at run time with `UnsupportedClassVersionError`. A project on Java 17 can still use fake-llm as a separate service: the Docker image includes its own Java 21 runtime (the fat jar needs Java 21 or newer on the machine that runs it), and the vendor endpoints and the [control API](#control-api) work over HTTP from any language. Anything that runs in the project's own JVM (the in-process server and `ScenarioEngine`, `FakeLlmClient`, the JUnit extension) needs Java 21.

### Control API client

`FakeLlmClient` drives a running server over HTTP, for tests that reach it by URL, such as the Docker image, `java -jar`, or a server started elsewhere in the test. Where it and `ScenarioEngine` have the same operation, the method has the same name:

```kotlin
FakeLlmClient("http://localhost:8080", controlToken = System.getenv("FAKE_LLM_CONTROL_TOKEN")).use { client ->
    client.load("rate-limit-429")          // or client.loadInline(Scenario(...))
    // ... exercise the code under test against http://localhost:8080 ...
    val calls: List<CallRecord> = client.calls()
    client.reset()
}
```

From Java, `new FakeLlmClient(baseUrl)` or `new FakeLlmClient(baseUrl, token)`, in a `try`-with-resources block. `health()` returns the loaded scenario's name and whether the server is lenient, and `scenarioNames()` lists what the server can load by name. When a token is set, every request carries `Authorization: Bearer <token>`; a blank token counts as none, as a blank `FAKE_LLM_CONTROL_TOKEN` does on the server. The constructor rejects a `baseUrl` that is not an `http://` or `https://` URL with a host and a valid port (or that has a query or fragment), and a timeout that is not positive, with `IllegalArgumentException`.

Every failed call throws `FakeLlmClientException`. `statusCode` is the HTTP status, or null when no response arrived (a refused connection, a timeout or an interrupt, kept as the `cause`). For an error status, `serverMessage` is the server's explanation, verbatim, such as `unknown scenario 'x' — ...`. A 401 says whether the token was missing or rejected.

The client ignores response fields it does not know, so a server that has gained fields does not break it. It also leaves fields at their default values out of the scenarios it sends, so a scenario that does not use a newly added field is still accepted by a server that predates it.

### JUnit Jupiter extension

`com.davidjonesgriz:fake-llm-junit-jupiter:0.1.0` runs an in-process server for each test class, so a test needs no setup or teardown of its own. Its classes are in the `com.davidjonesgriz.fakellm.junit` package, and it brings `fake-llm` with it. It works with JUnit Jupiter 5.10.5 or newer, including 6.x. Your project supplies JUnit; the extension does not add it. Kotlin's `kotlin("test")` brings JUnit 5.10.1 on its own, so a Kotlin project should also add a newer BOM, such as `testImplementation(platform("org.junit:junit-bom:5.14.4"))`.

```kotlin
@FakeLlmTest
@FakeLlmScenario("rate-limit-429")
class MyClientTest {
    @Test
    fun `retries after a rate limit`(server: FakeLlmServer) {
        val client = MyClient(baseUrl = "${server.baseUrl}/anthropic")
        // ... exercise the code under test ...
        assertEquals(2, server.engine.calls().size)
    }

    @Test
    @FakeLlmScenario("hello")
    fun `answers a greeting`(server: FakeLlmServer) { /* ... */ }
}
```

```java
@FakeLlmTest
@FakeLlmScenario("hello")
class MyClientTest {
    @Test
    void answers(FakeLlmServer server) { /* server.getBaseUrl(), server.getEngine().calls() */ }
}
```

- **One server per class.** The server starts on an ephemeral port when the class first needs it, and stops when the class finishes. `@Nested` classes share it, and subclasses inherit `@FakeLlmTest`.
- **A scenario per test.** Before each test, the nearest `@FakeLlmScenario` is loaded: the method's, then the class's (its own, then its interfaces', then its superclasses'), then each enclosing class's, the same way. Loading clears the call log and step counters, so every test starts fresh. A test with no `@FakeLlmScenario` anywhere runs with no scenario loaded (`ScenarioEngine.unload()`), so a scenario loaded by hand in `@BeforeAll` is gone again by the first test; put a shared scenario on the class instead. The extension runs before your own `@BeforeEach` methods, so they see the scenario and can replace it.
- **Injection.** Constructors, `@BeforeAll`, `@BeforeEach` and test methods can take a `FakeLlmServer` (`baseUrl`, `port`, `engine`) or the `ScenarioEngine` itself.
- **Configuration.** `@FakeLlmTest(scenarioDirectory = "src/test/scenarios", lenient = true)` sets the engine's scenario directory (searched before the classpath; relative to the working directory) and lenient mode. The server ignores `FAKE_LLM_CONTROL_TOKEN`. Both annotations also work inside your own composed annotation or on an interface the test class implements. When the outer class runs the extension, a `@Nested` class uses the outer class's server, so options on the nested class's own `@FakeLlmTest` have no effect.
- **`@RegisterExtension`.** Instead of `@FakeLlmTest`, a field can hold `FakeLlmExtension(scenarioDirectory, lenient)`; from Java, `new FakeLlmExtension()` or `new FakeLlmExtension(path, lenient)`. A static field behaves like the annotation. An instance field exists only once the test instance does, so it cannot inject into its own class's constructor or a static `@BeforeAll` method, and a `@Nested` class may get a server of its own rather than the outer class's; use a static field or the annotation for those. Register the extension once per class: with both `@FakeLlmTest` and a field, JUnit rejects injected parameters because two extensions offer them.
- **Parallel execution.** Test classes can run in parallel, each with its own server and port. The tests of one class share one engine, so they must run one at a time: a second test that starts while another is running fails immediately with an explanation. With JUnit's parallel execution enabled, run classes concurrently and methods on the same thread:

  ```properties
  # src/test/resources/junit-platform.properties
  junit.jupiter.execution.parallel.enabled=true
  junit.jupiter.execution.parallel.mode.default=same_thread
  junit.jupiter.execution.parallel.mode.classes.default=concurrent
  ```

The extension calls `ExtensionContext.Store.getOrComputeIfAbsent`, which JUnit 6 deprecates in favour of `computeIfAbsent`. JUnit 5.10 has no `computeIfAbsent`, so the extension moves to it once the minimum supported JUnit version does.

[examples/](examples/) uses the extension in `SummarizerExtensionTest`.

### API stability

The public API is exactly what [api/fake-llm.api](api/fake-llm.api) and [junit-jupiter/api/fake-llm-junit-jupiter.api](junit-jupiter/api/fake-llm-junit-jupiter.api) list, and the build fails if the code drifts from it. The one exception is `main`, the command-line entry point, which is listed only because the JVM requires it to be public. Before 1.0, a minor version may change that API, and every change is listed in [CHANGELOG.md](CHANGELOG.md). The scenario model classes (`Scenario`, `Rule`, `Match`, `Step`, `ErrorStep`) are data classes, so a release that adds a field changes their constructors: recompile against a new version rather than swapping the jar. `CallRecord`, `FakeLlmHealth` and `FakeLlmClientException` are read-only, with internal constructors. A field added to `CallRecord` or `FakeLlmHealth` always gets a default value, so it does not break callers, including code that decodes `GET /control/calls` with `CallRecord.serializer()`, and so a newer `FakeLlmClient` can still read an older server's responses. Exception messages from scenario validation are diagnostics, not API.

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

The reference for the control API, `/health` and `/openapi` is the OpenAPI 3.1 document [openapi/fake-llm.yaml](openapi/fake-llm.yaml). A running server serves the copy for its version at `GET /openapi` (as `application/yaml`, with no token needed), so a client in any language can read the contract, or generate code from it. The spec lists the vendor routes too and links to each provider's own reference. This section summarises it.

Clients should ignore response fields they do not know, since a later version may add fields. Request bodies are strict: `POST /control/scenario` rejects unknown fields, as does the scenario format. Human-readable messages, such as most `fake_llm_error` texts and the `detail` of a 400, are diagnostics, not part of the contract.

When `FAKE_LLM_CONTROL_TOKEN` is set to a non-blank value, every path under `/control/` requires an `Authorization` header whose value is exactly `Bearer <token>`. A missing or different header is HTTP 401, with `WWW-Authenticate: Bearer`:

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

From Kotlin or Java, [`FakeLlmClient`](#control-api-client) makes these calls and parses the responses. With curl, pass the bearer token on each control call when one is configured:

```bash
curl -s http://localhost:8080/control/calls \
  -H "authorization: Bearer $FAKE_LLM_CONTROL_TOKEN"
```

## Configuration

For the port, the scenario, and the scenario directory, a flag overrides the environment variable, and a blank environment variable is ignored. `--lenient` takes no value and turns lenient mode on regardless of `FAKE_LLM_LENIENT`. An unknown flag or argument, a port outside 0–65535, a scenario directory that is not a valid path, or a `FAKE_LLM_LENIENT` value other than `true` or `false` (any case), prints a usage line to stderr and exits 1.

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

Kotlin explicit API mode is on, so a new public declaration in `src/main` (or `junit-jupiter/src/main`) needs an explicit `public` modifier. `./gradlew check`, and so `./gradlew build` and CI, compares the compiled public API with `api/fake-llm.api` and `junit-jupiter/api/fake-llm-junit-jupiter.api` and fails on any difference. For an intended change, run `./gradlew apiDump`, commit the updated `.api` file with the change, and add a CHANGELOG entry. Add new fields to the scenario model classes last, with a default value, so existing Kotlin call sites still compile. A Kotlin or kotlinx-serialization upgrade can change the generated members in the dump, so run `apiDump` after one and review the diff.

### Quality gates

Compiler warnings are errors. A warning in the main or test code fails `./gradlew build`, and one in `examples/` fails `./gradlew -p examples check`; CI runs both.

`./gradlew check`, and so `./gradlew build` and CI, measures the test coverage of `src/main` with [Kover](https://github.com/Kotlin/kotlinx-kover) and fails when line coverage drops below 95% or branch coverage below 65%. The JUnit extension in `junit-jupiter/` is measured on its own against the same floors, so neither module's coverage can hide a drop in the other's. The floors are set in the root `build.gradle.kts`, which is the source of truth; update the numbers here when they change. Raise a floor when coverage rises, and never lower one without a reason in the pull request. To see the current numbers, run `./gradlew koverHtmlReport` and open `build/reports/kover/html/index.html` (the extension's is `junit-jupiter/build/reports/kover/html/index.html`), which shows line and branch coverage; `./gradlew koverLog` prints line coverage only. CI attaches both reports as the `coverage-report` artifact on every run where the tests pass. Nothing is excluded from the measurement, so a new `@Serializable` class usually adds one uncovered generated line.

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
- **The HTTP API is spec-first.** [openapi/fake-llm.yaml](openapi/fake-llm.yaml) is the hand-written contract, and a change to the control API starts there. `OpenApiConformanceTest` runs every control route and `/health` against a real server, and fails when a status, header or body differs from the spec, when a response has a field the spec doesn't document, or when a status the spec documents is never exercised. It also checks every scenario file in the repository against the spec's scenario format. CI's `lint` job validates the spec itself with [Redocly](https://redocly.com/docs/cli/) (`recommended-strict`, configured in `redocly.yaml`; a rule exempted for one location is listed in `.redocly.lint-ignore.yaml` with its reason). Locally, from the repository root: `docker run --rm -v "$PWD:/repo" -w /repo "$(sed -n 's/^FROM //p' .github/linters/redocly.Dockerfile)" lint openapi/fake-llm.yaml`. lychee checks only the Markdown files, so the provider links in the spec are not link-checked.
- Commits that only reformat code are listed in `.git-blame-ignore-revs`, which GitHub's blame view honours; locally, run `git config blame.ignoreRevsFile .git-blame-ignore-revs`.

### Dependency updates

[Dependabot](.github/dependabot.yml) opens version-update pull requests every Monday for the Gradle builds (the root build and `examples/`, including the Gradle wrapper), the GitHub Actions in `.github/workflows/`, the Dockerfile base images, and the linter images in `.github/linters/`. Minor and patch updates are grouped into one pull request per ecosystem, and the two base-image digests are updated together. Kotlin, its Gradle plugins and the kotlinx-serialization runtime move together in one group across both builds, majors included, and the detekt and ktlint plugins have a group of their own, because a new rule release can bring new findings. Every other major update arrives on its own, except the linter images, which are grouped whatever the update type. Each update must pass the same required checks as any other pull request.

- A Kotlin or kotlinx-serialization bump can change the generated members in `api/fake-llm.api`; see [Public API](#public-api) for the `apiDump` step. A bump that adds a compiler warning or lowers coverage is fixed on its pull request; the [quality gates](#quality-gates) are not lowered to let it through.
- `main` only accepts branches that are up to date with it, so merge Dependabot pull requests one at a time. After each merge, comment `@dependabot rebase` on the next one; otherwise Dependabot only rebases it on its next weekly run. Once anyone pushes a commit to a Dependabot branch, such as an `apiDump`, Dependabot stops rebasing it: bring it up to date with "Update branch" instead. `@dependabot recreate` rebuilds the pull request from scratch and discards pushed commits.
- Actions are pinned to a full commit SHA with the release in a trailing comment (`uses: actions/checkout@<sha> # v4.4.0`), and base images to a digest (`eclipse-temurin:21-jdk@sha256:...`). Keep that form when editing by hand; Dependabot updates both. Java major versions of the base images are ignored on purpose: the images stay on Java 21, the supported minimum ([#39](https://github.com/davidjones-griz/fake-llm/issues/39)), and newer Java versions are tested by CI's `java25` job instead. When a newer LTS becomes the one to test, rename that job and its required check.
- The linter images (actionlint, hadolint, lychee and Redocly) are updated in a group of their own, tag and digest together. A new release can bring new findings; fix them on that pull request rather than adding exceptions to get it through.
- The ktlint version itself (`ktlintVersion` in `gradle.properties`) is not visible to Dependabot. Raise it by hand when a new ktlint release comes out, run `./gradlew ktlintFormat` and `./gradlew -p examples ktlintFormat`, and commit any reformatting separately, adding that commit to `.git-blame-ignore-revs`.
- CI's `gradle/actions/setup-gradle` and the dependency submission workflow's `gradle/actions/dependency-submission` use `cache-provider: basic`, the MIT-licensed cache built on GitHub's `actions/cache`. From v6 the action defaults to "enhanced" caching, a proprietary component under separate Gradle terms; keeping CI entirely open-source matters more here than its speed-up on a small build. Keep the setting in both when Dependabot updates the actions.
- The Gradle wrapper pins the distribution's SHA-256 (`distributionSha256Sum` in `gradle/wrapper/gradle-wrapper.properties`), so a download that does not match fails instead of running. Dependabot updates the checksum with the version.
- To change the Gradle version by hand, run `./gradlew wrapper --gradle-version <version> --gradle-distribution-sha256-sum <sum>`, with the sum from `https://services.gradle.org/distributions/gradle-<version>-bin.zip.sha256`. Run it twice: the first run uses the old Gradle, and the second lets the new version regenerate `gradlew`, `gradlew.bat` and the wrapper jar.
- JUnit versions are ignored by Dependabot. The extension compiles against the oldest JUnit Jupiter it supports (5.10.5, `minimumJupiter` in `junit-jupiter/build.gradle.kts`) and its tests run again on newer releases (the `testJupiter514` and `testJupiter6` tasks, both part of `check`). Update the tested releases by hand; raise the minimum only as a deliberate, documented change.
- Dependabot security alerts cover the Gradle dependencies. GitHub cannot read them from the build files, so the [Dependency submission](.github/workflows/dependency-submission.yml) workflow resolves the root build (`junit-jupiter/` included) on every push to `main`, weekly, and on demand, and submits every resolved dependency, transitive ones included. The libraries' runtime classpaths, which are also what the fat jar and the Docker image bundle, are marked as runtime dependencies; build plugins, linters, test libraries and Gradle itself are marked as development, so alerts can be filtered by scope. `examples/` is not submitted: its third-party dependencies are currently the same coordinates as the root build's, so a dependency added only to `examples/` would not be covered. An alert usually needs a hand-made fix here: transitive versions are not written in any build file (add a constraint or a BOM), the JUnit versions are set by hand (see the JUnit bullet above), and `ktlintVersion` is a property Dependabot cannot follow. The alerts to dismiss rather than fix are on the JUnit release at the extension's minimum (Jupiter 5.10.5, Platform 1.10.5): it is `compileOnly`, never shipped, and it is the release the extension's tests exist to check. The JUnit the root tests get through `kotlin("test")` (5.10.1) is incidental; fix an alert on it by adding a JUnit BOM to the root `testImplementation`, as `examples/` does.

## Roadmap

Tracked in https://github.com/davidjones-griz/fake-llm/issues

## License

Apache-2.0. See [LICENSE](LICENSE). Copyright 2026 David Jones.
