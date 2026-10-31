# Use as a library

Kotlin and Java tests can start the server in the same JVM instead of running a separate process. The coordinates are `com.davidjonesgriz:fake-llm:0.6.0`. Classes are in the `com.davidjonesgriz.fakellm` package. To build from a checkout instead, publish it to your local Maven repository:

```bash
./gradlew publishToMavenLocal
```

```kotlin
repositories { mavenLocal() }
dependencies { testImplementation("com.davidjonesgriz:fake-llm:0.6.0") }
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

From Java, `startFakeLlmServer` is a static method of the class `FakeLlm`. Pass `null` as the token for open control routes, or leave the argument out to read `FAKE_LLM_CONTROL_TOKEN`, as the Kotlin default does:

```java
ScenarioEngine engine = new ScenarioEngine();
try (FakeLlmServer server = FakeLlm.startFakeLlmServer(0, engine, null)) {
    engine.load("hello");
    MyClient client = new MyClient(server.getBaseUrl() + "/anthropic");
    // ... exercise the code under test ...
    assertEquals("text", engine.calls().get(0).getOutcome());
}
```

`engine.exchanges()` returns each call's full request and response; see [`GET /control/exchanges`](control-api.md#get-controlexchanges). `engine.unusedSteps` lists the steps not used up since the last load, reset or unload; a parallel step is listed until every step in it is used.

A scenario for calls made in parallel can be built in code too: a step's `match` takes a `Match`, and a parallel step a `ParallelSection` (see [Parallel calls](scenarios.md#parallel-calls)). In Kotlin, `equals` is `equalTo`:

```kotlin
val doc = { d: String -> Step(text = "Summary of $d.", match = Match(lastUser = TextMatch(contains = d))) }
engine.loadInline(
    Scenario(
        name = "fan-out",
        steps = listOf(
            Step(parallel = ParallelSection(listOf(doc("doc-a"), doc("doc-b")))),
            Step(text = "Both, combined.", match = Match(system = TextMatch(contains = "Merge"))),
        ),
    ),
)
```

`engine.load(name)` finds `/scenarios/<name>.json` on the classpath, so a test project can keep its scenarios in `src/test/resources/scenarios/`. `engine.loadInline(Scenario(...))` takes a scenario built in code. Each `ScenarioEngine` has its own scenario, call log, exchange log, and optional scenario directory. An engine created with `ScenarioEngine(scenarioDirectory = Path.of("src/test/scenarios"))` (from Java, `new ScenarioEngine(false, dir)`) reads `<directory>/<name>.json` first, as the command-line server does with `--scenario-dir`; a relative path resolves against the working directory, which in a Gradle test is the project directory. For scenario files kept elsewhere, point the engine's scenario directory at them, so they get the same checks and messages as any load by name.

`Scenario.fromJson(text)` decodes a scenario written in the scenario file format, with the checks and messages of an inline load: a `"$schema"` key is ignored, and the name need not match a file. From Java, it builds a scenario in code with a text block:

```java
engine.loadInline(Scenario.fromJson("""
        {
          "name": "rate-limited-once",
          "steps": [
            {"error": {"status": 429, "headers": {"retry-after": "0"}}},
            {"text": "Recovered."}
          ]
        }
        """));
```

A name is looked up in this order, and the first match wins: the engine's scenario directory, then your project's classpath `/scenarios/<name>.json` (the first on the classpath, if several modules have one), then the bundled scenarios, which fake-llm keeps under its own resource path, so your `index.txt` no longer hides the bundled list. So a scenario of yours with a bundled name, such as `scenarios/rate-limit-429.json`, replaces the bundled one, on purpose. `GET /control/scenarios` lists the directory's `*.json` names, the names in every `/scenarios/index.txt` on the classpath, and the bundled names. To have your classpath scenarios listed, add an `index.txt` beside them, one name per line (blank lines and whole-line `#` comments are skipped, and surrounding spaces are trimmed); without one they still load by name. A listed name is only as good as its index: a name whose file is missing still fails to load, and names that are not legal scenario names are skipped.

The library is built with Kotlin 2.4. A Kotlin project that uses it needs Kotlin 2.4 (2.3 may work, on a best-effort basis), and gets `kotlin-stdlib` 2.4 and `kotlinx-serialization-json` 1.12.0 as dependencies, as does a Java project. The three libraries, `fake-llm`, `fake-llm-junit-jupiter` and `fake-llm-testcontainers`, need Java 21 or newer, and CI tests them on Java 21 and 25. A Gradle build that targets an older Java fails at dependency resolution (the library is published as compatible with Java 21). A Maven or plain-classpath build fails to compile under an older JDK (`class file has wrong version 65.0`), or, if compiled with a newer JDK, fails at run time with `UnsupportedClassVersionError`. A project on Java 17 can still use fake-llm as a separate service: the Docker image includes its own Java 21 runtime (the fat jar needs Java 21 or newer on the machine that runs it), and the vendor endpoints and the [control API](control-api.md) work over HTTP from any language. Anything that runs in the project's own JVM (the in-process server and `ScenarioEngine`, `FakeLlmClient`, the JUnit extension, `FakeLlmContainer`) needs Java 21.

