// Meta Model API transport (plan §7.3 SSE collector, slice W3-05).
//
// Every Meta call in this worker (SAM 3.1 grounding here, the optional
// Muse Spark scout in W3-09) streams Responses events; output text is
// collected from `response.output_text.delta` events, or from the
// `response.output_text.done` event when the model sends no deltas
// (SAM 3.1's actual behaviour, live-verified in review 0003).
// This module owns that collection generically: callers pass any request
// body, this returns the concatenated output text. Retryability follows
// the plan verbatim — 429/5xx retry via the Workflow, anything else is a
// NonRetryableError — so no caller re-implements status handling.

import { NonRetryableError } from "cloudflare:workflows";
import type { Env } from "./index";

const RESPONSES_URL = "https://api.meta.ai/v1/responses";

/** POST a streaming Responses request and collect the output text. */
export async function metaResponses(env: Env, body: object): Promise<string> {
  const key = env.META_API_KEY;
  if (!key) throw new NonRetryableError("meta: META_API_KEY is not configured");
  const res = await fetch(RESPONSES_URL, {
    method: "POST",
    body: JSON.stringify({ ...body, stream: true }),
    headers: {
      Authorization: `Bearer ${key}`,
      "Content-Type": "application/json",
      Accept: "text/event-stream",
    },
  });
  if (res.status === 429 || res.status >= 500) {
    throw new Error(`retryable meta ${res.status}`);
  }
  if (!res.ok) {
    throw new NonRetryableError(`meta ${res.status}: ${(await res.text()).slice(0, 500)}`);
  }
  const reader = res.body!.pipeThrough(new TextDecoderStream()).getReader();
  let buf = "";
  let out = "";
  let sawDelta = false;
  for (let r = await reader.read(); !r.done; r = await reader.read()) {
    buf += r.value;
    let i: number;
    while ((i = buf.indexOf("\n\n")) >= 0) {
      for (const line of buf.slice(0, i).split("\n")) {
        const data = line.startsWith("data:") ? line.slice(5).trim() : "";
        if (!data || data === "[DONE]") continue;
        const j = JSON.parse(data);
        if (j.type === "response.output_text.delta") {
          out += j.delta;
          sawDelta = true;
        } else if (j.type === "response.output_text.done" && !sawDelta && typeof j.text === "string") {
          // Live-verified (review 0003 R5): SAM 3.1 sends no deltas at
          // all — the whole output arrives in the done event. Collecting
          // only deltas read every real response as empty. The done text
          // restates the full output, so it is used only when no delta
          // was seen (never appended after deltas: no duplication).
          out += j.text;
        } else if (j.type === "response.failed" || j.type === "error") {
          throw new Error(data.slice(0, 500));
        }
      }
      buf = buf.slice(i + 2);
    }
  }
  return out;
}
