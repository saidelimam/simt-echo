import { describe, expect, it } from "vitest";
import { app, type Env } from "./index";
import { MAX_UPLOAD_BYTES } from "./types";
import { sweepExpiredUploads } from "./uploads";
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

describe("W11-09 ready-on-arrival uploads", () => {
  it("discards staged uploads and treats unknown ids as gone", async () => {
    const media = new FakeR2();
    const env = await envFor(media, new FakePipeline());
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

  it("does not sweep other sessions on create (R10-09: cron owns cleanup)", async () => {
    const media = new FakeR2();
    const env = await envFor(media, new FakePipeline());
    // The state the old opportunistic sweep removed on every create: an
    // expired foreign session and an orphaned idempotency record.
    const stale = "11111111-1111-1111-1111-111111111111";
    await media.put(
      `jobs/staging/${stale}/meta.json`,
      JSON.stringify({ files: [], owner: KEY, createdAt: Date.now() - 2 * 3_600_000 }),
    );
    await media.put(`jobs/staging/${stale}/kf_n2_0`, new Uint8Array([1, 2, 3]));
    await media.put("jobs/idem/orphan.json", JSON.stringify({ jobId: "no-such-job" }));

    const res = await createUpload(env, [
      { name: "kf_n2_0", sha256: "a".repeat(64), bytes: 3 },
    ]);
    expect(res.status).toBe(200);

    // Create touches only its own session: the stale keys survive until
    // the hourly cron (or a lazy per-session check) removes them.
    expect(media.store.has(`jobs/staging/${stale}/meta.json`)).toBe(true);
    expect(media.store.has(`jobs/staging/${stale}/kf_n2_0`)).toBe(true);
    expect(media.store.has("jobs/idem/orphan.json")).toBe(true);
  });

  it("creates a job against per-window sessions and consumes all of them", async () => {
    const media = new FakeR2();
    const pipeline = new FakePipeline();
    const env = await envFor(media, pipeline);
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
      expect([...new Uint8Array(stored!.body)]).toEqual([...mediaOf(name, data)]);
    }
    expect([...media.store.keys()].filter((k) => k.startsWith("jobs/staging/"))).toEqual([]);
  });

  it("rejects a create naming both uploadId and uploadIds", async () => {
    const media = new FakeR2();
    const env = await envFor(media, new FakePipeline());
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
    const env = await envFor(media, pipeline);
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

  it("completes a max-size job (80 MB) through the streaming intake move", async () => {
    const media = new FakeR2();
    const pipeline = new FakePipeline();
    const env = await envFor(media, pipeline);
    // Two 40 MiB blobs total exactly MAX_UPLOAD_BYTES: the move must stream
    // each R2→R2 without holding the whole job in memory (R10-10). The
    // manifest references exactly these names, so completeness passes.
    const half = MAX_UPLOAD_BYTES / 2;
    const [a, b] = await Promise.all([randomBlobWithSha(half), randomBlobWithSha(half)]);
    const uploadId = await createUpload(
      env,
      [
        { name: "kf_a", sha256: a.sha256, bytes: half },
        { name: "kf_b", sha256: b.sha256, bytes: half },
      ],
    );
    expect(uploadId.status).toBe(200);
    const id = ((await uploadId.json()) as { uploadId: string }).uploadId;
    expect((await putBlob(env, id, "kf_a", a.data)).status).toBe(200);
    expect((await putBlob(env, id, "kf_b", b.data)).status).toBe(200);

    const res = await postJobJson(env, { manifest: bigManifest(), uploadId: id });
    expect(res.status).toBe(202);
    const jobId = ((await res.json()) as { jobId: string }).jobId;

    // Both blobs landed intact under the job — the stream pass-through
    // preserved every byte of a 40 MiB object.
    expect(media.store.has(`jobs/${jobId}/in/kf_a`)).toBe(true);
    expect(media.store.has(`jobs/${jobId}/in/kf_b`)).toBe(true);
    expect(media.store.get(`jobs/${jobId}/in/kf_a`)!.body.byteLength).toBe(half);
    expect(media.store.get(`jobs/${jobId}/in/kf_b`)!.body.byteLength).toBe(half);
    expect(pipeline.instances.size).toBe(1);
  });
});

describe("idempotent create and job cleanup", () => {
  const KEY = "idempotent-client-key-1";
  const create = async (
    env: never,
    extra: Record<string, unknown> = {},
    manifest: unknown = validManifest(),
  ) => {
    const uploadId = await stageFullUpload(env);
    return postJobJson(env, { manifest, uploadId, idempotencyKey: KEY, ...extra });
  };
  const idJson = async (res: Response) => ((await res.json()) as { jobId: string }).jobId;

  it("derives one job id from the key so concurrent creates cannot double-create", async () => {
    const media = new FakeR2();
    const pipeline = new FakePipeline();
    const env = await envFor(media, pipeline);

    // One shared session: concurrent creates post the identical payload, so
    // the payload-hash binding cannot conflict between them.
    const uploadId = await stageFullUpload(env);

    const [a, b] = await Promise.all([
      postJobJson(env, { manifest: validManifest(), uploadId, idempotencyKey: KEY }),
      postJobJson(env, { manifest: validManifest(), uploadId, idempotencyKey: KEY }),
    ]);
    expect([a.status, b.status]).toEqual([202, 202]);
    const [idA, idB] = [await idJson(a), await idJson(b)];

    expect(idA).toBe(idB);
    expect(idA).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-5[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/);
    expect(pipeline.instances.size).toBe(1);
    // A different key is a different job.
    const other = await create(env, { idempotencyKey: "other-key" });
    expect(await idJson(other)).not.toBe(idA);
  });

  it("discards a stale record whose job lost its manifest, then creates afresh", async () => {
    const media = new FakeR2();
    const pipeline = new FakePipeline();
    const env = await envFor(media, pipeline);
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

  it("replays the same payload under the same key to the original job", async () => {
    const media = new FakeR2();
    const pipeline = new FakePipeline();
    const env = await envFor(media, pipeline);
    const uploadId = await stageFullUpload(env);
    const first = await postJobJson(env, { manifest: validManifest(), uploadId, idempotencyKey: KEY });
    expect(first.status).toBe(202);
    const jobId = await idJson(first);

    // The move consumed the staging; the replay must not need it.
    const again = await postJobJson(env, { manifest: validManifest(), uploadId, idempotencyKey: KEY });
    expect(again.status).toBe(202);
    expect(await idJson(again)).toBe(jobId);
    expect(pipeline.instances.size).toBe(1);
  });

  it("answers 409 when a changed payload arrives under the same key", async () => {
    const media = new FakeR2();
    const pipeline = new FakePipeline();
    const env = await envFor(media, pipeline);
    const uploadId = await stageFullUpload(env);
    const first = await postJobJson(env, { manifest: validManifest(), uploadId, idempotencyKey: KEY });
    expect(first.status).toBe(202);
    const jobId = await idJson(first);

    // A second session is a different payload under the same key: the old
    // path silently handed back the first job.
    const otherUpload = await stageFullUpload(env);
    const res = await postJobJson(env, {
      manifest: validManifest(),
      uploadId: otherUpload,
      idempotencyKey: KEY,
    });
    expect(res.status).toBe(409);
    expect(await res.json()).toEqual({ ok: false, error: "idempotency_conflict" });
    // The original job stands untouched.
    expect(pipeline.instances.size).toBe(1);
    expect(media.store.has(`jobs/${jobId}/manifest.json`)).toBe(true);
  });

  it("answers 409 for a legacy idem record without a payload hash", async () => {
    const media = new FakeR2();
    const pipeline = new FakePipeline();
    const env = await envFor(media, pipeline);
    const uploadId = await stageFullUpload(env);
    const first = await postJobJson(env, { manifest: validManifest(), uploadId, idempotencyKey: KEY });
    expect(first.status).toBe(202);
    const jobId = await idJson(first);

    // A record written before this deploy: shape {jobId}, no hash. It
    // cannot prove the payload matches, so the replay conflicts (decision:
    // strict).
    await media.put(`jobs/idem/${jobId}.json`, JSON.stringify({ jobId }));
    const res = await postJobJson(env, { manifest: validManifest(), uploadId, idempotencyKey: KEY });
    expect(res.status).toBe(409);
    expect(await res.json()).toEqual({ ok: false, error: "idempotency_conflict" });
    expect(pipeline.instances.size).toBe(1);
  });

  it("rejects a manifest-only create at intake, before any paid work", async () => {
    const media = new FakeR2();
    const pipeline = new FakePipeline();
    const env = await envFor(media, pipeline);

    const res = await postJobJson(env, { manifest: validManifest(), idempotencyKey: KEY });
    expect(res.status).toBe(400);
    expect(await res.json()).toEqual({ ok: false, error: "upload_reference_required" });
    // Nothing was reserved and no Workflow instance was burned.
    expect(pipeline.instances.size).toBe(0);
    expect([...media.store.keys()].filter((k) => k.startsWith("spend/"))).toEqual([]);
  });

  it("answers 400 duplicate_file when one name is staged in two sessions", async () => {
    const media = new FakeR2();
    const pipeline = new FakePipeline();
    const env = await envFor(media, pipeline);
    const first = await createUploadFor(env, [
      { name: "kf_n2_0", data: [1, 2, 3] },
      { name: "kf_n2_1", data: [4, 5, 6] },
      { name: "clip_n2", data: [7, 8, 9] },
    ]);
    for (const [name, data] of [
      ["kf_n2_0", [1, 2, 3]],
      ["kf_n2_1", [4, 5, 6]],
      ["clip_n2", [7, 8, 9]],
    ] as Array<[string, number[]]>) {
      expect((await putBlob(env, first, name, data)).status).toBe(200);
    }
    // The second window re-stages kf_n2_0 — the old move took the first one
    // silently.
    const second = await createUploadFor(env, [{ name: "kf_n2_0", data: [9, 9, 9] }]);
    expect((await putBlob(env, second, "kf_n2_0", [9, 9, 9])).status).toBe(200);

    const res = await postJobJson(env, { manifest: validManifest(), uploadIds: [first, second] });
    expect(res.status).toBe(400);
    expect(await res.json()).toEqual({ ok: false, error: "duplicate_file", file: "kf_n2_0" });
    expect(pipeline.instances.size).toBe(0);
  });

  it("rejects duplicate uploadIds in the create body", async () => {
    const media = new FakeR2();
    const env = await envFor(media, new FakePipeline());
    const uploadId = await stageFullUpload(env);
    const res = await postJobJson(env, {
      manifest: validManifest(),
      uploadIds: [uploadId, uploadId],
    });
    expect(res.status).toBe(400);
    expect(await res.json()).toEqual({ ok: false, error: "invalid_request" });
  });

  it("rejects manifests that break the uniqueness and cross-field rules", async () => {
    const media = new FakeR2();
    const pipeline = new FakePipeline();
    const env = await envFor(media, pipeline);
    const uploadId = await stageFullUpload(env);

    const cases: Array<(m: ReturnType<typeof validManifest>) => void> = [
      (m) => {
        // Duplicate nodeId (and, transitively, duplicate media ids).
        m.checkpoints.push({ ...m.checkpoints[0] });
      },
      (m) => {
        // A second checkpoint reusing the first's keyframe ids.
        m.checkpoints.push({ ...m.checkpoints[0], nodeId: "n3" });
      },
      (m) => {
        m.checkpoints[0].heroKeyframeId = "kf_other";
      },
      (m) => {
        m.checkpoints[0].clip.endMs = 1000; // < startMs 37250
      },
    ];
    for (const mutate of cases) {
      const m = validManifest();
      mutate(m);
      const res = await postJobJson(env, { manifest: m, uploadId });
      expect(res.status).toBe(400);
      expect(await res.json()).toEqual({ ok: false, error: "invalid_request" });
    }
    expect(pipeline.instances.size).toBe(0);
  });

  it("accepts a keyframe scheduled before video 0 (early-walk turn)", async () => {
    // Regression: the W3-01 reference window schedules a keyframe at
    // `t − 4000`; when the first turn lands in the first seconds of the walk
    // that time is negative (field manifest: turn at 1764 ms, keyframe at
    // −236 ms) and the old nonnegative floor 400'd the whole create.
    const media = new FakeR2();
    const pipeline = new FakePipeline();
    const env = await envFor(media, pipeline);
    const uploadId = await stageFullUpload(env);
    const m = validManifest();
    m.checkpoints[0].tMs = 1764;
    m.checkpoints[0].keyframes[0].tMs = -236;
    const res = await postJobJson(env, { manifest: m, uploadId });
    expect(res.status).toBe(202);
    expect(pipeline.instances.size).toBe(1);
  });

  it("DELETE removes the idempotency record so the key can create again", async () => {
    const media = new FakeR2();
    const pipeline = new FakePipeline();
    const env = await envFor(media, pipeline);
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
    const env = await envFor(media, new FakePipeline());
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
    const env = await envFor(media, pipeline);
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
      (k) =>
        !k.startsWith("jobs/staging/") &&
        !k.startsWith("jobs/idem/") &&
        !k.startsWith("devices/") &&
        // R10-05: the day's ledger reservation stands — a reserve with a
        // failed job spends toward the cap, never under it.
        !k.startsWith("spend/"),
    );
    expect(leftover).toEqual([]);
    // Staging survives, so the client can simply retry the create.
    pipeline.failCreate = false;
    const retry = await postJobJson(env, { manifest: validManifest(), uploadId, idempotencyKey: KEY });
    expect(retry.status).toBe(202);
  });

  it("refuses to DELETE the staging and idem trees through a job id", async () => {
    const media = new FakeR2();
    const env = await envFor(media, new FakePipeline());
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
