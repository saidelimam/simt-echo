import { Hono } from "hono";
import type { AppEnv, Env } from "./index";
import { deletePrefix, handleCreateJobV11, idemKey, readJobOwner, writeJobOwner } from "./uploads";
import { reserveDailySpend, spendDisabled } from "./spend";
import {
  JobEventSchema,
  JobResultSchema,
  ManifestSchema,
  MAX_SAM_FRAMES,
  type JobEvent,
  type JobStage,
  type Manifest,
} from "./types";

// Jobs API + R2 layout (plan §10 W3-03, R2 layout plan §5.3):
//   jobs/<id>/manifest.json   validated manifest
//   jobs/<id>/in/<fileId>     keyframes (JPEG) + clips (MP4), allow-listed
//   jobs/<id>/progress.json   {stage, updatedAt} polled by the app
//   jobs/<id>/result.json     JobResult, written by assemble (W3-06 owns content)
//   jobs/<id>/error.json      {error} detail when the pipeline fails

const MAX_FILES = 60;
const MAX_TOTAL_BYTES = 80 * 1024 * 1024;

const jobsPrefix = (jobId: string) => `jobs/${jobId}/`;

/** Job ids are UUIDs minted here, but any Workflow-instance-safe token is
 * accepted; the reserved names are sibling trees under `jobs/`. */
const RESERVED_JOB_IDS = new Set(["staging", "idem"]);
const isJobId = (id: string) => /^[A-Za-z0-9_-]{1,100}$/.test(id) && !RESERVED_JOB_IDS.has(id);
const manifestKey = (jobId: string) => `jobs/${jobId}/manifest.json`;
const inputKey = (jobId: string, name: string) => `jobs/${jobId}/in/${name}`;
const progressKey = (jobId: string) => `jobs/${jobId}/progress.json`;
const resultKey = (jobId: string) => `jobs/${jobId}/result.json`;
const errorKey = (jobId: string) => `jobs/${jobId}/error.json`;

export async function putProgress(env: Env, jobId: string, stage: JobStage): Promise<void> {
  await env.MEDIA.put(progressKey(jobId), JSON.stringify({ stage, updatedAt: Date.now() }));
}

/** Records a pipeline failure for GET to surface. Never masks the original throw. */
export async function putError(env: Env, jobId: string, message: string): Promise<void> {
  try {
    await env.MEDIA.put(errorKey(jobId), JSON.stringify({ error: message }));
    await putProgress(env, jobId, "errored");
  } catch {
    // R2 itself is down; the instance still reports errored via its status.
  }
}

// W10-06 job events (plan §10 W11-06 item 6), emitted by the SAM lens and
// the pipeline's lens path. One R2 record per event under
// `jobs/<id>/events/<eventId>.json` — append-only, per-job scoped (deleted
// with the job by the DELETE sweep, same as every other jobs/<id>/ key),
// so a concurrent emit never rewrites another event. GET /v1/jobs/:id/events
// replays them with [readJobEvents] (see [jobEventStream]).

const eventsPrefix = (jobId: string) => `jobs/${jobId}/events/`;
const eventKey = (jobId: string, id: string) => `jobs/${jobId}/events/${id}.json`;

/** Event id: zero-padded base-36 epoch ms (fixed width) plus the full
 * UUID as tiebreak. No isolate state (R9-08, review 0009 F7): two emits
 * in the same millisecond from two isolates share the timestamp part,
 * and [readJobEvents] orders by that part, so neither can sort before
 * the other's cursor and be skipped on resume. */
const EVENT_ID_TS_WIDTH = 11; // 36^11 ms ≈ year 41 000 CE
export function newEventId(now: number = Date.now()): string {
  return `${now.toString(36).padStart(EVENT_ID_TS_WIDTH, "0")}-${crypto.randomUUID()}`;
}

/** Validates against the frozen W10-06 union, then stores the record. */
export async function emitJobEvent(env: Env, jobId: string, event: JobEvent): Promise<void> {
  JobEventSchema.parse(event);
  await env.MEDIA.put(eventKey(jobId, event.id), JSON.stringify(event));
}

/** The timestamp part of an event id: the base-36 epoch ms before the
 * first "-", compared numerically so padded and legacy unpadded ids
 * order chronologically. NaN when unparseable. */
