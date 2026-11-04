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

/** One collected Meta response: output text plus vendor usage, when the
 * `response.completed` event carries it (plan §10 W3-06 usage). */
export interface MetaResponse {
  text: string;
  /** `usage.video_frames_processed` from response.completed, if sent. */
  videoFramesProcessed?: number;
}

/** Per-call bounds (plan §10 W11-06 closes G9): 60 s for the whole call,
 * 20 s idle between SSE events. Callers may narrow them (tests) or pass
 * their own already-linked signal; the idle watchdog always applies. */
export const META_CALL_TIMEOUT_MS = 60_000;
export const META_CALL_IDLE_MS = 20_000;

export interface MetaCallOptions {
  timeoutMs?: number;
  idleMs?: number;
  signal?: AbortSignal;
}

/** POST a streaming Responses request and collect the output text. */
export async function metaResponses(env: Env, body: object): Promise<string> {
  return (await metaResponsesDetailed(env, body)).text;
}

/** As [metaResponses], but also surfaces the vendor usage counters. */
export async function metaResponsesDetailed(
  env: Env,
  body: object,
  opts: MetaCallOptions = {},
): Promise<MetaResponse> {
  const key = env.META_API_KEY;
  if (!key) throw new NonRetryableError("meta: META_API_KEY is not configured");
  const timeoutMs = opts.timeoutMs ?? META_CALL_TIMEOUT_MS;
  const idleMs = opts.idleMs ?? META_CALL_IDLE_MS;
  const timeoutSignal = opts.signal ?? AbortSignal.timeout(timeoutMs);
  const ctrl = new AbortController();
  if (timeoutSignal.aborted) {
    ctrl.abort(timeoutSignal.reason);
  } else {
    timeoutSignal.addEventListener("abort", () => ctrl.abort(timeoutSignal.reason), { once: true });
  }
  let idleTimer: ReturnType<typeof setTimeout> | undefined;
  const clearIdle = (): void => {
    if (idleTimer !== undefined) clearTimeout(idleTimer);
    idleTimer = undefined;
  };
  const armIdle = (): void => {
    clearIdle();
    idleTimer = setTimeout(
      () => ctrl.abort(new Error(`meta: no SSE event for ${idleMs} ms`)),
      idleMs,
    );
  };
  let out = "";
  let sawDelta = false;
  let videoFramesProcessed: number | undefined;
  const handleBlock = (block: string): void => {
    const payloads: string[] = [];
    for (const rawLine of block.split("\n")) {
      const line = rawLine.trim();
      // SSE keep-alive comments (": ...") and bare heartbeats carry no
      // payload; anything that is not a data line is not our event.
      if (line === "" || line.startsWith(":")) continue;
      if (!line.startsWith("data:")) continue;
      const data = line.slice(5).trim();
      if (!data || data === "[DONE]") continue;
      payloads.push(data);
    }
    for (const data of payloads) {
      let j: { type?: string; delta?: unknown; text?: unknown };
      try {
        j = JSON.parse(data);
      } catch {
        // A split or garbage line is not a protocol event; the collector
        // holds text only, so it skips rather than failing the call.
        continue;
      }
      if (j.type === "response.output_text.delta") {
        if (typeof j.delta === "string") out += j.delta;
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
      } else if (j.type === "response.incomplete") {
        throw new NonRetryableError(`meta incomplete: ${data.slice(0, 500)}`);
      } else if (j.type === "response.completed") {
        const frames = (j as { response?: { usage?: { video_frames_processed?: unknown } } })
          .response?.usage?.video_frames_processed;
        if (typeof frames === "number" && Number.isFinite(frames) && frames >= 0) {
          videoFramesProcessed = frames;
        }
      }
    }
  };
  // The idle watchdog covers the whole call: armed before fetch (headers
  // may never arrive), re-armed per body chunk, and cleared on every exit
  // path. Only silence aborts (G9).
  armIdle();
  try {
    const res = await fetch(RESPONSES_URL, {
      method: "POST",
      body: JSON.stringify({ ...body, stream: true }),
      headers: {
        Authorization: `Bearer ${key}`,
        "Content-Type": "application/json",
        Accept: "text/event-stream",
      },
      signal: ctrl.signal,
    });
    if (res.status === 429 || res.status >= 500) {
      await res.body?.cancel().catch(() => undefined);
      throw new Error(`retryable meta ${res.status}`);
    }
    if (!res.ok) {
      throw new NonRetryableError(`meta ${res.status}: ${(await res.text()).slice(0, 500)}`);
    }
    if (res.body === null) throw new Error("meta: response has no body");
    const reader = res.body.pipeThrough(new TextDecoderStream()).getReader();
    let buf = "";
    // Re-arm after headers: body silence trips the same watchdog.
    armIdle();
    try {
      for (let r = await reader.read(); !r.done; r = await reader.read()) {
        // Normalise CRLF: event boundaries may arrive as \r\n\r\n.
        buf += r.value.replace(/\r\n/g, "\n");
        // Any chunk resets the idle watchdog: only silence aborts (G9).
        armIdle();
        let i: number;
        while ((i = buf.indexOf("\n\n")) >= 0) {
          handleBlock(buf.slice(0, i));
          buf = buf.slice(i + 2);
        }
      }
      // A final event without a trailing blank line still counts.
      if (buf.includes("data:")) handleBlock(buf);
    } catch (e) {
      // An error event or a watchdog abort ends the call: release the
      // connection instead of leaving the response stream open.
      await reader.cancel().catch(() => undefined);
      throw e;
    }
  } finally {
    clearIdle();
  }
  return { text: out, ...(videoFramesProcessed !== undefined ? { videoFramesProcessed } : {}) };
}
