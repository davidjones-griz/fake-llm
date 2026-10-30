// The official openai package against fake-llm's Responses route (buffered and streaming).

import { after, before, test } from "node:test";
import assert from "node:assert/strict";

import OpenAI from "openai";
import type { FunctionTool } from "openai/resources/responses/responses";

import { fake, openaiClient, startServer, type LogEntry, type Server } from "./support.ts";

const TOOLS: FunctionTool[] = [
  { type: "function", name: "get_weather", parameters: { type: "object", properties: { city: { type: "string" } } }, strict: false },
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

const create = (extra: { tools?: FunctionTool[] } = {}) => client.responses.create({ model: "example-gpt", input: "Hello", ...extra });
const call = (seq: number, outcome: string, step: number | null): LogEntry => [seq, "openai-responses", "example-gpt", outcome, step];

function errorStep(status: number, error: object) {
  return { error: { status, body: JSON.stringify({ error }) } };
}

test("text", async (t) => {
  const f = fake(server, t);
  await f.load([{ text: "Billing." }]);
  const r = await create();
  assert.deepEqual([r.id, r.object, r.model, r.status], ["resp_fake_1", "response", "fake-openai", "completed"]);
  assert.equal(r.output_text, "Billing.");
  assert.deepEqual([r.usage?.input_tokens, r.usage?.output_tokens, r.usage?.total_tokens], [100, 50, 150]);
  assert.deepEqual(await f.log(), [call(1, "text", 1)]);
});

test("max tokens", async (t) => {
  const f = fake(server, t);
  await f.load([{ text: "The summary stops in the mid", stopReason: "max_tokens" }]);
  const r = await create();
  assert.equal(r.status, "incomplete");
  assert.equal(r.incomplete_details?.reason, "max_output_tokens");
  assert.equal(r.output_text, "The summary stops in the mid");
  assert.deepEqual(await f.log(), [call(1, "text", 1)]);
});

test("function calls", async (t) => {
  const f = fake(server, t);
  await f.load([
    {
      toolCalls: [
        { name: "get_weather", arguments: { city: "Paris" } },
        { name: "get_time", rawArguments: '{"tz": Europe/Paris}', id: "call_custom" },
      ],
    },
  ]);
  const r = await create({ tools: TOOLS });
  assert.deepEqual(
    r.output.map((o) => (o.type === "function_call" ? [o.type, o.id, o.call_id, o.name, o.arguments, o.status] : [o.type])),
    [
      ["function_call", "fc_fake_1_1", "call_fake_1_1", "get_weather", '{"city":"Paris"}', "completed"],
      ["function_call", "fc_fake_1_2", "call_custom", "get_time", '{"tz": Europe/Paris}', "completed"],
    ],
  );
  assert.deepEqual([r.output_text, r.status], ["", "completed"]);
  assert.deepEqual(await f.log(), [call(1, "tool-calls", 1)]);
});

test("stream text", async (t) => {
  const f = fake(server, t);
  await f.load([{ text: "Streaming text that is long enough.", streamChunkChars: 8 }]);
  const events = client.responses.stream({ model: "example-gpt", input: "Hello" });
  const deltas: string[] = [];
  events.on("response.output_text.delta", (e) => deltas.push(e.delta));
  const final = await events.finalResponse();
  assert.deepEqual(deltas, ["Streamin", "g text t", "hat is l", "ong enou", "gh."]);
  assert.deepEqual([final.id, final.status, final.output_text], ["resp_fake_1", "completed", "Streaming text that is long enough."]);
  assert.equal(final.usage?.total_tokens, 150);
  assert.deepEqual(await f.log(), [call(1, "text", 1)]);
});

test("stream error after chunks", async (t) => {
  const f = fake(server, t);
  const error = { message: "server error", type: "server_error", param: null, code: null };
  await f.load([{ ...errorStep(500, error), streamErrorAfterChunks: 2, streamChunkChars: 8 }, { text: "never" }]);
  const deltas: string[] = [];
  await assert.rejects(
    (async () => {
      for await (const e of await client.responses.create({ model: "example-gpt", input: "Hello", stream: true })) {
        if (e.type === "response.output_text.delta") deltas.push(e.delta);
      }
    })(),
    (e) => {
      assert.ok(e instanceof OpenAI.APIError);
      // An error inside a 200 stream has no HTTP status, unlike a status error.
      assert.equal(e.status, undefined);
      assert.deepEqual([e.error, e.message], [error, "server error"]);
      return true;
    },
  );
  assert.deepEqual(deltas, ["Thinking", " this th"]);
  assert.deepEqual(await f.log(), [call(1, "error:500", 1)]);
});

test("call after last step", async (t) => {
  const f = fake(server, t);
  await f.load([{ text: "once" }]);
  assert.equal((await create()).output_text, "once");
  await assert.rejects(create(), (e) => {
    assert.ok(e instanceof OpenAI.BadRequestError);
    assert.equal((e.error as { fake_llm_error?: unknown }).fake_llm_error, "no more steps");
    return true;
  });
  assert.deepEqual(await f.log(), [call(1, "text", 1), call(2, "no-more-steps", null)]);
});

test("scripted usage", async (t) => {
  const f = fake(server, t);
  await f.load([{ text: "Counted.", usage: { inputTokens: 1200, outputTokens: 340 } }]);
  const usage = (await create()).usage;
  assert.deepEqual([usage?.input_tokens, usage?.output_tokens, usage?.total_tokens], [1200, 340, 1540]);
  assert.deepEqual(await f.log(), [call(1, "text", 1)]);
});
