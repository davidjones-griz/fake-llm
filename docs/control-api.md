# Control API

A running fake-llm server is driven over HTTP: load a scenario, start it over, and read back what the app sent. The contract for these routes, `/health` and `/openapi` is the OpenAPI 3.1 document [openapi/fake-llm.yaml](https://github.com/davidjones-griz/fake-llm/blob/main/openapi/fake-llm.yaml), which this page summarises.

Clients should ignore response fields they do not know, since a later version may add fields. Request bodies are strict: `POST /control/scenario` rejects unknown fields, as does the scenario format. An error's `message` is a diagnostic, not part of the contract; its `fake_llm_error` code is part of it (see [fake-llm's error responses](endpoints.md#fake-llms-error-responses)).

## Control token

When `FAKE_LLM_CONTROL_TOKEN` is set to a non-blank value, every path under `/control/` requires an `Authorization` header whose value is exactly `Bearer <token>`. A missing or different header is HTTP 401 `unauthorized`, with `WWW-Authenticate: Bearer`, and a message that says how to send the token:

```json
{"fake_llm_error":"unauthorized","message":"unauthorized: missing or invalid Authorization header. Send Authorization: Bearer <token> on every /control/ request, where <token> is the server's FAKE_LLM_CONTROL_TOKEN."}
```

Vendor routes and `/health` stay open. With no token configured, the control routes are open. The environment variable is read first. A JVM system property of the same name is used only when the environment variable is absent. A blank environment variable counts as unset and does not fall through to the system property.

With curl, pass the token on each control call when one is configured:

```bash
curl -s http://localhost:8080/control/calls \
  -H "authorization: Bearer $FAKE_LLM_CONTROL_TOKEN"
```

From Kotlin or Java, [`FakeLlmClient`](library.md#control-api-client) makes these calls and parses the responses, and the [pytest plugin](pytest.md) has a Python client.

## Routes

### `GET /health`

Always open, including when a control token is set. Reports liveness, the loaded scenario name or `null` when none is loaded, whether lenient mode is on, the scenario's step count and unused steps, and the server's version.

```json
{"status":"ok","scenario":"hello","lenient":false,"steps":1,"unusedSteps":[1],"version":"0.6.0"}
```

The version varies with the release. `steps` is the loaded scenario's step count, and `unusedSteps` lists the steps no call has used since the last load, reset or unload (both `null` / `[]` with no scenario). A test can assert `unusedSteps` is empty to check its app made every scripted call. The JUnit extension makes the same check in process after each test.

### `GET /openapi`

The OpenAPI document for the running version, as `application/yaml`. It needs no token, so a client in any language can read the contract or generate code from it. The document also lists the vendor routes and links to each provider's own reference.

### `POST /control/scenario`

`POST /control/scenario` loads a scenario in the running process and starts it over: the call log and the exchange log are emptied, and the next call is call 1 and gets step 1. The body is a JSON object with exactly one of `name` or `scenario`:

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

Sending both `name` and `scenario`, or neither, is HTTP 400 `invalid-request`, and a body that is not a JSON object is `invalid-json`. A name that finds no scenario is `unknown-scenario`, and an illegal name or a scenario the decoder rejects is `invalid-scenario`, with the reason in `message`: `{"fake_llm_error":"unknown-scenario","message":"unknown scenario 'x' — ..."}`. [Endpoints](endpoints.md#fake-llms-error-responses) lists every code.

### `GET /control/scenarios`

`GET /control/scenarios` returns the names the loader can see:

```json
{"scenarios":["fail-then-retry","hello","malformed-json","max-tokens-cutoff","overloaded-529","rate-limit-429","refusal","stream-error-midway","thinking-stream"]}
```

With a scenario directory set, `*.json` names from that directory are merged in, and the list is sorted again.

### `POST /control/reset`

`POST /control/reset` starts the loaded scenario over, as loading it again would: the call log and the exchange log are emptied, and the next call is call 1 and gets step 1, with response IDs from `fake-msg-1` again. The body is ignored.

```json
{"reset":true}
```

### `POST /control/unload`

`POST /control/unload` removes the loaded scenario, as `ScenarioEngine.unload()` does. The call log and the exchange log are emptied, and the next call is call 1. Until a scenario is loaded again, a call gets the `no scenario loaded` 400, or the canned reply on a lenient server, and `GET /health` reports `"scenario": null`. With no scenario loaded, it succeeds, and still empties both logs and restarts numbering. The body is ignored. Use it between tests that share one server, so that each one starts like a server started without a scenario.

```json
{"unloaded":true}
```

### `GET /control/calls`

`GET /control/calls` returns the call log as a JSON array, in call order. `vendor` is `anthropic`, `openai`, `openai-responses` or `gemini`. `seq` is the call's number since the last load, reset or unload, from 1. `step` is the step that served it, equal to `seq`, or `null` when no step did. `outcome` is `text`, `tool-calls` (a step with `toolCalls`) or `error:<status>` for a step, `default` for the lenient canned reply, and `no-more-steps` or `no-scenario` for the two 400s. `promptSample` is the last 500 characters of the combined prompt text. `timestampMs` is the wall-clock time when the call arrived, before any wait. `toolCalls` lists the id and name of each tool call the response carried, and `toolResults` each tool result the request sent back, both `[]` when there are none; [Tool calls](scenarios.md#tool-calls) describes them. `firstByteDelayMs` and `streamInterChunkDelayMs` are the delays the call's step applied: `streamInterChunkDelayMs` only when the response was an event stream, and both 0 when no step served the call. The CLI and the image also print each call's `seq`, vendor, model, outcome and step as a line on stdout (see [Configuration](https://github.com/davidjones-griz/fake-llm#configuration)).

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
    "promptSample": "Hello\n",
    "toolCalls": [],
    "toolResults": [],
    "firstByteDelayMs": 0,
    "streamInterChunkDelayMs": 0
  }
]
```

### `GET /control/exchanges`

`GET /control/exchanges` returns the exchange log: one entry per call, in `seq` order, matching `GET /control/calls` entry for entry, with the request as fake-llm received it and the response as fake-llm wrote it. Each entry has `seq`, `request` and `response`:

- `request.method` is the HTTP method, and `request.path` the raw path, then `?` and the raw query when there is one, still percent-encoded.
- `request.headers` has every request header, names lowercased and sorted, each with its values in the order received.
- `request.body` is the raw request body, decoded as UTF-8 and not re-serialized, so whitespace and key order are kept.
- Secrets are redacted: each value of the `authorization`, `proxy-authorization`, `x-api-key`, `x-goog-api-key` and `api-key` headers is recorded as `[redacted]`, and so is the value of a query parameter named exactly `key` (`key=[redacted]`).
- `response.status` is the status fake-llm sent, or started to send, and `response.headers` the headers fake-llm set, in the same form, without the `Date`, `Content-length` and `Transfer-encoding` headers the server adds as it sends.
- `response.body` is every byte written to the response body, in order: the JSON text, or the whole event stream, every frame, including a mid-stream error frame and OpenAI's `data: [DONE]`.
- `response.state` is `open` while the body is still being written (`body` holds what has been written so far), `complete` once fake-llm finished the response and closed the body normally, and `aborted` when sending the headers, or a write, flush or close, failed (usually because the client disconnected), or the server stopped mid-response. An `aborted` body holds the bytes written before the failure; the client may have received fewer. A server stopped while a call waits out its `firstByteDelayMs` records the `exception` 400 it tried to send, `aborted`, while the call log still names the step.
- `response` is `null` until fake-llm starts sending the response, for example during a step's `firstByteDelayMs` wait.

Requests that are not calls (the 400s other than `no-more-steps` and `no-scenario`, 404s, the Batches 501, the embeddings routes, token counting and the model lists, and health and control requests) are not listed. Bodies are not capped. The log is emptied with the call log by a load, a reset or an unload.

```json
[
  {
    "seq": 1,
    "request": {
      "method": "POST",
      "path": "/anthropic/v1/messages",
      "headers": {
        "content-length": ["88"],
        "content-type": ["application/json"],
        "host": ["127.0.0.1:8080"],
        "x-api-key": ["[redacted]"]
      },
      "body": "{\"model\":\"example-model\",\"max_tokens\":64,\"messages\":[{\"role\":\"user\",\"content\":\"Hello\"}]}"
    },
    "response": {
      "status": 200,
      "headers": { "content-type": ["application/json"] },
      "body": "{\"id\":\"fake-msg-1\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"example-model\",\"content\":[{\"type\":\"text\",\"text\":\"Hi.\"}],…}",
      "state": "complete"
    }
  }
]
```
