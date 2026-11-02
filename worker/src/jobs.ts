import { Hono } from "hono";
import type { Env } from "./index";
import { handleCreateJobV11 } from "./uploads";
import {
  JobEventSchema,
  JobResultSchema,
  ManifestSchema,
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
// so a concurrent emit never rewrites another event. The GET SSE endpoint
// reads them back with [readJobEvents]; no HTTP shape is added here.

const eventsPrefix = (jobId: string) => `jobs/${jobId}/events/`;
const eventKey = (jobId: string, id: string) => `jobs/${jobId}/events/${id}.json`;

/** Monotonic-ish event id: time-ordered, unique per emit. The per-isolate
 * sequence keeps same-millisecond emits in emit order under the R2 key
 * sort that [readJobEvents] relies on. */
let eventSeq = 0;
export function newEventId(): string {
  const seq = (eventSeq++).toString(36).padStart(4, "0");
  return `${Date.now().toString(36)}-${seq}-${crypto.randomUUID().slice(0, 8)}`;
}

/** Validates against the frozen W10-06 union, then stores the record. */
export async function emitJobEvent(env: Env, jobId: string, event: JobEvent): Promise<void> {
  JobEventSchema.parse(event);
  await env.MEDIA.put(eventKey(jobId, event.id), JSON.stringify(event));
}

/** All stored events for a job, in emit (key) order. Skips unreadable rows. */
export async function readJobEvents(env: Env, jobId: string): Promise<JobEvent[]> {
  const keys: string[] = [];
  let cursor: string | undefined;
  for (;;) {
    const page = await env.MEDIA.list({ prefix: eventsPrefix(jobId), cursor });
    for (const o of page.objects) keys.push(o.key);
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

async function readJson<T>(env: Env, key: string): Promise<T | null> {
  const obj = await env.MEDIA.get(key);
  if (!obj) return null;
  try {
    return (await obj.json()) as T;
  } catch {
    return null;
  }
}

export function registerJobsRoutes(app: Hono<{ Bindings: Env }>): void {
  app.post("/v1/jobs", async (c) => {
    if (!c.env.PIPELINE) {
      return c.json({ ok: false, error: "server_misconfigured" }, 500);
    }
    // W11-05: JSON bodies are v1.1 creates (uploadId/budget/idempotencyKey);
    // the frozen v1 multipart path below is untouched for v1 clients.
    if ((c.req.header("content-type") ?? "").includes("application/json")) {
      return handleCreateJobV11(c);
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
    // Storage/creation failures are 500s: the manifest above was valid,
    // so reporting invalid_manifest here would mask an outage.
    try {
      const jobId = crypto.randomUUID();
      for (const { name, file } of files) {
        await c.env.MEDIA.put(inputKey(jobId, name), await file.arrayBuffer(), {
          httpMetadata: { contentType: file.type || "application/octet-stream" },
        });
      }
      await c.env.MEDIA.put(manifestKey(jobId), JSON.stringify(manifest));
      await putProgress(c.env, jobId, "queued");
      await c.env.PIPELINE.create({ id: jobId, params: { jobId } });
      return c.json({ ok: true, jobId }, 202);
    } catch {
      return c.json({ ok: false, error: "job_create_failed" }, 500);
    }
  });

  app.get("/v1/jobs/:id", async (c) => {
    if (!c.env.PIPELINE) {
      return c.json({ ok: false, error: "server_misconfigured" }, 500);
    }
    const id = c.req.param("id");
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

  app.delete("/v1/jobs/:id", async (c) => {
    const id = c.req.param("id");
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
    const prefix = jobsPrefix(id);
    let cursor: string | undefined;
    for (;;) {
      const page = await c.env.MEDIA.list({ prefix, cursor });
      if (page.objects.length > 0) {
        await c.env.MEDIA.delete(page.objects.map((o) => o.key));
      }
      if (!page.truncated) break;
      cursor = page.cursor;
    }
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
