# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project uses
[Semantic Versioning](https://semver.org/spec/v2.0.0.html). Before 1.0.0, a minor version may
change the public API.

## [Unreleased]

### Added

- `Scenario.fromJson(text)`: decodes a scenario written in the scenario file format, with the
  checks and messages of an inline load, so Java code can build a scenario from a text block.
- From Java, `FakeLlm.startFakeLlmServer(port, engine)` without the control token argument, which
  reads `FAKE_LLM_CONTROL_TOKEN` as the Kotlin default does.
- `com.davidjonesgriz:fake-llm-testcontainers`: `FakeLlmContainer`, a Testcontainers
  `GenericContainer` for the Docker image. By default it runs the image tagged with the library's
  version, and it starts once `GET /health` answers. It has the base URLs for the Anthropic, OpenAI
  and Gemini SDKs, copies a host scenario directory into the container, and returns a
  `FakeLlmClient` for its control API.
- `examples/` has a three-stage pipeline, `TicketPipeline`, tested end to end in
  `TicketPipelineTest`: one call each to the Anthropic, OpenAI Chat Completions and Gemini
  formats, with a tool call and a streamed reply, and a test for each failure its scenarios script.
- `pytest-fake-llm`, a pytest plugin in `pytest-fake-llm/`, installed from this repository: a
  session fixture that starts the Docker image tagged with its version, or uses the server at
  `--fake-llm-url` or `FAKE_LLM_URL`; a `fake_llm` fixture that unloads the server before each test;
  a `fake_llm_scenario` marker that loads a scenario; and `FakeLlmClient`, a Python client for the
  control API with typed call records, whose `health()` also returns `steps` and `unused_steps`.
- `ScenarioEngine.unusedSteps`: the steps of the loaded scenario that no call has used since the
  last load, reset or unload.
- `@FakeLlmAllowUnusedSteps`, which turns off the JUnit extension's unused-steps check for a test
  or class.
- `GET /health` reports the loaded scenario's `steps` and `unusedSteps`, the steps no call has used
  since the last load, reset or unload, so a test that drives the server over HTTP can check that
  its app made every scripted call. `FakeLlmHealth.steps` and `unusedSteps` read them (null from an
  older server).
- `usage` on a step: the input and output token counts a reply reports, on every format, buffered
  and streamed (`TokenUsage` in Kotlin). Unset counts keep 100 and 50.
- A Node SDK conformance suite in `conformance/sdk/node/` that drives the official
  `@anthropic-ai/sdk`, `openai` and `@google/genai` packages, at pinned versions, against the fat
  jar and the Docker image in CI, plus the daily run against their latest releases.

### Changed

- The call log's `promptSample` and the 400 bodies' `prompt_preview` read more of the request: the
  string `text` of each part of an OpenAI Chat Completions message whose `content` is an array, the
  parts of a Gemini `systemInstruction` (or `system_instruction`), before `contents`, and the
  content of an Anthropic `tool_result` block. `systemShape` is `blocks` for an OpenAI `developer`
  message and for a Gemini `systemInstruction` object, and `unknown` for a Gemini
  `systemInstruction` that is not an object. A Gemini part whose `text` is not a string adds
  nothing.
- From Java, `startFakeLlmServer` is a static method of `FakeLlm` instead of `FakeLlmAppKt`, and
  `main` is in `FakeLlmMain`, now the fat jar's `Main-Class`. Kotlin source needs no change, but
  code compiled against an earlier version must be recompiled, and `fake-llm-junit-jupiter` must
  be the same version as `fake-llm`.
- The JUnit extension fails a test that passed if its scenario has steps no call used, naming
  them. Annotate a test that leaves steps unused on purpose with `@FakeLlmAllowUnusedSteps`.
- `Step` has a new last parameter, `usage`. Code compiled against `Step`'s constructor or `copy`
  must be recompiled.

## [0.5.0] - 2026-10-28

### Added

- `POST /control/unload` and `FakeLlmClient.unload()`: remove the loaded scenario and empty the
  call log and the exchange log, as `ScenarioEngine.unload()` does, so a test that reaches the
  server over HTTP can return it to having no scenario loaded.
- A CI recipe: `examples/github-actions/fake-llm-tests.yml` runs fake-llm as a GitHub Actions
  service container beside an app's tests, which load a scenario per test, check the call log and
  unload afterwards. "Run in CI" in the README describes it, with a `docker run` step for other CI
  systems. This repository's CI runs the recipe against the image built from each commit.

## [0.4.0] - 2026-10-27

### Added

- `firstByteDelayMs` on a step: a wait before any response bytes on every vendor route, for
  buffered and streamed replies and error steps, to test client timeouts. The call log records the
  delays each call applied (`firstByteDelayMs`, `streamInterChunkDelayMs`).
- Exchange log: `GET /control/exchanges`, `ScenarioEngine.exchanges()` and
  `FakeLlmClient.exchanges()` return, for each call in `seq` order, the request as received
  (method, path with query, headers, raw body) and the response as written (status, the headers
  fake-llm set, every body byte including a whole SSE stream, and `open`, `complete` or
  `aborted`). The values of `authorization`, `proxy-authorization`, `x-api-key`, `x-goog-api-key`
  and `api-key` and of a `key` query parameter are recorded as `[redacted]`. Requests that are not
  calls are not logged. Loading, resetting and unloading empty it with the call log.
- The conformance case format's `{"$type": "object"}` matcher.

### Changed

- `streamInterChunkDelayMs` and `firstByteDelayMs` must be from 0 to 600000 (10 minutes); a
  scenario with a larger value fails to load.
- `Step` has a new last parameter, `firstByteDelayMs`, and `CallRecord` has two new fields. Code
  compiled against `Step`'s constructor or `copy` must be recompiled.
- A request whose client has gone away mid-response is closed without a second response attempt.

## [0.3.0] - 2026-10-27

### Added

- Gemini SSE streaming: `POST /gemini/…:streamGenerateContent?alt=sse` returns one
  `GenerateContentResponse` per data frame, with text split and paced by `streamChunkChars` and
  `streamInterChunkDelayMs`, each tool call as a whole `functionCall` part, `finishReason` and
  `usageMetadata` on the last chunk, and an in-stream error after `streamErrorAfterChunks`
  chunks. Without `alt=sse` the request is the `exception` 400.
- OpenAI Responses API: `POST /openai/v1/responses`, buffered and streamed. Text is a `message`
  output item, tool calls are `function_call` items (`call_id` from the scripted or default id,
  argument text verbatim), `max_tokens` is `status` `incomplete` with `max_output_tokens`, and
  `"stream": true` returns the named Responses events, with an in-stream `error` event after
  `streamErrorAfterChunks` deltas. The call log reads `instructions` and `input`, and records
  `function_call_output` items as `toolResults`. `previous_response_id`, `store` and
  `conversation` are accepted and ignored.

### Changed

- `:streamGenerateContent` is now a route; it was a 404.
- The call log's `vendor`, and the `vendor` of the no-scenario and no-more-steps 400 bodies, can be
  `openai-responses`.

## [0.2.0] - 2026-10-26

### Added

- OpenAI Chat Completions SSE streaming: `"stream": true` returns `chat.completion.chunk` frames
  and `data: [DONE]`, split and paced by `streamChunkChars` and `streamInterChunkDelayMs`, with a
  usage chunk when `stream_options.include_usage` is true and an in-stream error after
  `streamErrorAfterChunks` chunks.
- Scripted tool calls: a step's `toolCalls` (`name`, `arguments` or verbatim `rawArguments`,
  optional `id`) are sent as Anthropic `tool_use` blocks, OpenAI `tool_calls` and Gemini
  `functionCall` parts, streamed as Anthropic `input_json_delta` events and OpenAI tool-call
  deltas. A step can carry text and tool calls together.
