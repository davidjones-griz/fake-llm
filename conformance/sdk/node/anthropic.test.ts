// The official @anthropic-ai/sdk package against fake-llm's Anthropic Messages route, buffered and streaming.

import { after, before, test } from "node:test";
import assert from "node:assert/strict";

import Anthropic from "@anthropic-ai/sdk";

import { anthropicClient, fake, startServer, type LogEntry, type Server } from "./support.ts";

const MESSAGES: Anthropic.MessageParam[] = [{ role: "user", content: "Hello" }];
const FAST_RETRY = { "retry-after-ms": "10" };
const TOOLS: Anthropic.Tool[] = [
  { name: "get_weather", description: "Current weather", input_schema: { type: "object", properties: { city: { type: "string" } } } },
];

let server: Server;
let client: Anthropic;
before(async () => {
  server = await startServer();
  client = anthropicClient(server.baseUrl);
});
after(async () => {
  await server?.stop();
});

const create = () => client.messages.create({ model: "example-model", max_tokens: 64, messages: MESSAGES });
const stream = (extra: { tools?: Anthropic.Tool[] } = {}) =>
  client.messages.stream({ model: "example-model", max_tokens: 64, messages: MESSAGES, ...extra });
const call = (seq: number, outcome: string, step: number | null): LogEntry => [seq, "anthropic", "example-model", outcome, step];

function errorStep(status: number, body: object, headers?: Record<string, string>) {
  return { error: { status, body: JSON.stringify(body), ...(headers ? { headers } : {}) } };
}

test("text", async (t) => {
  const f = fake(server, t);
  await f.load([{ text: "Billing questions go to the billing team." }]);
  const message = await create();
  assert.deepEqual([message.id, message.type, message.role, message.model], ["fake-msg-1", "message", "assistant", "example-model"]);
  assert.deepEqual(
    message.content.map((b) => [b.type, b.type === "text" ? b.text : null]),
    [["text", "Billing questions go to the billing team."]],
  );
  assert.equal(message.stop_reason, "end_turn");
  assert.deepEqual([message.usage.input_tokens, message.usage.output_tokens], [100, 50]);
  assert.deepEqual([message.usage.cache_creation_input_tokens, message.usage.cache_read_input_tokens], [0, 0]);
  assert.deepEqual(await f.log(), [call(1, "text", 1)]);
});

test("max tokens", async (t) => {
  const f = fake(server, t);
  await f.load([{ text: "The summary stops in the mid", stopReason: "max_tokens" }]);
  assert.equal((await create()).stop_reason, "max_tokens");
  assert.deepEqual(await f.log(), [call(1, "text", 1)]);
});

test("error not retried", async (t) => {
  const f = fake(server, t);
  const body = { type: "error", error: { type: "invalid_request_error", message: "bad request" } };
  await f.load([errorStep(400, body), { text: "ok" }]);
  await assert.rejects(create(), (e) => {
    assert.ok(e instanceof Anthropic.BadRequestError);
    assert.equal(e.status, 400);
    assert.deepEqual(e.error, body);
    return true;
  });
  assert.deepEqual(await f.log(), [call(1, "error:400", 1)]);
});

test("rate limit retried", async (t) => {
  const f = fake(server, t);
  const body = { type: "error", error: { type: "rate_limit_error", message: "slow down" } };
  await f.load([errorStep(429, body, FAST_RETRY), { text: "ok" }]);
  const message = await create();
  assert.deepEqual([message.id, message.content[0]?.type === "text" && message.content[0].text], ["fake-msg-2", "ok"]);
  assert.deepEqual(await f.log(), [call(1, "error:429", 1), call(2, "text", 2)]);
});

test("call after last step", async (t) => {
  const f = fake(server, t);
  await f.load([{ text: "once" }]);
  const first = (await create()).content[0];
  assert.equal(first?.type === "text" && first.text, "once");
  await assert.rejects(create(), (e) => {
    assert.ok(e instanceof Anthropic.BadRequestError);
    assert.equal((e.error as { fake_llm_error?: unknown }).fake_llm_error, "no more steps");
    return true;
  });
  assert.deepEqual(await f.log(), [call(1, "text", 1), call(2, "no-more-steps", null)]);
});

test("stream text", async (t) => {
  const f = fake(server, t);
  await f.load([{ text: "Streaming text that is long enough.", streamChunkChars: 8 }]);
  const events = stream();
  const chunks: string[] = [];
  events.on("text", (delta) => chunks.push(delta));
  const message = await events.finalMessage();
  assert.deepEqual(chunks, ["Streamin", "g text t", "hat is l", "ong enou", "gh."]);
  assert.deepEqual([message.id, message.model, message.stop_reason], ["fake-msg-1", "example-model", "end_turn"]);
  assert.deepEqual(
    message.content.map((b) => [b.type, b.type === "text" ? b.text : null]),
    [["text", "Streaming text that is long enough."]],
  );
  assert.deepEqual([message.usage.input_tokens, message.usage.output_tokens], [100, 50]);
  assert.deepEqual(await f.log(), [call(1, "text", 1)]);
});

test("stream tool use", async (t) => {
  const f = fake(server, t);
  await f.load([{ text: "Checking.", toolCalls: [{ name: "get_weather", arguments: { city: "Paris" } }], streamChunkChars: 8 }]);
  const events = stream({ tools: TOOLS });
  const pieces: string[] = [];
  events.on("inputJson", (partialJson) => pieces.push(partialJson));
  const message = await events.finalMessage();
  assert.deepEqual(pieces, ['{"city":', '"Paris"}']);
  assert.deepEqual(
    message.content.map((b) => (b.type === "tool_use" ? [b.type, b.id, b.name, b.input] : [b.type, b.type === "text" ? b.text : null])),
    [
      ["text", "Checking."],
      ["tool_use", "toolu_fake_1_1", "get_weather", { city: "Paris" }],
    ],
  );
  assert.equal(message.stop_reason, "tool_use");
  assert.deepEqual(await f.log(), [call(1, "tool-calls", 1)]);
});

test("scripted usage", async (t) => {
  const f = fake(server, t);
  const usage = { inputTokens: 1200, outputTokens: 340 };
  await f.load([
    { text: "Counted.", usage },
    { text: "Counted, streamed.", usage },
  ]);
  const message = await create();
  const streamed = await stream().finalMessage();
  assert.deepEqual([message.usage.input_tokens, message.usage.output_tokens], [1200, 340]);
  assert.deepEqual([streamed.usage.input_tokens, streamed.usage.output_tokens], [1200, 340]);
  assert.deepEqual(await f.log(), [call(1, "text", 1), call(2, "text", 2)]);
});
