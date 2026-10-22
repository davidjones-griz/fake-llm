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
- `examples/`, a separate build that tests a small client against the published artifact.
- Public API checks: Kotlin explicit API mode, and `api/fake-llm.api`, which `./gradlew check`
  compares with the compiled API.

### Changed

Compared with pre-release builds of `main`:

- `ScenarioLoader` is internal. The scenario directory is set only by `--scenario-dir` or
  `FAKE_LLM_SCENARIO_DIR`.
- `CallRecord`'s constructor and `copy` are internal.
- `Scenario.rules` has no default value. A scenario file without `rules` now fails as a missing
  field instead of as an empty list.
- `kotlinx-serialization-json` is an `api` dependency.
- Docker image and the `fake-llm-all.jar` fat jar.
