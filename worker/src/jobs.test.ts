import { describe, expect, it } from "vitest";
import type { WorkflowStep as RuntimeWorkflowStep } from "cloudflare:workers";
import { app } from "./index";
import { JobResultSchema } from "./types";
import { bearer, seedDevice } from "./testkit";
import {
  MAX_APPROACH_SAMPLES,
  MAX_CHECKPOINTS,
  MAX_DRAFT_ID_LENGTH,
  MAX_KEYFRAMES_PER_CHECKPOINT,
  MAX_ROUTE_CONTEXT_LENGTH,
} from "./types";

// R10-01: the bearer is a per-install device key. Two fixed installs prove
// isolation; the minted key in the isolation test proves the entropy path.
const KEY = "dk_test_install_one";
const OTHER = "dk_test_install_two";

// R10-10: the intake move puts a stream through, so the fake stores what R2
// would — a drained body. Consumed exactly once, like a real object body.
async function streamToArrayBuffer(stream: ReadableStream<Uint8Array>): Promise<ArrayBuffer> {
  const reader = stream.getReader();
  const chunks: Uint8Array[] = [];
  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    if (value) chunks.push(value);
  }
  const out = new Uint8Array(chunks.reduce((n, c) => n + c.byteLength, 0));
  let off = 0;
  for (const c of chunks) {
    out.set(c, off);
    off += c.byteLength;
  }
  return out.buffer;
}

// In-memory R2 stand-in: only the surface jobs.ts touches.
class FakeR2 {
  store = new Map<string, { body: ArrayBuffer; contentType?: string; etag: string }>();
  private seq = 0;

  async put(
    key: string,
    value: ArrayBuffer | Uint8Array | string | ReadableStream<Uint8Array>,
    opts?: { httpMetadata?: { contentType?: string }; onlyIf?: { etagMatches?: string } },
  ): Promise<null> {
    // R10-05: honor R2's etag semantics — a conditional put fails when the
    // etag moved or the object does not exist.
    if (opts?.onlyIf?.etagMatches !== undefined) {
      const entry = this.store.get(key);
      if (!entry || entry.etag !== opts.onlyIf.etagMatches) throw new Error("etag mismatch");
    }
    // Normalize to a detached ArrayBuffer: a Uint8Array view aliases its pool
    // buffer, and TS's generics treat the view as a distinct type. R10-10: a
    // stream is consumed eagerly so the stored body mirrors R2's stored size.
    const body =
      value instanceof ReadableStream
        ? await streamToArrayBuffer(value)
        : typeof value === "string"
          ? (new TextEncoder().encode(value).buffer as ArrayBuffer)
          : value instanceof Uint8Array
            ? (value.buffer.slice(value.byteOffset, value.byteOffset + value.byteLength) as ArrayBuffer)
            : value;
    this.seq++;
    this.store.set(key, { body, contentType: opts?.httpMetadata?.contentType, etag: `e${this.seq}` });
    return null;
  }

  async get(key: string) {
    const entry = this.store.get(key);
    if (!entry) return null;
    return {
      httpEtag: entry.etag,
      body: new Response(entry.body).body,
      size: entry.body.byteLength,
      text: async () => new TextDecoder().decode(entry.body),
      arrayBuffer: async () => entry.body.slice(0),
      json: async <T,>() => JSON.parse(new TextDecoder().decode(entry.body)) as T,
    };
  }

  // R10-10: the intake caps measure stored sizes via head — mirror R2.
  head = async (key: string) =>
    this.store.has(key) ? { key, size: this.store.get(key)!.body.byteLength } : null;

  async list({ prefix }: { prefix?: string; cursor?: string }) {
    const keys = [...this.store.keys()].filter((k) => !prefix || k.startsWith(prefix)).sort();
    return { objects: keys.map((key) => ({ key })), truncated: false as const, delimitedPrefixes: [] };
  }

  async delete(keys: string | string[]): Promise<void> {
    for (const k of Array.isArray(keys) ? keys : [keys]) this.store.delete(k);
  }
}

type InstanceStatus = "queued" | "running" | "complete" | "errored" | "terminated";

class FakeInstance {
  terminated = false;
  deleted = false;
  constructor(
    public readonly id: string,
    public statusValue: InstanceStatus,
    private readonly onDelete: (id: string) => void,
  ) {}
  async status() {
    return { status: this.statusValue };
  }
  async terminate() {
    this.terminated = true;
    this.statusValue = "terminated";
  }
  async delete() {
    this.deleted = true;
    this.onDelete(this.id);
  }
}