## Control API client

`FakeLlmClient` drives a running server over HTTP, for tests that reach it by URL, such as the Docker image, `java -jar`, or a server started elsewhere in the test. Where it and `ScenarioEngine` have the same operation, the method has the same name:

```kotlin
FakeLlmClient("http://localhost:8080", controlToken = System.getenv("FAKE_LLM_CONTROL_TOKEN")).use { client ->
    client.load("rate-limit-429")          // or client.loadInline(Scenario(...))
    // ... exercise the code under test against http://localhost:8080 ...
    val calls: List<CallRecord> = client.calls()
    val exchanges: List<ExchangeRecord> = client.exchanges()
    client.reset()
    client.unload()                        // no scenario: calls get the strict 400, or the lenient canned reply
}
```

From Java, `new FakeLlmClient(baseUrl)` or `new FakeLlmClient(baseUrl, token)`, in a `try`-with-resources block. `health()` returns the loaded scenario's name, whether the server is lenient, `steps` and `unusedSteps`, and `version`, the server's version (each null against a server older than this release), and `scenarioNames()` lists what the server can load by name. `unload()` returns the server to the state it has when started without a scenario: no scenario loaded and empty logs. Against a server older than 0.5.0 it fails with status 404. When a token is set, every request carries `Authorization: Bearer <token>`; a blank token counts as none, as a blank `FAKE_LLM_CONTROL_TOKEN` does on the server. The constructor rejects a `baseUrl` that is not an `http://` or `https://` URL with a host and a valid port (or that has a query or fragment), and a timeout that is not positive, with `IllegalArgumentException`.

Every failed call throws `FakeLlmClientException`. `statusCode` is the HTTP status, or null when no response arrived (a refused connection, a timeout or an interrupt, kept as the `cause`). For an error status, `serverMessage` is the server's explanation, verbatim, such as `unknown scenario 'x' — ...`, and `errorCode` is its [code](endpoints.md#fake-llms-error-responses), for any status, such as `unknown-scenario` or `no-route` (null for a server older than 0.7.0). A 401 says whether the token was missing or rejected.

The client ignores response fields it does not know, so a server that has gained fields does not break it. It also leaves fields at their default values out of the scenarios it sends, so a scenario that does not use a newly added field is still accepted by a server that predates it.

## JUnit Jupiter extension

`com.davidjonesgriz:fake-llm-junit-jupiter:0.6.0` runs an in-process server for each test class, so a test needs no setup or teardown of its own. Its classes are in the `com.davidjonesgriz.fakellm.junit` package, and it brings `fake-llm` with it. It works with JUnit Jupiter 5.10.5 or newer, including 6.x. Your project supplies JUnit; the extension does not add it. Kotlin's `kotlin("test")` brings JUnit 5.10.1 on its own, so a Kotlin project should also add a newer BOM, such as `testImplementation(platform("org.junit:junit-bom:5.14.4"))`.

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
- **A scenario per test.** Before each test, the nearest `@FakeLlmScenario` is loaded: the method's, then the class's (its own, then its interfaces', then its superclasses'), then each enclosing class's, the same way. Loading starts the scenario over, so every test's first call gets step 1 and the call log and the exchange log hold only that test's calls. A test with no `@FakeLlmScenario` anywhere runs with no scenario loaded (`ScenarioEngine.unload()`), so a scenario loaded by hand in `@BeforeAll` is gone again by the first test; put the scenario on each test method; a class-level `@FakeLlmScenario` is supported, but it shares one scenario between tests (see [Writing scenarios for tests](scenarios.md#writing-scenarios-for-tests)). The extension runs before your own `@BeforeEach` methods, so they see the scenario and can replace it.
- **Every call must get a step.** Right after the test method, before your `@AfterEach` methods, the test fails if a call made during `@BeforeEach` or the test got no step: a call after the scenario's last step, a call with no scenario loaded in strict mode, or a call that matched none of the steps it could take (`no-match`). The failure lists those calls. An app can catch the 400 and carry on, so without this check such a test could pass. A test that makes such a call on purpose, to test how the app handles it, opts out with `@FakeLlmAllowUnservedCalls` on the method or the class (found the same way as `@FakeLlmScenario`). An aborted test (a failed assumption) is not checked. For a `@TestFactory`, the check runs once around the whole factory. A `reset()` or `load()` in the middle of a test erases the calls made before it from the log, so the check does not see those.
- **Every step must get a call.** A test that passed also fails if its scenario has steps no call used, such as a retry the app never made, including a parallel step with any step unused. The failure names the unused steps. A test that leaves steps unused on purpose opts out with `@FakeLlmAllowUnusedSteps` on the method or the class (found the same way as `@FakeLlmScenario`). A test method that already failed is not checked for unused steps (it often stopped before its last calls), and an aborted test is not checked. For a `@TestFactory`, the check runs once around the whole factory, after its dynamic tests, whether or not they passed.
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

