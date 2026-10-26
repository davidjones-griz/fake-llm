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
    data line.

  With none of them, the body is not checked.

### Comparison rules

- **Exact:** the same JSON types, the same object keys, the same array lengths, equal values. An
  expected integer needs an integer in the response: `100.0` does not match `100`.
- **Headers:** names match case-insensitively; values exactly, except that an expected
  `content-type` without parameters matches by media type (`application/json; charset=utf-8`
  passes for `application/json`). Headers a case does not list are ignored.
- **The only matcher** is `{ "$type": "integer" }` or `{ "$type": "string" }`, which accepts any
  value of that type. Cases use it only for values that vary (`timestampMs`) and for the messages
  the spec says are diagnostics, not contract (`detail`, and the `fake_llm_error` text of a 401,
  404 or 501).
- **Timeouts:** each response, body included, must arrive within 10 seconds, so a stream that
  stalls fails its case.
- **Not checked:** a JSON body that repeats a key is read as its last value; the runner does not
  flag the duplicate.

### What the cases make contract

The spec does not describe the vendor routes' success bodies field by field, so the cases define
them, exactly. That includes the values fake-llm fixes:

- token counts: Anthropic `input_tokens` 100 and `output_tokens` 50, and cache tokens 20 (creation)
  and 10 (read) with a `cache_control` breakpoint, 0 and 0 without; OpenAI 100, 50 and 150; Gemini
  100, 50 and 150;
- response IDs from the call number: `fake-msg-<n>`, `fake-openai-<n>`, and Gemini's `fake_llm_seq`;
- OpenAI's `"model": "fake-openai"`, and Anthropic's buffered reply having no `model`;
- the thinking signature `fake-thinking-signature`;
- the streamed-error filler text (`Thinking this through before the connection drops. `, repeated)
  and the generic stream error body for an error step with a blank body;
- the lenient canned reply, `This is a canned response from fake-llm.`;
- the bundled scenario names, and the contents of `hello`.

## Adding a case

1. Add `conformance/cases/<area>/<name>.json`, with `"$schema": "../../schema/case.schema.json"` so
   an editor can check it as you type.
2. Write the expected responses from the spec and the README, not by copying what the server
   happens to send.
3. Run the suite against the fat jar (above) and `./gradlew :test --tests '*OpenApiConformanceTest*'`,
   which checks the case against the spec. A vendor 400 must list its body: one with
   `fake_llm_error` is fake-llm's own and is checked against the spec; any other is a scripted
   error step.
