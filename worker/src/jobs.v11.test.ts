// v1.1 contract freeze, resumable uploads and the EchoPipeline skeleton,
// split from jobs.test.ts to stay under the fileLines ceiling. The setup
// block duplicates jobs.test.ts (per-file fakes, the established pattern).
import { describe, expect, it } from "vitest";
import type { WorkflowStep as RuntimeWorkflowStep } from "cloudflare:workers";
import { app } from "./index";
import { EchoPipeline } from "./pipeline";
import { JobResultSchema } from "./types";
import { STAGING_TTL_MS, sweepExpiredUploads } from "./uploads";
import { bearer, seedDevice } from "./testkit";
import { deviceIdForKey } from "./devices";
import {
  CheckpointEventSchema,
  CompleteEventSchema,
  CreateJobV11RequestSchema,
  CreateUploadRequestSchema,
  CreateUploadResponseSchema,
  ErrorEventSchema,
  JobBudgetSchema,
  JobEventSchema,
  MAX_APPROACH_SAMPLES,
  MAX_CHECKPOINTS,
  MAX_DRAFT_ID_LENGTH,
  MAX_KEYFRAMES_PER_CHECKPOINT,
  MAX_ROUTE_CONTEXT_LENGTH,
  StageEventSchema,
  UploadPutParamsSchema,
  UsageEventSchema,
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

describe("v1.1 contract freeze (W10-06)", () => {
  const SHA =
    "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

  it("accepts a well-formed upload create request", () => {
    const parsed = CreateUploadRequestSchema.parse({
      files: [
        { name: "kf_n2_0", sha256: SHA, bytes: 41250 },
        { name: "clip_n2", sha256: SHA, bytes: 80120 },
      ],
    });
    expect(parsed.files).toHaveLength(2);
  });

  it("rejects empty, oversized, mis-hashed, and mis-named upload file lists", () => {
    expect(() =>
      CreateUploadRequestSchema.parse({ files: [] }),
    ).toThrow();
    expect(() =>
      CreateUploadRequestSchema.parse({
        files: Array.from({ length: 61 }, (_, i) => ({
          name: `kf_${i.toString().padStart(2, "0")}`,
          sha256: SHA,
          bytes: 10,
        })),
      }),
    ).toThrow();
    expect(() =>
      CreateUploadRequestSchema.parse({
        files: [{ name: "kf_n2_0", sha256: "not-a-hash", bytes: 10 }],
      }),
    ).toThrow();
    expect(() =>
      CreateUploadRequestSchema.parse({
        files: [{ name: "KF-BAD!", sha256: SHA, bytes: 10 }],
      }),
    ).toThrow();
  });

  it("parses the upload create response with a resumable missing[]", () => {
    const parsed = CreateUploadResponseSchema.parse({
      ok: true,
      uploadId: "11111111-1111-4111-8111-111111111111",
      missing: ["clip_n2"],
    });
    expect(parsed.missing).toEqual(["clip_n2"]);
  });

  it("parses upload PUT params and rejects bad file names", () => {
    const uploadId = "11111111-1111-4111-8111-111111111111";
    expect(UploadPutParamsSchema.parse({ uploadId, name: "kf_n2_0" }).name).toBe("kf_n2_0");
    expect(() => UploadPutParamsSchema.parse({ uploadId, name: "EVIL!" })).toThrow();
  });

  it("caps the SAM frame budget at 240", () => {
    expect(JobBudgetSchema.parse({ maxSamFrames: 240 }).maxSamFrames).toBe(240);
    expect(JobBudgetSchema.parse({ maxSamFrames: 1 }).maxSamFrames).toBe(1);
    expect(() => JobBudgetSchema.parse({ maxSamFrames: 241 })).toThrow();
    expect(() => JobBudgetSchema.parse({ maxSamFrames: 0 })).toThrow();
  });

  it("accepts a manifest-only v1.1 job create (v1-equivalent)", () => {
    const parsed = CreateJobV11RequestSchema.parse({ manifest: validManifest() });
    expect(parsed.uploadId).toBeUndefined();
    expect(parsed.budget).toBeUndefined();
    expect(parsed.resumeOf).toBeUndefined();
  });

  it("accepts a full v1.1 job create with upload, budget, idempotency, resume", () => {
    const parsed = CreateJobV11RequestSchema.parse({
      manifest: validManifest(),
      uploadId: "22222222-2222-4222-8222-222222222222",
      budget: { maxSamFrames: 120 },
      idempotencyKey: "idempotent-client-key-1",
      resumeOf: "33333333-3333-4333-8333-333333333333",
    });
    expect(parsed.budget).toEqual({ maxSamFrames: 120 });
    expect(parsed.idempotencyKey).toBe("idempotent-client-key-1");
  });

  it("rejects a v1.1 job create that exceeds the frame budget", () => {
    expect(() =>
      CreateJobV11RequestSchema.parse({
        manifest: validManifest(),
        budget: { maxSamFrames: 241 },
      }),
    ).toThrow();
    expect(() =>
      CreateJobV11RequestSchema.parse({
        manifest: validManifest(),
        idempotencyKey: "",
      }),
    ).toThrow();
  });

  it("parses every SSE event shape through the union", () => {
    const events = [
      { id: "1", type: "stage", stage: "scouting", updatedAt: 7 },
      { id: "2", type: "checkpoint", nodeId: "n2", status: "scouted", candidates: [] },
      { id: "3", type: "checkpoint", nodeId: "n2", status: "grounded", candidates: [] },
      { id: "4", type: "checkpoint", nodeId: "n2", status: "skipped", candidates: [] },
      { id: "5", type: "usage", usage: { samFrames: 12, scoutMs: 300 } },
      {
        id: "6",
        type: "complete",
        result: {
          jobId: "job-1",
          models: { scout: "workers-ai", ground: "none" },
          checkpoints: [{ nodeId: "n2", candidates: [] }],
          usage: { scoutMs: 21000 },
        },
      },
      { id: "7", type: "error", error: "scout exploded" },
    ];
    for (const e of events) {
      expect(() => JobEventSchema.parse(e)).not.toThrow();
    }
    expect(StageEventSchema.parse(events[0]).stage).toBe("scouting");
    expect(CheckpointEventSchema.parse(events[1]).status).toBe("scouted");
    expect(UsageEventSchema.parse(events[4]).usage).toEqual({ samFrames: 12, scoutMs: 300 });
    expect(CompleteEventSchema.parse(events[5]).result.jobId).toBe("job-1");
    expect(ErrorEventSchema.parse(events[6]).error).toBe("scout exploded");
  });

  it("requires resumable event ids and known checkpoint statuses", () => {
    expect(() =>
      JobEventSchema.parse({ type: "stage", stage: "scouting", updatedAt: 7 }),
    ).toThrow();
    expect(() =>
      JobEventSchema.parse({
        id: "9",
        type: "checkpoint",
        nodeId: "n2",
        status: "polishing",
        candidates: [],
      }),
    ).toThrow();
    expect(() =>
      JobEventSchema.parse({ id: "10", type: "unknown-shape" }),
    ).toThrow();
  });

  it("still accepts the frozen v1 multipart job create (202)", async () => {
    const media = new FakeR2();
    const pipeline = new FakePipeline();
    const res = await postJob(await envFor(media, pipeline), validManifest());

    expect(res.status).toBe(202);
    const body = (await res.json()) as { ok: boolean; jobId: string };
    expect(body.ok).toBe(true);
    expect(pipeline.instances.has(body.jobId)).toBe(true);
  });
});

describe("v1.1 resumable uploads (W11-05)", () => {
  const auth = bearer(KEY);
  const jsonAuth = { ...auth, "Content-Type": "application/json" };

  async function shaHexOf(data: number[]): Promise<string> {
    const bytes = new Uint8Array(data);
    const digest = await crypto.subtle.digest("SHA-256", bytes);
    return [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, "0")).join("");
  }

  function stagedFiles() {
    return [
      { name: "kf_n2_0", data: [1, 2, 3] },
      { name: "kf_n2_1", data: [4, 5, 6] },
      { name: "clip_n2", data: [7, 8, 9] },
    ];
  }

  async function createUpload(
    env: never,
    files: Array<{ name: string; sha256: string; bytes: number }>,
  ) {
    return app.request(
      "/v1/uploads",
      { method: "POST", headers: jsonAuth, body: JSON.stringify({ files }) },
      env,
    );
  }

  async function createUploadFor(env: never, files = stagedFiles()) {
    const refs = await Promise.all(
      files.map(async (f) => {
        const data = mediaOf(f.name, f.data);
        return { name: f.name, sha256: await shaHexOf([...data]), bytes: data.length };
      }),
    );
    const res = await createUpload(env, refs);
    expect(res.status).toBe(200);
    return ((await res.json()) as { uploadId: string; missing: string[] }).uploadId;
  }

  function putBlob(env: never, uploadId: string, name: string, data: number[]) {
    return app.request(
      `/v1/uploads/${uploadId}/${name}`,
      {
        method: "PUT",
        headers: {
          ...auth,
          "Content-Type": name.startsWith("clip") ? "video/mp4" : "image/jpeg",
        },
        body: new Blob([mediaOf(name, data)]),
      },
      env,
    );
  }

  function postJobJson(env: never, payload: unknown) {
    return app.request(
      "/v1/jobs",
      { method: "POST", headers: jsonAuth, body: JSON.stringify(payload) },
      env,
    );
  }

  function readMeta(media: FakeR2, uploadId: string) {
    const entry = media.store.get(`jobs/staging/${uploadId}/meta.json`);
    expect(entry).toBeDefined();
    return JSON.parse(new TextDecoder().decode(entry!.body)) as { createdAt: number };
  }

  it("resumes: missing[] shrinks as sha-verified blobs arrive", async () => {
    const media = new FakeR2();
    const env = await envFor(media, new FakePipeline());
    const files = stagedFiles();
    const uploadId = await createUploadFor(env, files);

    // Create reports everything missing: a fresh client PUTs all three.
    const created = (await (
      await createUpload(
        env,
        await Promise.all(
          files.map(async (f) => {
            const data = mediaOf(f.name, f.data);
            return { name: f.name, sha256: await shaHexOf([...data]), bytes: data.length };
          }),
        ),
      )
    ).json()) as { missing: string[] };
    expect(created.missing).toEqual(["kf_n2_0", "kf_n2_1", "clip_n2"]);

    expect((await putBlob(env, uploadId, "kf_n2_0", [1, 2, 3])).status).toBe(200);
    expect(media.store.has(`jobs/staging/${uploadId}/kf_n2_0`)).toBe(true);
    const mid = (await (
      await app.request(`/v1/uploads/${uploadId}`, { headers: auth }, env)
    ).json()) as { uploadId: string; missing: string[] };
    expect(mid).toEqual({ ok: true, uploadId, missing: ["kf_n2_1", "clip_n2"] });

    expect((await putBlob(env, uploadId, "kf_n2_1", [4, 5, 6])).status).toBe(200);
    expect((await putBlob(env, uploadId, "clip_n2", [7, 8, 9])).status).toBe(200);
    const done = (await (
      await app.request(`/v1/uploads/${uploadId}`, { headers: auth }, env)
    ).json()) as { missing: string[] };
    expect(done.missing).toEqual([]);
  });

  it("rejects sha mismatches with 400 and stores nothing", async () => {
    const media = new FakeR2();
    const env = await envFor(media, new FakePipeline());
    const uploadId = await createUploadFor(env);

    const res = await putBlob(env, uploadId, "kf_n2_0", [9, 9, 9]);
    expect(res.status).toBe(400);
    expect(await res.json()).toEqual({ ok: false, error: "sha_mismatch" });
    expect(media.store.has(`jobs/staging/${uploadId}/kf_n2_0`)).toBe(false);

    const status = (await (
      await app.request(`/v1/uploads/${uploadId}`, { headers: auth }, env)
    ).json()) as { missing: string[] };
    expect(status.missing).toContain("kf_n2_0");
  });

  it("rejects unknown files, bad names, and unknown uploads", async () => {
    const media = new FakeR2();
    const env = await envFor(media, new FakePipeline());
    const uploadId = await createUploadFor(env);

    const unknown = await putBlob(env, uploadId, "kf_n9_9", [1]);
    expect(unknown.status).toBe(400);
    expect(await unknown.json()).toEqual({ ok: false, error: "unknown_file" });

    const badName = await app.request(
      `/v1/uploads/${uploadId}/EVIL!`,
      {
        method: "PUT",
        headers: { ...auth, "Content-Type": "application/octet-stream" },
        body: new Blob([new Uint8Array([1])]),
      },
      env,
    );
    expect(badName.status).toBe(400);

    const ghost = await putBlob(env, "00000000-0000-0000-0000-000000000000", "kf_n2_0", [1, 2, 3]);
    expect(ghost.status).toBe(404);
    expect(await ghost.json()).toEqual({ ok: false, error: "upload_not_found" });

    const ghostGet = await app.request("/v1/uploads/00000000-0000-0000-0000-000000000000", {
      headers: auth,
    }, env);
    expect(ghostGet.status).toBe(404);
  });

  it("creates a job by moving staged blobs under it, then consumes staging", async () => {
    const media = new FakeR2();
    const pipeline = new FakePipeline();
    const env = await envFor(media, pipeline);
    const files = stagedFiles();
    const uploadId = await createUploadFor(env, files);
    for (const f of files) {
      expect((await putBlob(env, uploadId, f.name, f.data)).status).toBe(200);
    }

    const res = await postJobJson(env, { manifest: validManifest(), uploadId });
    expect(res.status).toBe(202);
    const { jobId } = (await res.json()) as { jobId: string };
    expect(pipeline.instances.has(jobId)).toBe(true);

    for (const f of files) {
      const stored = media.store.get(`jobs/${jobId}/in/${f.name}`);
      expect(stored).toBeDefined();
      expect([...new Uint8Array(stored!.body)]).toEqual([...mediaOf(f.name, f.data)]);
    }
    expect(media.store.has(`jobs/${jobId}/manifest.json`)).toBe(true);
    // Single-use staging: the move consumes it, so a resumed GET is gone.
    expect([...media.store.keys()].filter((k) => k.startsWith(`jobs/staging/${uploadId}/`))).toEqual(
      [],
    );
    expect((await app.request(`/v1/uploads/${uploadId}`, { headers: auth }, env)).status).toBe(404);
  });

  it("returns the same job id for an idempotent double-create", async () => {
    const media = new FakeR2();
    const pipeline = new FakePipeline();
    const env = await envFor(media, pipeline);
    const files = stagedFiles();
    const uploadId = await createUploadFor(env, files);
    for (const f of files) {
      await putBlob(env, uploadId, f.name, f.data);
    }
    const payload = {
      manifest: validManifest(),
      uploadId,
      budget: { maxSamFrames: 120 },
      idempotencyKey: "idempotent-client-key-1",
    };

    const first = await postJobJson(env, payload);
    expect(first.status).toBe(202);
    const { jobId: firstId } = (await first.json()) as { jobId: string };

    // Staging was consumed, yet the replay returns the same job id.
    const second = await postJobJson(env, payload);
    expect(second.status).toBe(202);
    const { jobId: secondId } = (await second.json()) as { jobId: string };
    expect(secondId).toBe(firstId);
    expect(pipeline.instances.size).toBe(1);
  });

  it("persists the v1.1 budget and resume source for the W11-06 governor", async () => {
    const media = new FakeR2();
    const pipeline = new FakePipeline();
    const env = await envFor(media, pipeline);
    // resumeOf must name a job that exists AND belongs to this install.
    const prior = "33333333-3333-4333-8333-333333333333";
    await media.put(`jobs/${prior}/manifest.json`, "{}");
    const priorId = await seedDevice(media, KEY);
    await media.put(`jobs/${prior}/owner.json`, JSON.stringify({ owner: priorId }));
    // R10-11 (F10.2): the create is upload-backed like every other one.
    const uploadId = await createUploadFor(env);
    for (const f of stagedFiles()) await putBlob(env, uploadId, f.name, f.data);
    const res = await postJobJson(env, {
      manifest: validManifest(),
      uploadId,
      budget: { maxSamFrames: 120 },
      resumeOf: prior,
      idempotencyKey: "governor-budget-key-1",
    });
    expect(res.status).toBe(202);
    const { jobId } = (await res.json()) as { jobId: string };
    const stored = media.store.get(`jobs/${jobId}/budget.json`);
    expect(stored).toBeDefined();
    expect(JSON.parse(new TextDecoder().decode(stored!.body))).toEqual({
      maxSamFrames: 120,
      resumeOf: prior,
    });
  });

  it("refuses to create from an incomplete upload, naming what is missing", async () => {
    const media = new FakeR2();
    const pipeline = new FakePipeline();
    const env = await envFor(media, pipeline);
    const uploadId = await createUploadFor(env);
    expect((await putBlob(env, uploadId, "kf_n2_0", [1, 2, 3])).status).toBe(200);

    const res = await postJobJson(env, { manifest: validManifest(), uploadId });
    expect(res.status).toBe(400);
    expect(await res.json()).toEqual({
      ok: false,
      error: "upload_incomplete",
      missing: ["kf_n2_1", "clip_n2"],
    });
    expect(pipeline.instances.size).toBe(0);
  });

  it("expires staged uploads after 1h on every endpoint and sweeps them", async () => {
    const media = new FakeR2();
    const env = await envFor(media, new FakePipeline());
    const uploadId = await createUploadFor(env);
    await putBlob(env, uploadId, "kf_n2_0", [1, 2, 3]);

    // Backdate past the TTL: deterministic without waiting an hour.
    const ownerId = await deviceIdForKey(KEY);
    const backdated = {
      files: [
        { name: "kf_n2_0", sha256: await shaHexOf([1, 2, 3]), bytes: 3 },
        { name: "kf_n2_1", sha256: await shaHexOf([4, 5, 6]), bytes: 3 },
        { name: "clip_n2", sha256: await shaHexOf([7, 8, 9]), bytes: 3 },
      ],
      // R10-01: a backdated meta is still the test install's session.
      owner: ownerId,
      createdAt: Date.now() - STAGING_TTL_MS - 1,
    };
    await media.put(`jobs/staging/${uploadId}/meta.json`, JSON.stringify(backdated));
    expect(readMeta(media, uploadId).createdAt).toBe(backdated.createdAt);

    const get = await app.request(`/v1/uploads/${uploadId}`, { headers: auth }, env);
    expect(get.status).toBe(410);
    expect(await get.json()).toEqual({ ok: false, error: "upload_expired" });
    // The expired read sweeps its own staging area.
    expect([...media.store.keys()].filter((k) => k.startsWith("jobs/staging/"))).toEqual([]);

    // PUT and job create agree once a second upload ages out.
    const uploadId2 = await createUploadFor(env);
    await media.put(
      `jobs/staging/${uploadId2}/meta.json`,
      JSON.stringify({ ...backdated, createdAt: Date.now() - STAGING_TTL_MS - 1 }),
    );
    const put = await putBlob(env, uploadId2, "kf_n2_1", [4, 5, 6]);
    expect(put.status).toBe(410);

    const uploadId3 = await createUploadFor(env);
    await media.put(
      `jobs/staging/${uploadId3}/meta.json`,
      JSON.stringify({ ...backdated, createdAt: Date.now() - STAGING_TTL_MS - 1 }),
    );
    const job = await postJobJson(env, { manifest: validManifest(), uploadId: uploadId3 });
    expect(job.status).toBe(410);
    expect(await job.json()).toEqual({ ok: false, error: "upload_expired" });

    // The sweep removes aged uploads (and orphans) but keeps fresh ones.
    // Note: backdate only after every create, since each create sweeps.
    const fresh = await createUploadFor(env);
    const stale = await createUploadFor(env);
    await media.put(
      `jobs/staging/${stale}/meta.json`,
      JSON.stringify({ ...backdated, createdAt: Date.now() - STAGING_TTL_MS - 1 }),
    );
    await media.put(`jobs/staging/${stale}/kf_n2_0`, new Uint8Array([1, 2, 3]).buffer as ArrayBuffer);
    const swept = await sweepExpiredUploads(env as never);
    expect(swept).toBe(1);
    expect([...media.store.keys()].filter((k) => k.startsWith(`jobs/staging/${stale}/`))).toEqual([]);
    expect((await app.request(`/v1/uploads/${fresh}`, { headers: auth }, env)).status).toBe(200);
  });

  it("holds the 60-file / 80 MB caps on the new endpoints", async () => {
    const media = new FakeR2();
    const env = await envFor(media, new FakePipeline());
    const sha = await shaHexOf([1]);

    const tooBig = await createUpload(env, [
      { name: "kf_n2_0", sha256: sha, bytes: 80 * 1024 * 1024 + 1 },
    ]);
    expect(tooBig.status).toBe(413);
    expect(await tooBig.json()).toEqual({ ok: false, error: "payload_too_large" });

    const tooMany = await createUpload(
      env,
      Array.from({ length: 61 }, (_, i) => ({
        name: `kf_${i.toString().padStart(2, "0")}`,
        sha256: sha,
        bytes: 10,
      })),
    );
    expect(tooMany.status).toBe(400);
    expect(await tooMany.json()).toEqual({ ok: false, error: "invalid_request" });
  });

  it("rejects manifest-only v1.1 creates and bad v1.1 bodies", async () => {
    const media = new FakeR2();
    const pipeline = new FakePipeline();
    const env = await envFor(media, pipeline);

    // R10-11 (F10.2): no upload reference means no media to move; the scout
    // would burn a paid instance on a guaranteed media_missing.
    const res = await postJobJson(env, { manifest: validManifest() });
    expect(res.status).toBe(400);
    expect(await res.json()).toEqual({ ok: false, error: "upload_reference_required" });
    expect(pipeline.instances.size).toBe(0);

    const empty = await postJobJson(env, {});
    expect(empty.status).toBe(400);
    expect(await empty.json()).toEqual({ ok: false, error: "invalid_request" });

    const noConsent = await postJobJson(env, {
      manifest: { ...validManifest(), consent: { sendFramesToCloudAi: false, atEpochMs: 0 } },
    });
    expect(noConsent.status).toBe(400);
    expect(await noConsent.json()).toEqual({ ok: false, error: "consent_required" });

    // The upload-backed create still works end to end.
    const uploadId = await createUploadFor(env);
    for (const f of stagedFiles()) await putBlob(env, uploadId, f.name, f.data);
    const ok = await postJobJson(env, { manifest: validManifest(), uploadId });
    expect(ok.status).toBe(202);
    const { jobId } = (await ok.json()) as { jobId: string };
    expect(pipeline.instances.has(jobId)).toBe(true);
    expect(media.store.has(`jobs/${jobId}/manifest.json`)).toBe(true);
  });
});