function eventIdTimestamp(id: string): number {
  const dash = id.indexOf("-");
  return parseInt(dash === -1 ? id : id.slice(0, dash), 36);
}

/** Stored events for a job in id (key) order, only those whose timestamp
 * is at least `afterId`'s when given (R9-08, review 0009 F7). Same-
 * timestamp events are included even if the client saw them: the
 * stream's events are additive and the client merges them idempotently,
 * so an event emitted after the client's cursor can never sort before
 * it and be skipped. An unparseable id biases toward inclusion. Within
 * one millisecond the order follows the id's UUID tiebreak (arbitrary);
 * the client treats same-millisecond events as a set. Skips unreadable
 * rows. */
export async function readJobEvents(
  env: Env,
  jobId: string,
  afterId?: string,
): Promise<JobEvent[]> {
  const prefix = eventsPrefix(jobId);
  const after = afterId === undefined ? Number.NaN : eventIdTimestamp(afterId);
  const keys: string[] = [];
  let cursor: string | undefined;
  for (;;) {
    const page = await env.MEDIA.list({ prefix, cursor });
    for (const o of page.objects) {
      const ts = eventIdTimestamp(o.key.slice(prefix.length));
      if (!Number.isFinite(after) || !Number.isFinite(ts) || ts >= after) keys.push(o.key);
    }
    if (!page.truncated) break;
    cursor = page.cursor;
  }
  keys.sort();
  const out: JobEvent[] = [];
  for (const key of keys) {
    const obj = await env.MEDIA.get(key);
    if (!obj) continue;
    try {
      out.push(JobEventSchema.parse(await obj.json()));
    } catch {
      // A half-written record never blocks the stream.
    }
  }
  return out;
}

/** SSE pacing: the server holds a connection ≤ 25 s (the client then
 * reconnects with Last-Event-ID), with a `: heartbeat` comment every 10 s. */
export interface EventStreamTiming {
  pollMs: number;
  heartbeatMs: number;
  maxMs: number;
}
const EVENT_STREAM_TIMING: EventStreamTiming = { pollMs: 1_000, heartbeatMs: 10_000, maxMs: 25_000 };
const TERMINAL_STATUSES = new Set(["complete", "errored", "terminated"]);

/** SSE body for one job: replays events after `afterId`, then follows new
 * ones until the job is terminal or `maxMs` elapses. The status is read
 * before the events, so a terminal status guarantees the final drain saw
 * every event the pipeline wrote. */
export function jobEventStream(
  env: Env,
  jobId: string,
  instance: WorkflowInstance,
  afterId: string | undefined,
  timing: EventStreamTiming = EVENT_STREAM_TIMING,
): ReadableStream<Uint8Array> {
  const encoder = new TextEncoder();
  let cancelled = false;
  // R9-08: the timestamp filter re-includes the cursor's millisecond
  // bucket on every poll; within one connection the client has already
  // seen those ids, so skip re-sending them. A fresh connection with
  // Last-Event-ID still drains the bucket once — at-least-once across
  // disconnects, and the client merges events idempotently.
  const sent = new Set<string>();
  return new ReadableStream<Uint8Array>({
    async start(controller) {
      const startedAt = Date.now();
      let lastWrite = startedAt;
      let cursor = afterId;
      try {
        while (!cancelled) {
          const terminal = TERMINAL_STATUSES.has((await instance.status()).status);
          for (const e of await readJobEvents(env, jobId, cursor)) {
            if (!sent.has(e.id)) {
              sent.add(e.id);
              controller.enqueue(encoder.encode(`id: ${e.id}\ndata: ${JSON.stringify(e)}\n\n`));
            }
            cursor = e.id;
            lastWrite = Date.now();
          }
          const now = Date.now();
          if (terminal || now - startedAt >= timing.maxMs) break;
          if (now - lastWrite >= timing.heartbeatMs) {
            controller.enqueue(encoder.encode(": heartbeat\n\n"));
            lastWrite = now;
          }
          await new Promise((resolve) => setTimeout(resolve, timing.pollMs));
        }
      } catch {
        // R2/Workflow hiccup: end the response; the client reconnects with
        // Last-Event-ID and loses nothing.
      }
      if (!cancelled) controller.close();
    },
    cancel() {
      cancelled = true;
    },
  });
}

