# Conformance suite

A language-neutral definition of what a fake-llm server must do over HTTP. Each case plays the app:
it loads a scenario, sends requests in order, and states the exact response the server must
return to each. The cases are plain JSON, so a server written in any language can be checked
against them, and the Kotlin runner here runs them against any server it can start or reach.

Where a case and [openapi/fake-llm.yaml](../openapi/fake-llm.yaml) disagree, the spec wins.
`OpenApiConformanceTest` in the root build checks every case's expected responses against the spec.

## Layout

```
conformance/
  cases/<area>/<case-name>.json   one case per file
  schema/case.schema.json         JSON Schema for a case file
  runner/                         the runner (Gradle subproject :fake-llm-conformance, not published)
```

## Running it

From the repository root:

```bash
# Against the fat jar, built first so the run never tests a stale one: the runner starts the server.
./gradlew shadowJar :fake-llm-conformance:test -Pfakellm.command="java -jar build/libs/fake-llm-all.jar"

# Against the Docker image.
docker build -t fake-llm .
./gradlew :fake-llm-conformance:test \
  -Pfakellm.command='docker run --rm -e PORT -e FAKE_LLM_LENIENT -e FAKE_LLM_CONTROL_TOKEN -p "$PORT:$PORT" fake-llm'

# Against a server that is already running.
./gradlew :fake-llm-conformance:test -Pfakellm.baseUrl=http://localhost:8080
```

Results are in `conformance/runner/build/reports/tests/test/index.html`: one test per case, grouped
by the server it ran on. A failure names the case, the exchange, the JSON path of the first
difference, the expected and actual values, the response, and (when the runner started the server)
what the server printed. A run with a target always runs, even if nothing in the build changed.

Without `-Pfakellm.command` or `-Pfakellm.baseUrl` the cases are skipped with that reason, but the
case files are still validated, so `./gradlew build` catches a malformed case.

### `-Pfakellm.command`

The runner starts the server with this command, through `sh -c` (so a POSIX shell is needed, and
`$PORT` and other variables expand). It starts one server for each group of cases with the same
`server` settings, and one for each `fresh` case, and stops each when its cases are done: SIGTERM to
the process and everything it started, then SIGKILL after 5 seconds. A server must exit on SIGTERM.

Any implementation that follows this contract can be started this way:

- it listens on `127.0.0.1` (it may bind more) at the port in `PORT`;
- it reads `FAKE_LLM_LENIENT` (`true` or `false`) and `FAKE_LLM_CONTROL_TOKEN` (empty means none);
  the runner also sets `FAKE_LLM_SCENARIO` and `FAKE_LLM_SCENARIO_DIR` empty, and removes any other
  `FAKE_LLM_*` variable it inherited;
- it starts with no scenario loaded;
- it answers `GET /health` with 200 once it is ready; the runner waits up to 30 seconds;
- it speaks HTTP/1.1;
- it ships the bundled scenarios the cases load by name (`hello`, and the names
  `GET /control/scenarios` lists), with the same contents.

### `-Pfakellm.baseUrl`

The runner uses one running server and reads its lenient mode from `GET /health`. If the server has
a control token, pass it with `-Pfakellm.controlToken=<token>`; the runner checks it against the
server once, before any case. Cases the server cannot run are skipped, with the reason: `fresh`
cases, and cases whose lenient mode or control token differ from the server's.

### `-Pfakellm.failOnSkip=true`

Turns every skip into a failure. CI sets it: with a command target, nothing should be skipped.

## The case format

```json
{
  "$schema": "../../schema/case.schema.json",
  "name": "scripted-400-vs-own-400",
  "description": "A scripted 400 is the scenario's body; fake-llm's own 400 after the last step is its no-more-steps body.",
  "scenario": {
    "name": "scripted-400",
    "steps": [{ "error": { "status": 400, "body": "{\"type\":\"error\"}" } }]
  },
  "exchanges": [
    {
      "request": {
        "method": "POST",
        "path": "/anthropic/v1/messages",
        "headers": { "content-type": "application/json" },
        "body": { "model": "example-model", "max_tokens": 64, "messages": [{ "role": "user", "content": "Hello" }] }
      },
      "response": { "status": 400, "body": { "type": "error" } }
    }
  ]
}
```

- `name` equals the file name without `.json`, and is unique across the suite.
- `scenario` is an inline scenario, a bundled scenario name, or `null` for none. The runner loads it
  with `POST /control/scenario` before the first exchange (sending the case's control token, if
  any); a load that does not return 200 fails the case. `null` needs `"fresh": true`.
- `server` (optional) gives the settings the case needs: `lenient` (default `false`),
  `controlToken` (default `null`), and `fresh` (default `false`) for a case that needs a newly
  started server, such as one that checks the state before any scenario is loaded.
- `request`: `method`, `path`, optional `headers` (sent as given; the runner adds only what HTTP/1.1
  needs, such as `Host` and `Content-Length`), and either
  `body` (a JSON value, sent as given) or `rawBody` (a string, sent byte for byte, for bodies that
  must not be valid JSON).