describe("EchoPipeline skeleton", () => {  it("runs load → scout → ground → assemble and seals a valid empty result", async () => {
    const media = new FakeR2();
    const jobId = crypto.randomUUID();
    await media.put(`jobs/${jobId}/manifest.json`, JSON.stringify(validManifest()));
    // W3-04: the scout step runs Workers AI. The scripted binding returns an
    // empty slate; assemble still seals the empty result (W3-06 owns content).
    for (const id of ["kf_n2_0", "kf_n2_1"]) {
      await media.put(`jobs/${jobId}/in/${id}`, new Uint8Array([1, 2, 3]).buffer as ArrayBuffer);
    }
    const ai = {
      run: async () => ({ response: { nodeId: "n2", candidates: [] } }),
    };
    const base = (await envFor(media, new FakePipeline())) as unknown as Record<string, unknown>;
    const env = {
      ...base,
      AI: ai,
      SCOUT_ORDER: "workers-ai",
      AI_GATEWAY_ID: "echo-test",
      WORKERS_AI_MODELS: "@cf/meta/llama-4-scout-17b-16e-instruct",
    } as unknown as import("./index").Env;
    const pipeline = new EchoPipeline({} as ExecutionContext, env);
    const seen: string[] = [];
    // Minimal step runner: the vitest stub has no scheduler, so steps run
    // inline. Cast follows the repo's `as never` Env precedent for fakes.
    const step = {
      do: async (name: string, _config: unknown, fn: () => Promise<unknown>) => {
        seen.push(name);
        return fn();
      },
    } as unknown as RuntimeWorkflowStep;

    await pipeline.run(
      { payload: { jobId }, timestamp: new Date(), instanceId: jobId, workflowName: "echo-pipeline" },
      step,
    );

    // W11-06: no META_API_KEY here, so the lens grounds box-only with zero
    // SAM calls — and zero calls means zero per-call `ground:…` steps. The
    // ground phase still runs (box-only seal below); per-call steps appear
    // only when SAM calls are actually sent (pipeline.test.ts proves it).
    // Event emits and the ground.json seal are their own replay-safe steps.
    // R10-04: the scout is one step per checkpoint (scout:<provider>:<nodeId>).
    expect(seen).toEqual([
      "load",
      "scout:workers-ai:n2",
      "emit:stage:grounding",
      "emit:checkpoint:n2",
      "ground:seal",
      "assemble",
      "emit:complete",
    ]);
    const progress = JSON.parse(
      new TextDecoder().decode(media.store.get(`jobs/${jobId}/progress.json`)!.body),
    );
    expect(progress.stage).toBe("complete");
    const scouted = JSON.parse(
      new TextDecoder().decode(media.store.get(`jobs/${jobId}/scout.json`)!.body),
    );
    expect(scouted.model).toBe("@cf/meta/llama-4-scout-17b-16e-instruct");
    expect(scouted.checkpoints).toEqual([{ nodeId: "n2", candidates: [] }]);
    const result = JobResultSchema.parse(
      JSON.parse(new TextDecoder().decode(media.store.get(`jobs/${jobId}/result.json`)!.body)),
    );
    expect(result.jobId).toBe(jobId);
    expect(result.checkpoints.map((c) => c.nodeId)).toEqual(["n2"]);
    expect(result.checkpoints.every((c) => c.candidates.length === 0)).toBe(true);
  });

  it("records errored progress and rethrows when loading fails", async () => {
    const media = new FakeR2();
    const jobId = crypto.randomUUID();
    const env = (await envFor(media, new FakePipeline())) as import("./index").Env;
    const pipeline = new EchoPipeline({} as ExecutionContext, env);
    const step = {
      do: async (_name: string, _config: unknown, fn: () => Promise<unknown>) => fn(),
    } as unknown as RuntimeWorkflowStep;

    await expect(
      pipeline.run(
        { payload: { jobId }, timestamp: new Date(), instanceId: jobId, workflowName: "echo-pipeline" },
        step,
      ),
    ).rejects.toThrow(/manifest missing/);
    const progress = JSON.parse(
      new TextDecoder().decode(media.store.get(`jobs/${jobId}/progress.json`)!.body),
    );
    expect(progress.stage).toBe("errored");
    const detail = JSON.parse(
      new TextDecoder().decode(media.store.get(`jobs/${jobId}/error.json`)!.body),
    );
    expect(detail.error).toMatch(/manifest missing/);
  });
});