- The call log records the tool calls each response carried (`toolCalls`) and the tool results
  each request sent back (`toolResults`), and outcome `tool-calls` for a step with tool calls.
  `ToolCall`, `ToolCallRecord` and `ToolResultRecord` are public.

### Changed

- An OpenAI request with `"stream": true` is now answered as a stream; it was answered with one
  JSON completion.
- A step is an `error` or a reply: `text`, `toolCalls` or both. `thinking` is allowed on any
  reply. `Step` has a new last parameter, `toolCalls`, and `CallRecord` has two new fields.

### Fixed

- An OpenAI message with `"content": null`, such as an assistant tool-call message sent back, no
  longer adds `null` to the prompt sample.

## [0.1.0] - 2026-10-26

### Added

- HTTP server that stands in for Anthropic Messages, OpenAI Chat Completions, and Gemini
  `generateContent`, driven by a JSON scenario: an ordered list of steps, where call N after a load
  or reset gets step N, on any vendor route. Steps script errors, `max_tokens` truncation,
  thinking, and failures followed by a retry.
- Anthropic SSE streaming, including an error partway through the stream.
- A call the scenario has no step for fails: a call after the last step is HTTP 400
  (`no more steps`), and so, in strict mode (the default), is a call with no scenario loaded
  (`no scenario loaded`). `--lenient` serves a canned reply for the second case only.
