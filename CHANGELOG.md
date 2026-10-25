# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project uses
[Semantic Versioning](https://semver.org/spec/v2.0.0.html). Before 1.0.0, a minor version may
change the public API.

## [0.1.0] - Unreleased

### Added

- HTTP server that stands in for Anthropic Messages, OpenAI Chat Completions, and Gemini
  `generateContent`, driven by a JSON scenario: first-match rules, and per-rule step sequences
  for scripted errors, `max_tokens` truncation, thinking, and failures followed by a retry.
- Anthropic SSE streaming, including an error partway through the stream.
- Strict mode by default: a request no rule matches is HTTP 400 with an explanation.
  `--lenient` serves a canned reply instead.
- Control API: load a scenario by name or inline, reset, list scenarios, read the call log, and an
  optional bearer token.
- Bundled scenarios for rate limits, overload, refusal, truncation, malformed JSON, stream errors,
  thinking, and fail-then-retry.
- Library use: the `com.davidjonesgriz:fake-llm` Maven publication with sources and javadoc jars, and
  `startFakeLlmServer`, which returns a `FakeLlmServer` handle (`port`, `baseUrl`, `engine`,
  `close()`) for starting the server in-process from tests.
- `FakeLlmClient`: a Kotlin/Java client for the control API (`health`, `scenarioNames`, `load`,
  `loadInline`, `reset`, `calls`) with an optional bearer token. Every failed call is a
  `FakeLlmClientException`, with the HTTP status and the server's message when a response arrived.
- `ScenarioEngine(scenarioDirectory = path)`: a per-engine directory that `load(name)` searches
  first and `GET /control/scenarios` lists, so engines in one JVM can load same-named scenarios
  from different directories.
- Scenarios from the project's classpath: a name is looked up in the scenario directory, then the
  classpath `/scenarios/<name>.json`, then the bundled scenarios, which live under fake-llm's own
  resource path, so a project's `index.txt` no longer hides the bundled list.
  `GET /control/scenarios` merges every `/scenarios/index.txt` on the classpath with the bundled
  names.
- `com.davidjonesgriz:fake-llm-junit-jupiter`, a JUnit Jupiter extension (5.10.5 or newer,
  including 6.x): `@FakeLlmTest` runs one in-process server per test class, `@FakeLlmScenario`
  loads a scenario before each test, and `FakeLlmServer` and `ScenarioEngine` are injected as
  parameters. The annotations also work inside composed annotations and on interfaces. Also
  usable through `@RegisterExtension` with `FakeLlmExtension`.
- `ScenarioEngine.unload()`: removes the loaded scenario and clears the call log and counters.
- An OpenAPI 3.1 document for the control API, `/health` and `/openapi`
  (`openapi/fake-llm.yaml`), served by the running server at `GET /openapi`. Tests check every
  control response against it. The scenario format is part of it.
- Strict input checks matching the spec: `POST /control/scenario` rejects unknown fields, a
  `Match` rejects a `systemShape` other than `blocks`, `string` or `absent`, and an `ErrorStep`
  rejects a status outside 100 to 599. The control routes' 401 carries `WWW-Authenticate: Bearer`,
  and a scripted `content-type` header replaces the default instead of adding a second one. The
  Gemini route matches only `/gemini/{version}/models/{model}:generateContent`, and the Batches
  stand-in only `/anthropic/v1/messages/batches` and paths under it, as the spec documents.
- `examples/`, a separate build that tests a small client against the published artifact.
- Public API checks: Kotlin explicit API mode, and `api/fake-llm.api`, which `./gradlew check`
  compares with the compiled API.

### Changed

Compared with pre-release builds of `main`:

- `ScenarioLoader` is internal. The scenario directory belongs to each `ScenarioEngine` instead of
  the whole process; `--scenario-dir` and `FAKE_LLM_SCENARIO_DIR` set it for the command-line
  server's engine and behave as before.
- `CallRecord`'s constructor and `copy` are internal.
- `Scenario.rules` has no default value. A scenario file without `rules` now fails as a missing
  field instead of as an empty list.
- `kotlinx-serialization-json` is an `api` dependency.
- Built with Kotlin 2.4, and the `kotlinx-serialization-json` dependency is 1.12.0. A Kotlin build
  that uses the library needs Kotlin 2.4 (2.3 may work, on a best-effort basis), and gets
  `kotlin-stdlib` 2.4 and `kotlinx-serialization-json` 1.12.0 as dependencies, as does a Java build.
  Both published libraries need Java 21 or newer, tested on Java 21 and 25.
- Including this repository as a composite build (`includeBuild`) needs Gradle 9.2 or newer in
  the including project, running on JDK 17 or newer.
- A scenario that fails validation while being decoded from JSON now throws kotlinx-serialization's
  `SerializationException` (still an `IllegalArgumentException`), whose message wraps the
  validation message with the JSON path and input, and whose cause is the validation exception.
- Docker image and the `fake-llm-all.jar` fat jar.
