// The Node SDK suite's server, control helpers and clients, as conftest.py is the Python suite's: one
// fake-llm server per test file, control helpers, and the official SDK clients pointed at it.
//
// The server is either started from FAKE_LLM_COMMAND (run through `sh -c`, with PORT and the
// FAKE_LLM_* settings in its environment) or reached at FAKE_LLM_BASE_URL. Exactly one must be set.

import { spawn, type ChildProcess } from "node:child_process";
import { closeSync, mkdtempSync, openSync, readFileSync, rmSync } from "node:fs";
import { createServer } from "node:net";
import { tmpdir } from "node:os";
import { join } from "node:path";
import type { TestContext } from "node:test";
import { setTimeout as sleep } from "node:timers/promises";
import assert from "node:assert/strict";

import Anthropic from "@anthropic-ai/sdk";
import { GoogleGenAI } from "@google/genai";
import OpenAI from "openai";

const STARTUP_MS = 30_000;
const STOP_GRACE_MS = 5_000;
const POLL_MS = 100;
const HEALTH_REQUEST_MS = 1_000;
// httpx's default timeout, which the Python suite's checks of a running server use.
const CHECK_REQUEST_MS = 5_000;

export interface Server {
  baseUrl: string;
  stop(): Promise<void>;
}

/** Starts FAKE_LLM_COMMAND on a free port, or checks FAKE_LLM_BASE_URL. Exactly one must be set. */
export async function startServer(): Promise<Server> {
  const command = process.env.FAKE_LLM_COMMAND ?? "";
  const baseUrl = (process.env.FAKE_LLM_BASE_URL ?? "").replace(/\/+$/, "");
  if (Boolean(command) === Boolean(baseUrl)) {
    throw new Error("set exactly one of FAKE_LLM_COMMAND (a command that starts the server) or FAKE_LLM_BASE_URL (a running server)");
  }
  if (baseUrl) {
    await checkRunning(baseUrl);
    return { baseUrl, stop: async () => {} };
  }
  return started(command);
}

/** A running server must be strict and have open control routes. */
async function checkRunning(baseUrl: string): Promise<void> {
  let health: Response;
  let lenient: unknown;
  let calls: Response;
  try {
    health = await fetch(`${baseUrl}/health`, { signal: AbortSignal.timeout(CHECK_REQUEST_MS) });
    lenient = health.status === 200 ? ((await health.clone().json()) as { lenient?: unknown }).lenient : undefined;
    calls = await fetch(`${baseUrl}/control/calls`, { signal: AbortSignal.timeout(CHECK_REQUEST_MS) });
  } catch (e) {
    throw new Error(`cannot check the server at ${baseUrl}: ${String(e)}`);
  }
  if (lenient !== false) {
    throw new Error(`GET ${baseUrl}/health must answer 200 with "lenient": false, got ${health.status}: ${await health.text()}`);
  }
  if (calls.status !== 200) {
    throw new Error(`GET ${baseUrl}/control/calls must answer without a control token, got ${calls.status}: ${await calls.text()}`);
  }
}

async function freePort(): Promise<number> {
  const probe = createServer();
  await new Promise<void>((resolve, reject) => {
    probe.once("error", reject);
    probe.listen(0, "127.0.0.1", resolve);
  });
  const address = probe.address();
  await new Promise<void>((resolve) => probe.close(() => resolve()));
  if (address === null || typeof address === "string") throw new Error(`unexpected probe address ${String(address)}`);
  return address.port;
}

/** Starts the command on a free port in its own process group; stop() stops the group. */
async function started(command: string): Promise<Server> {
  const port = await freePort();
  const env: NodeJS.ProcessEnv = Object.fromEntries(Object.entries(process.env).filter(([name]) => !name.startsWith("FAKE_LLM_")));
  Object.assign(env, {
    PORT: String(port),
    FAKE_LLM_LENIENT: "false",
    FAKE_LLM_CONTROL_TOKEN: "",
    FAKE_LLM_SCENARIO: "",
    FAKE_LLM_SCENARIO_DIR: "",
  });
  // Output goes to a file, not a pipe that could fill up and stall the server.
  const outputDir = mkdtempSync(join(tmpdir(), "fake-llm-node-"));
  const outputPath = join(outputDir, "server.log");
  const fd = openSync(outputPath, "w");
  const child = spawn("sh", ["-c", command], { env, detached: true, stdio: ["ignore", fd, fd] });
  closeSync(fd);
  // A failed spawn emits 'error' and no 'exit'; record it so the startup error can name it.
  let spawnError: Error | undefined;
  const exited = new Promise<number | null>((resolve) => {
    child.once("exit", (code) => resolve(code));
    child.once("error", (e) => {
      spawnError = e;
      resolve(null);
    });
  });
  const baseUrl = `http://127.0.0.1:${port}`;
  try {
    await awaitHealthy(baseUrl, child, outputPath, () => spawnError);
  } catch (e) {
    try {
      await stop(child, exited);
    } finally {
      rmSync(outputDir, { recursive: true, force: true });
    }
    throw e;
  }
  return {
    baseUrl,
    stop: async () => {
      await stop(child, exited);
      rmSync(outputDir, { recursive: true, force: true });
    },
  };
}

