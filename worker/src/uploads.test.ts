import { describe, expect, it } from "vitest";
import { app } from "./index";

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

  async list({ prefix }: { prefix?: string; cursor?: string }) {
    const keys = [...this.store.keys()].filter((k) => !prefix || k.startsWith(prefix)).sort();
    return { objects: keys.map((key) => ({ key })), truncated: false as const, delimitedPrefixes: [] };
  }

  async delete(keys: string | string[]): Promise<void> {
    for (const k of Array.isArray(keys) ? keys : [keys]) this.store.delete(k);
  }
}

class FakePipeline {
  instances = new Map<string, { id: string }>();

  async create({ id }: { id?: string; params?: unknown }) {
    const inst = { id: id ?? crypto.randomUUID() };
    this.instances.set(inst.id, inst);
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
