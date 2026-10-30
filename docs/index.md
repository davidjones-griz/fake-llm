# fake-llm

fake-llm is a local HTTP server that answers the Anthropic Messages, OpenAI Chat Completions and Responses, and Gemini `generateContent` and `streamGenerateContent` APIs from a script. An app under test points its SDK at fake-llm instead of the provider, and each call gets the next step of a JSON scenario: a reply, a tool call, or a scripted failure.

The code that calls a model is deterministic; the model is the only part a test cannot predict. A scenario takes that part away. Call N gets step N, locally and with no model call, including the failure paths: an error status, a cutoff in the middle of a stream, `max_tokens` truncation, malformed output, a tool call with valid or malformed arguments, and a failure followed by a successful retry. The server records every call, so a test can check what the app sent as well as what it did with the reply.

A call the scenario has no step for fails: a call after the last step returns HTTP 400 (`no more steps`), and so does a call with no scenario loaded, unless the server runs in lenient mode.

## Where to go next

- [Quickstart](quickstart.md): run the released Docker image, load a scenario and make a call, then point the Anthropic, OpenAI and Gemini SDKs at it.
- [Scenario reference](scenarios.md): the scenario format, how calls map to steps, failure injection, latency, tool calls and every field.
- [Control API](control-api.md): loading scenarios, starting them over, and reading the call and exchange logs over HTTP.
- [Recipes](recipes/index.md): tests for retries, truncated output, malformed JSON, mid-stream failures, and the errors fake-llm itself returns.

fake-llm is also a Kotlin library, with a JUnit Jupiter extension and a Testcontainers module, and has a pytest plugin for Python tests; the [README](https://github.com/davidjones-griz/fake-llm#readme) describes them.