[examples/](https://github.com/davidjones-griz/fake-llm/tree/main/examples/) uses the extension in `SummarizerExtensionTest` and `TicketPipelineTest`.

## Testcontainers

`com.davidjonesgriz:fake-llm-testcontainers:0.6.0` runs fake-llm's Docker image with [Testcontainers](https://java.testcontainers.org/), for tests that want the server in its own container, as they run a database or a message broker. `FakeLlmContainer` is a `GenericContainer`, in the `com.davidjonesgriz.fakellm.testcontainers` package. It brings `fake-llm` (for `FakeLlmClient`) and Testcontainers 2 with it, and needs Docker, or another runtime Testcontainers supports, where the tests run. With Testcontainers' JUnit Jupiter extension, `org.testcontainers:testcontainers-junit-jupiter`:

```java
@Testcontainers
class MyClientTest {
    @Container
    static final FakeLlmContainer FAKE_LLM = new FakeLlmContainer().withScenarioDirectory(Path.of("src/test/scenarios"));

    @Test
    void retriesAfterARateLimit() {
        try (FakeLlmClient client = FAKE_LLM.client()) {
            client.load("rate-limit-429");
            MyClient app = new MyClient(FAKE_LLM.getAnthropicBaseUrl());
            // ... exercise the code under test ...
            assertEquals(2, client.calls().size());
        }
    }
}
```

- **The image.** `new FakeLlmContainer()` runs `ghcr.io/davidjones-griz/fake-llm` tagged with the library's own version, so the server and the client always match. Any tag of that repository can be passed instead, as a string or a `DockerImageName`. Another image, such as a mirror or a local build, must be declared a substitute, as for any Testcontainers module: `new FakeLlmContainer(DockerImageName.parse("fake-llm:dev").asCompatibleSubstituteFor("ghcr.io/davidjones-griz/fake-llm"))`.
- **Startup.** The container counts as started once `GET /health` answers 200, so the first test call finds the server listening.
- **Base URLs.** `getBaseUrl()` is `http://<host>:<mapped port>`. `getAnthropicBaseUrl()` (`…/anthropic`), `getOpenAiBaseUrl()` (`…/openai/v1`) and `getGeminiBaseUrl()` (`…/gemini/`) are the base URLs the official SDKs take; see [Quickstart](quickstart.md). Read them after the container has started.
- **Scenarios.** `withScenarioDirectory(path)` copies a host directory into the container before it starts and sets `FAKE_LLM_SCENARIO_DIR` to it, so its scenarios load by name. The copies are readable by the server whatever their permissions on the host, so a JUnit `@TempDir` works. `withScenario(name)` loads one at startup. A test can also send its scenario inline with `client.loadInline(...)`, with no directory.
- **Options.** `withLenient()` sets `FAKE_LLM_LENIENT=true`, and `withControlToken(token)` sets `FAKE_LLM_CONTROL_TOKEN`. `client()` returns a [`FakeLlmClient`](#control-api-client) for the container, with that token if one is set; close it after use.
- **One container per class.** A `static` `@Container` field starts once for the class. Tests that share it should each load their scenario first, as under [A scenario per test](ci.md#a-scenario-per-test).

## API stability

The public API is exactly what [api/fake-llm.api](https://github.com/davidjones-griz/fake-llm/blob/main/api/fake-llm.api), [junit-jupiter/api/fake-llm-junit-jupiter.api](https://github.com/davidjones-griz/fake-llm/blob/main/junit-jupiter/api/fake-llm-junit-jupiter.api) and [testcontainers/api/fake-llm-testcontainers.api](https://github.com/davidjones-griz/fake-llm/blob/main/testcontainers/api/fake-llm-testcontainers.api) list, and the build fails if the code drifts from it. The one exception is `main`, the command-line entry point, which is listed only because the JVM requires it to be public. From Java, the top-level functions are in two classes whose names are set with `@file:JvmName`, not taken from source file names: `FakeLlm` has `startFakeLlmServer`, and `FakeLlmMain` has `main`. Before 1.0, a minor version may change that API, and every change is listed in [CHANGELOG.md](https://github.com/davidjones-griz/fake-llm/blob/main/CHANGELOG.md). The scenario model classes (`Scenario`, `Step`, `ToolCall`, `ErrorStep`, `TokenUsage`) are data classes, so a release that adds a field changes their constructors: recompile against a new version rather than swapping the jar. `CallRecord`, `ToolCallRecord`, `ToolResultRecord`, `ExchangeRecord`, `ExchangeRequest`, `ExchangeResponse`, `FakeLlmHealth` and `FakeLlmClientException` are read-only, with internal constructors. A field added to `CallRecord`, the three exchange records or `FakeLlmHealth` always gets a default value, so it does not break callers, including code that decodes `GET /control/calls` with `CallRecord.serializer()` or `GET /control/exchanges` with `ExchangeRecord.serializer()`, and so a newer `FakeLlmClient` can still read an older server's responses. Exception messages from scenario validation are diagnostics, not API.

[examples/](https://github.com/davidjones-griz/fake-llm/tree/main/examples/) is a complete consumer, tested against the published artifact: a small client with retry logic, tested from Kotlin and from Java, and a three-stage pipeline, tested end to end.
