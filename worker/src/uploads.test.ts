import { describe, expect, it } from "vitest";
import { app, type Env } from "./index";
import { sweepExpiredUploads } from "./uploads";

// W11-09 ready-on-arrival: per-window staged sessions, multi-session job
// create, and staged-upload discard. The FakeR2/FakePipeline stand-ins
// mirror jobs.test.ts (duplicated per file, the established pattern).

const TOKEN = "test-token-123";

class FakeR2 {
  store = new Map<string, { body: ArrayBuffer; contentType?: string }>();

  async put(
    key: string,
    value: ArrayBuffer | string,
    opts?: { httpMetadata?: { contentType?: string } },
  ): Promise<null> {
    const body =
      typeof value === "string" ? new TextEncoder().encode(value).buffer as ArrayBuffer : value;
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

  async head(key: string) {
    return this.store.has(key) ? { key } : null;
  }

  async list({ prefix }: { prefix?: string; cursor?: string }) {
    const keys = [...this.store.keys()].filter((k) => !prefix || k.startsWith(prefix)).sort();
    return { objects: keys.map((key) => ({ key })), truncated: false as const, delimitedPrefixes: [] };
  }

  async delete(keys: string | string[]): Promise<void> {
    for (const k of Array.isArray(keys) ? keys : [keys]) this.store.delete(k);
  }
}

class FakePipeline {
  instances = new Map<
    string,
    {
      id: string;
      status(): Promise<{ status: string }>;
      terminate(): Promise<void>;
      delete(): Promise<void>;
    }
  >();
  failCreate = false;

  async create({ id }: { id?: string; params?: unknown }) {
    if (this.failCreate) throw new Error("workflow create failed");
    const instId = id ?? crypto.randomUUID();
    // Like the runtime: an instance id can only be created once.
    if (this.instances.has(instId)) throw new Error("instance.already_exists");
    const inst = {
      id: instId,
      status: async () => ({ status: "queued" }),
      terminate: async () => undefined,
      delete: async () => {
        this.instances.delete(instId);
      },
    };
    this.instances.set(instId, inst);
    return inst;
  }

