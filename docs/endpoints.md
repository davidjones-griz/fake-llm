# Endpoints

fake-llm serves the vendor routes below. Each generation route answers from the loaded scenario: call N, on any route, gets step N, or the step its [match](scenarios.md#parallel-calls) picks (see the [scenario reference](scenarios.md)). Embeddings, token counting and the model lists are not calls: they answer from the request alone. The control routes, `/health` and `/openapi` are on the [control API](control-api.md) page, and the OpenAPI document, [openapi/fake-llm.yaml](https://github.com/davidjones-griz/fake-llm/blob/main/openapi/fake-llm.yaml), lists every route with a link to the provider's own reference.

Every generation reply names the request's model, as the providers do: `model` on a buffered Anthropic message and the stream's `message_start`, on OpenAI Chat Completions and Responses bodies, chunks and stream events, and `modelVersion` on Gemini bodies and chunks. A request without a string `model` gets `""` back.

Anything that matches none of the routes below is HTTP 404 `no-route`:

```json
{"fake_llm_error":"no-route","message":"no route: GET /nope"}
```

A request fake-llm can't serve gets one of [fake-llm's error responses](#fake-llms-error-responses), described below.

## fake-llm's error responses

Every error fake-llm sends itself, whatever its status, has the same structure: a stable error code and a message.

```json
{"fake_llm_error":"invalid-request","message":"request has no messages array"}
```

| Field | Meaning |
| --- | --- |
| `fake_llm_error` | The error code, from the table below. It is part of the contract, so a test can assert on it. A body that has it is fake-llm's own error, not a step's scripted error. |
| `message` | Why the request failed, for a person to read. It is a diagnostic, not part of the contract, and its wording can change. |
| other fields | `no-more-steps`, `no-scenario` and `no-match` also carry the call's fields; see [Scenario files](scenarios.md#scenario-files). |

| Code | Status | When | A call? |
| --- | --- | --- | --- |
| `no-more-steps` | 400 | A call after the loaded scenario's last step, in strict and lenient mode. The body also has `scenario`, `steps`, `seq`, `vendor`, `model`, `system_shape`, `message_count` and `prompt_preview`. | Yes |
| `no-scenario` | 400 | A call with no scenario loaded, in strict mode (lenient mode serves the canned reply). The body has the same call fields as `no-more-steps`, without `scenario` and `steps`. | Yes |
| `no-match` | 400 | A call that matched none of the unused steps it could have taken (see [Parallel calls](scenarios.md#parallel-calls)), in strict and lenient mode. It uses nothing. The body has the same call fields as `no-scenario`, and the message lists the steps with their rules. | Yes |
| `invalid-json` | 400 | The request body is not JSON, or not a JSON object, on any route that reads a body. | No |
| `invalid-request` | 400 | Valid JSON the route rejects: no `messages` or `contents` array, an invalid `cache_control`, a Gemini `streamGenerateContent` request without `alt=sse`, an embeddings request outside the rules, a count_tokens body the Messages route rejects, or a `POST /control/scenario` body that has an unknown field, lacks exactly one of `name` and `scenario`, or has a `name` that is not a non-blank string or a `scenario` that is not an object. | No |
| `unsupported-step` | 400 | The step the call chose has a tool call with `rawArguments` that the request's format can't carry: a buffered Anthropic request, or a Gemini request. The step stays unused. | No |
| `unknown-scenario` | 400 | A load by name found no such scenario. | No |
| `invalid-scenario` | 400 | A scenario failed to load: an illegal name, a scenario that doesn't decode or validate, or a file whose declared name differs from its file name. | No |
| `exception` | 400 | Any other failure. | No |
| `unauthorized` | 401 | A `/control/` request without the configured control token, or with a different one; see [Control token](control-api.md#control-token). The message says how to send it, and the response has `WWW-Authenticate: Bearer`. | No |
| `no-route` | 404 | A method and path that no route serves, including a known path with another method. The message names the method and path. | No |
| `not-implemented` | 501 | Any method on the Anthropic Batches API, `/anthropic/v1/messages/batches` and the paths under it. | No |
| `openapi-missing` | 500 | `GET /openapi` on a server built without its OpenAPI document, a packaging fault. | No |

A request that gets a 400 and is not a call takes no step and no call-log or exchange-log entry. A streamed request that gets a 400 gets it as JSON, with no SSE bytes.

## Requests that are not calls

### Embeddings

`POST /openai/v1/embeddings`, `POST /gemini/…:embedContent` and `POST /gemini/…:batchEmbedContents` answer with fake vectors derived from the text, so a pipeline that embeds text can run against fake-llm with no second fake. `google-genai`'s `embed_content` calls `batchEmbedContents`, for one input or many.

- **Not calls.** They answer from the request alone: no step, no call-log or exchange-log entry, no `firstByteDelayMs`, and the same answer with or without a scenario and in strict or lenient mode. Adding embedding requests to an app does not renumber its scenario.
- **The vector.** Component i comes from SHA-256 of the UTF-8 text plus the block number `i / 8` (4 bytes, big-endian): the signed big-endian 32-bit integer at offset `(i % 8) * 4`, over 2^31. The vector is then divided by its length, so it has unit length, and stored as float32. The same text and size give the same vector on every run and platform, on both vendors; different texts give unrelated vectors. There is no semantic similarity.
- **Size.** `dimensions` (OpenAI) or `outputDimensionality` (Gemini, per request), an integer from 1 to 8192, 1536 by default. Anything else, such as `0`, `"8"` or `8.0`, gets the `invalid-request` 400.
- **OpenAI.** `input` is a string, an array of strings, one token array or an array of token arrays; a token array is hashed as its JSON text (`[1,2,3]`). An empty string, an empty array or a mixed array gets the 400. `encoding_format` is `float` (the default) or `base64` (little-endian float32, which the `openai` SDK asks for and decodes when you don't set it). Each JSON number is the shortest decimal that reads back as the float32's exact value, such as `0.10000000149011612` for the float32 nearest 0.1, so both forms give the same values. `model` is echoed, and `usage` is an estimate: a quarter of each string's code points, rounded up (at least 1), or a token array's length.
- **Gemini.** The text is the content's parts' `text` joined with a newline. `taskType`, `title`, `role` and the models in the path and the requests are ignored.
- **Limits.** At most 2048 inputs per OpenAI request (OpenAI's own limit), and at most 3,145,728 values (2048 × 1536) in one response on any route, each input's size counted. Both are checked before any vector is built; over either is the 400.

```python
vectors = client.embeddings.create(model="example-embedding", input=["a", "b"], dimensions=8)
```

### Token counting and model lists

These requests answer fixed bodies, so an app that counts tokens or checks its model before a call runs unchanged against fake-llm. Each route's body is under its own heading below.

- **One model.** Each list holds one model, `fake-llm`, in the vendor's format. A lookup answers for any id and echoes it, decoded (an SDK sends a `/` in an id as `%2F`), so an app that checks its configured model exists passes with whatever name it uses.
- **Not calls.** Like the embeddings routes, they take no step, have no call-log or exchange-log entry, and answer the same with or without a scenario and in strict or lenient mode. The count is the fixed 100 even when the next step sets `usage`.
- **Routing.** Query parameters (`limit`, `pageSize`, `pageToken` and so on) are ignored. Another method on these paths, a trailing slash, and a `GET` on a Gemini method path such as `.../models/m:generateContent` are 404.

## Anthropic

### `POST /anthropic/v1/messages`

Anthropic Messages JSON. A reply step with `"stream": true` is answered as `text/event-stream`: `message_start`, content blocks and deltas, `message_delta`, `message_stop`. A thinking step adds a thinking block before the text block, and tool calls add `tool_use` blocks after it (see [Tool calls](scenarios.md#tool-calls)). An error step with `streamErrorAfterChunks` set uses the shorter stream that the [`streamErrorAfterChunks`](scenarios.md#step-fields) step field describes. Any other request, including an error step that leaves `streamErrorAfterChunks` unset, is one buffered response: the reply step as JSON, or the error step as that HTTP status, even when the client set `"stream": true`.

The buffered message carries `id` (`fake-msg-<seq>`), `type`, `role`, `model` (the request's model, as on the stream's `message_start`), `content` (an optional thinking block, the text block when the step has text, then one `tool_use` block per tool call), `stop_reason`, `stop_sequence` (the step's `stopSequence`, or `null`), and `usage`.

A call the scenario has no step for is HTTP 400 with the JSON body described in the [scenario reference](scenarios.md#scenario-files), including when `"stream": true`. That response is `application/json`, and no SSE bytes are written. With no scenario loaded, lenient mode serves the canned reply instead, and `"stream": true` streams it as a text step.

### `POST /anthropic/v1/messages/count_tokens`

`{"input_tokens":100}` for any body the Messages route accepts; the `invalid-request` 400 for one it rejects. Not a call; see [Token counting and model lists](#token-counting-and-model-lists).

### `GET /anthropic/v1/messages/batches`

The Batches API is not implemented. Any method on a path that starts with `/anthropic/v1/messages/batches` returns HTTP 501 `not-implemented`:

```json
{"fake_llm_error":"not-implemented","message":"the Anthropic Batches API is not implemented"}
```

### `POST /anthropic/v1/messages/batches`

HTTP 501, as above.

### `GET /anthropic/v1/messages/batches/{batchId}`

HTTP 501, as above, and so is every deeper path, such as `/results`.

### `GET /anthropic/v1/models`

`{"data":[<model>],"has_more":false,"first_id":"fake-llm","last_id":"fake-llm"}`, where `<model>` is the lookup body below for `fake-llm`.

### `GET /anthropic/v1/models/{modelId}`

`{"type":"model","id":"<modelId>","display_name":"<modelId>","created_at":"1970-01-01T00:00:00Z","lifecycle":"active"}`

## OpenAI

### `POST /openai/v1/chat/completions`

One JSON chat completion: `id` `fake-openai-<seq>`, `object` `chat.completion`, `model` (the request's), one choice with `message.role` `assistant`, `message.content` set to the step text (`null` on a tool-call step without text), `message.tool_calls` on a tool-call step, `finish_reason` as [`stopReason`](scenarios.md#step-fields) maps it, and `usage`. [Tool calls](scenarios.md#tool-calls) describes the tool-call fields and chunks. A call the scenario has no step for is HTTP 400 with the JSON body described in the [scenario reference](scenarios.md#scenario-files), not a chat completion. With no scenario loaded, lenient mode returns the canned reply as a completion.

With `"stream": true`, a reply step is answered as `text/event-stream` of data-only frames (`data: <payload>`, no `event:` line). Each chunk has `id` `fake-openai-<seq>`, `object` `chat.completion.chunk`, `model` (the request's), and one choice with `index`, `delta` and `finish_reason`. The frames are, in order: the role chunk (`delta` `{"role":"assistant","content":""}`, with `content` `null` on a tool-call step without text), one content chunk per `streamChunkChars` code points of the text, the tool-call chunks described under [Tool calls](scenarios.md#tool-calls), the finish chunk (empty `delta`, `finish_reason` as [`stopReason`](scenarios.md#step-fields) maps it), then `data: [DONE]`. When the request sets `stream_options.include_usage` to true (read like `stream`), a usage chunk with no choices comes before `[DONE]`, and every other chunk carries `"usage": null`. An error step with `streamErrorAfterChunks` set sends the role chunk and the filler chunks, then the error body as a data frame (a generic `server_error` body when `body` is blank), and closes the stream with no finish chunk, usage chunk or `[DONE]`. A call the scenario has no step for, and an error step that leaves `streamErrorAfterChunks` unset, get the JSON response, with no SSE bytes.

### `POST /openai/v1/responses`

The OpenAI Responses API. A buffered request gets one `Response`: `id` `resp_fake_<seq>`, `object` `response`, `status`, `error` (null), `incomplete_details`, `model` (the request's), `output`, `parallel_tool_calls`, `tool_choice`, `tools` and `usage`, in that order, with no `created_at`. `output` is the reasoning item `rs_fake_<seq>` (`type` `reasoning`, one `summary_text` part holding the step's `thinking`, no `status`) when the step has `thinking`, the message item `msg_fake_<seq>` (role `assistant`, one `output_text` part) when it has text, then one `function_call` item per tool call (see [Tool calls](scenarios.md#tool-calls)). `max_tokens` makes the response and its message item `incomplete`, with `incomplete_details.reason` `max_output_tokens`; anything else is `completed`. `usage.output_tokens_details.reasoning_tokens` is always 0. A reasoning item sent back in a later request's `input` is accepted and adds no prompt text.

With `"stream": true`, the response is `text/event-stream` of named events (`event: <type>`), each payload starting with `type` and ending with `sequence_number`, numbered from 0. The stream is `response.created` and `response.in_progress` (an in-progress response with empty `output` and null `usage`); for thinking, the reasoning item's `response.output_item.added`, `response.reasoning_summary_part.added`, one `response.reasoning_summary_text.delta` per `streamChunkChars` code points (each after `streamInterChunkDelayMs`), then `response.reasoning_summary_text.done`, `response.reasoning_summary_part.done` and `response.output_item.done`, all at `output_index` 0; for text, the message's `response.output_item.added` and `response.content_part.added`, one `response.output_text.delta` per `streamChunkChars` code points (each after `streamInterChunkDelayMs`), then `response.output_text.done`, `response.content_part.done` and `response.output_item.done`; then each tool call's events, with the message's and the calls' `output_index` one higher after a reasoning item; and last `response.completed`, or `response.incomplete` for `max_tokens`, carrying the buffered response. There is no `[DONE]`.

An error step with `streamErrorAfterChunks` set opens the message, sends the filler deltas, then `event: error` with `error.body` as given, or the API's own error event (`{"type":"error","code":"server_error","message":"scripted stream error","param":null,"sequence_number":<n>}`) when `body` is blank, and closes the stream. The `openai` SDK raises `APIError` (not `APIStatusError`, and not retried) for a body with an `error` key, and yields the generic event without raising. A call the scenario has no step for, and an error step that leaves `streamErrorAfterChunks` unset, get the JSON response, with no SSE bytes.

fake-llm accepts `previous_response_id`, `store` and `conversation` and doesn't resolve or store anything. Each call is the next step by arrival order, like every other call, and the request's own `instructions` and `input` are what is parsed and logged. A `previous_response_id` that fake-llm never issued is not an error. Request fields such as `tools`, `text.format` and `reasoning` are ignored.

```python
response = client.responses.create(model="example-gpt", input="Hello")
print(response.output_text)

with client.responses.stream(model="example-gpt", input="Hello") as stream:
    for event in stream:
        if event.type == "response.output_text.delta":
            print(event.delta, end="")
    final = stream.get_final_response()
```

### `POST /openai/v1/embeddings`

The OpenAI embeddings list; see [Embeddings](#embeddings).

### `GET /openai/v1/models`

`{"object":"list","data":[<model>]}`, where `<model>` is the lookup body below for `fake-llm`.

### `GET /openai/v1/models/{model}`

`{"id":"<model>","object":"model","created":0,"owned_by":"fake-llm"}`

## Gemini

The model id is the path segment between `/models/` and `:`, and `{version}` is any API version segment, such as `v1beta`.

### `GET /gemini/{version}/models`

`{"models":[<model>]}`, with no `nextPageToken`, where `<model>` is the lookup body below for `fake-llm`.

### `GET /gemini/{version}/models/{model}`

`{"name":"models/<model>","version":"fake-llm","displayName":"<model>","supportedGenerationMethods":["generateContent","streamGenerateContent","embedContent","batchEmbedContents"]}`

### `POST /gemini/{version}/models/{model}:generateContent`

One JSON `generateContent` body: one candidate whose parts are the text part, when the step has text, and one `functionCall` part per tool call (see [Tool calls](scenarios.md#tool-calls)), `finishReason` `STOP` or `MAX_TOKENS`, `usageMetadata`, `modelVersion` (the model in the URL), and `fake_llm_seq`. A call the scenario has no step for is HTTP 400 with the JSON body described in the [scenario reference](scenarios.md#scenario-files), not a `generateContent` body. With no scenario loaded, lenient mode returns the canned reply as one candidate.

### `POST /gemini/{version}/models/{model}:streamGenerateContent`

The same reply as a server-sent event stream, served only with `alt=sse` in the query string, which the `google-genai` SDK always sends. Without `alt=sse` the request gets the `invalid-request` 400, whatever its body (the query is checked before the body is read), and is not a call: it takes no number, and the step stays next. The JSON-array form the real API sends without `alt=sse` is not served.

The response is `text/event-stream` of data-only frames (`data: <payload>`, no `event:` line), with no `[DONE]`. Each frame is one `GenerateContentResponse` whose candidate has exactly one part: first one text part per `streamChunkChars` code points of the text (each after `streamInterChunkDelayMs`), then each tool call as a whole `functionCall` part, as in the buffered body. Only the last chunk carries `finishReason` and `usageMetadata`; every chunk carries `modelVersion` and `fake_llm_seq`. A step with no text and no tool calls is one last chunk with an empty text part. An error step with `streamErrorAfterChunks` set sends the filler chunks, then the error body as a data frame (a generic `INTERNAL` body when `body` is blank), with no chunk carrying `finishReason`. A call the scenario has no step for, and an error step that leaves `streamErrorAfterChunks` unset, get the JSON response, with no SSE bytes.

```python
for chunk in client.models.generate_content_stream(model="gemini-2.0-flash", contents="Hello"):
    print(chunk.text, end="")
```

### `POST /gemini/{version}/models/{model}:embedContent`

One embedding; see [Embeddings](#embeddings).

### `POST /gemini/{version}/models/{model}:batchEmbedContents`

One embedding per request in the batch; see [Embeddings](#embeddings). `google-genai`'s `embed_content` calls this route, for one input or many.