/** Polls GET /health. Any fetch error counts as not ready yet: Docker accepts connections early. */
async function awaitHealthy(baseUrl: string, child: ChildProcess, outputPath: string, spawnError: () => Error | undefined): Promise<void> {
  const deadline = Date.now() + STARTUP_MS;
  while (Date.now() < deadline && child.exitCode === null && child.signalCode === null && spawnError() === undefined) {
    try {
      const health = await fetch(`${baseUrl}/health`, { signal: AbortSignal.timeout(HEALTH_REQUEST_MS) });
      await health.body?.cancel();
      if (health.status === 200) return;
    } catch {
      // Not ready yet.
    }
    await sleep(POLL_MS);
  }
  const failed = spawnError();
  if (failed !== undefined) throw new Error(`cannot start the server with sh -c: ${failed.message}`);
  const printed = readFileSync(outputPath, "utf8");
  if (child.exitCode !== null || child.signalCode !== null) {
    throw new Error(`the server exited with code ${child.exitCode ?? child.signalCode} before answering GET /health:\n${printed}`);
  }
  throw new Error(`no answer from GET ${baseUrl}/health within ${STARTUP_MS / 1000} s:\n${printed}`);
}

/** SIGTERM to the process group, then SIGKILL after the grace period. */
async function stop(child: ChildProcess, exited: Promise<number | null>): Promise<void> {
  if (child.pid === undefined) return;
  if (!killGroup(child.pid, "SIGTERM")) return;
  const timedOut = Symbol("timed out");
  // Unref'd, so a server that stops in time doesn't keep the test file running for the rest of the grace period.
  if ((await Promise.race([exited, sleep(STOP_GRACE_MS, timedOut, { ref: false })])) === timedOut) {
    killGroup(child.pid, "SIGKILL");
    await exited;
  }
}

/** Signals the process group; false when it is already gone. */
function killGroup(pid: number, signal: NodeJS.Signals): boolean {
  try {
    process.kill(-pid, signal);
    return true;
  } catch (e) {
    if ((e as NodeJS.ErrnoException).code === "ESRCH") return false;
    throw e;
  }
}

/** Every item of an async iterable, such as an SDK stream, in order. */
export async function collect<T>(items: AsyncIterable<T>): Promise<T[]> {
  const all: T[] = [];
  for await (const item of items) all.push(item);
  return all;
}

/** A call log record's [seq, vendor, model, outcome, step]. */
export type LogEntry = [number, string, string, string, number | null];

/** Control helpers for one test. The scenario is named after the test. */
export class Fake {
  readonly baseUrl: string;
  readonly name: string;

  // Explicit fields, not constructor parameter properties, which type stripping can't erase.
  constructor(baseUrl: string, name: string) {
    this.baseUrl = baseUrl;
    this.name = name;
  }

  /** Loads the steps as an inline scenario, which starts it over and empties the call log. */
  async load(steps: object[]): Promise<void> {
    const response = await fetch(`${this.baseUrl}/control/scenario`, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ scenario: { name: this.name, steps } }),
    });
    const text = await response.text();
    assert.equal(response.status, 200, text);
    assert.deepEqual(JSON.parse(text), { loaded: this.name, steps: steps.length });
  }

  /** Removes the loaded scenario and empties the call log; calls then get the no-scenario 400. */
  async unload(): Promise<void> {
    const response = await fetch(`${this.baseUrl}/control/unload`, { method: "POST" });
    const text = await response.text();
    assert.equal(response.status, 200, text);
    assert.deepEqual(JSON.parse(text), { unloaded: true });
  }

  async calls(): Promise<Record<string, unknown>[]> {
    const response = await fetch(`${this.baseUrl}/control/calls`);
    return (await response.json()) as Record<string, unknown>[];
  }

  /** Waits until the call log holds count records, so a timed-out call is logged before the next is sent. */
  async waitForCalls(count: number, timeoutMs = 5_000): Promise<void> {
    const deadline = Date.now() + timeoutMs;
    while ((await this.calls()).length < count && Date.now() < deadline) await sleep(10);
    assert.equal((await this.calls()).length, count);
  }

  async log(): Promise<LogEntry[]> {
    return (await this.calls()).map((c) => [c.seq, c.vendor, c.model, c.outcome, c.step] as LogEntry);
  }
}

/** The helpers for the running test, its scenario named after the test with spaces as `-`. */
export function fake(server: Server, t: TestContext): Fake {
  return new Fake(server.baseUrl, t.name.replaceAll(" ", "-"));
}

export const anthropicClient = (base: string) => new Anthropic({ apiKey: "not-used", baseURL: `${base}/anthropic`, timeout: 10_000 });
export const openaiClient = (base: string) => new OpenAI({ apiKey: "not-used", baseURL: `${base}/openai/v1`, timeout: 10_000 });
export const geminiClient = (base: string) =>
  new GoogleGenAI({ apiKey: "not-used", httpOptions: { baseUrl: `${base}/gemini/`, timeout: 10_000 } });
