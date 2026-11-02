// Meta transport tests (review 0003 R5): the done-event collection is
// the live-verified SAM 3.1 behaviour, not a guess.
import { NonRetryableError } from "cloudflare:workflows";
import { afterEach, describe, expect, it, vi } from "vitest";
import { metaResponses } from "./meta";

const env = { META_API_KEY: "k" } as never;

function sse(events: string[], status = 200): Response {
  return new Response(events.map((e) => `data: ${e}\n\n`).join("") + "data: [DONE]\n\n", { status });
}

/** An SSE body delivered in arbitrary transport chunks (golden splits). */
function chunked(body: string, pieces: number[]): Response {
  const parts: string[] = [];
  let at = 0;
  for (const n of pieces) {
    parts.push(body.slice(at, at + n));
    at += n;
  }
  parts.push(body.slice(at));
  const stream = new ReadableStream<string>({
    start(c) {
      for (const p of parts) c.enqueue(p);
      c.close();
    },
  });
  return new Response(stream.pipeThrough(new TextEncoderStream()), { status: 200 });
}

const delta = (text: string) =>
  JSON.stringify({ type: "response.output_text.delta", delta: text });
const done = (text: string) =>
  JSON.stringify({ type: "response.output_text.done", text });

describe("metaResponses", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("collects the done-event text when the model sends no deltas (SAM 3.1 live shape)", async () => {
    vi.stubGlobal("fetch", async () => sse([done("<0f>0<|box;…|>")]));
    await expect(metaResponses(env, {})).resolves.toBe("<0f>0<|box;…|>");
  });

  it("concatenates deltas and does not duplicate the done restatement", async () => {
    vi.stubGlobal("fetch", async () => sse([delta("ab"), delta("cd"), done("abcd")]));
    await expect(metaResponses(env, {})).resolves.toBe("abcd");
  });

  it("maps 4xx to NonRetryableError and 5xx to a retryable error", async () => {
    vi.stubGlobal("fetch", async () => new Response("no", { status: 403 }));
    await expect(metaResponses(env, {})).rejects.toBeInstanceOf(NonRetryableError);
    vi.stubGlobal("fetch", async () => new Response("busy", { status: 503 }));
    await expect(metaResponses(env, {})).rejects.toThrow(/retryable meta 503/);
  });

  it("fails closed without a key", async () => {
    await expect(metaResponses({} as never, {})).rejects.toBeInstanceOf(NonRetryableError);
  });

  it("reassembles events split across transport chunks (mid-\\n\\n split)", async () => {
    const body =
      `data: ${delta("ab")}\n\n` + `data: ${delta("cd")}\n\n` + `data: ${done("abcd")}\n\n` + "data: [DONE]\n\n";
    // Splits land mid-event, mid-JSON, and inside the \n\n boundary itself.
    vi.stubGlobal("fetch", async () => chunked(body, [7, 3, 25, 2, 40, 1]));
    await expect(metaResponses(env, {})).resolves.toBe("abcd");
  });

  it("accepts CRLF line endings", async () => {
    const wire =
      `data: ${delta("ab")}\r\n\r\n` +
      `data: ${done("<0f>0<|box;…|>")}\r\n\r\n` +
      "data: [DONE]\r\n\r\n";
    vi.stubGlobal("fetch", async () => chunked(wire, [11, 5, 30]));
    await expect(metaResponses(env, {})).resolves.toBe("ab");
  });

  it("ignores ':' keep-alive comments and heartbeats", async () => {
    const wire =
      ": ping\n\n" +
      `data: ${delta("xy")}\n\n` +
      ":keep-alive\n\n" +
      `\n` +
      `data: ${done("xy")}\n\n` +
      "data: [DONE]\n\n";
    vi.stubGlobal("fetch", async () => chunked(wire, [4, 9, 2, 33]));
    await expect(metaResponses(env, {})).resolves.toBe("xy");
  });

  it("fails closed (non-retryable) on response.incomplete", async () => {
    const incomplete = JSON.stringify({ type: "response.incomplete", reason: "max_output_tokens" });
    vi.stubGlobal("fetch", async () => sse([delta("ab"), incomplete]));
    await expect(metaResponses(env, {})).rejects.toBeInstanceOf(NonRetryableError);
  });

  it("cancels the response stream when an error event ends the call", async () => {
    let cancelled = false;
    const failed = JSON.stringify({ type: "response.failed", error: "boom" });
    // The vendor keeps the connection open after the error event.
    const open = new ReadableStream<Uint8Array>({
      start(c) {
        c.enqueue(new TextEncoder().encode(`data: ${failed}\n\n`));
      },
      cancel() {
        cancelled = true;
      },
    });
    vi.stubGlobal("fetch", async () => new Response(open, { status: 200 }));
    await expect(metaResponses(env, {})).rejects.toThrow(/boom/);
    expect(cancelled).toBe(true);
  });

  it("fails retryably, not with a TypeError, when a 200 carries no body", async () => {
    vi.stubGlobal("fetch", async () => new Response(null, { status: 200 }));
    await expect(metaResponses(env, {})).rejects.toThrow(/response has no body/);
  });

  it("leaves no idle watchdog armed after an early status failure", async () => {
    vi.useFakeTimers();
    try {
      vi.stubGlobal("fetch", async () => new Response("busy", { status: 503 }));
      await expect(metaResponses(env, {})).rejects.toThrow(/retryable meta 503/);
      expect(vi.getTimerCount()).toBe(0);
      vi.stubGlobal("fetch", async () => new Response("no", { status: 403 }));
      await expect(metaResponses(env, {})).rejects.toBeInstanceOf(NonRetryableError);
      expect(vi.getTimerCount()).toBe(0);
    } finally {
      vi.useRealTimers();
    }
  });

  it("collects a final event with no trailing blank line", async () => {
    const wire = `data: ${done("<0f>7<|box|>")}`; // no \n\n terminator
    vi.stubGlobal("fetch", async () => chunked(wire, [10]));
    await expect(metaResponses(env, {})).resolves.toBe("<0f>7<|box|>");
  });
});
