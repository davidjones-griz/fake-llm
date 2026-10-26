# fake-llm

A local HTTP stand-in for Anthropic Messages, OpenAI chat completions, and Gemini `generateContent`, driven by a JSON scenario. The server is the JDK `com.sun.net.httpserver` plus kotlinx-serialization.

## Why

I got tired of my CI pipeline failing over trivial bugs that should have been caught easily, because there was no way to run each LLM-calling component without deploying it and paying for real model calls. I extracted fake-llm from a personal project so I could exercise each component on its own.

The code that calls an LLM is deterministic; the model is the only part you cannot predict. A scenario takes that part away: it is an ordered list of steps, and call N gets step N, locally and with no model call. That includes the failure paths: a scripted error status, a cutoff in the middle of a stream, `max_tokens` truncation, malformed output, and a failure followed by a successful retry.

A call the scenario has no step for fails: a call after the last step returns HTTP 400 (`no more steps`), and so does a call with no scenario loaded, unless the server runs with `--lenient` or `FAKE_LLM_LENIENT=true`, which answers that case with a canned reply.

## Quickstart

Build the image and start it. The entrypoint is `java -jar`, in exec form, so arguments after the image name are passed through to the process:

```bash
docker build -t fake-llm .
docker run -p 8080:8080 fake-llm --scenario hello
```

`GET /health` then returns `{"status":"ok","scenario":"hello","lenient":false}`. Building the image needs BuildKit, `docker build`'s default since Docker 23.

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
    http_options={"base_url": "http://localhost:8080/gemini/"},
)
response = client.models.generate_content(
    model="gemini-2.0-flash",
    contents="Hello",
)
```

The same call with curl. `hello` has one step, so the first call after it loads gets the greeting and the next gets HTTP 400 (`no more steps`). Each example above is one call: reset the scenario between them, which starts it over at step 1:

```bash
curl -s http://localhost:8080/health

curl -s http://localhost:8080/anthropic/v1/messages \
  -H 'content-type: application/json' \
  -d '{"model":"example-model","max_tokens":256,"messages":[{"role":"user","content":"Hello"}]}'

curl -s -X POST http://localhost:8080/control/reset
```

## Install

The image is on GHCR, for linux/amd64 and linux/arm64:

```bash
docker run -p 8080:8080 ghcr.io/davidjones-griz/fake-llm:0.1.0 --scenario hello
```

The libraries are on Maven Central. With Gradle:

```kotlin
repositories { mavenCentral() }
dependencies {
    testImplementation("com.davidjonesgriz:fake-llm:0.1.0")
    // Optional: the JUnit Jupiter extension.
    testImplementation("com.davidjonesgriz:fake-llm-junit-jupiter:0.1.0")
}
```

With Maven:

```xml
<dependency>
  <groupId>com.davidjonesgriz</groupId>
  <artifactId>fake-llm</artifactId>
  <version>0.1.0</version>
  <scope>test</scope>
</dependency>
<!-- Optional: the JUnit Jupiter extension. -->
<dependency>
  <groupId>com.davidjonesgriz</groupId>
  <artifactId>fake-llm-junit-jupiter</artifactId>
  <version>0.1.0</version>
  <scope>test</scope>
