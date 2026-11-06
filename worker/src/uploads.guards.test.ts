// Spend-cap, PUT/id-hardening, isolation and R12 guard tests, split from
// uploads.test.ts to stay under the fileLines ceiling. The setup block
// duplicates uploads.test.ts (per-file fakes, the established pattern).
import { describe, expect, it } from "vitest";
import { app, type Env } from "./index";
import { MAX_UPLOAD_BYTES, MIN_FRAMES_PER_CHECKPOINT } from "./types";
import { MAX_ACTIVE_STAGING_SESSIONS } from "./uploads";
import { cappedBody } from "./jobs";
import { bearer, seedDevice } from "./testkit";

// W11-09 ready-on-arrival: per-window staged sessions, multi-session job
// create, and staged-upload discard. The FakeR2/FakePipeline stand-ins
// mirror jobs.test.ts (duplicated per file, the established pattern).

// R10-01: the bearer is a per-install device key. Two fixed installs prove
// isolation; the seeded one is the owner in every test.
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

  async head(key: string) {
    // R10-10: the intake caps measure stored sizes via head — mirror R2.
    return this.store.has(key) ? { key, size: this.store.get(key)!.body.byteLength } : null;
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

async function envFor(media: FakeR2, pipeline?: FakePipeline) {
  // R10-01: register the test install so every authenticated request resolves.
  await seedDevice(media, KEY);
  return {
    ECHO_APP_TOKEN: "retired",
    MEDIA: media as never,
    ...(pipeline ? { PIPELINE: pipeline as never } : {}),
  } as never;
}

const auth = bearer(KEY);
const jsonAuth = { ...auth, "Content-Type": "application/json" };

async function shaHexOf(data: number[]): Promise<string> {
  const bytes = new Uint8Array(data);
  const digest = await crypto.subtle.digest("SHA-256", bytes);
  return [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

// R12-10: staged blobs must be real media, so fixtures carry the container
// header the server sniffs. `clip_` parts are MP4, everything else JPEG.
const JPEG_MAGIC = [0xff, 0xd8, 0xff];
const MP4_MAGIC = [0x00, 0x00, 0x00, 0x20, 0x66, 0x74, 0x79, 0x70];
function mediaOf(name: string, data: readonly number[] | Uint8Array): Uint8Array {
  const body = data instanceof Uint8Array ? data : new Uint8Array(data);
  const magic = name.startsWith("clip") ? MP4_MAGIC : JPEG_MAGIC;
  // Already-sniffable payloads pass through unchanged, so a blob that went
  // through the helper once is not prefixed twice.
  if (magic.every((byte, i) => body[i] === byte)) return body;
  const out = new Uint8Array(magic.length + body.length);
  out.set(magic, 0);
  out.set(body, magic.length);
  return out;
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

// R10-10: the max-size job's manifest references exactly the two staged
// blobs, so completeness passes and nothing else is required.
function bigManifest() {
  return {
    schema: 1,
    draftId: "draft-big",
    consent: { sendFramesToCloudAi: true, atEpochMs: 1762000000000 },
    routeContext: "Indoor walk, 62 m, 3 turns, 1 level change",
    checkpoints: [
      {
        nodeId: "n1",
        type: "CHECKPOINT",
        atDistanceM: 10,
        tMs: 10000,
        heroKeyframeId: "kf_a",
        keyframes: [
          { id: "kf_a", tMs: 9900 },
          { id: "kf_b", tMs: 10100 },
        ],
        approach: [],
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
    files.map(async (f) => {
      const data = mediaOf(f.name, f.data);
      return { name: f.name, sha256: await shaHexOf([...data]), bytes: data.length };
    }),
  );
  const res = await createUpload(env, refs);
  expect(res.status).toBe(200);
  return ((await res.json()) as { uploadId: string; missing: string[] }).uploadId;
}

/** The header the client actually sends for each staged file (R12-10). */
function mediaHeaderFor(name: string): string {
  return name.startsWith("clip") ? "video/mp4" : "image/jpeg";
}

function putBlob(env: never, uploadId: string, name: string, data: number[] | Uint8Array) {
  return app.request(
    `/v1/uploads/${uploadId}/${name}`,
    {
      method: "PUT",
      headers: { ...auth, "Content-Type": mediaHeaderFor(name) },
      body: new Blob([mediaOf(name, data)]),
    },
    env,
  );
}

// R10-11 (F10.2): manifest-only v1.1 creates are 400, so tests that create
// jobs stage the manifest's three media files in one session first.
async function stageFullUpload(env: never): Promise<string> {
  const files = [
    { name: "kf_n2_0", data: [1, 2, 3] },
    { name: "kf_n2_1", data: [4, 5, 6] },
    { name: "clip_n2", data: [7, 8, 9] },
  ];
  const uploadId = await createUploadFor(env, files);
  for (const f of files) await putBlob(env, uploadId, f.name, f.data);
  return uploadId;
}

// R10-10: a random blob plus the hex sha256 the create request must declare.
async function randomBlobWithSha(size: number): Promise<{ data: Uint8Array; sha256: string }> {
  const data = new Uint8Array(size);
  crypto.getRandomValues(data.subarray(0, 65536));
  data.set(JPEG_MAGIC, 0);
  for (let off = 65536; off < size; off += 65536) {
    crypto.getRandomValues(data.subarray(off, off + 65536));
  }
  const digest = await crypto.subtle.digest("SHA-256", data);
  const sha256 = [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, "0")).join("");
  return { data, sha256 };
}

function postJobJson(env: never, payload: unknown) {
  return app.request(
    "/v1/jobs",
    { method: "POST", headers: jsonAuth, body: JSON.stringify(payload) },
    env,
  );
}
describe("daily spend cap on v1.1 creates (R10-05)", () => {
  const day = () => `spend/daily/${new Date().toISOString().slice(0, 10)}.json`;

  it("reserves the declared budget, not the ceiling", async () => {
    const media = new FakeR2();
    const env = await envFor(media, new FakePipeline());
    const uploadId = await stageFullUpload(env);
    const res = await postJobJson(env, {
      manifest: validManifest(),
      uploadId,
      budget: { maxSamFrames: 100 },
    });
    expect(res.status).toBe(202);
    expect(JSON.parse(new TextDecoder().decode(media.store.get(day())!.body))).toEqual({ reserved: 100 });
  });

  it("reserves the 240-frame ceiling when no budget is declared", async () => {
    const media = new FakeR2();
    const env = await envFor(media, new FakePipeline());
    const uploadId = await stageFullUpload(env);
    const res = await postJobJson(env, { manifest: validManifest(), uploadId });
    expect(res.status).toBe(202);
    expect(JSON.parse(new TextDecoder().decode(media.store.get(day())!.body))).toEqual({ reserved: 240 });
  });

  it("rejects a create past the daily cap (503, nothing stored)", async () => {
    const media = new FakeR2();
    const env = await envFor(media, new FakePipeline());
    const uploadId = await stageFullUpload(env);
    await media.put(day(), JSON.stringify({ reserved: 1150 }));
    // 1150 + 240 exceeds the default 1200 cap.
    const res = await postJobJson(env, { manifest: validManifest(), uploadId });
    expect(res.status).toBe(503);
    expect(await res.json()).toEqual({ ok: false, error: "daily_budget_exceeded" });
    // The ledger stands; no job files, and the staging is untouched so the
    // client can retry the create.
    expect(JSON.parse(new TextDecoder().decode(media.store.get(day())!.body))).toEqual({ reserved: 1150 });
    expect(
      [...media.store.keys()].filter((k) => k.startsWith("jobs/") && !k.startsWith("jobs/staging/")),
    ).toEqual([]);
  });

  it("halts creates on the kill switch, idempotent replays included", async () => {
    const media = new FakeR2();
    const env = await envFor(media, new FakePipeline());
    const uploadId = await stageFullUpload(env);
    const created = await postJobJson(env, {
      manifest: validManifest(),
      uploadId,
      idempotencyKey: "k-one",
    });
    expect(created.status).toBe(202);

    const halted = { ...((env as never) as Record<string, unknown>), SPEND_KILL_SWITCH: "on" } as never;
    const res = await postJobJson(halted, {
      manifest: validManifest(),
      uploadId,
      idempotencyKey: "k-one",
    });
    expect(res.status).toBe(503);
    expect(await res.json()).toEqual({ ok: false, error: "spend_disabled" });
  });
});

describe("upload PUT and id hardening", () => {
  const DATA = [1, 2, 3];
  const stagedBlobs = (media: FakeR2) =>
    [...media.store.keys()].filter((k) => k.startsWith("jobs/staging/") && !k.endsWith("meta.json"));

  const putRaw = (env: never, uploadId: string, init: RequestInit & { duplex?: "half" }) =>
    app.request(
      `/v1/uploads/${uploadId}/kf_n2_0`,
      {
        ...init,
        method: "PUT",
        ...(init.body instanceof Uint8Array ? { body: mediaOf("kf_n2_0", init.body) } : {}),
        headers: {
          ...auth,
          "Content-Type": "image/jpeg",
          ...(init.headers as Record<string, string>),
        },
      },
      env,
    );

  it("rejects a Content-Length that differs from the declared bytes, storing nothing", async () => {
    const media = new FakeR2();
    const env = await envFor(media, new FakePipeline());
    const uploadId = await createUploadFor(env, [{ name: "kf_n2_0", data: DATA }]);

    const res = await putRaw(env, uploadId, {
      headers: { "Content-Length": "9" },
      body: new Uint8Array(DATA),
    });
    expect(res.status).toBe(400);
    expect(await res.json()).toEqual({ ok: false, error: "size_mismatch" });
    expect(stagedBlobs(media)).toEqual([]);
  });

  it("stops reading an oversized streamed body early and stores nothing", async () => {
    const media = new FakeR2();
    const env = await envFor(media, new FakePipeline());
    const uploadId = await createUploadFor(env, [{ name: "kf_n2_0", data: DATA }]);
    let pulled = 0;
    const stream = new ReadableStream<Uint8Array>({
      pull(controller) {
        pulled++;
        if (pulled > 1000) controller.close();
        else controller.enqueue(new Uint8Array(1024));
      },
    });

    const res = await putRaw(env, uploadId, { body: stream, duplex: "half" });
    expect(res.status).toBe(400);
    expect(await res.json()).toEqual({ ok: false, error: "size_mismatch" });
    expect(pulled).toBeLessThan(10);
    expect(stagedBlobs(media)).toEqual([]);
  });

  it("rejects a body shorter than declared and accepts the exact one", async () => {
    const media = new FakeR2();
    const env = await envFor(media, new FakePipeline());
    const uploadId = await createUploadFor(env, [{ name: "kf_n2_0", data: DATA }]);

    expect((await putRaw(env, uploadId, { body: new Uint8Array([1, 2]) })).status).toBe(400);
    expect(stagedBlobs(media)).toEqual([]);
    expect((await putRaw(env, uploadId, { body: new Uint8Array(DATA) })).status).toBe(200);
    expect(stagedBlobs(media)).toHaveLength(1);
  });

  it("requires resumeOf to be a UUID naming an existing job", async () => {
    const media = new FakeR2();
    const pipeline = new FakePipeline();
    const env = await envFor(media, pipeline);
    const uploadId = await stageFullUpload(env);

    const traversal = await postJobJson(env, { manifest: validManifest(), resumeOf: "../other/x" });
    expect(traversal.status).toBe(400);
    expect(await traversal.json()).toEqual({ ok: false, error: "invalid_request" });

    const missing = await postJobJson(env, {
      manifest: validManifest(),
      uploadId,
      resumeOf: "33333333-3333-4333-8333-333333333333",
    });
    expect(missing.status).toBe(404);
    expect(await missing.json()).toEqual({ ok: false, error: "resume_not_found" });
    expect(pipeline.instances.size).toBe(0);
  });

  it("only treats UUIDs as upload session ids", async () => {
    const media = new FakeR2();
    const env = await envFor(media, new FakePipeline());
    const uploadId = await createUploadFor(env, [{ name: "kf_n2_0", data: DATA }]);
    const before = media.store.size;

    expect((await app.request("/v1/uploads/not-a-uuid", { headers: auth }, env)).status).toBe(404);
    expect((await putRaw(env, "not-a-uuid", { body: new Uint8Array(DATA) })).status).toBe(400);
    for (const id of ["staging", `${uploadId}%2Fmeta.json`, "a%2Fb"]) {
      const del = await app.request(`/v1/uploads/${id}`, { method: "DELETE", headers: auth }, env);
      expect(del.status).toBe(400);
    }
    expect(media.store.size).toBe(before);
  });

  it("bounds declared sizes and requires unique file names", async () => {
    const env = await envFor(new FakeR2(), new FakePipeline());
    const sha = await shaHexOf(DATA);
    const dup = await createUpload(env, [
      { name: "kf_n2_0", sha256: sha, bytes: 3 },
      { name: "kf_n2_0", sha256: sha, bytes: 3 },
    ]);
    expect(dup.status).toBe(400);
    const huge = await createUpload(env, [{ name: "kf_n2_0", sha256: sha, bytes: 2 ** 40 }]);
    expect(huge.status).toBe(413);
    expect(await huge.json()).toEqual({ ok: false, error: "payload_too_large" });
  });
});

describe("per-install isolation (R10-01)", () => {
  const postJobAs = (env: never, headers: Record<string, string>, payload: unknown) =>
    app.request(
      "/v1/jobs",
      { method: "POST", headers, body: JSON.stringify(payload) },
      env,
    );

  const stagedKeys = (media: FakeR2, uploadId: string) =>
    [...media.store.keys()].filter((k) => k.startsWith(`jobs/staging/${uploadId}/`)).sort();

  const jobKeys = (media: FakeR2) =>
    [...media.store.keys()].filter(
      (k) =>
        k.startsWith("jobs/") && !k.startsWith("jobs/staging/") && !k.startsWith("jobs/idem/"),
    );

  it("refuses to create a job from another install's uploadId (R12-01)", async () => {
    const media = new FakeR2();
    const pipeline = new FakePipeline();
    const env = await envFor(media, pipeline);
    const uploadId = await stageFullUpload(env);
    const intact = stagedKeys(media, uploadId);
    expect(intact).toHaveLength(4); // meta.json + two keyframes + clip

    await seedDevice(media, OTHER);
    const res = await postJobAs(
      env,
      { ...bearer(OTHER), "Content-Type": "application/json" },
      { manifest: validManifest(), uploadId },
    );
    expect(res.status).toBe(404);
    expect(await res.json()).toEqual({ ok: false, error: "upload_not_found" });

    // No job was created, nothing was reserved, and the owner's staged
    // media is untouched — the foreign request only leaked "not found".
    expect(pipeline.instances.size).toBe(0);
    expect(jobKeys(media)).toEqual([]);
    expect(stagedKeys(media, uploadId)).toEqual(intact);
    expect([...media.store.keys()].filter((k) => k.startsWith("spend/"))).toEqual([]);
  });

  it("refuses a multi-session create naming any foreign uploadIds (R12-01)", async () => {
    const media = new FakeR2();
    const pipeline = new FakePipeline();
    const env = await envFor(media, pipeline);
    const own = await createUploadFor(env, [{ name: "kf_n2_0", data: [1, 2, 3] }]);
    expect((await putBlob(env, own, "kf_n2_0", [1, 2, 3])).status).toBe(200);

    // Another install stages the rest of the manifest's media.
    await seedDevice(media, OTHER);
    const foreignAuth = bearer(OTHER);
    const foreignFiles = [
      { name: "kf_n2_1", data: [4, 5, 6] },
      { name: "clip_n2", data: [7, 8, 9] },
    ];
    const foreignRefs = await Promise.all(
      foreignFiles.map(async (f) => {
        const data = mediaOf(f.name, f.data);
        return { name: f.name, sha256: await shaHexOf([...data]), bytes: data.length };
      }),
    );
    const created = await app.request(
      "/v1/uploads",
      {
        method: "POST",
        headers: { ...foreignAuth, "Content-Type": "application/json" },
        body: JSON.stringify({ files: foreignRefs }),
      },
      env,
    );
    expect(created.status).toBe(200);
    const foreign = ((await created.json()) as { uploadId: string }).uploadId;
    for (const [name, data] of [
      ["kf_n2_1", [4, 5, 6]],
      ["clip_n2", [7, 8, 9]],
    ] as Array<[string, number[]]>) {
      const put = await app.request(
        `/v1/uploads/${foreign}/${name}`,
        {
          method: "PUT",
          headers: { ...foreignAuth, "Content-Type": mediaHeaderFor(name) },
          body: new Blob([mediaOf(name, data)]),
        },
        env,
      );
      expect(put.status).toBe(200);
    }

    const res = await postJobAs(env, jsonAuth, {
      manifest: validManifest(),
      uploadIds: [own, foreign],
    });
    expect(res.status).toBe(404);
    expect(await res.json()).toEqual({ ok: false, error: "upload_not_found" });

    // Both sessions survive: the victim's media is not copied and neither
    // staging area is consumed by the failed create.
    expect(pipeline.instances.size).toBe(0);
    expect(jobKeys(media)).toEqual([]);
    expect(stagedKeys(media, own)).toHaveLength(2);
    expect(stagedKeys(media, foreign)).toHaveLength(3);
  });

  it("keeps one install's staged session unreadable, unwritable, and undeletable by another", async () => {
    const media = new FakeR2();
    const env = await envFor(media, new FakePipeline());
    const uploadId = await createUploadFor(env, [{ name: "kf_n2_0", data: [1, 2, 3] }]);

    // A second install on the same deployment.
    await seedDevice(media, OTHER);
    const foreign = bearer(OTHER);

    // Read: 404, no existence leak.
    const read = await app.request(`/v1/uploads/${uploadId}`, { headers: foreign }, env);
    expect(read.status).toBe(404);
    expect(await read.json()).toEqual({ ok: false, error: "upload_not_found" });

    // Write: 404 — a foreign session cannot be written into.
    const put = await app.request(
      `/v1/uploads/${uploadId}/kf_n2_0`,
      {
        method: "PUT",
        headers: { ...foreign, "Content-Type": "image/jpeg" },
        body: new Blob([new Uint8Array([1, 2, 3])]),
      },
      env,
    );
    expect(put.status).toBe(404);
    expect(media.store.has(`jobs/staging/${uploadId}/kf_n2_0`)).toBe(false);

    // Delete: 404 — the foreign install cannot destroy the session.
    const del = await app.request(
      `/v1/uploads/${uploadId}`,
      { method: "DELETE", headers: foreign },
      env,
    );
    expect(del.status).toBe(404);
    expect(media.store.has(`jobs/staging/${uploadId}/meta.json`)).toBe(true);

    // The owner discards it freely.
    const ownDel = await app.request(
      `/v1/uploads/${uploadId}`,
      { method: "DELETE", headers: bearer(KEY) },
      env,
    );
    expect(ownDel.status).toBe(204);
    expect([...media.store.keys()].filter((k) => k.startsWith(`jobs/staging/${uploadId}/`))).toEqual(
      [],
    );
  });
});

describe("R12-05 idempotency scope", () => {
  const K = "shared-client-key";
  const post = (env: never, headers: Record<string, string>, payload: unknown) =>
    app.request(
      "/v1/jobs",
      {
        method: "POST",
        headers: { ...headers, "content-type": "application/json" },
        body: JSON.stringify(payload),
      },
      env,
    );

  it("gives two installs that pick the same key their own jobs", async () => {
    const media = new FakeR2();
    const pipeline = new FakePipeline();
    const env = await envFor(media, pipeline);
    await seedDevice(media as never, OTHER);

    const uploadA = await stageFullUpload(env);
    const a = await post(env, bearer(KEY), {
      manifest: validManifest(),
      uploadId: uploadA,
      idempotencyKey: K,
    });
    expect(a.status).toBe(202);
    const jobA = ((await a.json()) as { jobId: string }).jobId;
    expect(pipeline.instances.size).toBe(1);

    // Install B replays the same client-chosen key against A's session. Under
    // the old global derivation this was A's job id and answered
    // `job_not_found`; scoped to the install it is B's own create, so the
    // foreign session is what fails.
    const b = await post(env, bearer(OTHER), {
      manifest: validManifest(),
      uploadId: uploadA,
      idempotencyKey: K,
    });
    expect(b.status).toBe(404);
    expect(((await b.json()) as { error: string }).error).toMatch(/^upload_/);
    // No second instance, and A's job was never handed out.
    expect(pipeline.instances.size).toBe(1);

    // A's own replay still resolves to A's job and burns no new instance.
    const replay = await post(env, bearer(KEY), {
      manifest: validManifest(),
      uploadId: uploadA,
      idempotencyKey: K,
    });
    expect(replay.status).toBe(202);
    expect(((await replay.json()) as { jobId: string }).jobId).toBe(jobA);
    expect(pipeline.instances.size).toBe(1);
  });
});

describe("R12-06 spend guards and staging quota", () => {
  it("halts staging on the kill switch before parsing the body", async () => {
    const media = new FakeR2();
    const env = await envFor(media, new FakePipeline());
    const res = await app.request(
      "/v1/uploads",
      { method: "POST", headers: jsonAuth, body: "not json" },
      { ...(env as Record<string, unknown>), SPEND_KILL_SWITCH: "on" } as never,
    );
    expect(res.status).toBe(503);
    expect(await res.json()).toEqual({ ok: false, error: "spend_disabled" });
    expect([...media.store.keys()].filter((k) => k.startsWith("jobs/staging/"))).toEqual([]);
  });

  it("halts writes into a live session on the kill switch", async () => {
    const media = new FakeR2();
    const env = await envFor(media, new FakePipeline());
    const uploadId = await createUploadFor(env, [{ name: "kf_n2_0", data: [1, 2, 3] }]);
    const res = await putBlob(
      { ...(env as Record<string, unknown>), SPEND_KILL_SWITCH: "on" } as never,
      uploadId,
      "kf_n2_0",
      [1, 2, 3],
    );
    expect(res.status).toBe(503);
    expect(media.store.has(`jobs/staging/${uploadId}/kf_n2_0`)).toBe(false);
  });

  it("caps live staged sessions per install at 12", async () => {
    const media = new FakeR2();
    const env = await envFor(media, new FakePipeline());
    for (let i = 0; i < MAX_ACTIVE_STAGING_SESSIONS; i++) {
      await createUploadFor(env, [{ name: "kf_n2_0", data: [i] }]);
    }
    // The 13th is refused before any body work: 12 sessions of up to 80 MB
    // is what one install may hold in R2.
    const res = await app.request(
      "/v1/uploads",
      { method: "POST", headers: jsonAuth, body: "not json" },
      env,
    );
    expect(res.status).toBe(429);
    expect(await res.json()).toEqual({ ok: false, error: "staging_quota_exceeded" });
  });

  it("reserves a frame floor per checkpoint, not the declared budget alone", async () => {
    const media = new FakeR2();
    const env = await envFor(media, new FakePipeline());
    const uploadId = await stageFullUpload(env);
    // A one-frame budget used to reserve 1 while the scout ran every
    // checkpoint of the manifest.
    const res = await postJobJson(env, {
      manifest: validManifest(),
      uploadId,
      budget: { maxSamFrames: 1 },
    });
    expect(res.status).toBe(202);
    const key = `spend/daily/${new Date().toISOString().slice(0, 10)}.json`;
    const entry = media.store.get(key);
    expect(entry).toBeDefined();
    const reserved = (JSON.parse(new TextDecoder().decode(entry!.body)) as { reserved: number })
      .reserved;
    expect(reserved).toBeGreaterThanOrEqual(MIN_FRAMES_PER_CHECKPOINT);
    expect(reserved % MIN_FRAMES_PER_CHECKPOINT).toBe(0);
  });
});

describe("R12-10 request body caps", () => {
  it("rejects an oversized JSON body before buffering it", async () => {
    const media = new FakeR2();
    const env = await envFor(media, new FakePipeline());
    const res = await app.request(
      "/v1/uploads",
      { method: "POST", headers: jsonAuth, body: JSON.stringify({ pad: "x".repeat(300 * 1024) }) },
      env,
    );
    expect(res.status).toBe(413);
    expect(await res.json()).toEqual({ ok: false, error: "payload_too_large" });
    expect([...media.store.keys()].filter((k) => k.startsWith("jobs/staging/"))).toEqual([]);
  });

  it("refuses a declared length past the multipart ceiling", async () => {
    const media = new FakeR2();
    const env = await envFor(media, new FakePipeline());
    const res = await app.request(
      "/v1/jobs",
      {
        method: "POST",
        headers: { ...bearer(KEY), "content-length": String(200 * 1024 * 1024) },
        body: "x",
      },
      env,
    );
    expect(res.status).toBe(413);
    expect(await res.json()).toEqual({ ok: false, error: "payload_too_large" });
  });

  it("stops an unlabelled stream at the cap instead of buffering it", async () => {
    // The multipart parser reads through `cappedBody`, so the bound holds
    // without a Content-Length header. Exercised at a small limit: production
    // uses the same function with MAX_UPLOAD_BYTES + 1 MiB.
    let exceeded = false;
    const source = new ReadableStream<Uint8Array>({
      start(controller) {
        for (let i = 0; i < 4; i++) controller.enqueue(new Uint8Array(4));
        controller.close();
      },
    });
    const reader = cappedBody(source, 10, () => {
      exceeded = true;
    }).getReader();
    let read = 0;
    for (;;) {
      try {
        const { done, value } = await reader.read();
        if (done) break;
        read += value.byteLength;
      } catch {
        break;
      }
    }
    expect(exceeded).toBe(true);
    expect(read).toBe(8);
  });

  it("stores the sniffed media type and refuses mislabelled bytes", async () => {
    const media = new FakeR2();
    const env = await envFor(media, new FakePipeline());
    const uploadId = await createUploadFor(env, [{ name: "kf_n2_0", data: [1, 2, 3] }]);
    const put = (id: string, name: string, type: string, body: Blob) =>
      app.request(
        `/v1/uploads/${id}/${name}`,
        { method: "PUT", headers: { ...auth, "Content-Type": type }, body },
        env,
      );

    // A declared type that contradicts the bytes never reaches storage.
    const mismatch = await put(
      uploadId,
      "kf_n2_0",
      "video/mp4",
      new Blob([mediaOf("kf_n2_0", [1, 2, 3])]),
    );
    expect(mismatch.status).toBe(415);
    expect(await mismatch.json()).toEqual({ ok: false, error: "unsupported_media_type" });
    expect(media.store.has(`jobs/staging/${uploadId}/kf_n2_0`)).toBe(false);

    // Bytes in neither container are not media, whatever the header says.
    const raw = [1, 2, 3];
    const rawSession = await createUpload(env, [
      { name: "kf_n2_1", sha256: await shaHexOf(raw), bytes: raw.length },
    ]);
    const rawId = ((await rawSession.json()) as { uploadId: string }).uploadId;
    const notMedia = await put(rawId, "kf_n2_1", "image/jpeg", new Blob([new Uint8Array(raw)]));
    expect(notMedia.status).toBe(415);
    expect(media.store.has(`jobs/staging/${rawId}/kf_n2_1`)).toBe(false);

    // The accepted path stores the sniffed type, not the caller's string.
    const ok = await put(
      uploadId,
      "kf_n2_0",
      "image/jpeg; charset=binary",
      new Blob([mediaOf("kf_n2_0", [1, 2, 3])]),
    );
    expect(ok.status).toBe(200);
    expect(media.store.get(`jobs/staging/${uploadId}/kf_n2_0`)!.contentType).toBe("image/jpeg");
  });

  it("refuses a multipart part whose bytes are not the declared media", async () => {
    const env = await envFor(new FakeR2(), new FakePipeline());
    const form = new FormData();
    form.append("manifest", JSON.stringify(validManifest()));
    form.append(
      "kf_n2_0",
      new File([new Uint8Array([1, 2, 3])], "kf_n2_0", { type: "image/jpeg" }),
    );
    const res = await app.request(
      "/v1/jobs",
      { method: "POST", headers: bearer(KEY), body: form },
      env,
    );
    expect(res.status).toBe(415);
    expect(await res.json()).toEqual({
      ok: false,
      error: "unsupported_media_type",
      file: "kf_n2_0",
    });
  });
});