class FakePipeline {
  instances = new Map<string, FakeInstance>();
  initial: InstanceStatus = "queued";

  async create({ id }: { id?: string; params?: unknown }) {
    const inst = new FakeInstance(id ?? crypto.randomUUID(), this.initial, (gone) =>
      this.instances.delete(gone),
    );
    this.instances.set(inst.id, inst);
    return inst;
  }

  async get(id: string) {
    const inst = this.instances.get(id);
    if (!inst || inst.deleted) throw new Error(`unknown instance ${id}`);
    return inst;
  }
}

async function envFor(media: FakeR2, pipeline?: FakePipeline) {
  // R10-01: register the test install so every authenticated request resolves.
  await seedDevice(media, KEY);
  return {
    ECHO_APP_TOKEN: "retired",
    MEDIA: media as never,
    ...(pipeline ? { PIPELINE: pipeline as never } : {}),
  } as never;
}

function validManifest() {
  return {
    schema: 1,
    draftId: "draft-1",
    consent: { sendFramesToCloudAi: true, atEpochMs: 1762000000000 },
    routeContext: "Indoor walk, 62 m, 3 turns, 1 level change",
    checkpoints: [
      {
        nodeId: "n2",
        type: "TURN",
        turn: "LEFT",
        atDistanceM: 23.5,
        tMs: 41250,
        heroKeyframeId: "kf_n2_1",
        keyframes: [
          { id: "kf_n2_0", tMs: 39250 },
          { id: "kf_n2_1", tMs: 40750 },
        ],
        clip: { id: "clip_n2", startMs: 37250, endMs: 42250, frameCount: 50, durationMs: 5000 },
        approach: [
          { tMs: 37250, d: 18.9 },
          { tMs: 37750, d: 19.4 },
        ],
      },
    ],
  };
}

// R12-10: media fixtures carry the container header the server sniffs.
const JPEG_MAGIC = [0xff, 0xd8, 0xff];
const MP4_MAGIC = [0x00, 0x00, 0x00, 0x20, 0x66, 0x74, 0x79, 0x70];
function mediaOf(name: string, data: readonly number[] | Uint8Array): Uint8Array {
  const body = data instanceof Uint8Array ? data : new Uint8Array(data);
  const magic = name.startsWith("clip") ? MP4_MAGIC : JPEG_MAGIC;
  if (magic.every((byte, i) => body[i] === byte)) return body;
  const out = new Uint8Array(magic.length + body.length);
  out.set(magic, 0);
  out.set(body, magic.length);
  return out;
}

function postJob(
  env: never,
  manifest: unknown,
  files: Array<{ id: string; bytes: Uint8Array; type: string }> = [
    { id: "kf_n2_0", bytes: new Uint8Array([1, 2, 3]), type: "image/jpeg" },
    { id: "kf_n2_1", bytes: new Uint8Array([4, 5, 6]), type: "image/jpeg" },
    { id: "clip_n2", bytes: new Uint8Array([7, 8, 9]), type: "video/mp4" },
  ],
) {
  const form = new FormData();
  form.append("manifest", typeof manifest === "string" ? manifest : JSON.stringify(manifest));
  for (const f of files)
    form.append(f.id, new File([mediaOf(f.id, f.bytes)], f.id, { type: f.type }));
  return app.request(
    "/v1/jobs",
    { method: "POST", headers: bearer(KEY), body: form },
    env,
  );
}