</dependency>
```

The fat jar is attached to each [GitHub Release](https://github.com/davidjones-griz/fake-llm/releases), with its SHA-256. It needs Java 21 or newer:

```bash
java -jar fake-llm-all.jar --scenario hello
```

## Use as a library

Kotlin and Java tests can start the server in the same JVM instead of running a separate process. The coordinates are `com.davidjonesgriz:fake-llm:0.1.0`. Classes are in the `com.davidjonesgriz.fakellm` package. To build from a checkout instead, publish it to your local Maven repository:

```bash
./gradlew publishToMavenLocal
```

```kotlin
repositories { mavenLocal() }
dependencies { testImplementation("com.davidjonesgriz:fake-llm:0.1.0") }
```

or include it as a composite build. Gradle then substitutes the dependency with the checkout's source, with no publishing step. The included build runs on your project's Gradle, which must be 9.2 or newer (and so run on JDK 17 or newer); it compiles with a JDK 21 toolchain, and your tests must run on Java 21 or newer:

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

`engine.load(name)` finds `/scenarios/<name>.json` on the classpath, so a test project can keep its scenarios in `src/test/resources/scenarios/`. `engine.loadInline(Scenario(...))` takes a scenario built in code. Each `ScenarioEngine` has its own scenario, call log, and optional scenario directory. An engine created with `ScenarioEngine(scenarioDirectory = Path.of("src/test/scenarios"))` (from Java, `new ScenarioEngine(false, dir)`) reads `<directory>/<name>.json` first, as the command-line server does with `--scenario-dir`; a relative path resolves against the working directory, which in a Gradle test is the project directory. For scenario files kept elsewhere, point the engine's scenario directory at them, so they get the same checks and messages as any load by name. The library depends on `kotlinx-serialization-json` as an API dependency, so `Json.decodeFromString<Scenario>(text)` also decodes a scenario file, but it rejects a `"$schema"` key (remove it first) and gives kotlinx's own error messages.

A name is looked up in this order, and the first match wins: the engine's scenario directory, then your project's classpath `/scenarios/<name>.json` (the first on the classpath, if several modules have one), then the bundled scenarios, which fake-llm keeps under its own resource path, so your `index.txt` no longer hides the bundled list. So a scenario of yours with a bundled name, such as `scenarios/rate-limit-429.json`, replaces the bundled one, on purpose. `GET /control/scenarios` lists the directory's `*.json` names, the names in every `/scenarios/index.txt` on the classpath, and the bundled names. To have your classpath scenarios listed, add an `index.txt` beside them, one name per line (blank lines and whole-line `#` comments are skipped, and surrounding spaces are trimmed); without one they still load by name. A listed name is only as good as its index: a name whose file is missing still fails to load, and names that are not legal scenario names are skipped.

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
class MyClientTest {
    @Test
    @FakeLlmScenario("rate-limit-429")
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
- **A scenario per test.** Before each test, the nearest `@FakeLlmScenario` is loaded: the method's, then the class's (its own, then its interfaces', then its superclasses'), then each enclosing class's, the same way. Loading starts the scenario over, so every test's first call gets step 1 and the call log holds only that test's calls. A test with no `@FakeLlmScenario` anywhere runs with no scenario loaded (`ScenarioEngine.unload()`), so a scenario loaded by hand in `@BeforeAll` is gone again by the first test; put the scenario on each test method; a class-level `@FakeLlmScenario` is supported, but it shares one scenario between tests (see Writing scenarios for tests). The extension runs before your own `@BeforeEach` methods, so they see the scenario and can replace it.
- **Every call must get a step.** Right after the test method, before your `@AfterEach` methods, the test fails if a call made during `@BeforeEach` or the test got no step: a call after the scenario's last step, or a call with no scenario loaded in strict mode. The failure lists those calls. An app can catch the 400 and carry on, so without this check such a test could pass. A test that makes such a call on purpose, to test how the app handles it, opts out with `@FakeLlmAllowUnservedCalls` on the method or the class (found the same way as `@FakeLlmScenario`). An aborted test (a failed assumption) is not checked. For a `@TestFactory`, the check runs once around the whole factory. A `reset()` or `load()` in the middle of a test erases the calls made before it from the log, so the check does not see those.
- **Injection.** Constructors, `@BeforeAll`, `@BeforeEach` and test methods can take a `FakeLlmServer` (`baseUrl`, `port`, `engine`) or the `ScenarioEngine` itself.
- **Configuration.** `@FakeLlmTest(scenarioDirectory = "src/test/scenarios", lenient = true)` sets the engine's scenario directory (searched first; relative to the working directory) and lenient mode. The server ignores `FAKE_LLM_CONTROL_TOKEN`. Both annotations also work inside your own composed annotation or on an interface the test class implements. When the outer class runs the extension, a `@Nested` class uses the outer class's server, so options on the nested class's own `@FakeLlmTest` have no effect.
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

The public API is exactly what [api/fake-llm.api](api/fake-llm.api) and [junit-jupiter/api/fake-llm-junit-jupiter.api](junit-jupiter/api/fake-llm-junit-jupiter.api) list, and the build fails if the code drifts from it. The one exception is `main`, the command-line entry point, which is listed only because the JVM requires it to be public. Before 1.0, a minor version may change that API, and every change is listed in [CHANGELOG.md](CHANGELOG.md). The scenario model classes (`Scenario`, `Step`, `ErrorStep`) are data classes, so a release that adds a field changes their constructors: recompile against a new version rather than swapping the jar. `CallRecord`, `FakeLlmHealth` and `FakeLlmClientException` are read-only, with internal constructors. A field added to `CallRecord` or `FakeLlmHealth` always gets a default value, so it does not break callers, including code that decodes `GET /control/calls` with `CallRecord.serializer()`, and so a newer `FakeLlmClient` can still read an older server's responses. Exception messages from scenario validation are diagnostics, not API.

[examples/](examples/) is a complete consumer: a small client with retry logic, tested against the published artifact.

## Scenarios

A scenario file is JSON. Unknown keys are rejected. `name` is required, `description` defaults to an empty string, and `steps` must be non-empty. Integers are plain JSON integers: `"24"`, `24.0` and `2e1` are rejected. For a load by name, the file is `<dir>/<name>.json`, the classpath resource `/scenarios/<name>.json`, or a bundled scenario, in that order; the declared `name` must equal the file name, and the name must match `[A-Za-z0-9_-]+`.

```json
{
  "$schema": "https://raw.githubusercontent.com/davidjones-griz/fake-llm/main/schema/scenario.schema.json",
  "name": "example",
  "description": "A rate limit, then the answer.",
  "steps": [
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
```

After the scenario is loaded or reset, call N on any vendor route gets step N. Calls are numbered in the order they arrive, so concurrent calls each get a different step. The last step does not repeat: a call after it gets HTTP 400. Every vendor request that the route's parser accepts is a call and gets one entry in the call log. A request answered with the `exception` 400 (a body that is not JSON, has no `messages` or `contents` array, or has an invalid `cache_control`), a 404 and the Batches 501 are not calls.

Each step sets exactly one of `text` or `error`. When a step in a file or an inline scenario is invalid, the error names its number, from 1, and the path of the field at fault, such as `step 2: $.steps[1]: unknown field 'stopReasn'`. The path is relative to the scenario, including for an inline scenario in `POST /control/scenario`.

| Field | Meaning |
| --- | --- |
| `text` | Assistant text copied into the vendor response. |
| `thinking` | Optional thinking trace. Legal only on a text step. Anthropic includes it as a thinking block (buffered) or as thinking deltas before the text deltas (streaming), with signature `fake-thinking-signature`. OpenAI and Gemini ignore it and return `text` only. |
| `stopReason` | Stop reason for a text step. Default `end_turn`. Anthropic copies it to `stop_reason`. `"max_tokens"` means the reply was truncated: OpenAI then reports `finish_reason` `length`, and Gemini reports `finishReason` `MAX_TOKENS`. Any other value becomes OpenAI `stop` and Gemini `STOP`. |
| `error` | A scripted HTTP error instead of a text reply. `status` is the HTTP status, from 100 to 599. `body` is sent as given (default empty), and `headers` are added as given (for example `retry-after`). The response is `Content-Type: application/json` unless `headers` sets a `content-type`, which replaces it. |
| `streamChunkChars` | SSE delta size in Unicode code points, not UTF-16 chars. Default 24. Must be positive. Used only for Anthropic and OpenAI streaming. A buffered request ignores it. |
| `streamInterChunkDelayMs` | Milliseconds to wait between SSE deltas. Default 0. Must be zero or positive. Anthropic and OpenAI streaming. |
| `streamErrorAfterChunks` | Legal only on an error step, and must be positive. Anthropic and OpenAI streaming. Null, the default, means the error is a plain HTTP response and no SSE bytes are written, including when the client asked for a stream. Setting it writes HTTP 200 and an event stream. Anthropic: `message_start`, a text block start, this many filler deltas, then `event: error` whose data is `error.body` (or a generic `api_error` body when `body` is blank); `content_block_stop`, `message_delta`, and `message_stop` are skipped. OpenAI: the role chunk, this many filler chunks, then a `data:` frame whose payload is `error.body` (or a generic `server_error` body when `body` is blank); no finish chunk, usage chunk or `[DONE]` follows. `error.status` and `error.headers` are not used on that path. A non-streaming client still receives `error.status` and `error.body`. The filler is fixed text, not the error body. |

A call after the last step returns HTTP 400, in strict and lenient mode alike. The body is JSON:

```json
{
  "fake_llm_error": "no more steps",
  "scenario": "example",
  "steps": 2,
  "seq": 3,
  "vendor": "anthropic",
  "model": "example-model",
  "system_shape": "absent",
  "message_count": 1,
  "prompt_preview": "Hello\n"
}
```

With no scenario loaded, strict mode (the default) returns HTTP 400 with the same fields except `scenario` and `steps`, and `"fake_llm_error": "no scenario loaded"`. `seq` is the call's number, as in the call log. `prompt_preview` is the first 200 characters of the combined prompt text. The call log records outcome `no-more-steps` or `no-scenario`, with `step` null. A streamed Anthropic or OpenAI request gets the same status and JSON body, and the server writes no SSE bytes.

Lenient mode (`--lenient` or `FAKE_LLM_LENIENT=true`) answers a call with no scenario loaded with one canned assistant reply, `This is a canned response from fake-llm.`, logged with outcome `default`. It is a normal text reply, so a streamed Anthropic or OpenAI request streams it. Lenient mode changes nothing once a scenario is loaded.

The combined prompt text, used by `promptSample` and `prompt_preview`, depends on the dialect:

- Anthropic: the `system` field, when it is a string or an array of text blocks, then each message `content` that is a string or an array of text blocks. Non-text blocks contribute nothing. Each piece is followed by a newline. A `null`, number or boolean contributes nothing.
- OpenAI: every message whose `content` is a JSON string, including a system message. Array content is skipped. A message with role `system` sets `systemShape` to `blocks`; otherwise it is `absent`. OpenAI requests never record `string`.
- Gemini: each `contents[].parts[].text`. `systemInstruction` is not read, and `systemShape` is always `absent`. The model id comes from the URL, not the body.

### Writing scenarios for tests

- Each test owns its scenario, even when that means repeating steps another scenario also has. Changing a scenario then affects only the test that owns it. Name a scenario file after its test, or build it inline with `loadInline`.
- Script one step per call, in the order the app makes its calls, including every retry and error. A call the scenario has no step for fails with `no more steps`, so a missing or extra call shows up in the test.
- Load the scenario, or reset it, at the start of each test, so its first call gets step 1 and the call log holds only its calls. The JUnit extension does this before each test.

For example, a test whose app retries once after a rate limit scripts both calls, then checks the app's reply and that the app made exactly those two calls:

```kotlin
@FakeLlmTest
class MyClientTest {
    @Test
    fun `retries once after a rate limit`(engine: ScenarioEngine, server: FakeLlmServer) {
        engine.loadInline(
            Scenario(
                name = "retries-once-after-a-rate-limit",
                steps =
                    listOf(
                        Step(error = ErrorStep(status = 429, headers = mapOf("retry-after" to "0"))),
                        Step(text = "Recovered."),
                    ),
            ),
        )
        val client = MyClient(baseUrl = "${server.baseUrl}/anthropic")

        assertEquals("Recovered.", client.ask("Summarize this."))
        assertEquals(listOf("error:429", "text"), engine.calls().map { it.outcome })
    }
}
```

### Scenario schema and `validate`

[schema/scenario.schema.json](schema/scenario.schema.json) is a JSON Schema (2020-12) for scenario files, generated from the [OpenAPI spec](openapi/fake-llm.yaml)'s `Scenario` schema. A top-level `"$schema"` key pointing at it, as in the example above, lets VS Code and IntelliJ check a scenario file and complete its field names as you type. The server ignores the key; it is allowed only at the top level, not in a step. JSON Schema counts `24.0` and `2e1` as integers, so an editor accepts them; the server and `validate` reject them. The URL follows `main`; inside a repository that has a copy of the schema, a relative path such as `"../schema/scenario.schema.json"` also works, offline too.

`validate` checks scenario files without starting a server, with the same checks as a load by name, including that the declared `name` matches the file name (so a file meant only for an inline load is reported if its name differs). A directory means its `*.json` files, not its subdirectories:

```bash
java -jar build/libs/fake-llm-all.jar validate src/test/scenarios
docker run --rm -v "$PWD/scenarios:/scenarios:ro" fake-llm validate /scenarios
```

Each valid file gets an `ok <path>` line, and each invalid one a `<path>: <problem>` line on stderr, such as `scenarios/weather.json: step 2: $.steps[1]: unknown field 'stopReasn'`. Every file is checked, with the first problem in each reported. The exit code is 0 when every file is valid, 1 when any is invalid, and 2 for a usage error: no paths, a path that does not exist, a directory with no `.json` files, or a flag. (The server's own usage errors exit 1.)

### Bundled scenarios

| Name | What it does |
| --- | --- |
| `hello` | One step: a short greeting. |
| `rate-limit-429` | Call 1 is HTTP 429 with a `retry-after: 2` header. Call 2 is a weather sentence. |
| `overloaded-529` | Call 1 is HTTP 529. Call 2 is a short support reply. A streamed Anthropic or OpenAI request still gets HTTP 529, because the error step leaves `streamErrorAfterChunks` unset. |
| `refusal` | One text reply with `stopReason` `refusal`. |
| `max-tokens-cutoff` | One text reply cut off inside a JSON value, with `stopReason` `max_tokens`. |
| `malformed-json` | Call 1 is prose with a broken JSON fragment. Call 2 is a valid JSON object. |
| `stream-error-midway` | Anthropic and OpenAI streaming write three 8-code-point filler deltas, then the error body: an `overloaded_error` event for Anthropic, a data frame for OpenAI (the `openai` SDK raises `APIError`, because the body has an `error` key). A non-streaming client, including Gemini, receives that error as HTTP 529. |
| `thinking-stream` | A text reply with a thinking trace. Anthropic streaming splits both into 8-code-point chunks and waits 20 ms between chunks. |
| `fail-then-retry` | Call 1 is HTTP 500. Call 2 is a normal text reply. |

The bundled scenarios are for trying the server and for the examples in this README. A test suite should keep its own scenarios, as above.

### Loading a scenario

At startup, `--scenario <name>` overrides `FAKE_LLM_SCENARIO`. With neither set, the process starts with no scenario. A vendor request then returns HTTP 400 until a scenario is loaded, unless the process is lenient, in which case it serves the canned reply.

`--scenario-dir <path>` overrides `FAKE_LLM_SCENARIO_DIR`. A name is read from `<dir>/<name>.json` when that file exists, and otherwise from the bundled scenarios. `GET /control/scenarios` lists the `*.json` stems in that directory plus the bundled names. The combined list is deduplicated and sorted. (In-process use can add your project's classpath scenarios; see [Use as a library](#use-as-a-library).)

`POST /control/scenario` loads a scenario in the running process and starts it over: the call log is emptied, and the next call is call 1 and gets step 1. The body is a JSON object with exactly one of `name` or `scenario`:

```bash
curl -s -X POST http://localhost:8080/control/scenario \
  -H 'content-type: application/json' \
  -d '{"name":"hello"}'
```

```json
{"loaded":"hello","steps":1}
```

An inline scenario is decoded with the same strict rules as a file. The name does not have to match a file:

```bash
curl -s -X POST http://localhost:8080/control/scenario \
  -H 'content-type: application/json' \
  -d '{"scenario":{"name":"inline","steps":[{"text":"ok"}]}}'
```

```json
{"loaded":"inline","steps":1}
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

A call after the scenario's last step, or a call with no scenario loaded in strict mode, is also HTTP 400. Its `fake_llm_error` value is `no more steps` or `no scenario loaded`, not `exception`. The fields are described under Scenarios.

### `GET /health`

Always open, including when a control token is set. Reports liveness, the loaded scenario name or `null` when none is loaded, and whether lenient mode is on.

```json
{"status":"ok","scenario":"hello","lenient":false}
```

### `POST /anthropic/v1/messages`

Anthropic Messages JSON. A text step with `"stream": true` is answered as `text/event-stream`: `message_start`, content blocks and deltas, `message_delta`, `message_stop`. A thinking step adds a thinking block before the text block. An error step with `streamErrorAfterChunks` set uses the shorter stream described above. Any other request, including an error step that leaves `streamErrorAfterChunks` unset, is one buffered response: the text step as JSON, or the error step as that HTTP status, even when the client set `"stream": true`.

The buffered message carries `id` (`fake-msg-<seq>`), `type`, `role`, `content` (an optional thinking block, then one text block), `stop_reason`, and `usage`. It does not include a `model` field. `message_start` on the stream does, and it echoes the request model.

A call the scenario has no step for is HTTP 400 with the JSON body described under Scenarios, including when `"stream": true`. That response is `application/json`, and no SSE bytes are written. With no scenario loaded, lenient mode serves the canned reply instead, and `"stream": true` streams it as a text step.

### `/anthropic/v1/messages/batches`

Any method on a path that starts with `/anthropic/v1/messages/batches` returns HTTP 501:

```json
{"fake_llm_error":"the Anthropic Batches API is not implemented"}
```

### `POST /openai/v1/chat/completions`

One JSON chat completion: `id` `fake-openai-<seq>`, `object` `chat.completion`, `model` `fake-openai`, one choice with `message.role` `assistant` and `message.content` set to the step text, `finish_reason` as mapped above, and `usage`. A call the scenario has no step for is HTTP 400 with the JSON body described under Scenarios, not a chat completion. With no scenario loaded, lenient mode returns the canned reply as a completion.

With `"stream": true`, a text step is answered as `text/event-stream` of data-only frames (`data: <payload>`, no `event:` line). Each chunk has `id` `fake-openai-<seq>`, `object` `chat.completion.chunk`, `model` `fake-openai`, and one choice with `index`, `delta` and `finish_reason`. The frames are, in order: the role chunk (`delta` `{"role":"assistant","content":""}`), one content chunk per `streamChunkChars` code points of the text, the finish chunk (empty `delta`, `finish_reason` as mapped above), then `data: [DONE]`. When the request sets `stream_options.include_usage` to true (read like `stream`), a usage chunk with no choices comes before `[DONE]`, and every other chunk carries `"usage": null`. An error step with `streamErrorAfterChunks` set sends the role chunk and the filler chunks, then the error body as a data frame (a generic `server_error` body when `body` is blank), and closes the stream with no finish chunk, usage chunk or `[DONE]`. A call the scenario has no step for, and an error step that leaves `streamErrorAfterChunks` unset, get the JSON response, with no SSE bytes.

### `POST /gemini/…:generateContent`

One JSON `generateContent` body: one candidate whose part text is the step text, `finishReason` `STOP` or `MAX_TOKENS`, `usageMetadata`, and `fake_llm_seq`. The parser forces the call to be non-streaming. `:streamGenerateContent` does not contain `:generateContent`, so that path is a 404. A call the scenario has no step for is HTTP 400 with the JSON body described under Scenarios, not a `generateContent` body. With no scenario loaded, lenient mode returns the canned reply as one candidate.

### Control API

The reference for the control API, `/health` and `/openapi` is the OpenAPI 3.1 document [openapi/fake-llm.yaml](openapi/fake-llm.yaml). A running server serves the copy for its version at `GET /openapi` (as `application/yaml`, with no token needed), so a client in any language can read the contract, or generate code from it. The spec lists the vendor routes too and links to each provider's own reference. This section summarises it.

Clients should ignore response fields they do not know, since a later version may add fields. Request bodies are strict: `POST /control/scenario` rejects unknown fields, as does the scenario format. Human-readable messages, such as most `fake_llm_error` texts and the `detail` of a 400, are diagnostics, not part of the contract.

When `FAKE_LLM_CONTROL_TOKEN` is set to a non-blank value, every path under `/control/` requires an `Authorization` header whose value is exactly `Bearer <token>`. A missing or different header is HTTP 401, with `WWW-Authenticate: Bearer`:

```json
{"fake_llm_error":"unauthorized: missing or invalid Authorization: Bearer <token>"}
```

Vendor routes and `/health` stay open. With no token configured, the control routes are open. The environment variable is read first. A JVM system property of the same name is used only when the environment variable is absent. A blank environment variable counts as unset and does not fall through to the system property.

`POST /control/scenario` is described above. It replaces the loaded scenario and starts it over.

`GET /control/scenarios` returns the names the loader can see:

```json
{"scenarios":["fail-then-retry","hello","malformed-json","max-tokens-cutoff","overloaded-529","rate-limit-429","refusal","stream-error-midway","thinking-stream"]}
```

With a scenario directory set, `*.json` names from that directory are merged in, and the list is sorted again.

`POST /control/reset` starts the loaded scenario over, as loading it again would: the call log is emptied, and the next call is call 1 and gets step 1, with response IDs from `fake-msg-1` again. The body is ignored.

```json
{"reset":true}
```

`GET /control/calls` returns the call log as a JSON array, in call order. `seq` is the call's number since the last load or reset, from 1. `step` is the step that served it, equal to `seq`, or `null` when no step did. `outcome` is `text` or `error:<status>` for a step, `default` for the lenient canned reply, and `no-more-steps` or `no-scenario` for the two 400s. `promptSample` is the last 500 characters of the combined prompt text. `timestampMs` is the wall-clock time when the call was recorded.

```json
[
  {
    "seq": 1,
    "step": 1,
    "vendor": "anthropic",
    "model": "example-model",
    "systemShape": "absent",
    "messageCount": 1,
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
| `--scenario-dir <path>` | `FAKE_LLM_SCENARIO_DIR` | bundled scenarios only | Directory searched before the bundled scenarios. |
| `--lenient` | `FAKE_LLM_LENIENT` | off (`false`) | Calls made while no scenario is loaded. Off returns HTTP 400. On returns the canned assistant reply. A call after the last step is HTTP 400 either way. The flag takes no value and turns lenient mode on. The environment variable accepts `true` or `false`, case-insensitively. |
| none | `FAKE_LLM_CONTROL_TOKEN` | unset, control routes open | Bearer token for `/control/*`. A JVM system property of the same name is the fallback when the environment variable is absent. |

## Limits

Anthropic Messages and OpenAI Chat Completions stream. On `POST /anthropic/v1/messages` and `POST /openai/v1/chat/completions`, `"stream": true` is a server-sent event response for a text step, and for an error step that sets `streamErrorAfterChunks`. An error step that leaves that field unset is a plain HTTP error instead. Gemini always returns one JSON `generateContent` body. The Gemini parser sets `stream` to false, and `:streamGenerateContent` is not a route.

A call after the scenario's last step, and in strict mode (the default) a call with no scenario loaded, gets HTTP 400 and a JSON body on every vendor route. On `POST /anthropic/v1/messages` and `POST /openai/v1/chat/completions` that includes `"stream": true`: the response is `application/json`, and no server-sent events are written. With no scenario loaded, lenient mode (`--lenient` or `FAKE_LLM_LENIENT=true`) returns the canned assistant reply instead. For a streamed Anthropic or OpenAI request, that reply is a normal text stream.

The Anthropic Batches API is not implemented. Every request under `/anthropic/v1/messages/batches` returns HTTP 501.

Token counts are constants, not a count of the prompt or the reply. Anthropic reports `input_tokens` 100 and `output_tokens` 50. `cache_creation_input_tokens` is 20 and `cache_read_input_tokens` is 10 when the request carries a `cache_control` breakpoint, and both are 0 otherwise. A breakpoint is a `cache_control` object on a block in the `system` array or in a message `content` array. `type` must be `ephemeral`, and `ttl`, if present, must be `5m` or `1h`. On a stream, the input and cache figures are on `message_start`, and `output_tokens` 50 is on `message_delta`. OpenAI reports `prompt_tokens` 100, `completion_tokens` 50, and `total_tokens` 150. On an OpenAI stream, usage is sent only when `stream_options.include_usage` is true, in a final chunk with no choices. Gemini reports `promptTokenCount` 100, `candidatesTokenCount` 50, and `totalTokenCount` 150.

The server does not simulate tool use or function calling. Responses are text, plus an Anthropic thinking block when the step sets `thinking`. Request fields such as `tools`, `tool_choice`, and Gemini `tools` are ignored, and the response never contains `tool_use`, `tool_calls`, or `functionCall` parts.

Each process holds one scenario and one call log in memory. Nothing is written to disk. Another process does not share that state.

## Development

`./gradlew build` compiles the server, runs the Kotlin tests, and writes `build/libs/fake-llm-all.jar`.

The example project tests the published artifact, not the source, so publish it first. `check` runs its tests and its quality gates, as CI does:

```bash
./gradlew publishAllPublicationsToBuildLocalRepository
./gradlew -p examples check
```

`./gradlew -p examples test --include-build ..` runs the same tests against the source instead, through a composite build.

### Public API

Kotlin explicit API mode is on, so a new public declaration in `src/main` (or `junit-jupiter/src/main`) needs an explicit `public` modifier. `./gradlew check`, and so `./gradlew build` and CI, compares the compiled public API with `api/fake-llm.api` and `junit-jupiter/api/fake-llm-junit-jupiter.api` and fails on any difference. For an intended change, run `./gradlew apiDump`, commit the updated `.api` file with the change, and add a CHANGELOG entry. Add new fields to the scenario model classes last, with a default value, so existing Kotlin call sites still compile. A scenario field also goes in the spec's `Scenario`, `Step` or `ErrorStep` schema (a test fails until it does); then run `./gradlew updateScenarioSchema` and commit the regenerated `schema/scenario.schema.json`. A Kotlin or kotlinx-serialization upgrade can change the generated members in the dump, so run `apiDump` after one and review the diff.

### Quality gates

Compiler warnings are errors. A warning in the main or test code fails `./gradlew build`, and one in `examples/` fails `./gradlew -p examples check`; CI runs both.

`./gradlew check`, and so `./gradlew build` and CI, measures the test coverage of `src/main` with [Kover](https://github.com/Kotlin/kotlinx-kover) and fails when line coverage drops below 95% or branch coverage below 65%. The JUnit extension in `junit-jupiter/` and the conformance runner in `conformance/runner/` are each measured on their own against the same floors, so no module's coverage can hide a drop in another's. The conformance cases run against the fat jar from another module, so they add nothing to the root module's coverage. The floors are set in the root `build.gradle.kts`, which is the source of truth; update the numbers here when they change. Raise a floor when coverage rises, and never lower one without a reason in the pull request. To see the current numbers, run `./gradlew koverHtmlReport` and open `build/reports/kover/html/index.html` (the extension's is `junit-jupiter/build/reports/kover/html/index.html`, the runner's `conformance/runner/build/reports/kover/html/index.html`), which shows line and branch coverage; `./gradlew koverLog` prints line coverage only. CI attaches the reports as the `coverage-report` artifact on every run where the tests pass. Nothing is excluded from the measurement, so a new `@Serializable` class usually adds one uncovered generated line.

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
- **The conformance suite** in [conformance/](conformance/README.md) defines the server's HTTP behaviour as JSON cases, for this server and for any other implementation. CI's `conformance` job runs it against the fat jar and the Docker image built from the same commit; locally, `./gradlew shadowJar :fake-llm-conformance:test -Pfakellm.command="java -jar build/libs/fake-llm-all.jar"`. A change to HTTP behaviour comes with a case. CI's `conformance` job also runs the official Python SDKs against the same jar and image (see [conformance/README.md](conformance/README.md#sdk-suite)), and the `SDK latest` workflow runs them daily against their newest releases.
- Commits that only reformat code are listed in `.git-blame-ignore-revs`, which GitHub's blame view honours; locally, run `git config blame.ignoreRevsFile .git-blame-ignore-revs`.

### Dependency updates

[Dependabot](.github/dependabot.yml) opens version-update pull requests every Monday for the Gradle builds (the root build and `examples/`, including the Gradle wrapper), the GitHub Actions in `.github/workflows/`, the Dockerfile base images, and the linter images in `.github/linters/`. Minor and patch updates are grouped into one pull request per ecosystem, and the two base-image digests are updated together. Kotlin, its Gradle plugins and the kotlinx-serialization runtime move together in one group across both builds, majors included, and the detekt and ktlint plugins have a group of their own, because a new rule release can bring new findings. Every other major update arrives on its own, except the linter images and the SDK conformance suite's Python packages, which are grouped whatever the update type. Each update must pass the same required checks as any other pull request.

- Dependabot also updates the SDK conformance suite's lock (`conformance/sdk/python/requirements.txt`) weekly, all packages in one pull request, majors included.
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

### Releasing

A release is published from a `vX.Y.Z` tag on `main` by the [Release](.github/workflows/release.yml) workflow.

1. In a pull request, set `version` in `gradle.properties` to `X.Y.Z` and change the CHANGELOG heading from `## [X.Y.Z] - Unreleased` to `## [X.Y.Z] - YYYY-MM-DD`.
2. After it merges, tag the merge commit and push the tag: `git tag vX.Y.Z <merge commit> && git push origin vX.Y.Z`.

The workflow then runs four jobs:

- `verify` fails unless the tag is on `main`, matches `version` in `gradle.properties`, and has a dated CHANGELOG heading. It extracts that CHANGELOG section as the release notes, then builds and tests as CI does: the build, `examples/`, and the HTTP conformance suite against the fat jar.
- `image` builds the image for linux/amd64 and linux/arm64, pushes it to `ghcr.io/davidjones-griz/fake-llm` by digest only, and checks `GET /health` on each platform. Only then does it tag it `X.Y.Z`, `X.Y` and `latest`.
- `maven` runs after `image`, because a Maven Central publish cannot be undone. It signs both artifacts, checks that every file in the bundle is signed, uploads the bundle to the Central Portal, and waits up to 30 minutes for it to publish. It needs four repository secrets: `MAVEN_CENTRAL_USERNAME` and `MAVEN_CENTRAL_PASSWORD` (a Central Portal user token), `SIGNING_KEY` (the ASCII-armored private key) and `SIGNING_KEY_PASSWORD`. If any is missing, the job passes with a "Maven Central skipped" notice in its log and summary, and the image and GitHub Release are still published.
- `release` creates the GitHub Release `vX.Y.Z` with the release notes, the fat jar and its `.sha256`. It runs only when the other three jobs succeeded.

Because `maven` runs after `image`, a failed `maven` job leaves the image tags pushed with no GitHub Release yet; the Release is created when the re-run succeeds. If a job fails for a reason outside the code, such as a missing secret, a key that hasn't reached the keyservers, or an outage, fix the cause and re-run the failed jobs. A problem in the code, the POM or the signatures needs a new tag, because a re-run uses the same commit. Central does not accept the same version twice: if Central published after the `maven` job gave up waiting, re-running that job fails, so check the deployment on central.sonatype.com and create the Release by hand. After the first release, set the `fake-llm` package on GHCR to public.

## Roadmap

Tracked in https://github.com/davidjones-griz/fake-llm/issues

## License

Apache-2.0. See [LICENSE](LICENSE). Copyright 2026 David Jones.