async function readJson<T>(env: Env, key: string): Promise<T | null> {
  const obj = await env.MEDIA.get(key);
  if (!obj) return null;
  try {
    return (await obj.json()) as T;
  } catch {
    return null;
  }
}

export function registerJobsRoutes(app: Hono<AppEnv>): void {
  app.post("/v1/jobs", async (c) => {
    if (!c.env.PIPELINE) {
      return c.json({ ok: false, error: "server_misconfigured" }, 500);
    }
    // W11-05: JSON bodies are v1.1 creates (uploadId/budget/idempotencyKey);
    // the frozen v1 multipart path below is untouched for v1 clients.
    if ((c.req.header("content-type") ?? "").includes("application/json")) {
      return handleCreateJobV11(c);
    }
    // R9-05 (F4): the daily cap precedes storage — a halted deployment
    // creates nothing, and the ledger is untouched.
    if (spendDisabled(c.env)) {
      return c.json({ ok: false, error: "spend_disabled" }, 503);
    }
    let manifest: Manifest;
    let allowed: Set<string>;
    let files: Array<{ name: string; file: File }>;
    let totalBytes: number;
    try {
      const form = await c.req.formData();
      manifest = ManifestSchema.parse(JSON.parse(String(form.get("manifest"))));
      if (!manifest.consent.sendFramesToCloudAi) {
        return c.json({ ok: false, error: "consent_required" }, 400);
      }
      allowed = new Set(
        manifest.checkpoints.flatMap((k) => [
          ...k.keyframes.map((f) => f.id),
          ...(k.clip ? [k.clip.id] : []),
        ]),
      );
      files = [];
      totalBytes = 0;
      for (const [name, v] of form.entries()) {
        if (v instanceof File && allowed.has(name)) {
          files.push({ name, file: v });
          totalBytes += v.size;
        }
      }
    } catch {
      return c.json({ ok: false, error: "invalid_manifest" }, 400);
    }
    if (files.length > MAX_FILES) {
      return c.json({ ok: false, error: "too_many_files" }, 413);
    }
    if (totalBytes > MAX_TOTAL_BYTES) {
      return c.json({ ok: false, error: "payload_too_large" }, 413);
    }
    // R9-05 (F4): reserve the job's worst case before any file lands —
    // v1 carries no budget, so the hard ceiling is what a job can spend.
    // Over the cap is 503; a contention storm is the handler's 500 —
    // nothing was written.
    const reserve = await reserveDailySpend(c.env, MAX_SAM_FRAMES);
    if (!reserve.ok) {
      return reserve.reason === "over_cap"
        ? c.json({ ok: false, error: "daily_budget_exceeded" }, 503)
        : c.json({ ok: false, error: "job_create_failed" }, 500);
    }
    // Storage/creation failures are 500s: the manifest above was valid,
    // so reporting invalid_manifest here would mask an outage.
    const jobId = crypto.randomUUID();
    try {
      for (const { name, file } of files) {
        await c.env.MEDIA.put(inputKey(jobId, name), await file.arrayBuffer(), {
          httpMetadata: { contentType: file.type || "application/octet-stream" },
        });
      }
      await c.env.MEDIA.put(manifestKey(jobId), JSON.stringify(manifest));
      // R9-01: bind the job to the creating install.
      await writeJobOwner(c.env, jobId, c.get("deviceId"));
      await putProgress(c.env, jobId, "queued");
      await c.env.PIPELINE.create({ id: jobId, params: { jobId } });
      return c.json({ ok: true, jobId }, 202);
    } catch {
      // No instance was created, so nothing will read the partial writes.
      try {
        await deletePrefix(c.env, jobsPrefix(jobId));
      } catch {
        // Best effort: an unreferenced job prefix is inert.
      }
      return c.json({ ok: false, error: "job_create_failed" }, 500);
    }
  });

  app.get("/v1/jobs/:id", async (c) => {
    if (!c.env.PIPELINE) {
      return c.json({ ok: false, error: "server_misconfigured" }, 500);
    }
    const id = c.req.param("id");
    if (!isJobId(id)) return c.json({ ok: false, error: "job_not_found" }, 404);
    // R9-01: a job of another install is indistinguishable from a missing one.
    if ((await readJobOwner(c.env, id)) !== c.get("deviceId")) {
      return c.json({ ok: false, error: "job_not_found" }, 404);
    }
    let status: string;
    try {
      status = (await (await c.env.PIPELINE.get(id)).status()).status;
    } catch {
      return c.json({ ok: false, error: "job_not_found" }, 404);
    }
    if (status === "complete") {
      const raw = await readJson<unknown>(c.env, resultKey(id));
      const result = raw === null ? null : JobResultSchema.safeParse(raw);
      if (!result || !result.success) {
        return c.json({ ok: false, error: "result_missing" }, 500);
      }
      return c.json({ ok: true, status: "complete", stage: "complete", result: result.data });
    }
    if (status === "errored" || status === "terminated") {
      const detail = await readJson<{ error?: unknown }>(c.env, errorKey(id));
      const stage = (await readJson<{ stage?: JobStage }>(c.env, progressKey(id)))?.stage ?? "errored";
      const body: { ok: true; status: "errored"; stage: JobStage; error?: string } = {
        ok: true,
        status: "errored",
        stage,
      };
      if (typeof detail?.error === "string" && detail.error !== "") body.error = detail.error;
      return c.json(body);
    }
    if (status === "queued") {
      const stage = (await readJson<{ stage?: JobStage }>(c.env, progressKey(id)))?.stage ?? "queued";
      return c.json({ ok: true, status: "queued", stage });
    }
    const stage = (await readJson<{ stage?: JobStage }>(c.env, progressKey(id)))?.stage ?? "reading";
    return c.json({ ok: true, status: "running", stage });
  });

  app.get("/v1/jobs/:id/events", async (c) => {
    if (!c.env.PIPELINE) {
      return c.json({ ok: false, error: "server_misconfigured" }, 500);
    }
    const id = c.req.param("id");
    if (!isJobId(id)) return c.json({ ok: false, error: "job_not_found" }, 404);
    // R9-01: a foreign install's stream is a missing job, not a 200 with no
    // data — the events carry the job's route context.
    if ((await readJobOwner(c.env, id)) !== c.get("deviceId")) {
      return c.json({ ok: false, error: "job_not_found" }, 404);
    }
    let instance: WorkflowInstance;
    try {
      instance = await c.env.PIPELINE.get(id);
      await instance.status();
    } catch {
      return c.json({ ok: false, error: "job_not_found" }, 404);
    }
    const afterId = c.req.header("Last-Event-ID") ?? c.req.query("after") ?? undefined;
    return new Response(
      jobEventStream(c.env, id, instance, afterId === "" ? undefined : afterId),
      {
        headers: {
          "Content-Type": "text/event-stream",
          "Cache-Control": "no-cache",
        },
      },
    );
  });

  app.delete("/v1/jobs/:id", async (c) => {
    const id = c.req.param("id");
    // The id becomes an R2 prefix: `staging` and `idem` are other trees,
    // and an encoded slash would reach into them.
    if (!isJobId(id)) return c.json({ ok: false, error: "invalid_request" }, 400);
    // R9-01: a job owned by another install is a 404 (no existence leak, no
    // cross-install sweep). A job with no owner record is the unknown/legacy
    // case and keeps the idempotent 204.
    const owner = await readJobOwner(c.env, id);
    if (owner !== null && owner !== c.get("deviceId")) {
      return c.json({ ok: false, error: "job_not_found" }, 404);
    }
    let handle: WorkflowInstance | null = null;
    const pipeline = c.env.PIPELINE;
    if (pipeline) {
      try {
        handle = await pipeline.get(id);
        const status = (await handle.status()).status;
        if (
          status === "queued" ||
          status === "running" ||
          status === "paused" ||
          status === "waiting" ||
          status === "waitingForPause"
        ) {
          await handle.terminate();
        }
      } catch {
        // Unknown instance: nothing to terminate; the R2 sweep below still runs.
        handle = null;
      }
    }
    await deletePrefix(c.env, jobsPrefix(id));
    // The idempotency record would otherwise keep answering replays with
    // the id of this deleted job.
    await c.env.MEDIA.delete(idemKey(id));
    // Remove the instance record too, so a later GET reports 404
    // (job_not_found) instead of 500 (result_missing for swept keys).
    if (handle) {
      try {
        await handle.delete();
      } catch {
        // Already terminal: the sweep above is what matters.
      }
    }
    return new Response(null, { status: 204 });
  });
}
