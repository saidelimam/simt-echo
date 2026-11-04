import { Hono, type Context } from "hono";
import type { AppEnv, Env } from "./index";
import { reserveDailySpend, spendDisabled } from "./spend";
import {
  CreateJobV11RequestSchema,
  CreateUploadRequestSchema,
  MIN_FRAMES_PER_CHECKPOINT,
  MAX_SAM_FRAMES,
  MAX_UPLOAD_BYTES,
  UploadPutParamsSchema,
  UuidSchema,
  type UploadFileRef,
} from "./types";

// W11-05 intake: content-addressed resumable uploads backing the frozen
// W10-06 v1.1 contract. R2 layout (additive to the jobs/ tree in jobs.ts):
//   jobs/staging/<uploadId>/meta.json  {files:[{name,sha256,bytes}], owner, createdAt}
//   jobs/staging/<uploadId>/<name>      staged blob, sha-verified on PUT
//   jobs/idem/<jobId>.json  {jobId} marking a completed idempotent create;
//     the job id itself is derived from the install and the idempotency key
//     (see jobIdForKey) — R12-05: the scope is the install, not the key
//     alone, so two installs that pick the same key never share a job
//   jobs/<jobId>/owner.json  {owner:<deviceId>} — R10-01: the minting
//     install that created the job; a foreign id reads it as 404
// The v1 multipart path in jobs.ts is untouched; JSON job creates dispatch here.

export const STAGING_TTL_MS = 3_600_000;
/** R12-10 (review 0012, F10): the largest JSON body a route will
 *  buffer. Manifests and session declarations are small; anything larger is
 *  an attempt to exhaust the isolate, not a request. */
export const MAX_JSON_BODY_BYTES = 256 * 1024;
/** R12-06 (F6.2): sessions one install may hold at once. Staging is
 * otherwise uncapped: 80 MB per session with only a per-IP rate limit. */
export const MAX_ACTIVE_STAGING_SESSIONS = 12;

const stagingPrefix = (uploadId: string) => `jobs/staging/${uploadId}/`;
const stagingMetaKey = (uploadId: string) => `jobs/staging/${uploadId}/meta.json`;
const stagingBlobKey = (uploadId: string, name: string) => `jobs/staging/${uploadId}/${name}`;
export const idemKey = (jobId: string) => `jobs/idem/${jobId}.json`;
const jobManifestKey = (jobId: string) => `jobs/${jobId}/manifest.json`;
export const jobOwnerKey = (jobId: string) => `jobs/${jobId}/owner.json`;