- `response`: `status`, optional `headers`, and at most one of:
  - `body`: compared with the parsed JSON body exactly;
  - `rawBody`: compared with the body text exactly;
  - `events`: an SSE body, compared as an ordered list of `{ "event": "<name>", "data": … }` with no
    missing or extra events. A JSON `data` is compared with the parsed data; a string with the raw
    data line. An event without an `event:` line, such as every OpenAI or Gemini chunk, is named `message`;
    `data: [DONE]` is the string `"[DONE]"`.

  With none of them, the body is not checked.

### Comparison rules

- **Exact:** the same JSON types, the same object keys, the same array lengths, equal values. An
  expected integer needs an integer in the response: `100.0` does not match `100`.
- **Headers:** names match case-insensitively; values exactly, except that an expected
  `content-type` without parameters matches by media type (`application/json; charset=utf-8`
  passes for `application/json`). Headers a case does not list are ignored.
- **Matchers**: `{ "$type": "integer" }`, `{ "$type": "string" }` and `{ "$type": "object" }`,
  which accept any value of that type. Cases use them only for values that vary (`timestampMs`)
  and for the messages the spec says are diagnostics, not contract (an error's `message`; its
  `fake_llm_error` code is matched exactly). Cases use `object` only for the request headers in
  `GET /control/exchanges` entries, which depend on the client.
- **Timeouts:** each response, body included, must arrive within 10 seconds, so a stream that
  stalls fails its case.
- **Not checked:** a JSON body that repeats a key is read as its last value; the runner does not
  flag the duplicate.

### What the cases make contract

The spec does not describe the vendor routes' success bodies field by field, so the cases define
them, exactly. That includes the values fake-llm fixes:

- token counts, when the step sets no `usage`: Anthropic `input_tokens` 100 and `output_tokens` 50,
  and cache tokens 20 (creation) and 10 (read) with a `cache_control` breakpoint, 0 and 0 without;
  OpenAI 100, 50 and 150; OpenAI Responses 100, 50 and 150; Gemini 100, 50 and 150. The cases in
  `usage/` fix where a step's scripted counts go; the cache tokens don't change;
- response IDs from the call number: `fake-msg-<n>`, `fake-openai-<n>`, Gemini's `fake_llm_seq`,
  and `resp_fake_<n>`, `msg_fake_<n>` and `fc_fake_<n>_<i>` on OpenAI Responses;
- every reply naming the request's model: `model` on Anthropic (buffered and `message_start`) and
  OpenAI (Chat Completions and Responses) bodies and chunks, and `modelVersion`, the model in the
  path, on Gemini bodies and chunks;
- the thinking signature `fake-thinking-signature`;
- the streamed-error filler text (`Thinking this through before the connection drops. `, repeated)
  and the generic stream error body for an error step with a blank body, including the OpenAI
  Responses `error` event;
- the lenient canned reply, `This is a canned response from fake-llm.`;
- the embeddings vectors (pinned at small sizes, cross-checked with an independent script), their
  JSON number form and base64 form, and the usage estimate;
- the count_tokens count (100) and the model id `fake-llm` in the model lists;
- the bundled scenario names, and the contents of `hello`.

## Adding a case

1. Add `conformance/cases/<area>/<name>.json`, with `"$schema": "../../schema/case.schema.json"` so
   an editor can check it as you type.
2. Write the expected responses from the spec and the reference in `docs/`, not by copying what
   the server happens to send.
3. Run the suite against the fat jar (above) and `./gradlew :test --tests '*OpenApiConformanceTest*'`,
   which checks the case against the spec. A vendor 400 must list its body: one with
   `fake_llm_error` is fake-llm's own and is checked against the spec; any other is a scripted
   error step.

## SDK suite

The SDK suites check that the official client libraries accept what the server sends, in two
languages: `conformance/sdk/python/` drives the Python SDKs and `conformance/sdk/node/` the Node
SDKs. The HTTP cases above say what the server sends; these suites say a real client can read it.
CI's `conformance` job runs all three against the same fat jar and Docker image, and the
[SDK latest](../.github/workflows/sdk-latest.yml) workflow runs both SDK suites daily against the
newest SDK releases.

### Python

