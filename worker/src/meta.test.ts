// Meta transport tests (review 0003 R5): the done-event collection is
// the live-verified SAM 3.1 behaviour, not a guess.
import { NonRetryableError } from "cloudflare:workflows";
import { afterEach, describe, expect, it, vi } from "vitest";
import { metaResponses } from "./meta";

const env = { META_API_KEY: "k" } as never;

function sse(events: string[], status = 200): Response {
  return new Response(events.map((e) => `data: ${e}\n\n`).join("") + "data: [DONE]\n\n", { status });
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
});
