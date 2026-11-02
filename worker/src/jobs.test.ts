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
  StageEventSchema,
  UploadPutParamsSchema,
  UsageEventSchema,
} from "./types";

// R9-01: the bearer is a per-install device key. Two fixed installs prove
// isolation; the minted key in the isolation test proves the entropy path.
const KEY = "dk_test_install_one";
const OTHER = "dk_test_install_two";

// In-memory R2 stand-in: only the surface jobs.ts touches.
class FakeR2 {
  store = new Map<string, { body: ArrayBuffer; contentType?: string }>();

  async put(
    key: string,
    value: ArrayBuffer | Uint8Array | string,
    opts?: { httpMetadata?: { contentType?: string } },
  ): Promise<null> {
    // Normalize to a detached ArrayBuffer: a Uint8Array view aliases its pool
    // buffer, and TS's generics treat the view as a distinct type.
    const body =
      typeof value === "string"
        ? (new TextEncoder().encode(value).buffer as ArrayBuffer)
        : value instanceof Uint8Array
          ? (value.buffer.slice(value.byteOffset, value.byteOffset + value.byteLength) as ArrayBuffer)
          : value;
    this.store.set(key, { body, contentType: opts?.httpMetadata?.contentType });
    return null;
  }

  async get(key: string) {
    const entry = this.store.get(key);
    if (!entry) return null;
    return {
      text: async () => new TextDecoder().decode(entry.body),
      arrayBuffer: async () => entry.body.slice(0),
      json: async <T,>() => JSON.parse(new TextDecoder().decode(entry.body)) as T,
    };
  }

  head = async (key: string) => (this.store.has(key) ? { key } : null);

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
  // R9-01: register the test install so every authenticated request resolves.
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
  for (const f of files) form.append(f.id, new File([f.bytes], f.id, { type: f.type }));
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
      { id: "evil", bytes: new Uint8Array([9]), type: "image/jpeg" },
    ]);

    expect(res.status).toBe(202);
    const body = (await res.json()) as { ok: boolean; jobId: string };
    expect(body.ok).toBe(true);
    expect(body.jobId).toMatch(/^[0-9a-f-]{36}$/);

    expect(media.store.has(`jobs/${body.jobId}/manifest.json`)).toBe(true);
    expect(media.store.has(`jobs/${body.jobId}/in/kf_n2_0`)).toBe(true);
    expect(media.store.has(`jobs/${body.jobId}/in/evil`)).toBe(false);
    const progress = JSON.parse(
      new TextDecoder().decode(media.store.get(`jobs/${body.jobId}/progress.json`)!.body),
    );
    expect(progress.stage).toBe("queued");
    expect(pipeline.instances.has(body.jobId)).toBe(true);
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
    const manifest = structuredClone(validManifest());
    manifest.checkpoints[0].keyframes = Array.from({ length: 61 }, (_, i) => ({
      id: `kf_${i.toString().padStart(2, "0")}`,
      tMs: 39000 + i * 100,
    }));
    const files = manifest.checkpoints[0].keyframes.map((k) => ({
      id: k.id,
      bytes: new Uint8Array([1]),
      type: "image/jpeg",
    }));
    const res = await postJob(await envFor(media, new FakePipeline()), manifest, files);

    expect(res.status).toBe(413);
    expect(await res.json()).toEqual({ ok: false, error: "too_many_files" });
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

  it("maps errored and terminated to errored with detail", async () => {
    const media = new FakeR2();
    const pipeline = new FakePipeline();
    const env = await envFor(media, pipeline);
    const { jobId } = (await (await postJob(env, validManifest())).json()) as { jobId: string };
    await media.put(`jobs/${jobId}/error.json`, JSON.stringify({ error: "scout exploded" }));
    await media.put(`jobs/${jobId}/progress.json`, JSON.stringify({ stage: "errored", updatedAt: 2 }));

    pipeline.instances.get(jobId)!.statusValue = "errored";
    expect(await (await app.request(`/v1/jobs/${jobId}`, { headers: auth }, env)).json()).toEqual({
      ok: true,
      status: "errored",
      stage: "errored",
      error: "scout exploded",
    });

    pipeline.instances.get(jobId)!.statusValue = "terminated";
    expect(await (await app.request(`/v1/jobs/${jobId}`, { headers: auth }, env)).json()).toEqual({
      ok: true,
      status: "errored",
      stage: "errored",
      error: "scout exploded",
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
    // Nothing job-related was stored (the seeded device record is not a job artifact).
    expect([...media.store.keys()].filter((k) => !k.startsWith("devices/"))).toEqual([]);
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

describe("per-install isolation (R9-01)", () => {
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
      files.map(async (f) => ({ name: f.name, sha256: await shaHexOf(f.data), bytes: f.data.length })),
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
        headers: { ...auth, "Content-Type": "application/octet-stream" },
        body: new Blob([new Uint8Array(data)]),
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
          files.map(async (f) => ({ name: f.name, sha256: await shaHexOf(f.data), bytes: f.data.length })),
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
      expect([...new Uint8Array(stored!.body)]).toEqual(f.data);
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
    const res = await postJobJson(env, {
      manifest: validManifest(),
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
      // R9-01: a backdated meta is still the test install's session.
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

  it("creates manifest-only v1.1 jobs and rejects bad v1.1 bodies", async () => {
    const media = new FakeR2();
    const pipeline = new FakePipeline();
    const env = await envFor(media, pipeline);

    const res = await postJobJson(env, { manifest: validManifest() });
    expect(res.status).toBe(202);
    const { jobId } = (await res.json()) as { jobId: string };
    expect(pipeline.instances.has(jobId)).toBe(true);
    expect(media.store.has(`jobs/${jobId}/manifest.json`)).toBe(true);

    const empty = await postJobJson(env, {});
    expect(empty.status).toBe(400);
    expect(await empty.json()).toEqual({ ok: false, error: "invalid_request" });

    const noConsent = await postJobJson(env, {
      manifest: { ...validManifest(), consent: { sendFramesToCloudAi: false, atEpochMs: 0 } },
    });
    expect(noConsent.status).toBe(400);
    expect(await noConsent.json()).toEqual({ ok: false, error: "consent_required" });
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
    expect(seen).toEqual([
      "load",
      "scout",
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