interface StoredUploadMeta {
  files: UploadFileRef[];
  /** R10-01: the install that opened this session; every read/put/delete is
   *  bound to it. */
  owner: string;
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

export async function deletePrefix(env: Env, prefix: string): Promise<void> {
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
  const m = raw as { files?: unknown; owner?: unknown; createdAt?: unknown };
  return Array.isArray(m.files) && typeof m.owner === "string" && typeof m.createdAt === "number";
}

/** R10-01: the install id a job was created by, or null when the job has no
 * owner record (a pre-R10-01 job, or a swept one). */
export async function readJobOwner(env: Env, jobId: string): Promise<string | null> {
  const rec = await readJson<{ owner?: unknown }>(env, jobOwnerKey(jobId));
  return typeof rec?.owner === "string" ? rec.owner : null;
}

/** Stamps the job with the creating install (swept with the job prefix). */
export async function writeJobOwner(env: Env, jobId: string, deviceId: string): Promise<void> {
  await env.MEDIA.put(jobOwnerKey(jobId), JSON.stringify({ owner: deviceId }));
}

async function hexSha256Hex(data: Uint8Array | string): Promise<string> {
  const bytes = typeof data === "string" ? new TextEncoder().encode(data) : data;
  const digest = await crypto.subtle.digest(
    "SHA-256",
    bytes as unknown as ArrayBuffer,
  );
  return [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

/**
 * The job id for an idempotency key: the first 128 bits of its sha256 in
 * RFC 4122 shape (version 5, variant 10), so it is a valid UUID and equal
 * keys always name the same job. That makes concurrent creates collide on
 * the Workflow instance id instead of racing to make two jobs.
 */
/**
 * The job id an install's idempotency key names: sha256 of
 * `deviceId\0idempotencyKey`, shaped like a UUIDv5.
 *
 * R12-05 (review 0012, F5): the key alone made the id global, so install B
 * guessing or replaying install A's key either read A's job back (after the
 * owner check, only as a 404) or, before it, was handed A's progress. The
 * delimiter cannot appear in either input, so no two pairs collide by
 * concatenation. Same install, same key, same id — which is what makes a
 * retry land on the same Workflow instance.
 */
async function jobIdForKey(deviceId: string, idempotencyKey: string): Promise<string> {
  const hex = await hexSha256Hex(`${deviceId}\u0000${idempotencyKey}`);
  const variant = ((parseInt(hex[16], 16) & 0x3) | 0x8).toString(16);
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-5${hex.slice(13, 16)}-${variant}${hex.slice(17, 20)}-${hex.slice(20, 32)}`;
}

/** Reads a request body, giving up (null) as soon as it exceeds `limit`
 * bytes so an oversized upload is never fully buffered. Used by the
 * upload PUTs and the relay POST (R10-07). */
export async function readBodyCapped(
  body: ReadableStream<Uint8Array> | null,
  limit: number,
): Promise<Uint8Array | null> {
  if (body === null) return new Uint8Array(0);
  const reader = body.getReader();
  const chunks: Uint8Array[] = [];
  let total = 0;
  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    total += value.byteLength;
    if (total > limit) {
      await reader.cancel();
      return null;
    }
    chunks.push(value);
  }
  const out = new Uint8Array(total);
  let offset = 0;
  for (const chunk of chunks) {
    out.set(chunk, offset);
    offset += chunk.byteLength;
  }
  return out;
}

async function instanceExists(pipeline: Workflow, jobId: string): Promise<boolean> {
  try {
    await pipeline.get(jobId);
    return true;
  } catch {
    return false;
  }
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

/** Removes idempotency records whose job no longer has a manifest (the job
 * was deleted or never finished writing): they would answer a replay with
 * the id of a job that is gone. */
async function sweepOrphanedIdem(env: Env): Promise<void> {
  const orphaned: string[] = [];
  let cursor: string | undefined;
  for (;;) {
    const page = await env.MEDIA.list({ prefix: "jobs/idem/", cursor });
    for (const o of page.objects) {
      const rec = await readJson<{ jobId?: unknown }>(env, o.key);
      const jobId = typeof rec?.jobId === "string" ? rec.jobId : null;
      if (jobId === null || (await env.MEDIA.head(jobManifestKey(jobId))) === null) {
        orphaned.push(o.key);
      }
    }
    if (!page.truncated) break;
    cursor = page.cursor;
  }
  if (orphaned.length > 0) await env.MEDIA.delete(orphaned);
}

/**
 * Best-effort sweep of staged uploads older than 1 h, then of orphaned
 * idempotency records. Groups every key under jobs/staging/ by upload id so
 * orphan blobs (meta missing or unreadable) are swept too. Returns the
 * number of uploads removed.
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
  await sweepOrphanedIdem(env);
  return swept;
}

// R10-02 (review 0010, F2): indoor photos are the privacy-sensitive payload,
// so a job's prefix — inputs, events, SAM evidence, results — is retained
// 24 h after the job reaches a terminal stage, then deleted wholesale. The
// R2 lifecycle rule in wrangler.jsonc (same window) is the final backstop
// for whatever an hourly cycle misses.
export const JOB_RETENTION_MS = 24 * 60 * 60 * 1000;
const TERMINAL_STAGES = new Set(["complete", "errored"]);

/** Workflow states that no longer need terminating before deletion. */
const TERMINAL_WORKFLOW_STATUSES = new Set(["complete", "errored", "terminated"]);

/** Drops a job's Workflow instance: step outputs (`Manifest`, scout titles,
 * descriptions, indoor `signText`, SAM masks and the sealed result) live in
 * Workflow storage, so deleting only the R2 prefix would leave the job's
 * route metadata behind after the 24 h window. Best effort — an instance
 * that never existed or is already gone is not a failure. */
async function dropJobInstance(pipeline: Workflow | undefined, jobId: string): Promise<void> {
  if (!pipeline) return;
  try {
    const handle = await pipeline.get(jobId);
    const status = (await handle.status()).status;
    if (!TERMINAL_WORKFLOW_STATUSES.has(status)) await handle.terminate();
    await handle.delete();
  } catch {
    // Unknown or already-removed instance: nothing left to drop.
  }
}

/**
 * Deletes jobs that are past the 24 h retention window, with their
 * idempotency record and Workflow instance.
 *
 * A job expires when either its progress record is terminal and older than
 * the window, or its oldest stored object is older than the window. The
 * second test is what R12-04 adds: a Workflow that terminates or times out
 * without writing a terminal progress record otherwise leaves a
 * `scouting`/`grounding` prefix — and every step's output — forever.
 * Returns the number of job prefixes deleted.
 */
export async function sweepExpiredJobs(env: Env, now: number = Date.now()): Promise<number> {
  const groups = new Map<string, { keys: string[]; oldestUploaded: number }>();
  let cursor: string | undefined;
  for (;;) {
    const page = await env.MEDIA.list({ prefix: "jobs/", cursor });
    for (const o of page.objects) {
      const rest = o.key.slice("jobs/".length);
      const slash = rest.indexOf("/");
      if (slash < 0) continue;
      const id = rest.slice(0, slash);
      if (id === "staging" || id === "idem") continue; // Sibling trees, not jobs.
      const uploaded = o.uploaded instanceof Date ? o.uploaded.getTime() : Number.NaN;
      const g = groups.get(id) ?? { keys: [], oldestUploaded: Number.NaN };
      g.keys.push(o.key);
      g.oldestUploaded = Number.isFinite(uploaded)
        ? Number.isFinite(g.oldestUploaded)
          ? Math.min(g.oldestUploaded, uploaded)
          : uploaded
        : g.oldestUploaded;
      groups.set(id, g);
    }
    if (!page.truncated) break;
    cursor = page.cursor;
  }
  let swept = 0;
  for (const [id, g] of groups) {
    const progress = await readJson<{ stage?: unknown; updatedAt?: unknown }>(
      env,
      `jobs/${id}/progress.json`,
    );
    const stage = progress?.stage;
    const updatedAt = progress?.updatedAt;
    const terminalExpired =
      typeof stage === "string" &&
      TERMINAL_STAGES.has(stage) &&
      typeof updatedAt === "number" &&
      now - updatedAt >= JOB_RETENTION_MS;
    const prefixExpired =
      Number.isFinite(g.oldestUploaded) && now - g.oldestUploaded >= JOB_RETENTION_MS;
    if (!terminalExpired && !prefixExpired) continue;
    await env.MEDIA.delete(g.keys);
    // A replay of the idempotency key must not answer with a swept job.
    await env.MEDIA.delete(idemKey(id));
    await dropJobInstance(env.PIPELINE, id);
    swept++;
  }
  return swept;
}

/**
 * R10-02: deletes relay objects whose `expiresAt` has passed even though no
 * one GETted them — `getRelay` covers the read path, this covers abandoned
 * codes. Returns the number of objects deleted.
 */
export async function sweepExpiredRelayObjects(env: Env, now: number = Date.now()): Promise<number> {
  const expired: string[] = [];
  let cursor: string | undefined;
  for (;;) {
    // R12-04 (review 0012, F4): production R2 omits customMetadata unless it
    // is requested, so the old list made every object look unknown and this
    // sweep deleted nothing. The include is part of the read contract.
    const page = await env.MEDIA.list({ prefix: "relay/", cursor, include: ["customMetadata"] });
    for (const o of page.objects) {
      const expiresAt = Number(o.customMetadata?.expiresAt ?? NaN);
      if (Number.isFinite(expiresAt) && expiresAt <= now) expired.push(o.key);
    }
    if (!page.truncated) break;
    cursor = page.cursor;
  }
  if (expired.length > 0) await env.MEDIA.delete(expired);
  return expired.length;
}

/** POST /v1/jobs with a JSON v1.1 body (jobs.ts dispatches here). */
export async function handleCreateJobV11(c: Context<AppEnv>): Promise<Response> {
  // R10-05 (F4): the kill switch precedes everything, including idempotent
  // replays — a halted deployment creates nothing, ledger untouched.
  if (spendDisabled(c.env)) {
    return c.json({ ok: false, error: "spend_disabled" }, 503);
  }
  if (!c.env.PIPELINE) {
    return c.json({ ok: false, error: "server_misconfigured" }, 500);
  }
  const raw = await readBodyCapped(c.req.raw.body, MAX_JSON_BODY_BYTES);
  if (raw === null) {
    // R12-10: read capped, never buffered first.
    return c.json({ ok: false, error: "payload_too_large" }, 413);
  }
  let body: unknown;
  try {
    body = JSON.parse(new TextDecoder().decode(raw));
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
  if (uploadId === undefined && uploadIds === undefined) {
    // R10-11 (review 0010, F10.2): a manifest-only create has no staged
    // media to move; its scout would burn a paid Workflow instance only to
    // hit media_missing. Fail at intake.
    return c.json({ ok: false, error: "upload_reference_required" }, 400);
  }

  // R10-11 (review 0010, F10.1): the idempotency record binds to the
  // payload. zod's parsed.data is canonical — declared field order, unknown
  // keys dropped — so the hash is stable across replays of the same logical
  // request and differs for a changed one.
  const payloadHash = await hexSha256Hex(JSON.stringify(parsed.data));

  // The id is derived from this install and the idempotency key (R12-05), so
  // concurrent creates from one install with one key name the same Workflow
  // instance and cannot make two jobs — while another install's identical key
  // names its own job.
  const jobId =
    idempotencyKey !== undefined
      ? await jobIdForKey(c.get("deviceId"), idempotencyKey)
      : crypto.randomUUID();

  // Idempotent replay: a completed create returns the original job id only
  // when the payload matches the recorded one — a changed payload under the
  // same key is 409, not a silent wrong job (R10-11, F10.1). Records written
  // before this deploy carry no hash and cannot prove a match: 409 as well
  // (decision: strict). A record whose job lost its manifest is stale:
  // discard it and create afresh.
  if (idempotencyKey !== undefined) {
    const record = await readJson<{ jobId?: string; payloadHash?: string }>(
      c.env,
      idemKey(jobId),
    );
    if (record !== null) {
      if ((await c.env.MEDIA.head(jobManifestKey(jobId))) !== null) {
        // R10-01/R12-05: the id is already scoped to this install, so this
        // replay is this install's own; the owner check stays as a second
        // barrier in case a prefix ever holds a foreign job id.
        if ((await readJobOwner(c.env, jobId)) !== c.get("deviceId")) {
          return c.json({ ok: false, error: "job_not_found" }, 404);
        }
        if (record.payloadHash !== payloadHash) {
          return c.json({ ok: false, error: "idempotency_conflict" }, 409);
        }
        return c.json({ ok: true, jobId }, 202);
      }
      await c.env.MEDIA.delete(idemKey(jobId));
    }
  }

  // resumeOf later becomes an R2 key prefix for cache adoption, so it must
  // name a job that exists in this deployment AND was created by this
  // install: a foreign job's SAM cache is not adoptable (R10-01).
  if (
    resumeOf !== undefined &&
    ((await c.env.MEDIA.head(jobManifestKey(resumeOf))) === null ||
      (await readJobOwner(c.env, resumeOf)) !== c.get("deviceId"))
  ) {
    return c.json({ ok: false, error: "resume_not_found" }, 404);
  }

  // Staged blobs to move under the job. One session (uploadId) or several
  // per-window sessions (uploadIds, W11-09): completeness is measured over
  // the union of staged names.
  // R10-10 (review 0010, F9): the move streams each blob R2→R2, one at a
  // time — the old path held every staged byte (up to the 80 MB cap) in
  // memory at once. Caps are measured on the stored sizes, never the
  // declared ones.
  const move: Array<{ name: string; source: string }> = [];
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
      // R12-01 (review 0012, F1): a session of another install is
      // indistinguishable from a missing one, and the check precedes every
      // list, head, copy and consume below — a foreign create can neither
      // read the staged media nor destroy the owner's session.
      if (loaded.meta.owner !== c.get("deviceId")) {
        return c.json({ ok: false, error: "upload_not_found" }, 404);
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
    // Caps are measured on stored sizes (head), so no body is read here.
    // R10-11 (F10.3): a name staged in two sessions is a client error — the
    // old first-wins move silently dropped one of the two payloads.
    let totalBytes = 0;
    const seen = new Set<string>();
    for (const { id } of loadedBySession) {
      for (const name of allowed) {
        const head = await c.env.MEDIA.head(stagingBlobKey(id, name));
        if (!head) continue;
        if (seen.has(name)) {
          return c.json({ ok: false, error: "duplicate_file", file: name }, 400);
        }
        seen.add(name);
        move.push({ name, source: stagingBlobKey(id, name) });
        totalBytes += head.size;
      }
    }
    if (move.length > 60) {
      return c.json({ ok: false, error: "too_many_files" }, 413);
    }
    if (totalBytes > MAX_UPLOAD_BYTES) {
      return c.json({ ok: false, error: "payload_too_large" }, 413);
    }
  }

  // R10-05 (F4): reserve the job's worst case before the move consumes
  // staging. A later failure leaves the reservation standing —
  // over-reserving toward the cap, never under. Over the cap is 503; a
  // contention storm or a corrupt ledger is 500 — nothing was written.
  // R12-06 (F6.1): the worst case is the larger of the declared SAM budget
  // and one floor per checkpoint, because scout runs over every checkpoint
  // regardless of the budget. `maxSamFrames: 1` over 40 checkpoints used to
  // reserve one frame for 40 checkpoints of vendor calls.
  const reserveFrames = Math.max(
    budget?.maxSamFrames ?? MAX_SAM_FRAMES,
    manifest.checkpoints.length * MIN_FRAMES_PER_CHECKPOINT,
  );
  const reserve = await reserveDailySpend(c.env, reserveFrames);
  if (!reserve.ok) {
    return reserve.reason === "over_cap"
      ? c.json({ ok: false, error: "daily_budget_exceeded" }, 503)
      : c.json({ ok: false, error: "job_create_failed" }, 500);
  }

  // Set once the Workflow instance exists: from then on the job owns its
  // files and a failure must not delete them.
  let instanceCreated = false;
  try {
    for (const { name, source } of move) {
      const obj = await c.env.MEDIA.get(source);
      if (!obj?.body) continue; // Vanished since the head: skip, as before.
      await c.env.MEDIA.put(`jobs/${jobId}/in/${name}`, obj.body);
    }
    await c.env.MEDIA.put(`jobs/${jobId}/manifest.json`, JSON.stringify(manifest));
    // R10-01: bind the job to the creating install.
    await writeJobOwner(c.env, jobId, c.get("deviceId"));
    // W11-06: the SAM lens governor reads this (never the request body —
    // the Workflow only gets the job id). An absent file caps at the hard
    // 240-frame ceiling (v1 creates); resumeOf is the only sanctioned
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
    try {
      await c.env.PIPELINE.create({ id: jobId, params: { jobId } });
    } catch (e) {
      // A concurrent create with the same key won the instance id: this
      // request is its replay, not a failure.
      if (idempotencyKey === undefined || !(await instanceExists(c.env.PIPELINE, jobId))) throw e;
    }
    instanceCreated = true;
    if (idempotencyKey !== undefined) {
      // R10-11 (F10.1): record the payload hash the create was made with, so
      // a later replay can be checked against it (a mismatch is 409).
      await c.env.MEDIA.put(
        idemKey(jobId),
        JSON.stringify({ jobId, payloadHash }),
      );
    }
    // The move consumes the staging areas: single-use uploads cannot seed
    // a second job, and replay arrives via the idempotency map above.
    for (const prefix of consumePrefixes) {
      await deletePrefix(c.env, prefix);
    }
    return c.json({ ok: true, jobId }, 202);
  } catch {
    // No instance means nothing will read the partial writes: drop them
    // (staging is untouched, so the client can retry the create). A keyed
    // create whose id another request already owns leaves them alone.
    if (!instanceCreated) {
      try {
        if (!(await instanceExists(c.env.PIPELINE, jobId))) {
          await deletePrefix(c.env, `jobs/${jobId}/`);
        }
      } catch {
        // Best effort: an unreferenced job prefix is inert.
      }
    }
    return c.json({ ok: false, error: "job_create_failed" }, 500);
  }
}

/** Live staged sessions this install holds. Counting metas is O(sessions),
 * and the cap keeps that small (R12-06). */
async function activeStagingSessions(
  env: Env,
  deviceId: string,
  now: number,
): Promise<number> {
  let count = 0;
  let cursor: string | undefined;
  for (;;) {
    const page = await env.MEDIA.list({ prefix: "jobs/staging/", cursor });
    for (const o of page.objects) {
      if (!o.key.endsWith("/meta.json")) continue;
      const meta = await readJson<StoredUploadMeta>(env, o.key);
      if (meta?.owner === deviceId && !isExpired(meta.createdAt, now)) count++;
    }
    if (!page.truncated) break;
    cursor = page.cursor;
  }
  return count;
}


/** R12-10 (review 0012, F10): the bytes decide the type, never the header.
 * JPEG opens `FF D8 FF`; MP4 carries an `ftyp` box starting at offset 4. */
export function mediaTypeOf(bytes: Uint8Array): "image/jpeg" | "video/mp4" | null {
  if (bytes.length >= 3 && bytes[0] === 0xff && bytes[1] === 0xd8 && bytes[2] === 0xff) {
    return "image/jpeg";
  }
  if (
    bytes.length >= 8 &&
    bytes[4] === 0x66 &&
    bytes[5] === 0x74 &&
    bytes[6] === 0x79 &&
    bytes[7] === 0x70
  ) {
    return "video/mp4";
  }
  return null;
}

/** The media type a caller may store: the sniffed one, and only when any
 * declared type agrees with it. `null` means the payload was refused. */
export function acceptedMediaType(
  bytes: Uint8Array,
  declared: string | null | undefined,
): "image/jpeg" | "video/mp4" | null {
  const sniffed = mediaTypeOf(bytes);
  if (sniffed === null) return null;
  // R12-10: the declared type must name the sniffed one. No octet-stream
  // escape hatch — "no type" is exactly what a mislabelled upload looks like,
  // and the client always declares the media type it built.
  const header = (declared ?? "").split(";")[0].trim().toLowerCase();
  if (header !== sniffed) return null;
  return sniffed;
}

export function registerUploadRoutes(app: Hono<AppEnv>): void {
  app.post("/v1/uploads", async (c) => {
    // R12-06 (F6.2): the kill switch is account-wide, so it stops staging
    // too — otherwise a halted deployment still accepts 80 MB per session.
    if (spendDisabled(c.env)) {
      return c.json({ ok: false, error: "spend_disabled" }, 503);
    }
    // R12-06 (F6.2): a per-install ceiling on live sessions, enforced before
    // the body is read. Expired sessions are outside the window and swept, so
    // they do not count.
    const active = await activeStagingSessions(c.env, c.get("deviceId"), Date.now());
    if (active >= MAX_ACTIVE_STAGING_SESSIONS) {
      return c.json({ ok: false, error: "staging_quota_exceeded" }, 429);
    }
    const raw = await readBodyCapped(c.req.raw.body, MAX_JSON_BODY_BYTES);
    if (raw === null) {
      // R12-10: read capped, never buffered first.
      return c.json({ ok: false, error: "payload_too_large" }, 413);
    }
    let body: unknown;
    try {
      body = JSON.parse(new TextDecoder().decode(raw));
    } catch {
      return c.json({ ok: false, error: "invalid_request" }, 400);
    }
    const parsed = CreateUploadRequestSchema.safeParse(body);
    if (!parsed.success) {
      // A single declared file over the cap keeps the documented 413.
      const oversize = parsed.error.issues.some(
        (i) => i.code === "too_big" && i.path[i.path.length - 1] === "bytes",
      );
      return oversize
        ? c.json({ ok: false, error: "payload_too_large" }, 413)
        : c.json({ ok: false, error: "invalid_request" }, 400);
    }
    const { files } = parsed.data;
    const names = files.map((f) => f.name);
    if (files.reduce((sum, f) => sum + f.bytes, 0) > MAX_UPLOAD_BYTES) {
      return c.json({ ok: false, error: "payload_too_large" }, 413);
    }
    try {
      const uploadId = crypto.randomUUID();
      const meta: StoredUploadMeta = { files, owner: c.get("deviceId"), createdAt: Date.now() };
      await c.env.MEDIA.put(stagingMetaKey(uploadId), JSON.stringify(meta));
      // R10-09 (review 0010, F8): no opportunistic sweep here. It cost
      // O(staging + idem) list/GET/HEAD ops on every create — quadratically
      // punishing the bursty ready-on-arrival client this endpoint serves.
      // Cleanup is owned off the request path: the hourly cron runs
      // sweepExpiredUploads (index.ts), loadUploadMeta lazily expires each
      // session on its own reads, and the jobs/ lifecycle rule is the
      // backstop.
      return c.json({ ok: true, uploadId, missing: names });
    } catch {
      return c.json({ ok: false, error: "upload_create_failed" }, 500);
    }
  });

  app.get("/v1/uploads/:uploadId", async (c) => {
    const uploadId = c.req.param("uploadId");
    if (!UuidSchema.safeParse(uploadId).success) {
      return c.json({ ok: false, error: "upload_not_found" }, 404);
    }
    const loaded = await loadUploadMeta(c.env, uploadId, Date.now());
    if ("error" in loaded) {
      return loaded.error === "expired"
        ? c.json({ ok: false, error: "upload_expired" }, 410)
        : c.json({ ok: false, error: "upload_not_found" }, 404);
    }
    // R10-01: another install's session is indistinguishable from a missing one.
    if (loaded.meta.owner !== c.get("deviceId")) {
      return c.json({ ok: false, error: "upload_not_found" }, 404);
    }
    const missing = missingOf(loaded.meta, await stagedNames(c.env, uploadId));
    return c.json({ ok: true, uploadId, missing });
  });

  app.put("/v1/uploads/:uploadId/:name", async (c) => {
    // R12-06 (F6.2): the kill switch also stops writes into live sessions.
    if (spendDisabled(c.env)) {
      return c.json({ ok: false, error: "spend_disabled" }, 503);
    }
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
    // R10-01: a foreign session cannot be written into.
    if (loaded.meta.owner !== c.get("deviceId")) {
      return c.json({ ok: false, error: "upload_not_found" }, 404);
    }
    const declared = loaded.meta.files.find((f) => f.name === name);
    if (!declared) {
      return c.json({ ok: false, error: "unknown_file" }, 400);
    }
    // The declared size is the contract: refuse a mismatching Content-Length
    // before reading anything, and cap the read for bodies without one.
    const contentLength = c.req.header("content-length");
    if (contentLength !== undefined && Number(contentLength) !== declared.bytes) {
      return c.json({ ok: false, error: "size_mismatch" }, 400);
    }
    const body = await readBodyCapped(c.req.raw.body, declared.bytes);
    if (body === null || body.byteLength !== declared.bytes) {
      return c.json({ ok: false, error: "size_mismatch" }, 400);
    }
    const bytes = body.buffer as ArrayBuffer;
    const actual = await hexSha256Hex(body);
    if (actual !== declared.sha256) {
      return c.json({ ok: false, error: "sha_mismatch" }, 400);
    }
    // R12-10: a staged blob is media, not an opaque stream — the stored type
    // comes from the bytes, and a declared type that contradicts them is a
    // client error rather than something to preserve.
    const mediaType = acceptedMediaType(body, c.req.header("content-type"));
    if (mediaType === null) {
      return c.json({ ok: false, error: "unsupported_media_type" }, 415);
    }
    try {
      await c.env.MEDIA.put(stagingBlobKey(uploadId, name), bytes, {
        httpMetadata: { contentType: mediaType },
      });
    } catch {
      return c.json({ ok: false, error: "upload_failed" }, 500);
    }
    return c.json({ ok: true, name });
  });

  // W11-09 ready-on-arrival: cancel/discard deletes staged uploads.
  // Idempotent like the job DELETE — unknown or expired sessions are
  // already gone, so both answer 204 with nothing to sweep. R10-01: a
  // session owned by another install is a 404, so the delete cannot be
  // used to probe or destroy it.
  app.delete("/v1/uploads/:uploadId", async (c) => {
    const uploadId = c.req.param("uploadId");
    // The id becomes an R2 prefix: only a UUID names a staging session.
    if (!UuidSchema.safeParse(uploadId).success) {
      return c.json({ ok: false, error: "invalid_request" }, 400);
    }
    const loaded = await loadUploadMeta(c.env, uploadId, Date.now());
    if ("meta" in loaded) {
      if (loaded.meta.owner !== c.get("deviceId")) {
        return c.json({ ok: false, error: "upload_not_found" }, 404);
      }
      await deletePrefix(c.env, stagingPrefix(uploadId));
    }
    return new Response(null, { status: 204 });
  });
}