  async get(id: string) {
    const inst = this.instances.get(id);
    if (!inst) throw new Error(`unknown instance ${id}`);
    return inst;
  }
}

function envFor(media: FakeR2, pipeline?: FakePipeline) {
  return {
    ECHO_APP_TOKEN: TOKEN,
    MEDIA: media as never,
    ...(pipeline ? { PIPELINE: pipeline as never } : {}),
  } as never;
}

const auth = { Authorization: `Bearer ${TOKEN}` };
const jsonAuth = { ...auth, "Content-Type": "application/json" };

async function shaHexOf(data: number[]): Promise<string> {
  const bytes = new Uint8Array(data);
  const digest = await crypto.subtle.digest("SHA-256", bytes);
  return [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, "0")).join("");
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

async function createUploadFor(env: never, files: Array<{ name: string; data: number[] }>) {
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

describe("W11-09 ready-on-arrival uploads", () => {
  it("discards staged uploads and treats unknown ids as gone", async () => {
    const media = new FakeR2();
    const env = envFor(media, new FakePipeline());
    const uploadId = await createUploadFor(env, [{ name: "kf_n2_0", data: [1, 2, 3] }]);
    expect((await putBlob(env, uploadId, "kf_n2_0", [1, 2, 3])).status).toBe(200);

    const del = await app.request(`/v1/uploads/${uploadId}`, { method: "DELETE", headers: auth }, env);
    expect(del.status).toBe(204);
    expect([...media.store.keys()].filter((k) => k.startsWith(`jobs/staging/${uploadId}/`))).toEqual([]);
    expect((await app.request(`/v1/uploads/${uploadId}`, { headers: auth }, env)).status).toBe(404);

    const ghost = await app.request(
      "/v1/uploads/00000000-0000-0000-0000-000000000000",
      { method: "DELETE", headers: auth },
      env,
    );
    expect(ghost.status).toBe(204);
  });

  it("creates a job against per-window sessions and consumes all of them", async () => {
    const media = new FakeR2();
    const pipeline = new FakePipeline();
    const env = envFor(media, pipeline);
    const first = await createUploadFor(env, [{ name: "kf_n2_0", data: [1, 2, 3] }]);
    const second = await createUploadFor(env, [
      { name: "kf_n2_1", data: [4, 5, 6] },
      { name: "clip_n2", data: [7, 8, 9] },
    ]);
    expect((await putBlob(env, first, "kf_n2_0", [1, 2, 3])).status).toBe(200);
    expect((await putBlob(env, second, "kf_n2_1", [4, 5, 6])).status).toBe(200);
    expect((await putBlob(env, second, "clip_n2", [7, 8, 9])).status).toBe(200);

    const res = await postJobJson(env, { manifest: validManifest(), uploadIds: [first, second] });
    expect(res.status).toBe(202);
    const { jobId } = (await res.json()) as { jobId: string };
    expect(pipeline.instances.has(jobId)).toBe(true);
    for (const [name, data] of [
      ["kf_n2_0", [1, 2, 3]],
      ["kf_n2_1", [4, 5, 6]],
      ["clip_n2", [7, 8, 9]],
    ] as const) {
      const stored = media.store.get(`jobs/${jobId}/in/${name}`);
      expect(stored).toBeDefined();
      expect([...new Uint8Array(stored!.body)]).toEqual(data);
    }
    expect([...media.store.keys()].filter((k) => k.startsWith("jobs/staging/"))).toEqual([]);
  });

  it("rejects a create naming both uploadId and uploadIds", async () => {
    const media = new FakeR2();
    const env = envFor(media, new FakePipeline());
    const uploadId = await createUploadFor(env, [{ name: "kf_n2_0", data: [1, 2, 3] }]);
    const res = await postJobJson(env, {
      manifest: validManifest(),
      uploadId,
      uploadIds: [uploadId],
    });
    expect(res.status).toBe(400);
    expect(await res.json()).toEqual({ ok: false, error: "invalid_request" });
  });

  it("measures multi-session completeness over the union", async () => {
    const media = new FakeR2();
    const pipeline = new FakePipeline();
    const env = envFor(media, pipeline);
    const first = await createUploadFor(env, [{ name: "kf_n2_0", data: [1, 2, 3] }]);
    const second = await createUploadFor(env, [
      { name: "kf_n2_1", data: [4, 5, 6] },
      { name: "clip_n2", data: [7, 8, 9] },
    ]);
    expect((await putBlob(env, first, "kf_n2_0", [1, 2, 3])).status).toBe(200);

    const res = await postJobJson(env, { manifest: validManifest(), uploadIds: [first, second] });
    expect(res.status).toBe(400);
    expect(await res.json()).toEqual({
      ok: false,
      error: "upload_incomplete",
      missing: ["kf_n2_1", "clip_n2"],
    });
    expect(pipeline.instances.size).toBe(0);
  });
});

describe("idempotent create and job cleanup", () => {
  const KEY = "idempotent-client-key-1";
  const create = (env: never, extra: Record<string, unknown> = {}) =>
    postJobJson(env, { manifest: validManifest(), idempotencyKey: KEY, ...extra });
  const idJson = async (res: Response) => ((await res.json()) as { jobId: string }).jobId;

  it("derives one job id from the key so concurrent creates cannot double-create", async () => {
    const media = new FakeR2();
    const pipeline = new FakePipeline();
    const env = envFor(media, pipeline);

    const [a, b] = await Promise.all([create(env), create(env)]);
    expect([a.status, b.status]).toEqual([202, 202]);
    const [idA, idB] = [await idJson(a), await idJson(b)];

    expect(idA).toBe(idB);
    expect(idA).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-5[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/);
    expect(pipeline.instances.size).toBe(1);
    // A different key is a different job.
    const other = await postJobJson(env, { manifest: validManifest(), idempotencyKey: "other-key" });
    expect(await idJson(other)).not.toBe(idA);
  });

  it("discards a stale record whose job lost its manifest, then creates afresh", async () => {
    const media = new FakeR2();
    const pipeline = new FakePipeline();
    const env = envFor(media, pipeline);
    const jobId = await idJson(await create(env));
    // The job vanished without going through DELETE.
    media.store.delete(`jobs/${jobId}/manifest.json`);
    await pipeline.instances.get(jobId)?.delete();

    const again = await create(env);
    expect(again.status).toBe(202);
    expect(await idJson(again)).toBe(jobId);
    expect(media.store.has(`jobs/${jobId}/manifest.json`)).toBe(true);
    expect(pipeline.instances.has(jobId)).toBe(true);
  });

  it("DELETE removes the idempotency record so the key can create again", async () => {
    const media = new FakeR2();
    const pipeline = new FakePipeline();
    const env = envFor(media, pipeline);
    const jobId = await idJson(await create(env));
    expect(media.store.has(`jobs/idem/${jobId}.json`)).toBe(true);

    const del = await app.request(`/v1/jobs/${jobId}`, { method: "DELETE", headers: auth }, env);
    expect(del.status).toBe(204);
    expect([...media.store.keys()].filter((k) => k.startsWith("jobs/idem/"))).toEqual([]);

    const again = await create(env);
    expect(again.status).toBe(202);
    expect(media.store.has(`jobs/${jobId}/manifest.json`)).toBe(true);
    expect(pipeline.instances.has(jobId)).toBe(true);
  });

  it("sweeps orphaned idempotency records but keeps live ones", async () => {
    const media = new FakeR2();
    const env = envFor(media, new FakePipeline());
    const live = await idJson(await create(env));
    await media.put("jobs/idem/gone.json", JSON.stringify({ jobId: "gone-job" }));
    await media.put("jobs/idem/garbage.json", "not json");

    await sweepExpiredUploads(env as never as Env);

    expect([...media.store.keys()].filter((k) => k.startsWith("jobs/idem/"))).toEqual([
      `jobs/idem/${live}.json`,
    ]);
  });

  it("deletes partially written job files when the workflow create fails", async () => {
    const media = new FakeR2();
    const pipeline = new FakePipeline();
    pipeline.failCreate = true;
    const env = envFor(media, pipeline);
    const files = [
      { name: "kf_n2_0", data: [1, 2, 3] },
      { name: "kf_n2_1", data: [4, 5, 6] },
      { name: "clip_n2", data: [7, 8, 9] },
    ];
    const uploadId = await createUploadFor(env, files);
    for (const f of files) await putBlob(env, uploadId, f.name, f.data);

    const res = await postJobJson(env, { manifest: validManifest(), uploadId, idempotencyKey: KEY });
    expect(res.status).toBe(500);
    expect(await res.json()).toEqual({ ok: false, error: "job_create_failed" });

    const leftover = [...media.store.keys()].filter(
      (k) => !k.startsWith("jobs/staging/") && !k.startsWith("jobs/idem/"),
    );
    expect(leftover).toEqual([]);
    // Staging survives, so the client can simply retry the create.
    pipeline.failCreate = false;
    const retry = await postJobJson(env, { manifest: validManifest(), uploadId, idempotencyKey: KEY });
    expect(retry.status).toBe(202);
  });

  it("refuses to DELETE the staging and idem trees through a job id", async () => {
    const media = new FakeR2();
    const env = envFor(media, new FakePipeline());
    const uploadId = await createUploadFor(env, [{ name: "kf_n2_0", data: [1, 2, 3] }]);
    await media.put("jobs/idem/keep.json", JSON.stringify({ jobId: "x" }));
    const before = media.store.size;

    for (const id of ["staging", "idem", `staging%2F${uploadId}`, "a%2Fb"]) {
      const res = await app.request(`/v1/jobs/${id}`, { method: "DELETE", headers: auth }, env);
      expect(res.status).toBe(400);
    }
    expect(media.store.size).toBe(before);
  });
});