- Load and reset are a fresh start: the call log is emptied, numbering restarts at 1, and
  response IDs restart with it. The call log records the step that served each call.
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
  usable through `@RegisterExtension` with `FakeLlmExtension`. A test fails when one of its calls
  got no step, since an app can catch the 400 and carry on; `@FakeLlmAllowUnservedCalls` opts a
  test out.
- `ScenarioEngine.unload()`: removes the loaded scenario and empties the call log.
- An OpenAPI 3.1 document for the control API, `/health` and `/openapi`
  (`openapi/fake-llm.yaml`), served by the running server at `GET /openapi`. Tests check every
  control response against it. The scenario format is part of it.
- Strict input checks matching the spec: `POST /control/scenario` rejects unknown fields, a
  `Step` must set exactly one of `text` and `error` (checked when it is built), and an `ErrorStep`
  rejects a status outside 100 to 599. The control routes' 401 carries `WWW-Authenticate: Bearer`,
  and a scripted `content-type` header replaces the default instead of adding a second one. The
  Gemini route matches only `/gemini/{version}/models/{model}:generateContent`, and the Batches
  stand-in only `/anthropic/v1/messages/batches` and paths under it, as the spec documents.
- `examples/`, a separate build that tests a small client against the published artifact.
- A language-neutral HTTP conformance suite in `conformance/`: JSON cases, checked against a JSON
  Schema and against the OpenAPI spec, and a runner (`:fake-llm-conformance`, not published) that
  runs them against a server it starts with a command or one already running at a URL. CI runs it
  against the fat jar and the Docker image.
- An SDK conformance suite in `conformance/sdk/python/` that drives the official `anthropic`,
  `openai` and `google-genai` Python SDKs, at pinned versions, against the fat jar and the Docker
  image in CI, plus a daily run against their latest releases.
- Releases from a `vX.Y.Z` tag: signed artifacts on Maven Central, a multi-arch (amd64, arm64) image
  at `ghcr.io/davidjones-griz/fake-llm`, and a GitHub Release with the fat jar and this changelog's
  notes.
- A JSON Schema for scenario files, `schema/scenario.schema.json`, generated from the spec. A
  scenario may name it in a top-level `"$schema"` key, which the server ignores, so editors such
  as VS Code and IntelliJ check and complete scenario files.
- `fake-llm validate <file|dir>...` checks scenario files without starting a server, with the
  checks a load by name makes. It names each invalid file, step and field, and exits 0 when all
  are valid, 1 when any is invalid and 2 for a usage error.
- Scenario errors name the path of the field at fault, such as
  `step 2: $.steps[1]: unknown field 'stopReasn'`, and malformed JSON is reported by line and
  column.
- Public API checks: Kotlin explicit API mode, and `api/fake-llm.api`, which `./gradlew check`
  compares with the compiled API.

### Changed

Compared with pre-release builds of `main`:

- `ScenarioLoader` is internal. The scenario directory belongs to each `ScenarioEngine` instead of
  the whole process; `--scenario-dir` and `FAKE_LLM_SCENARIO_DIR` set it for the command-line
  server's engine and behave as before.
- `CallRecord`'s constructor and `copy` are internal.
- A scenario is an ordered list of `steps`. Rules, rule matching (`Rule`, `Match`, `role`, the
  match fields) and the `X-Fake-LLM-Caller` header are removed, and so are the call log's `role`
  and `occurrence`. The last step no longer repeats, and lenient mode covers only "no scenario
  loaded". `POST /control/scenario` returns `steps` instead of `rules`.
- `Step` checks text-xor-error itself, so an invalid `Step` throws when it is built, not only
  inside a scenario.
- Scenario integers must be plain JSON integers, as the spec says: a quoted number (`"24"`) or
  one written with an exponent (`2e1`) is now rejected.
- `kotlinx-serialization-json` is an `api` dependency.
- Built with Kotlin 2.4, and the `kotlinx-serialization-json` dependency is 1.12.0. A Kotlin build
  that uses the library needs Kotlin 2.4 (2.3 may work, on a best-effort basis), and gets
  `kotlin-stdlib` 2.4 and `kotlinx-serialization-json` 1.12.0 as dependencies, as does a Java build.
  Both published libraries need Java 21 or newer, tested on Java 21 and 25.
- Including this repository as a composite build (`includeBuild`) needs Gradle 9.2 or newer in
  the including project, running on JDK 17 or newer.
- A scenario that fails validation while being decoded from JSON throws an
  `IllegalArgumentException`. When a step is invalid, the message starts with its 1-based number
  (`step 2: …`), and the message names the path of the field at fault.
- Docker image and the `fake-llm-all.jar` fat jar.

### Fixed

- An Anthropic request with `"system": null` is recorded as `systemShape` `absent`, not `string`,
  and no longer adds `null` to the prompt sample. A number or boolean `system` is `unknown`.
