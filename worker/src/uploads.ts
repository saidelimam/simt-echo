import { Hono, type Context } from "hono";
import type { Env } from "./index";
import {
  CreateJobV11RequestSchema,
  CreateUploadRequestSchema,
  UploadPutParamsSchema,
  type UploadFileRef,
} from "./types";

// W11-05 intake: content-addressed resumable uploads backing the frozen
// W10-06 v1.1 contract. R2 layout (additive to the jobs/ tree in jobs.ts):
//   jobs/staging/<uploadId>/meta.json  {files:[{name,sha256,bytes}], createdAt}
//   jobs/staging/<uploadId>/<name>      staged blob, sha-verified on PUT
//   jobs/idem/<sha256(idempotencyKey)>.json  {jobId} for idempotent job create
// The v1 multipart path in jobs.ts is untouched; JSON job creates dispatch here.

export const STAGING_TTL_MS = 3_600_000;
const MAX_TOTAL_BYTES = 80 * 1024 * 1024;

const stagingPrefix = (uploadId: string) => `jobs/staging/${uploadId}/`;
const stagingMetaKey = (uploadId: string) => `jobs/staging/${uploadId}/meta.json`;
const stagingBlobKey = (uploadId: string, name: string) => `jobs/staging/${uploadId}/${name}`;
const idemKey = (keyHex: string) => `jobs/idem/${keyHex}.json`;

