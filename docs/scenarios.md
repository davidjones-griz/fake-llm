# Scenario reference

A scenario is the script a fake-llm server follows: an ordered list of steps, where call N gets step N. This page describes the scenario file format, how calls map to steps, and how to load a scenario. Every field is listed under [Fields](#fields) at the end.

## Scenario files

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

After the scenario is loaded or reset, call N on any generation route gets step N. Calls are numbered in the order they arrive, so concurrent calls each get a different step. The last step does not repeat: a call after it gets HTTP 400. Every request to a generation route (Anthropic Messages, OpenAI Chat Completions and Responses, Gemini `generateContent` and `streamGenerateContent`) that the route's parser accepts is a call and gets one entry in the call log. A request answered with the `exception` 400 (a body that is not JSON, has no `messages` or `contents` array, or has an invalid `cache_control`, or whose next step has a tool call with `rawArguments` that its format can't carry), a 404, the Batches 501, the [embeddings routes](https://github.com/davidjones-griz/fake-llm/blob/main/README.md#embeddings), and [token counting and the model lists](https://github.com/davidjones-griz/fake-llm/blob/main/README.md#token-counting-and-model-lists) are not calls.

Each step is a reply (`text`, `toolCalls`, or both) or an `error`. When a step in a file or an inline scenario is invalid, the error names its number, from 1, and the path of the field at fault, such as `step 2: $.steps[1]: unknown field 'stopReasn'`. The path is relative to the scenario, including for an inline scenario in `POST /control/scenario`.

The step fields are listed under [Step fields](#step-fields).

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

With no scenario loaded, strict mode (the default) returns HTTP 400 with the same fields except `scenario` and `steps`, and `"fake_llm_error": "no scenario loaded"`. `seq` is the call's number, as in the call log. `prompt_preview` is the first 200 characters of the combined prompt text. The call log records outcome `no-more-steps` or `no-scenario`, with `step` null. A streamed Anthropic, OpenAI (either route) or Gemini request gets the same status and JSON body, and the server writes no SSE bytes.

Lenient mode (`--lenient` or `FAKE_LLM_LENIENT=true`) answers a call with no scenario loaded with one canned assistant reply, `This is a canned response from fake-llm.`, logged with outcome `default`. It is a normal text reply, so a streamed Anthropic, OpenAI (either route) or Gemini request streams it. Lenient mode changes nothing once a scenario is loaded.

The combined prompt text, used by `promptSample` and `prompt_preview`, depends on the dialect:

- Anthropic: the `system` field, when it is a string or an array of text blocks, then each message `content` that is a string or an array of text blocks. A `tool_result` block adds its own `content` the same way: a string, or its text blocks. Other blocks contribute nothing. Each piece is followed by a newline. A `null`, number or boolean contributes nothing.
- OpenAI: each message's `content` when it is a JSON string, or the string `text` of each of its parts when it is an array (`image_url` and other parts without `text` add nothing), whatever the role. A message with role `system` or `developer` sets `systemShape` to `blocks`; otherwise it is `absent`. OpenAI requests never record `string`.
- OpenAI Responses: `instructions` when it is a JSON string, then `input` when it is a JSON string. When `input` is an array, for each item in order: a message item's `content` when it is a string, or the string `text` of each of its content parts (`input_text`, `output_text`); a `function_call_output`'s `output` when it is a string. Other items add nothing, and an item whose `type` is absent or not a string counts as a message. `systemShape` is `string` for string `instructions`, `unknown` for any other non-null `instructions`, and without `instructions` `blocks` when an input item has role `system` or `developer`, else `absent`. `messageCount` is 1 for a string `input`, the number of items for an array (every item counts), and 0 otherwise.
- Gemini: the string `text` of each part of `systemInstruction`, or of `system_instruction` (the REST API's other spelling) when `systemInstruction` is missing or `null`, then of each `contents[].parts[]`. `systemShape` is `blocks` when that system instruction is an object, `absent` when it is missing or `null`, and `unknown` otherwise. The model id comes from the URL, not the body.

## Tool calls

A step's `toolCalls` lists the tool calls its reply makes, after any `text`. Its fields are listed under [ToolCall fields](#toolcall-fields).

Each format renders them as its API does:

- Anthropic: one `tool_use` block per call, after the thinking and text blocks, with `input` set to `arguments`, and `stop_reason` `tool_use`. Streamed, each call is a `tool_use` block start with an empty `input`, the argument text as `input_json_delta` pieces of `streamChunkChars` code points, and a block stop.
- OpenAI: `message.tool_calls`, each with `type` `function` and the argument text as `function.arguments`, and `finish_reason` `tool_calls`. `message.content` is the text, or `null` without text. Streamed, the tool calls come after the content chunks: per call a header chunk with its `index`, `id`, `type` and `name` and empty `arguments`, then its argument text in `streamChunkChars` pieces. Without text, the role chunk's `content` is `null`.
- OpenAI Responses: one `function_call` output item per call, after the message item, with `id` `fc_fake_<seq>_<n>`, `call_id`, `name`, the argument text as `arguments`, and `status` `completed`; the response's `status` is `completed`. Streamed, each call is an `output_item.added` with empty `arguments`, its argument text as `response.function_call_arguments.delta` events of `streamChunkChars` code points, `response.function_call_arguments.done`, and `output_item.done`.
- Gemini: one `functionCall` part per call, after the text part, with `args` set to `arguments`, and `finishReason` `STOP`. Streamed, each call is its own chunk whose only part is that `functionCall`, after the text chunks, and the last chunk carries `finishReason` `STOP`.

`rawArguments` is sent verbatim by OpenAI (both routes), buffered or streamed, and by a streamed Anthropic reply. A buffered Anthropic `tool_use.input` and a Gemini `functionCall.args` must be JSON objects, so a buffered Anthropic request or a Gemini request whose next step sets `rawArguments` gets the `exception` 400. That request is not a call: the step is not used, and the next call gets it.

The request's `tools` and `tool_choice` (and Gemini `tools` and `toolConfig`) are ignored: a step's tool calls are sent whatever tools the request declares.

A tool loop is two steps: the tool call, then the reply after the app sends the result back.

```json
{
  "name": "weather-tool",
  "steps": [
    { "toolCalls": [{ "name": "get_weather", "arguments": { "city": "Paris" } }] },
    { "text": "It is 18 degrees in Paris." }
  ]
}
```

With the OpenAI SDK, the app sends the assistant message back with the tool's result:

```python
first = client.chat.completions.create(model="example-gpt", messages=messages, tools=tools)
message = first.choices[0].message
messages += [message, {"role": "tool", "tool_call_id": message.tool_calls[0].id, "content": "18 degrees"}]
second = client.chat.completions.create(model="example-gpt", messages=messages, tools=tools)
```

`GET /control/calls` then shows both calls, with the tool call the first response carried and the result the second request sent back:

```json
[
  {
    "seq": 1,
    "step": 1,
    "vendor": "openai",
    "model": "example-gpt",
    "systemShape": "absent",
    "messageCount": 1,
    "outcome": "tool-calls",
    "timestampMs": 1710000000000,
    "promptSample": "Hello\n",
    "toolCalls": [{ "id": "call_fake_1_1", "name": "get_weather" }],
    "toolResults": [],
    "firstByteDelayMs": 0,
    "streamInterChunkDelayMs": 0
  },
  {
    "seq": 2,
    "step": 2,
    "vendor": "openai",
    "model": "example-gpt",
    "systemShape": "absent",
    "messageCount": 3,
    "outcome": "text",
    "timestampMs": 1710000000100,
    "promptSample": "Hello\n18 degrees\n",
    "toolCalls": [],
    "toolResults": [{ "id": "call_fake_1_1", "name": "get_weather", "content": "18 degrees", "isError": false }],
    "firstByteDelayMs": 0,
    "streamInterChunkDelayMs": 0
  }
]
```

`toolResults` lists every tool result in the request, in order: Anthropic `tool_result` blocks (`id` from `tool_use_id`, `content` as sent, `isError` when `is_error` is `true`), OpenAI `tool` messages (`id` from `tool_call_id`, `content` as sent), OpenAI Responses `function_call_output` items (`id` from `call_id`, `output` as `content`, named after the `function_call` item with the same `call_id`), and Gemini `functionResponse` parts (`id`, `name`, and `response` as `content`). For Anthropic and OpenAI Chat Completions, `name` is the name of the tool call in the same request with the matching id, or `null` when there is none. A multi-turn request resends its history, so a later call lists the earlier results again. The fake records what was sent; the test decides what is right.

## Latency and timeouts

Two step fields add latency. `firstByteDelayMs` holds back the whole response: the server waits that long before sending anything, status and headers included, on every generation route, for a buffered or streamed reply and for an error step. On a stream, `streamInterChunkDelayMs` then waits before each delta (each text, thinking and tool-call argument delta; a Gemini `functionCall` chunk has no wait); a buffered response ignores it. Both are 0 by default and at most 600000 (10 minutes).

Only a step's own fields wait. The responses that come from no step never wait: the no-more-steps and no-scenario 400s, the lenient canned reply, and the `exception` 400s, 401s, 404s and the Batches 501.

A call is numbered and logged when it arrives, before the wait, and the wait runs outside the engine's lock. Other calls are served while one waits, so a later call can finish first, and a reset, load or unload during the wait doesn't change the waiting call's response. The call log records the delays each call used (`firstByteDelayMs`, and `streamInterChunkDelayMs` only when the response was an event stream: a streamed request answered by a reply, or by an error step with `streamErrorAfterChunks`), not measured time.

A client whose timeout is shorter than `firstByteDelayMs` times out. Its call was logged once, when it arrived, and stays in the log. The server finishes the wait (it does not stop early), its response goes nowhere, and the next request is served normally with the next step. A client that retries the request, such as the `openai` SDK with its default `max_retries`, makes a new call, which gets the next step. A client that disconnects between streamed chunks is handled the same way.

```json
{ "name": "slow-provider", "steps": [{ "text": "slow", "firstByteDelayMs": 5000 }] }
```

With that scenario, an `openai` client created with `timeout=1.0` and `max_retries=0` raises `openai.APITimeoutError`.

## Writing scenarios for tests

- Each test owns its scenario, even when that means repeating steps another scenario also has. Changing a scenario then affects only the test that owns it. Name a scenario file after its test, or build it inline with `loadInline`.
- Script one step per call, in the order the app makes its calls, including every retry and error. A call the scenario has no step for fails with `no more steps`, and with the JUnit extension a step no call used fails the test, so a missing or extra call shows up in the test. Without the extension, check `engine.unusedSteps` is empty at the end of the test. Over HTTP, check that `GET /health`'s `unusedSteps` is empty.
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

For a whole pipeline, see `TicketPipelineTest` in [examples/](https://github.com/davidjones-griz/fake-llm/blob/main/examples/). `TicketPipeline` makes three calls, each to a different vendor's API: it classifies a support ticket (Anthropic), extracts its details with a forced tool call (OpenAI Chat Completions), and streams a summary (Gemini). Each test's scenario file scripts every call in order, including a rate limit, tool-call arguments that are not valid JSON, a summary cut off at `max_tokens`, and a call after the last step.

## Scenario schema and `validate`

[schema/scenario.schema.json](https://github.com/davidjones-griz/fake-llm/blob/main/schema/scenario.schema.json) is a JSON Schema (2020-12) for scenario files, generated from the [OpenAPI spec](https://github.com/davidjones-griz/fake-llm/blob/main/openapi/fake-llm.yaml)'s `Scenario` schema. A top-level `"$schema"` key pointing at it, as in the example above, lets VS Code and IntelliJ check a scenario file and complete its field names as you type. The server ignores the key; it is allowed only at the top level, not in a step. JSON Schema counts `24.0` and `2e1` as integers, so an editor accepts them; the server and `validate` reject them. The URL follows `main`; inside a repository that has a copy of the schema, a relative path such as `"../schema/scenario.schema.json"` also works, offline too.

`validate` checks scenario files without starting a server, with the same checks as a load by name, including that the declared `name` matches the file name (so a file meant only for an inline load is reported if its name differs). A directory means its `*.json` files, not its subdirectories:

```bash
java -jar build/libs/fake-llm-all.jar validate src/test/scenarios
docker run --rm -v "$PWD/scenarios:/scenarios:ro" ghcr.io/davidjones-griz/fake-llm:0.5.0 validate /scenarios
```

Each valid file gets an `ok <path>` line, and each invalid one a `<path>: <problem>` line on stderr, such as `scenarios/weather.json: step 2: $.steps[1]: unknown field 'stopReasn'`. Every file is checked, with the first problem in each reported. The exit code is 0 when every file is valid, 1 when any is invalid, and 2 for a usage error: no paths, a path that does not exist, a directory with no `.json` files, or a flag. (The server's own usage errors exit 1.)

## Bundled scenarios

| Name | What it does |
| --- | --- |
| `hello` | One step: a short greeting. |
| `rate-limit-429` | Call 1 is HTTP 429 with a `retry-after: 2` header. Call 2 is a weather sentence. |
| `overloaded-529` | Call 1 is HTTP 529. Call 2 is a short support reply. A streamed request on any route still gets HTTP 529, because the error step leaves `streamErrorAfterChunks` unset. |
| `refusal` | One text reply with `stopReason` `refusal`. |
| `max-tokens-cutoff` | One text reply cut off inside a JSON value, with `stopReason` `max_tokens`. |
| `malformed-json` | Call 1 is prose with a broken JSON fragment. Call 2 is a valid JSON object. |
| `stream-error-midway` | Anthropic and OpenAI streaming write three 8-code-point filler deltas, then the error body: an `overloaded_error` event for Anthropic, a data frame for OpenAI (the `openai` SDK raises `APIError`, because the body has an `error` key). Gemini streaming writes the same three filler chunks, then that body as a data frame. Its first key is not `error`, so the `google-genai` SDK does not raise: it yields one more chunk with no candidates, and the stream ends. OpenAI Responses streaming writes the same three filler deltas, then that body as an `error` event; the body has an `error` key, so the `openai` SDK raises `APIError`. A non-streaming client receives that error as HTTP 529. |
| `thinking-stream` | A text reply with a thinking trace. Anthropic and OpenAI Responses streaming split both into 8-code-point chunks and wait 20 ms between chunks. |
| `fail-then-retry` | Call 1 is HTTP 500. Call 2 is a normal text reply. |

The bundled scenarios are for trying the server and for the examples in these docs. A test suite should keep its own scenarios, as above.

## Loading a scenario

At startup, `--scenario <name>` overrides `FAKE_LLM_SCENARIO`. With neither set, the process starts with no scenario. A generation request then returns HTTP 400 until a scenario is loaded, unless the process is lenient, in which case it serves the canned reply.

`--scenario-dir <path>` overrides `FAKE_LLM_SCENARIO_DIR`. A name is read from `<dir>/<name>.json` when that file exists, and otherwise from the bundled scenarios. `GET /control/scenarios` lists the `*.json` stems in that directory plus the bundled names. The combined list is deduplicated and sorted. (In-process use can add your project's classpath scenarios; see [Use as a library](https://github.com/davidjones-griz/fake-llm/blob/main/README.md#use-as-a-library).)

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

Sending both `name` and `scenario`, or neither, is HTTP 400. A bad name or a body the decoder rejects is HTTP 400 with `{"fake_llm_error":"exception","detail":"..."}`.

## Fields

Types are JSON types. Integers are plain JSON integers. A field with no default is optional unless it says required.

### Scenario fields

| Field | Type | Default | Meaning |
| --- | --- | --- | --- |
| `name` | string | required | The scenario's name, `[A-Za-z0-9_-]+` for a load by name, where it must equal the file name. |
| `description` | string | `""` | What the scenario is for. |
| `steps` | array of [Step](#step-fields), at least one | required | The replies, in call order: call N gets step N. |

A top-level `$schema` key is allowed and ignored; see [Scenario schema and `validate`](#scenario-schema-and-validate).

### Step fields

Each step is a reply (`text`, `toolCalls`, or both) or an `error`.

| Field | Type | Default | Meaning |
| --- | --- | --- | --- |
| `text` | string or null | none | Assistant text copied into the vendor response. Optional on a step with `toolCalls`. |
| `thinking` | string or null | none | Optional thinking trace. Legal only on a reply step. Anthropic includes it as a thinking block (buffered) or as thinking deltas (streaming), and OpenAI Responses as a `reasoning` output item `rs_fake_<seq>` with one `summary_text` part (streamed as `response.reasoning_summary_text.delta` events), before the text and tool calls. Anthropic's signature is `fake-thinking-signature`. OpenAI Chat Completions and Gemini ignore it. |
| `stopReason` | string | `end_turn` | Stop reason for a reply. Default `end_turn`. Anthropic copies it to `stop_reason`. `"max_tokens"` means the reply was truncated: OpenAI then reports `finish_reason` `length`, and Gemini reports `finishReason` `MAX_TOKENS`. `"stop_sequence"` reports a matched stop sequence; set `stopSequence` to say which. Any other value becomes OpenAI `stop` and Gemini `STOP`. With `toolCalls` it must be `end_turn` or `tool_use`, and is sent as Anthropic `tool_use`, OpenAI `tool_calls` and Gemini `STOP`. OpenAI Responses reports `max_tokens` as `status` `incomplete` with `incomplete_details.reason` `max_output_tokens`, and any other value as `completed`. A tool-call step is `completed`. |
| `stopSequence` | string, not empty, or null | none | The stop sequence that ended the reply. Only with `stopReason` `stop_sequence`, and not empty. Anthropic sends it as `stop_sequence` (buffered, and in the stream's `message_delta`); without it, `stop_sequence` is `null`. Other formats ignore it. The text is sent as written, so write it as it would be up to the sequence. |
| `error` | [ErrorStep](#errorstep-fields) or null | none | A scripted HTTP error instead of a reply. The response is `Content-Type: application/json` unless `headers` sets a `content-type`, which replaces it. |
| `streamChunkChars` | integer, 1 or more | 24 | SSE delta size in Unicode code points, not UTF-16 chars. Default 24. Must be positive. Used only for Anthropic, OpenAI Chat Completions and Responses, and Gemini streaming. A buffered request ignores it. |
| `streamInterChunkDelayMs` | integer, 0 to 600000 | 0 | Milliseconds to wait before each streamed delta. Default 0. From 0 to 600000 (10 minutes). Anthropic, OpenAI Chat Completions and Responses, and Gemini streaming; a Gemini `functionCall` chunk has no wait. |
| `streamErrorAfterChunks` | integer, 1 or more, or null | none | Legal only on an error step, and must be positive. Anthropic, OpenAI Chat Completions and Responses, and Gemini streaming. Null, the default, means the error is a plain HTTP response and no SSE bytes are written, including when the client asked for a stream. Setting it writes HTTP 200 and an event stream. Anthropic: `message_start`, a text block start, this many filler deltas, then `event: error` whose data is `error.body` (or a generic `api_error` body when `body` is blank); `content_block_stop`, `message_delta`, and `message_stop` are skipped. OpenAI: the role chunk, this many filler chunks, then a `data:` frame whose payload is `error.body` (or a generic `server_error` body when `body` is blank); no finish chunk, usage chunk or `[DONE]` follows. Gemini: this many filler chunks, then a `data:` frame whose payload is `error.body`, or a generic `INTERNAL` body with `code` 500 when `body` is blank. No chunk with `finishReason` follows. The `google-genai` SDK raises on that frame only when the body's first key is `error`, and picks `ClientError` or `ServerError` from `error.code`, which must be an integer. OpenAI Responses: `response.created`, `response.in_progress`, the message's opening events, this many filler `response.output_text.delta` events, then `event: error` whose data is `error.body`, or the API's own `error` event (`code` `server_error`) when `body` is blank. The `openai` SDK raises `APIError` only for a body with an `error` key; it yields the generic event without raising. `error.status` and `error.headers` are not used on that path. A non-streaming client still receives `error.status` and `error.body`. The filler is fixed text, not the error body. |
| `firstByteDelayMs` | integer, 0 to 600000 | 0 | Milliseconds to wait before any response bytes, status and headers included. Default 0. From 0 to 600000 (10 minutes). Every generation route: buffered and streamed replies and error steps. The call is numbered and logged when it arrives, before the wait. Use it to test client timeouts and slow-first-token handling. See [Latency and timeouts](#latency-and-timeouts). |
| `toolCalls` | array of [ToolCall](#toolcall-fields), at least one, or null | none | Tool calls the reply makes, in order, after any `text`. See [Tool calls](#tool-calls). |
| `usage` | [TokenUsage](#tokenusage-fields) or null | none (100 input, 50 output) | Token counts the reply reports. Legal only on a reply step. Every format, buffered and streamed, reports them where it reports usage; totals are the sum. The Anthropic cache figures don't change. |

### ErrorStep fields

| Field | Type | Default | Meaning |
| --- | --- | --- | --- |
| `status` | integer, 100 to 599 | required | The HTTP status. |
| `body` | string | `""` | The response body, sent as given. |
| `headers` | object of strings | none | Headers added to the response as given, for example `retry-after`. A `content-type` replaces the default `application/json`. |

### ToolCall fields

| Field | Type | Default | Meaning |
| --- | --- | --- | --- |
| `name` | string, not empty | required | The tool's name, sent as given: it need not match a tool the request declared. |
| `arguments` | object or null | `{}` | The arguments as a JSON object, sent as given. With `rawArguments` also unset, `{}`. |
| `rawArguments` | string or null | none | The argument text, sent verbatim, so it can be invalid JSON. Set at most one of `arguments` and `rawArguments`. |
| `id` | string, not empty, or null | generated | The call's id, not empty. By default `toolu_fake_<seq>_<n>` for Anthropic and `call_fake_<seq>_<n>` for OpenAI (sent as `call_id` on Responses), where `seq` is the call's number and `n` the tool call's 1-based position in the step. Gemini sends an id only when one is set. |

### TokenUsage fields

| Field | Type | Default | Meaning |
| --- | --- | --- | --- |
| `inputTokens` | integer, 0 to 2147483647 | 100 | The input token count the reply reports. |
| `outputTokens` | integer, 0 to 2147483647 | 50 | The output token count the reply reports. |
