// The official openai package against fake-llm's Chat Completions route (buffered and streaming).

import { after, before, test } from "node:test";
import assert from "node:assert/strict";

import OpenAI from "openai";
import type { ChatCompletionMessage, ChatCompletionMessageParam, ChatCompletionTool } from "openai/resources/chat/completions";

import { collect, fake, openaiClient, startServer, type LogEntry, type Server } from "./support.ts";

const MESSAGES: ChatCompletionMessageParam[] = [{ role: "user", content: "Hello" }];
const FAST_RETRY = { "retry-after-ms": "10" };
const TOOLS: ChatCompletionTool[] = [
  { type: "function", function: { name: "get_weather", parameters: { type: "object", properties: { city: { type: "string" } } } } },
];

let server: Server;
let client: OpenAI;
before(async () => {
  server = await startServer();
  client = openaiClient(server.baseUrl);
});
after(async () => {
  await server?.stop();
});

const create = (extra: { tools?: ChatCompletionTool[] } = {}) =>
  client.chat.completions.create({ model: "example-gpt", messages: MESSAGES, ...extra });
const stream = (extra: { stream_options?: { include_usage: boolean } } = {}) =>
  client.chat.completions.create({ model: "example-gpt", messages: MESSAGES, stream: true, ...extra });
const call = (seq: number, outcome: string, step: number | null): LogEntry => [seq, "openai", "example-gpt", outcome, step];

function errorStep(status: number, error: object, headers?: Record<string, string>) {
  return { error: { status, body: JSON.stringify({ error }), ...(headers ? { headers } : {}) } };
}

function toolCalls(message: ChatCompletionMessage) {
  return (message.tool_calls ?? []).map((c) =>
    c.type === "function" ? [c.id, c.type, c.function.name, c.function.arguments] : [c.id, c.type],
  );
}

test("text", async (t) => {
  const f = fake(server, t);
  await f.load([{ text: "Billing." }]);
  const completion = await create();
  assert.deepEqual([completion.id, completion.object, completion.model], ["fake-openai-1", "chat.completion", "fake-openai"]);
  assert.equal(completion.choices.length, 1);
  const [choice] = completion.choices;
  assert.deepEqual([choice?.message.role, choice?.message.content, choice?.finish_reason], ["assistant", "Billing.", "stop"]);
  const usage = completion.usage;
  assert.deepEqual([usage?.prompt_tokens, usage?.completion_tokens, usage?.total_tokens], [100, 50, 150]);
  assert.deepEqual(await f.log(), [call(1, "text", 1)]);
});

test("rate limit retried", async (t) => {
  const f = fake(server, t);
  const error = { message: "slow down", type: "requests", param: null, code: "rate_limit_exceeded" };
  await f.load([errorStep(429, error, FAST_RETRY), { text: "ok" }]);
  const completion = await create();
  assert.deepEqual([completion.id, completion.choices[0]?.message.content], ["fake-openai-2", "ok"]);
  assert.deepEqual(await f.log(), [call(1, "error:429", 1), call(2, "text", 2)]);
});

test("call after last step", async (t) => {
  const f = fake(server, t);
  await f.load([{ text: "once" }]);
  await create();
  await assert.rejects(create(), (e) => {
    assert.ok(e instanceof OpenAI.BadRequestError);
    assert.equal(e.status, 400);
    // The SDK wraps a body with no `error` key as { error: body }.
    assert.equal((e.error as { fake_llm_error?: unknown }).fake_llm_error, "no more steps");
    return true;
  });
  assert.deepEqual(await f.log(), [call(1, "text", 1), call(2, "no-more-steps", null)]);
});

test("stream text", async (t) => {
  const f = fake(server, t);
  await f.load([{ text: "Streaming text that is long enough.", streamChunkChars: 8 }]);
  const chunks = await collect(await stream());
  assert.deepEqual(
    chunks.map((c) => c.choices[0]?.delta.content ?? null),
    ["", "Streamin", "g text t", "hat is l", "ong enou", "gh.", null],
  );
  assert.equal(chunks[0]?.choices[0]?.delta.role, "assistant");
  assert.ok(chunks.every((c) => c.id === "fake-openai-1" && c.object === "chat.completion.chunk" && c.model === "fake-openai"));
  assert.deepEqual(
    chunks.map((c) => c.choices[0]?.finish_reason ?? null),
    [...Array<null>(chunks.length - 1).fill(null), "stop"],
  );
  assert.deepEqual(await f.log(), [call(1, "text", 1)]);
});

test("stream include usage", async (t) => {
  const f = fake(server, t);
  await f.load([{ text: "Billing." }]);
  const chunks = await collect(await stream({ stream_options: { include_usage: true } }));
  const last = chunks.at(-1);
  assert.deepEqual(last?.choices, []);
  assert.deepEqual([last?.usage?.prompt_tokens, last?.usage?.completion_tokens, last?.usage?.total_tokens], [100, 50, 150]);
  assert.deepEqual(
    chunks.slice(0, -1).map((c) => c.usage ?? null),
    Array<null>(chunks.length - 1).fill(null),
  );
  assert.deepEqual(await f.log(), [call(1, "text", 1)]);
});

test("tool calls", async (t) => {
  const f = fake(server, t);
  await f.load([{ text: "Checking.", toolCalls: [{ name: "get_weather", arguments: { city: "Paris" } }, { name: "get_time" }] }]);
  const [choice] = (await create({ tools: TOOLS })).choices;
  assert.ok(choice);
  assert.equal(choice.message.content, "Checking.");
  assert.deepEqual(toolCalls(choice.message), [
    ["call_fake_1_1", "function", "get_weather", '{"city":"Paris"}'],
    ["call_fake_1_2", "function", "get_time", "{}"],
  ]);
  assert.equal(choice.finish_reason, "tool_calls");
  assert.deepEqual(await f.log(), [call(1, "tool-calls", 1)]);
});

test("stream tool calls", async (t) => {
  const f = fake(server, t);
  await f.load([{ text: "Checking.", toolCalls: [{ name: "get_weather", arguments: { city: "Paris" } }], streamChunkChars: 8 }]);
  const events = client.chat.completions.stream({ model: "example-gpt", messages: MESSAGES, tools: TOOLS });
  const deltas: string[] = [];
  events.on("tool_calls.function.arguments.delta", (e) => deltas.push(e.arguments_delta));
  const completion = await events.finalChatCompletion();
  assert.deepEqual(deltas, ["", '{"city":', '"Paris"}']);
  const [choice] = completion.choices;
  assert.ok(choice);
  assert.equal(choice.message.content, "Checking.");
  assert.deepEqual(toolCalls(choice.message), [["call_fake_1_1", "function", "get_weather", '{"city":"Paris"}']]);
  assert.equal(choice.finish_reason, "tool_calls");
  assert.deepEqual(await f.log(), [call(1, "tool-calls", 1)]);
});

test("scripted usage", async (t) => {
  const f = fake(server, t);
  await f.load([{ text: "Counted.", usage: { inputTokens: 1200, outputTokens: 340 } }]);
  const usage = (await create()).usage;
  assert.deepEqual([usage?.prompt_tokens, usage?.completion_tokens, usage?.total_tokens], [1200, 340, 1540]);
  assert.deepEqual(await f.log(), [call(1, "text", 1)]);
});