interface StoredUploadMeta {
  files: UploadFileRef[];
  createdAt: number;
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

async function deletePrefix(env: Env, prefix: string): Promise<void> {
  let cursor: string | undefined;
  for (;;) {
    const page = await env.MEDIA.list({ prefix, cursor });
    if (page.objects.length > 0) {
      await env.MEDIA.delete(page.objects.map((o) => o.key));
    }
    if (!page.truncated) break;
    cursor = page.cursor;
  }
}

function isExpired(createdAt: number, now: number): boolean {
  return now - createdAt > STAGING_TTL_MS;
}

function isValidMeta(raw: unknown): raw is StoredUploadMeta {
  if (typeof raw !== "object" || raw === null) return false;
  const m = raw as { files?: unknown; createdAt?: unknown };
  return Array.isArray(m.files) && typeof m.createdAt === "number";
}

async function hexSha256Hex(data: Uint8Array | string): Promise<string> {
  const bytes = typeof data === "string" ? new TextEncoder().encode(data) : data;
  const digest = await crypto.subtle.digest(
    "SHA-256",
    bytes as unknown as ArrayBuffer,
  );
  return [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

/** Loads the staging record, sweeping it when it is older than 1 h. */
async function loadUploadMeta(
  env: Env,
  uploadId: string,
  now: number,
): Promise<{ meta: StoredUploadMeta } | { error: "not_found" } | { error: "expired" }> {
  const raw = await readJson<unknown>(env, stagingMetaKey(uploadId));
  if (!isValidMeta(raw)) return { error: "not_found" };
  if (isExpired(raw.createdAt, now)) {
    await deletePrefix(env, stagingPrefix(uploadId));
    return { error: "expired" };
  }
  return { meta: raw };
}

/** Names of blobs already staged under the upload (excludes meta.json). */
async function stagedNames(env: Env, uploadId: string): Promise<Set<string>> {
  const prefix = stagingPrefix(uploadId);
  const names = new Set<string>();
  let cursor: string | undefined;
  for (;;) {
    const page = await env.MEDIA.list({ prefix, cursor });
    for (const o of page.objects) {
      const suffix = o.key.slice(prefix.length);
      if (suffix !== "" && suffix !== "meta.json" && !suffix.includes("/")) names.add(suffix);
    }
    if (!page.truncated) break;
    cursor = page.cursor;
  }
  return names;
}

function missingOf(meta: StoredUploadMeta, staged: Set<string>): string[] {
  return meta.files.map((f) => f.name).filter((n) => !staged.has(n));
}

/**
 * Best-effort sweep of staged uploads older than 1 h. Groups every key
 * under jobs/staging/ by upload id so orphan blobs (meta missing or
 * unreadable) are swept too. Returns the number of uploads removed.
 */
export async function sweepExpiredUploads(env: Env, now: number = Date.now()): Promise<number> {
  const groups = new Map<string, { keys: string[]; metaKey: string | null }>();
  let cursor: string | undefined;
  for (;;) {
    const page = await env.MEDIA.list({ prefix: "jobs/staging/", cursor });
    for (const o of page.objects) {
      const rest = o.key.slice("jobs/staging/".length);
      const slash = rest.indexOf("/");
      if (slash < 0) continue;
      const id = rest.slice(0, slash);
      let g = groups.get(id);
      if (!g) {
        g = { keys: [], metaKey: null };
        groups.set(id, g);
      }
      g.keys.push(o.key);
      if (rest.slice(slash + 1) === "meta.json") g.metaKey = o.key;
    }
    if (!page.truncated) break;
    cursor = page.cursor;
  }
  let swept = 0;
  for (const g of groups.values()) {
    let createdAt: number | null = null;
    if (g.metaKey) {
      const raw = await readJson<unknown>(env, g.metaKey);
      if (isValidMeta(raw)) createdAt = raw.createdAt;
    }
    if (createdAt === null || isExpired(createdAt, now)) {
      await env.MEDIA.delete(g.keys);
      swept++;
    }
  }
  return swept;
}

/** POST /v1/jobs with a JSON v1.1 body (jobs.ts dispatches here). */
export async function handleCreateJobV11(c: Context<{ Bindings: Env }>): Promise<Response> {
  if (!c.env.PIPELINE) {
    return c.json({ ok: false, error: "server_misconfigured" }, 500);
  }
  let body: unknown;
  try {
    body = await c.req.json();
  } catch {
    return c.json({ ok: false, error: "invalid_request" }, 400);
  }
  const parsed = CreateJobV11RequestSchema.safeParse(body);
  if (!parsed.success) {
    return c.json({ ok: false, error: "invalid_request" }, 400);
  }
  const { manifest, uploadId, uploadIds, budget, idempotencyKey, resumeOf } = parsed.data;
  if (!manifest.consent.sendFramesToCloudAi) {
    return c.json({ ok: false, error: "consent_required" }, 400);
  }
  if (uploadId !== undefined && uploadIds !== undefined) {
    return c.json({ ok: false, error: "invalid_request" }, 400);
  }

  // Idempotent replay: a stored key returns the original job id without
  // touching staging, so double-create is safe even after the move below.
  let idemHex: string | null = null;
  if (idempotencyKey !== undefined) {
    idemHex = await hexSha256Hex(idempotencyKey);
    const hit = await readJson<{ jobId?: unknown }>(c.env, idemKey(idemHex));
    if (hit !== null && typeof hit.jobId === "string" && hit.jobId !== "") {
      return c.json({ ok: true, jobId: hit.jobId }, 202);
    }
  }

  // Staged blobs to move under the job (manifest-only creates skip this).
  // One session (uploadId) or several per-window sessions (uploadIds,
  // W11-09): completeness is measured over the union of staged names.
  let staged: Array<{ name: string; bytes: ArrayBuffer }> = [];
  const sessionIds = uploadIds ?? (uploadId !== undefined ? [uploadId] : []);
  // The move consumes every staging area below: single-use uploads cannot
  // seed a second job, and replay arrives via the idempotency map above.
  const consumePrefixes = sessionIds.map(stagingPrefix);
  if (sessionIds.length > 0) {
    const loadedBySession: Array<{ id: string; meta: StoredUploadMeta }> = [];
    for (const sessionId of sessionIds) {
      const loaded = await loadUploadMeta(c.env, sessionId, Date.now());
      if ("error" in loaded) {
        return loaded.error === "expired"
          ? c.json({ ok: false, error: "upload_expired" }, 410)
          : c.json({ ok: false, error: "upload_not_found" }, 404);
      }
      loadedBySession.push({ id: sessionId, meta: loaded.meta });
    }
    const allowed = new Set(
      manifest.checkpoints.flatMap((k) => [
        ...k.keyframes.map((f) => f.id),
        ...(k.clip ? [k.clip.id] : []),
      ]),
    );
    const present = new Set<string>();
    for (const { id } of loadedBySession) {
      for (const name of await stagedNames(c.env, id)) present.add(name);
    }
    const missing = [...allowed].filter((n) => !present.has(n));
    if (missing.length > 0) {
      return c.json({ ok: false, error: "upload_incomplete", missing }, 400);
    }
    // Caps are measured on actual PUT bytes, never the declared sizes.
    let totalBytes = 0;
    const seen = new Set<string>();
    for (const { id } of loadedBySession) {
      for (const name of allowed) {
        if (seen.has(name)) continue;
        const obj = await c.env.MEDIA.get(stagingBlobKey(id, name));
        if (!obj) continue;
        seen.add(name);
        const bytes = await obj.arrayBuffer();
        totalBytes += bytes.byteLength;
        staged.push({ name, bytes });
      }
    }
    if (staged.length > 60) {
      return c.json({ ok: false, error: "too_many_files" }, 413);
    }
    if (totalBytes > MAX_TOTAL_BYTES) {
      return c.json({ ok: false, error: "payload_too_large" }, 413);
    }
  }

  try {
    const jobId = crypto.randomUUID();
    for (const { name, bytes } of staged) {
      await c.env.MEDIA.put(`jobs/${jobId}/in/${name}`, bytes);
    }
    await c.env.MEDIA.put(`jobs/${jobId}/manifest.json`, JSON.stringify(manifest));
    // W11-06: the SAM lens governor reads this (never the request body —
    // the Workflow only gets the job id). An absent file caps at the hard
    // 240-frame ceiling (v1/manifest-only creates); resumeOf is the only sanctioned
    // cross-job SAM-cache reuse (W10-06 scoping).
    if (budget !== undefined || resumeOf !== undefined) {
      await c.env.MEDIA.put(
        `jobs/${jobId}/budget.json`,
        JSON.stringify({
          ...(budget !== undefined ? { maxSamFrames: budget.maxSamFrames } : {}),
          ...(resumeOf !== undefined ? { resumeOf } : {}),
        }),
      );
    }
    await c.env.MEDIA.put(
      `jobs/${jobId}/progress.json`,
      JSON.stringify({ stage: "queued", updatedAt: Date.now() }),
    );
    await c.env.PIPELINE.create({ id: jobId, params: { jobId } });
    if (idemHex !== null && idempotencyKey !== undefined) {
      await c.env.MEDIA.put(idemKey(idemHex), JSON.stringify({ jobId, idempotencyKey }));
    }
    // The move consumes the staging areas: single-use uploads cannot seed
    // a second job, and replay arrives via the idempotency map above.
    for (const prefix of consumePrefixes) {
      await deletePrefix(c.env, prefix);
    }
    return c.json({ ok: true, jobId }, 202);
  } catch {
    return c.json({ ok: false, error: "job_create_failed" }, 500);
  }
}

export function registerUploadRoutes(app: Hono<{ Bindings: Env }>): void {
  app.post("/v1/uploads", async (c) => {
    let body: unknown;
    try {
      body = await c.req.json();
    } catch {
      return c.json({ ok: false, error: "invalid_request" }, 400);
    }
    const parsed = CreateUploadRequestSchema.safeParse(body);
    if (!parsed.success) {
      return c.json({ ok: false, error: "invalid_request" }, 400);
    }
    const { files } = parsed.data;
    const names = files.map((f) => f.name);
    if (new Set(names).size !== names.length) {
      return c.json({ ok: false, error: "invalid_request" }, 400);
    }
    if (files.reduce((sum, f) => sum + f.bytes, 0) > MAX_TOTAL_BYTES) {
      return c.json({ ok: false, error: "payload_too_large" }, 413);
    }
    try {
      const uploadId = crypto.randomUUID();
      const meta: StoredUploadMeta = { files, createdAt: Date.now() };
      await c.env.MEDIA.put(stagingMetaKey(uploadId), JSON.stringify(meta));
      // Opportunistic expiry sweep; never fails the create itself.
      try {
        await sweepExpiredUploads(c.env);
      } catch {
        // Stale staging stays until the next sweep; the create succeeded.
      }
      return c.json({ ok: true, uploadId, missing: names });
    } catch {
      return c.json({ ok: false, error: "upload_create_failed" }, 500);
    }
  });

  app.get("/v1/uploads/:uploadId", async (c) => {
    const uploadId = c.req.param("uploadId");
    const loaded = await loadUploadMeta(c.env, uploadId, Date.now());
    if ("error" in loaded) {
      return loaded.error === "expired"
        ? c.json({ ok: false, error: "upload_expired" }, 410)
        : c.json({ ok: false, error: "upload_not_found" }, 404);
    }
    const missing = missingOf(loaded.meta, await stagedNames(c.env, uploadId));
    return c.json({ ok: true, uploadId, missing });
  });

  app.put("/v1/uploads/:uploadId/:name", async (c) => {
    const params = UploadPutParamsSchema.safeParse({
      uploadId: c.req.param("uploadId"),
      name: c.req.param("name"),
    });
    if (!params.success) {
      return c.json({ ok: false, error: "invalid_request" }, 400);
    }
    const { uploadId, name } = params.data;
    const loaded = await loadUploadMeta(c.env, uploadId, Date.now());
    if ("error" in loaded) {
      return loaded.error === "expired"
        ? c.json({ ok: false, error: "upload_expired" }, 410)
        : c.json({ ok: false, error: "upload_not_found" }, 404);
    }
    const declared = loaded.meta.files.find((f) => f.name === name);
    if (!declared) {
      return c.json({ ok: false, error: "unknown_file" }, 400);
    }
    const bytes = await c.req.arrayBuffer();
    const actual = await hexSha256Hex(new Uint8Array(bytes));
    if (actual !== declared.sha256) {
      return c.json({ ok: false, error: "sha_mismatch" }, 400);
    }
    try {
      await c.env.MEDIA.put(stagingBlobKey(uploadId, name), bytes, {
        httpMetadata: {
          contentType: c.req.header("content-type") || "application/octet-stream",
        },
      });
    } catch {
      return c.json({ ok: false, error: "upload_failed" }, 500);
    }
    return c.json({ ok: true, name });
  });

  // W11-09 ready-on-arrival: cancel/discard deletes staged uploads.
  // Idempotent like the job DELETE — unknown or expired sessions are
  // already gone, so both answer 204 with nothing to sweep.
  app.delete("/v1/uploads/:uploadId", async (c) => {
    const uploadId = c.req.param("uploadId");
    await deletePrefix(c.env, stagingPrefix(uploadId));
    return new Response(null, { status: 204 });
  });
}
