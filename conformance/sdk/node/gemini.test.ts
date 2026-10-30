// The official @google/genai package against fake-llm's Gemini generateContent and streamGenerateContent routes
// (buffered and streaming).

import { after, before, test } from "node:test";
import assert from "node:assert/strict";

import { ApiError, FinishReason, type GoogleGenAI } from "@google/genai";

import { collect, fake, geminiClient, startServer, type LogEntry, type Server } from "./support.ts";

const FAST_RETRY = { "retry-after-ms": "10" };

let server: Server;
let client: GoogleGenAI;
before(async () => {
  server = await startServer();
  client = geminiClient(server.baseUrl);
});
after(async () => {
  await server?.stop();
});

const generate = () => client.models.generateContent({ model: "example-gemini", contents: "Hello" });
const stream = async () => collect(await client.models.generateContentStream({ model: "example-gemini", contents: "Hello" }));
const call = (seq: number, outcome: string, step: number | null): LogEntry => [seq, "gemini", "example-gemini", outcome, step];

function errorStep(code: number, message: string, status: string, headers?: Record<string, string>) {
  return { error: { status: code, body: JSON.stringify({ error: { code, message, status } }), ...(headers ? { headers } : {}) } };
}

test("text", async (t) => {
  const f = fake(server, t);
  await f.load([{ text: "Billing." }]);
  const response = await generate();
  assert.equal(response.text, "Billing.");
  assert.equal(response.modelVersion, "example-gemini");
  assert.equal(response.candidates?.length, 1);
  assert.equal(response.candidates?.[0]?.content?.role, "model");
  assert.equal(response.candidates?.[0]?.finishReason, FinishReason.STOP);
  const usage = response.usageMetadata;
  assert.deepEqual([usage?.promptTokenCount, usage?.candidatesTokenCount, usage?.totalTokenCount], [100, 50, 150]);
  assert.deepEqual(await f.log(), [call(1, "text", 1)]);
});

test("max tokens", async (t) => {
  const f = fake(server, t);
  await f.load([{ text: "The summary stops in the mid", stopReason: "max_tokens" }]);
  assert.equal((await generate()).candidates?.[0]?.finishReason, FinishReason.MAX_TOKENS);
  assert.deepEqual(await f.log(), [call(1, "text", 1)]);
});

test("function call", async (t) => {
  const f = fake(server, t);
  await f.load([{ toolCalls: [{ name: "get_weather", arguments: { city: "Paris" } }, { name: "get_time", id: "t2" }] }]);
  const response = await generate();
  assert.deepEqual(
    response.functionCalls?.map((fc) => [fc.id ?? null, fc.name, fc.args]),
    [
      [null, "get_weather", { city: "Paris" }],
      ["t2", "get_time", {}],
    ],
  );
  assert.equal(response.candidates?.[0]?.finishReason, FinishReason.STOP);
  assert.deepEqual(await f.log(), [call(1, "tool-calls", 1)]);
});

test("default client does not retry", async (t) => {
  const f = fake(server, t);
  await f.load([errorStep(429, "slow down", "RESOURCE_EXHAUSTED", FAST_RETRY), { text: "ok" }]);
  await assert.rejects(generate(), (e) => {
    assert.ok(e instanceof ApiError);
    assert.equal(e.status, 429);
    // The message is the response body as JSON.
    assert.deepEqual(JSON.parse(e.message), { error: { code: 429, message: "slow down", status: "RESOURCE_EXHAUSTED" } });
    return true;
  });
  assert.deepEqual(await f.log(), [call(1, "error:429", 1)]);
});

test("call after last step", async (t) => {
  const f = fake(server, t);
  await f.load([{ text: "once" }]);
  assert.equal((await generate()).text, "once");
  await assert.rejects(generate(), (e) => {
    assert.ok(e instanceof ApiError);
    assert.equal(e.status, 400);
    assert.equal((JSON.parse(e.message) as { fake_llm_error?: unknown }).fake_llm_error, "no more steps");
    return true;
  });
  assert.deepEqual(await f.log(), [call(1, "text", 1), call(2, "no-more-steps", null)]);
});

test("stream text", async (t) => {
  const f = fake(server, t);
  await f.load([{ text: "Streaming text that is long enough.", streamChunkChars: 8 }]);
  const chunks = await stream();
  assert.deepEqual(
    chunks.map((c) => c.text),
    ["Streamin", "g text t", "hat is l", "ong enou", "gh."],
  );
  assert.ok(chunks.every((c) => c.candidates?.[0]?.content?.role === "model"));
  assert.deepEqual(
    chunks.map((c) => c.candidates?.[0]?.finishReason ?? null),
    [null, null, null, null, FinishReason.STOP],
  );
  assert.deepEqual(
    chunks.slice(0, -1).map((c) => c.usageMetadata ?? null),
    [null, null, null, null],
  );
  const usage = chunks.at(-1)?.usageMetadata;
  assert.deepEqual([usage?.promptTokenCount, usage?.candidatesTokenCount, usage?.totalTokenCount], [100, 50, 150]);
  assert.deepEqual(await f.log(), [call(1, "text", 1)]);
});

test("stream function call", async (t) => {
  const f = fake(server, t);
  await f.load([{ text: "Checking.", toolCalls: [{ name: "get_weather", arguments: { city: "Paris" } }], streamChunkChars: 8 }]);
  const chunks = await stream();
  // Each chunk's text parts, read directly: the `text` getter warns on a chunk with a function call.
  assert.deepEqual(
    chunks.map((c) => (c.candidates?.[0]?.content?.parts ?? []).flatMap((p) => (p.text === undefined ? [] : [p.text]))),
    [["Checking"], ["."], []],
  );
  assert.deepEqual(
    chunks.map((c) => (c.functionCalls ?? []).map((fc) => [fc.name, fc.args])),
    [[], [], [["get_weather", { city: "Paris" }]]],
  );
  assert.equal(chunks.at(-1)?.candidates?.[0]?.finishReason, FinishReason.STOP);
  assert.deepEqual(await f.log(), [call(1, "tool-calls", 1)]);
});

test("scripted usage", async (t) => {
  const f = fake(server, t);
  await f.load([{ text: "Counted.", usage: { inputTokens: 1200, outputTokens: 340 } }]);
  const usage = (await generate()).usageMetadata;
  assert.deepEqual([usage?.promptTokenCount, usage?.candidatesTokenCount, usage?.totalTokenCount], [1200, 340, 1540]);
  assert.deepEqual(await f.log(), [call(1, "text", 1)]);
});