describe("POST /v1/jobs", () => {
  it("stores allow-listed files, seeds progress, creates the workflow (202)", async () => {
    const media = new FakeR2();
    const pipeline = new FakePipeline();
    const res = await postJob(await envFor(media, pipeline), validManifest(), [
      { id: "kf_n2_0", bytes: new Uint8Array([1]), type: "image/jpeg" },
    ]);

    expect(res.status).toBe(202);
    const body = (await res.json()) as { ok: boolean; jobId: string };
    expect(body.ok).toBe(true);
    expect(body.jobId).toMatch(/^[0-9a-f-]{36}$/);

    expect(media.store.has(`jobs/${body.jobId}/manifest.json`)).toBe(true);
    expect(media.store.has(`jobs/${body.jobId}/in/kf_n2_0`)).toBe(true);
    const progress = JSON.parse(
      new TextDecoder().decode(media.store.get(`jobs/${body.jobId}/progress.json`)!.body),
    );
    expect(progress.stage).toBe("queued");
    expect(pipeline.instances.has(body.jobId)).toBe(true);
  });

  it("rejects part names the manifest does not list, and repeated parts (400)", async () => {
    const media = new FakeR2();
    const pipeline = new FakePipeline();
    const env = await envFor(media, pipeline);

    // R10-11 (F10): the old loop dropped unknown parts silently; now they are
    // client errors, as is the same part twice.
    const unknown = await postJob(env, validManifest(), [
      { id: "kf_n2_0", bytes: new Uint8Array([1]), type: "image/jpeg" },
      { id: "evil", bytes: new Uint8Array([9]), type: "image/jpeg" },
    ]);
    expect(unknown.status).toBe(400);
    expect(await unknown.json()).toEqual({ ok: false, error: "unknown_file", file: "evil" });

    const duplicate = await postJob(env, validManifest(), [
      { id: "kf_n2_0", bytes: new Uint8Array([1]), type: "image/jpeg" },
      { id: "kf_n2_0", bytes: new Uint8Array([2]), type: "image/jpeg" },
    ]);
    expect(duplicate.status).toBe(400);
    expect(await duplicate.json()).toEqual({ ok: false, error: "duplicate_file", file: "kf_n2_0" });

    expect(pipeline.instances.size).toBe(0);
    // Nothing job-related was stored (the seeded device record is not a job artifact).
    expect([...media.store.keys()].filter((k) => !k.startsWith("devices/"))).toEqual([]);
  });

  it("rejects a missing consent grant before storing anything (400)", async () => {
    const media = new FakeR2();
    const manifest = { ...validManifest(), consent: { sendFramesToCloudAi: false, atEpochMs: 0 } };
    const res = await postJob(await envFor(media, new FakePipeline()), manifest);

    expect(res.status).toBe(400);
    expect(await res.json()).toEqual({ ok: false, error: "consent_required" });
    // Nothing job-related was stored (the seeded device record is not a job artifact).
    expect([...media.store.keys()].filter((k) => !k.startsWith("devices/"))).toEqual([]);
  });

  it("rejects malformed and schema-violating manifests (400)", async () => {
    const media = new FakeR2();
    const env = await envFor(media, new FakePipeline());

    const notJson = await postJob(env, "this is not json");
    expect(notJson.status).toBe(400);
    expect(await notJson.json()).toEqual({ ok: false, error: "invalid_manifest" });

    const missingCheckpoints = await postJob(env, { ...validManifest(), checkpoints: [] });
    expect(missingCheckpoints.status).toBe(400);

    const badId = structuredClone(validManifest());
    badId.checkpoints[0].nodeId = "N2!";
    const badIdRes = await postJob(env, badId);
    expect(badIdRes.status).toBe(400);

    const turnWithoutTurn = structuredClone(validManifest());
    delete (turnWithoutTurn.checkpoints[0] as Partial<{ turn: string }>).turn;
    expect((await postJob(env, turnWithoutTurn)).status).toBe(400);

    // Nothing job-related was stored (the seeded device record is not a job artifact).
    expect([...media.store.keys()].filter((k) => !k.startsWith("devices/"))).toEqual([]);
  });

  it("rejects more than 60 files (413)", async () => {
    const media = new FakeR2();
    // 61 keyframes spread over 8 checkpoints (7×8 + 5): past the 60-file
    // limit while staying inside the per-checkpoint schema cap (R10-03), so
    // the file-count guard — not the schema — is what rejects. Media ids are
    // unique per checkpoint (R10-11) so the manifest itself is valid.
    const manifest = structuredClone(validManifest());
    manifest.checkpoints = [8, 8, 8, 8, 8, 8, 8, 5].map((count, i) => {
      const cp = structuredClone(validManifest().checkpoints[0]);
      cp.nodeId = `n${i + 2}`;
      cp.heroKeyframeId = `kf_${i}_0`;
      cp.keyframes = Array.from({ length: count }, (_, j) => ({
        id: `kf_${i}_${j}`,
        tMs: 39000 + j * 100,
      }));
      delete (cp as { clip?: unknown }).clip;
      return cp;
    });
    const files = manifest.checkpoints.flatMap((cp) =>
      cp.keyframes.map((k) => ({
        id: k.id,
        bytes: new Uint8Array([1]),
        type: "image/jpeg" as const,
      })),
    );
    const res = await postJob(await envFor(media, new FakePipeline()), manifest, files);

    expect(res.status).toBe(413);
    expect(await res.json()).toEqual({ ok: false, error: "too_many_files" });
  });

  it("rejects manifests past the fan-out caps (400) and accepts the cap boundary (202)", async () => {
    const media = new FakeR2();
    const env = await envFor(media, new FakePipeline());

    // One past each cap (R10-03, F3): every variant is rejected before
    // anything is stored. Cloned checkpoints carry unique media ids and
    // heroes (R10-11) so the cap — not the uniqueness rules — rejects.
    const tooManyCheckpoints = structuredClone(validManifest());
    while (tooManyCheckpoints.checkpoints.length < MAX_CHECKPOINTS + 1) {
      const n = tooManyCheckpoints.checkpoints.length + 2;
      const cp = structuredClone(tooManyCheckpoints.checkpoints[0]);
      cp.nodeId = `n${n}`;
      cp.heroKeyframeId = `kf_n${n}_0`;
      cp.keyframes = cp.keyframes.map((k, i) => ({ ...k, id: `kf_n${n}_${i}` }));
      delete (cp as { clip?: unknown }).clip;
      tooManyCheckpoints.checkpoints.push(cp);
    }
    expect((await postJob(env, tooManyCheckpoints)).status).toBe(400);

    const tooManyKeyframes = structuredClone(validManifest());
    tooManyKeyframes.checkpoints[0].keyframes = Array.from(
      { length: MAX_KEYFRAMES_PER_CHECKPOINT + 1 },
      (_, i) => ({ id: `kf_x${i}`, tMs: 39000 + i * 100 }),
    );
    tooManyKeyframes.checkpoints[0].heroKeyframeId = "kf_x1";
    expect((await postJob(env, tooManyKeyframes)).status).toBe(400);

    const tooMuchApproach = structuredClone(validManifest());
    tooMuchApproach.checkpoints[0].approach = Array.from(
      { length: MAX_APPROACH_SAMPLES + 1 },
      (_, i) => ({ tMs: 37000 + i * 50, d: 18 + i * 0.1 }),
    );
    expect((await postJob(env, tooMuchApproach)).status).toBe(400);

    const longDraft = await postJob(env, {
      ...validManifest(),
      draftId: "d".repeat(MAX_DRAFT_ID_LENGTH + 1),
    });
    expect(longDraft.status).toBe(400);

    const longContext = await postJob(env, {
      ...validManifest(),
      routeContext: "x".repeat(MAX_ROUTE_CONTEXT_LENGTH + 1),
    });
    expect(longContext.status).toBe(400);

    expect([...media.store.keys()].filter((k) => !k.startsWith("devices/"))).toEqual([]);

    // Exactly at every cap at once is a legitimate walk, not a hostile
    // client: accepted.
    const atCaps = structuredClone(validManifest());
    atCaps.checkpoints[0].keyframes = Array.from(
      { length: MAX_KEYFRAMES_PER_CHECKPOINT },
      (_, i) => ({ id: `kf_n2_${i}`, tMs: 39000 + i * 100 }),
    );
    atCaps.checkpoints[0].approach = Array.from(
      { length: MAX_APPROACH_SAMPLES },
      (_, i) => ({ tMs: 37000 + i * 50, d: 18 + i * 0.1 }),
    );
    while (atCaps.checkpoints.length < MAX_CHECKPOINTS) {
      const n = atCaps.checkpoints.length + 2;
      const cp = structuredClone(atCaps.checkpoints[0]);
      cp.nodeId = `n${n}`;
      cp.heroKeyframeId = `kf_n${n}_0`;
      cp.keyframes = cp.keyframes.map((k, i) => ({ ...k, id: `kf_n${n}_${i}` }));
      delete (cp as { clip?: unknown }).clip;
      atCaps.checkpoints.push(cp);
    }
    const res = await postJob(env, atCaps);
    expect(res.status).toBe(202);
  });

  it("rejects payloads over 80 MB (413)", async () => {
    const media = new FakeR2();
    const big = new Uint8Array(80 * 1024 * 1024 + 1);
    const res = await postJob(await envFor(media, new FakePipeline()), validManifest(), [
      { id: "kf_n2_0", bytes: big, type: "image/jpeg" },
    ]);

    expect(res.status).toBe(413);
    expect(await res.json()).toEqual({ ok: false, error: "payload_too_large" });
  });

  // R10-05 (F4): the daily spend cap on the v1 create path. v1 carries no
  // budget, so the hard 240-frame ceiling is what a job reserves.
  it("reserves the daily ledger before storing (240, the v1 ceiling)", async () => {
    const media = new FakeR2();
    const res = await postJob(await envFor(media, new FakePipeline()), validManifest());
    expect(res.status).toBe(202);

    const ledger = media.store.get(`spend/daily/${new Date().toISOString().slice(0, 10)}.json`);
    expect(ledger).toBeDefined();
    expect(JSON.parse(new TextDecoder().decode(ledger!.body))).toEqual({ reserved: 240 });
  });

  it("rejects creates past the daily cap (503, nothing stored)", async () => {
    const media = new FakeR2();
    const env = await envFor(media, new FakePipeline());
    const day = `spend/daily/${new Date().toISOString().slice(0, 10)}.json`;
    await media.put(day, JSON.stringify({ reserved: 1200 })); // default cap: 1200

    const res = await postJob(env, validManifest());

    expect(res.status).toBe(503);
    expect(await res.json()).toEqual({ ok: false, error: "daily_budget_exceeded" });
    // The ledger stands; no job files, no workflow instance.
    expect(JSON.parse(new TextDecoder().decode(media.store.get(day)!.body))).toEqual({ reserved: 1200 });
    expect([...media.store.keys()].filter((k) => k.startsWith("jobs/"))).toEqual([]);
  });

  it("halts every create on the kill switch (503, ledger untouched)", async () => {
    const media = new FakeR2();
    const base = (await envFor(media, new FakePipeline())) as Record<string, unknown>;
    const env = { ...base, SPEND_KILL_SWITCH: "on" } as never;

    const res = await postJob(env, validManifest());

    expect(res.status).toBe(503);
    expect(await res.json()).toEqual({ ok: false, error: "spend_disabled" });
    // Nothing job-related stored and the ledger never even read (the
    // device registration predates the create).
    expect([...media.store.keys()].filter((k) => k.startsWith("spend/"))).toEqual([]);
  });

  it("fails closed on a ledger contention storm (500, nothing stored)", async () => {
    const media = new FakeR2();
    const pipeline = new FakePipeline();
    const env = await envFor(media, pipeline);
    const day = `spend/daily/${new Date().toISOString().slice(0, 10)}.json`;
    const originalPut = media.put.bind(media);
    // A rival writer lands on the etag before every one of our puts. It
    // stays under the cap (101 < 1200), so the retries keep racing
    // instead of tripping over_cap.
    media.put = async (
      key: string,
      value: ArrayBuffer | Uint8Array | string,
      opts?: { onlyIf?: { etagMatches?: string } },
    ) => {
      if (opts?.onlyIf?.etagMatches !== undefined && key === day) {
        await originalPut(key, JSON.stringify({ reserved: 101 })); // fresh etag
      }
      return originalPut(key, value, opts);
    };
    await media.put(day, JSON.stringify({ reserved: 100 }));

    const res = await postJob(env, validManifest());

    expect(res.status).toBe(500);
    expect(await res.json()).toEqual({ ok: false, error: "job_create_failed" });
    expect(pipeline.instances.size).toBe(0);
  });

  it("fails closed without a workflow binding, storing nothing (500)", async () => {
    const media = new FakeR2();
    const res = await postJob(await envFor(media), validManifest());

    expect(res.status).toBe(500);
    expect(await res.json()).toEqual({ ok: false, error: "server_misconfigured" });
    // Nothing job-related was stored (the seeded device record is not a job artifact).
    expect([...media.store.keys()].filter((k) => !k.startsWith("devices/"))).toEqual([]);
  });

  it("reports storage failures as 500, not invalid_manifest", async () => {
    const media = new FakeR2();
    // Seed the install first, then take R2 down: the failure is the job
    // create's own writes, not the device record's.
    const env = await envFor(media, new FakePipeline());
    media.put = async () => {
      throw new Error("R2 down");
    };
    const res = await postJob(env, validManifest());

    expect(res.status).toBe(500);
    expect(await res.json()).toEqual({ ok: false, error: "job_create_failed" });
  });
});

