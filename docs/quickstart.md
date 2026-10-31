# Quickstart

You need Docker and curl. The image is on GHCR, for linux/amd64 and linux/arm64.

## Run it, load a scenario, make a call

```bash
--8<-- "docs/quickstart/quickstart.sh"
```

Apart from the container ID that `docker run -d` prints and the name `docker stop` prints, each step prints one line. `/health` reports that the server is up with no scenario loaded:

```json
{"status":"ok","scenario":null,"lenient":false,"steps":null,"unusedSteps":[],"version":"0.6.0"}
```

Loading `hello` reports its name and step count:

```json
{"loaded":"hello","steps":1}
```

The call gets step 1, the scenario's greeting, as an Anthropic message (`model` is the request's):

```json
{"id":"fake-msg-1","type":"message","role":"assistant","model":"example-model","content":[{"type":"text","text":"Hello. I can help with the weather, a simple recipe, or a short how-to."}],"stop_reason":"end_turn","stop_sequence":null,"usage":{"input_tokens":100,"output_tokens":50,"cache_read_input_tokens":0,"cache_creation_input_tokens":0}}
```

The call log has one entry: which route was called, with which model, what the app sent (`promptSample`), and which step answered it:

```json
[{"seq":1,"vendor":"anthropic","model":"example-model","systemShape":"absent","messageCount":1,"outcome":"text","timestampMs":1710000000000,"promptSample":"Hello\n","step":1,"toolCalls":[],"toolResults":[],"firstByteDelayMs":0,"streamInterChunkDelayMs":0}]
```

`hello` has one step, so a second call would get HTTP 400 (`no-more-steps`). `POST /control/reset` starts the scenario over at step 1. The server does not check API keys; any value satisfies a client that insists on sending one.

To keep the server in the foreground and load a scenario at startup instead:

```bash
docker run --rm -p 8080:8080 ghcr.io/davidjones-griz/fake-llm:0.6.0 --scenario hello
```

## From a checkout

To build the image from a checkout instead, which needs BuildKit, `docker build`'s default since Docker 23:

```bash
docker build -t fake-llm .
docker run -p 8080:8080 fake-llm --scenario hello
```

The same process without Docker, on Java 21 or newer:

```bash
./gradlew run --args='--scenario hello'
```

or, after `./gradlew shadowJar` (also produced by `./gradlew build`):

```bash
java -jar build/libs/fake-llm-all.jar --scenario hello
```

The listen port is 8080 unless you set `--port` or `PORT`; [Configuration](https://github.com/davidjones-griz/fake-llm#configuration) lists every flag and environment variable.

## Point an SDK at it

The Anthropic Python SDK appends `/v1/messages`, so the base URL stops at `/anthropic`:

```python
import anthropic

client = anthropic.Anthropic(api_key="not-used", base_url="http://localhost:8080/anthropic")
message = client.messages.create(
    model="example-model",
    max_tokens=256,
    messages=[{"role": "user", "content": "Hello"}],
)
```

The OpenAI Python SDK appends `/chat/completions` (or `/responses`) to `/openai/v1`:

```python
from openai import OpenAI

client = OpenAI(api_key="not-used", base_url="http://localhost:8080/openai/v1")
completion = client.chat.completions.create(
    model="example-gpt",
    messages=[{"role": "user", "content": "Hello"}],
)
```

Gemini is served on `POST /gemini/<version>/models/<model>:generateContent`, and streamed on `:streamGenerateContent?alt=sse`. The `google-genai` client appends `/v1beta/models/<model>:generateContent` to its base URL:

```python
from google import genai

client = genai.Client(api_key="not-used", http_options={"base_url": "http://localhost:8080/gemini/"})
response = client.models.generate_content(model="gemini-2.0-flash", contents="Hello")
```

Each example is one call. Load or reset a scenario before each one, and see the [scenario reference](scenarios.md) to write your own.