`conformance/sdk/python/` drives the `anthropic`, `openai` and `google-genai` Python SDKs, at the
versions pinned in `requirements.txt`. Each test loads its own scenario, makes calls through an SDK,
and asserts on what the SDK parsed or raised and on the exact call log (`GET /control/calls`) and,
in `test_exchanges.py`, the exchange log (`GET /control/exchanges`). It covers what the server
supports today: Anthropic Messages buffered and streaming, OpenAI Chat Completions and Responses
buffered and streaming, and Gemini `generateContent` buffered and `streamGenerateContent` streaming.
It also covers client timeouts against `firstByteDelayMs`, and the OpenAI and Gemini embeddings
routes (the `openai` SDK's default base64 call, the float form, and `embed_content`), Anthropic
token counting, and each SDK's model list and lookup.

```
conformance/sdk/python/
  .python-version    3.13
  requirements.in    the four top-level packages, unpinned
  requirements.txt   the lock: every package pinned, with hashes (pip-compile output)
  pytest.ini
  conftest.py        the server and client fixtures
  test_anthropic.py  test_openai.py  test_openai_responses.py  test_gemini.py
  test_exchanges.py
```

From the repository root:

```bash
python3.13 -m venv .venv && . .venv/bin/activate
python -m pip install --require-hashes -r conformance/sdk/python/requirements.txt
./gradlew shadowJar
FAKE_LLM_COMMAND="java -jar build/libs/fake-llm-all.jar" python -m pytest conformance/sdk/python
# or against a running strict server with no control token:
FAKE_LLM_BASE_URL=http://localhost:8080 python -m pytest conformance/sdk/python
```

`FAKE_LLM_COMMAND` has the same contract as the runner's `-Pfakellm.command`: it runs through
`sh -c` with `PORT`, `FAKE_LLM_LENIENT` and `FAKE_LLM_CONTROL_TOKEN` set, so the Docker command
above works here too. Set exactly one of the two variables; with neither or both, every test errors
rather than skipping.

**Retries.** The Anthropic and OpenAI clients keep their default retries (2, so up to 3 calls),
because that is what users get. They retry 408, 409, 429 and 5xx (529 included), so a test that
scripts one of those scripts one step per attempt, and gives each retryable error a
`retry-after-ms: 10` header so the run stays fast. `google-genai` does not retry by default, and
`test_default_client_does_not_retry` pins that down; its retry tests use a client set to 3
attempts. A "retries exhausted" test scripts exactly 3 errors, so an extra retry gets the
`no-more-steps` 400 and fails the test.

**Bumping a pin.** Edit nothing by hand. From `conformance/sdk/python/`, with Python 3.13 and
pip-tools 7.6.2, rerun
`pip-compile --generate-hashes --strip-extras --allow-unsafe --upgrade-package <name> requirements.in`
and commit `requirements.txt` as written. Dependabot does this weekly for all four packages in one
pull request.

**Latest releases.** The [SDK latest](../.github/workflows/sdk-latest.yml) workflow runs the suite
daily against the newest releases, unpinned, and lists their versions in the run's summary. A
failure there means a new SDK release breaks against fake-llm before Dependabot proposes it.

### Node

`conformance/sdk/node/` drives the `@anthropic-ai/sdk`, `openai` and `@google/genai` packages, at
the versions pinned in `package.json` and locked in `package-lock.json`. It mirrors the Python suite's
design: the same `FAKE_LLM_COMMAND` / `FAKE_LLM_BASE_URL` contract, one inline scenario per test,
and assertions on what the SDK parsed or threw and on the exact call log. It is a first cut of 31
tests (buffered and streamed replies, tool calls, errors, retries and scripted usage for each
format); the rest of the Python suite's coverage is follow-up work.

```
conformance/sdk/node/
  .node-version      26
  package.json       the five packages, pinned exactly, and the test and typecheck scripts
  package-lock.json  the lock (npm install output)
  tsconfig.json      strict type checking only (noEmit)
  support.ts         the server, the control helpers and the clients
  anthropic.test.ts  openai.test.ts  openai-responses.test.ts  gemini.test.ts
```

The tests are TypeScript, run by Node's type stripping (`node --test`, no build step), with the
built-in `node:test` runner. `npm run typecheck` runs `tsc --noEmit` against the SDKs' own type
definitions, so a change in an SDK's API fails at check time. Each test file starts its own server
and the files run one at a time. With Node 26, from `conformance/sdk/node/`:

```bash
npm ci --ignore-scripts
npm run typecheck
(cd ../../.. && ./gradlew shadowJar)
FAKE_LLM_COMMAND="java -jar $PWD/../../../build/libs/fake-llm-all.jar" npm test
# or against a running strict server with no control token:
FAKE_LLM_BASE_URL=http://localhost:8080 npm test
```

`npm test` prints a spec report and writes a JUnit report to `reports/<FAKE_LLM_REPORT>.xml`
(`reports/local.xml` by default). `--ignore-scripts` skips two install scripts that only print a
message (`@google/genai`'s `preinstall` and `protobufjs`'s `postinstall`); the suite needs neither.

**Retries.** As in the Python suite, the Anthropic and OpenAI clients keep their default retries,
and a retryable error step carries `retry-after-ms: 10`. `@google/genai` does not retry by default
either (it retries only when given `retryOptions`), and `default client does not retry` pins that
down.

**Bumping a pin.** From `conformance/sdk/node/`, run `npm install --ignore-scripts --save-exact
<name>@<version>` and commit `package.json` and `package-lock.json`. Dependabot does this weekly
for all five packages in one pull request, except `@types/node` majors, which follow
`.node-version` and move by hand with it.

**Latest releases.** The SDK latest workflow also installs the newest `@anthropic-ai/sdk`, `openai`
and `@google/genai` over the lock and runs the suite, without `typecheck`: a type-only change in a
new release does not fail the run if the behaviour is fine.

A new SDK test, in either language, needs a feature the server already supports; a missing feature
is its own issue.