describe("GET /v1/jobs/:id", () => {
  const auth = bearer(KEY);

  it("reports queued with the seeded stage", async () => {
    const media = new FakeR2();
    const pipeline = new FakePipeline();
    const env = await envFor(media, pipeline);
    const { jobId } = (await (await postJob(env, validManifest())).json()) as { jobId: string };

    const res = await app.request(`/v1/jobs/${jobId}`, { headers: auth }, env);
    expect(res.status).toBe(200);
    expect(await res.json()).toEqual({ ok: true, status: "queued", stage: "queued" });
  });

  it("maps a running instance and its stage through", async () => {
    const media = new FakeR2();
    const pipeline = new FakePipeline();
    const env = await envFor(media, pipeline);
    const { jobId } = (await (await postJob(env, validManifest())).json()) as { jobId: string };
    pipeline.instances.get(jobId)!.statusValue = "running";
    await media.put(`jobs/${jobId}/progress.json`, JSON.stringify({ stage: "scouting", updatedAt: 1 }));

    const res = await app.request(`/v1/jobs/${jobId}`, { headers: auth }, env);
    expect(await res.json()).toEqual({ ok: true, status: "running", stage: "scouting" });
  });

  it("returns the validated result on complete", async () => {
    const media = new FakeR2();
    const pipeline = new FakePipeline();
    const env = await envFor(media, pipeline);
    const { jobId } = (await (await postJob(env, validManifest())).json()) as { jobId: string };
    pipeline.instances.get(jobId)!.statusValue = "complete";
    const result = {
      jobId,
      models: { scout: "workers-ai", ground: "none" },
      checkpoints: [{ nodeId: "n2", candidates: [] }],
      usage: { scoutMs: 21000 },
    };
    await media.put(`jobs/${jobId}/result.json`, JSON.stringify(result));

    const res = await app.request(`/v1/jobs/${jobId}`, { headers: auth }, env);
    expect(res.status).toBe(200);
    const body = (await res.json()) as { ok: boolean; status: string; stage: string; result: unknown };
    expect(body).toMatchObject({ ok: true, status: "complete", stage: "complete" });
    expect(() => JobResultSchema.parse(body.result)).not.toThrow();
  });

  it("fails closed when a complete instance has no result (500)", async () => {
    const media = new FakeR2();
    const pipeline = new FakePipeline();
    pipeline.initial = "complete";
    const env = await envFor(media, pipeline);
    const { jobId } = (await (await postJob(env, validManifest())).json()) as { jobId: string };
    await media.delete(`jobs/${jobId}/result.json`);

    const res = await app.request(`/v1/jobs/${jobId}`, { headers: auth }, env);
    expect(res.status).toBe(500);
    expect(await res.json()).toEqual({ ok: false, error: "result_missing" });
  });

  it("maps errored and terminated to errored with the stable code, not the raw message", async () => {
    const media = new FakeR2();
    const pipeline = new FakePipeline();
    const env = await envFor(media, pipeline);
    const { jobId } = (await (await postJob(env, validManifest())).json()) as { jobId: string };
    // Vendor-flavored text: it is the log site and stays in error.json for
    // ops, but it never reaches the client (R10-12, review 0010 F11).
    await media.put(
      `jobs/${jobId}/error.json`,
      JSON.stringify({ error: "scout exploded: vendor response body" }),
    );
    await media.put(`jobs/${jobId}/progress.json`, JSON.stringify({ stage: "errored", updatedAt: 2 }));

    pipeline.instances.get(jobId)!.statusValue = "errored";
    const res = await app.request(`/v1/jobs/${jobId}`, { headers: auth }, env);
    // Per-user route data is never cached (R10-12, F11).
    expect(res.headers.get("cache-control")).toBe("no-store");
    const text = await res.text();
    expect(text).not.toContain("vendor response body");
    expect(JSON.parse(text)).toEqual({
      ok: true,
      status: "errored",
      stage: "errored",
      error: "job_failed",
    });
    // The raw message is untouched in error.json — ops reads it, not the app.
    expect(new TextDecoder().decode(media.store.get(`jobs/${jobId}/error.json`)!.body)).toContain(
      "vendor response body",
    );

    pipeline.instances.get(jobId)!.statusValue = "terminated";
    expect(await (await app.request(`/v1/jobs/${jobId}`, { headers: auth }, env)).json()).toEqual({
      ok: true,
      status: "errored",
      stage: "errored",
      error: "job_failed",
    });
  });

  it("returns 404 for an unknown job", async () => {
    const env = await envFor(new FakeR2(), new FakePipeline());
    const res = await app.request("/v1/jobs/does-not-exist", { headers: auth }, env);
    expect(res.status).toBe(404);
    expect(await res.json()).toEqual({ ok: false, error: "job_not_found" });
  });
});

describe("DELETE /v1/jobs/:id", () => {
  const auth = bearer(KEY);

  it("removes every job key and terminates a running instance (204)", async () => {
    const media = new FakeR2();
    const pipeline = new FakePipeline();
    const env = await envFor(media, pipeline);
    const { jobId } = (await (await postJob(env, validManifest())).json()) as { jobId: string };
    const instance = pipeline.instances.get(jobId)!;

    const res = await app.request(`/v1/jobs/${jobId}`, { method: "DELETE", headers: auth }, env);
    expect(res.status).toBe(204);
    expect([...media.store.keys()].filter((k) => k.startsWith(`jobs/${jobId}/`))).toEqual([]);
    expect(instance.terminated).toBe(true);
  });

  it("leaves a complete instance alone while sweeping its keys", async () => {
    const media = new FakeR2();
    const pipeline = new FakePipeline();
    const env = await envFor(media, pipeline);
    const { jobId } = (await (await postJob(env, validManifest())).json()) as { jobId: string };
    const instance = pipeline.instances.get(jobId)!;
    instance.statusValue = "complete";

    const res = await app.request(`/v1/jobs/${jobId}`, { method: "DELETE", headers: auth }, env);
    expect(res.status).toBe(204);
    expect(instance.terminated).toBe(false);
    // Nothing job-related was stored. The seeded device record is not a job
    // artifact, and the day's spend reservation (R10-05) stands — deleting a
    // job does not claw its reservation back from the cap.
    expect(
      [...media.store.keys()].filter((k) => !k.startsWith("devices/") && !k.startsWith("spend/")),
    ).toEqual([]);
  });

  it("is idempotent for an unknown job (204)", async () => {
    const env = await envFor(new FakeR2(), new FakePipeline());
    const res = await app.request("/v1/jobs/nope", { method: "DELETE", headers: auth }, env);
    expect(res.status).toBe(204);
  });

  it("reports a deleted job as not found, not result_missing", async () => {
    const media = new FakeR2();
    const pipeline = new FakePipeline();
    const env = await envFor(media, pipeline);
    const { jobId } = (await (await postJob(env, validManifest())).json()) as { jobId: string };
    pipeline.instances.get(jobId)!.statusValue = "complete";

    expect((await app.request(`/v1/jobs/${jobId}`, { method: "DELETE", headers: auth }, env)).status).toBe(204);
    const res = await app.request(`/v1/jobs/${jobId}`, { headers: auth }, env);
    expect(res.status).toBe(404);
    expect(await res.json()).toEqual({ ok: false, error: "job_not_found" });
  });
});

describe("per-install isolation (R10-01)", () => {
  it("keeps one install's jobs unreadable and undeletable by another", async () => {
    const media = new FakeR2();
    const pipeline = new FakePipeline();
    const env = await envFor(media, pipeline);
    const { jobId } = (await (await postJob(env, validManifest())).json()) as { jobId: string };

    // A second install on the same deployment.
    await seedDevice(media, OTHER);
    const foreign = bearer(OTHER);

    // Read: 404, no existence leak, no state change.
    const read = await app.request(`/v1/jobs/${jobId}`, { headers: foreign }, env);
    expect(read.status).toBe(404);
    expect(await read.json()).toEqual({ ok: false, error: "job_not_found" });
    expect(pipeline.instances.has(jobId)).toBe(true);

    // Delete: 404 — the foreign install cannot sweep the job.
    const del = await app.request(`/v1/jobs/${jobId}`, { method: "DELETE", headers: foreign }, env);
    expect(del.status).toBe(404);
    expect(media.store.has(`jobs/${jobId}/manifest.json`)).toBe(true);

    // The owner reads and deletes freely.
    const own = await app.request(`/v1/jobs/${jobId}`, { headers: bearer(KEY) }, env);
    expect(own.status).toBe(200);
    const ownDel = await app.request(
      `/v1/jobs/${jobId}`,
      { method: "DELETE", headers: bearer(KEY) },
      env,
    );
    expect(ownDel.status).toBe(204);
    expect([...media.store.keys()].filter((k) => k.startsWith(`jobs/${jobId}/`))).toEqual([]);
  });
});
