import { describe, expect, it } from "vitest";
import type { WorkflowStep as RuntimeWorkflowStep } from "cloudflare:workers";
import { app } from "./index";
import { EchoPipeline } from "./pipeline";
import { JobResultSchema } from "./types";

const TOKEN = "test-token-123";

// In-memory R2 stand-in: only the surface jobs.ts touches.
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

function envFor(media: FakeR2, pipeline?: FakePipeline) {
  return {
    ECHO_APP_TOKEN: TOKEN,
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
    { method: "POST", headers: { Authorization: `Bearer ${TOKEN}` }, body: form },
    env,
  );
}

describe("POST /v1/jobs", () => {
  it("stores allow-listed files, seeds progress, creates the workflow (202)", async () => {
    const media = new FakeR2();
    const pipeline = new FakePipeline();
    const res = await postJob(envFor(media, pipeline), validManifest(), [
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
    const res = await postJob(envFor(media, new FakePipeline()), manifest);

    expect(res.status).toBe(400);
    expect(await res.json()).toEqual({ ok: false, error: "consent_required" });
    expect(media.store.size).toBe(0);
  });

  it("rejects malformed and schema-violating manifests (400)", async () => {
    const media = new FakeR2();
    const env = envFor(media, new FakePipeline());

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

    expect(media.store.size).toBe(0);
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
    const res = await postJob(envFor(media, new FakePipeline()), manifest, files);

    expect(res.status).toBe(413);
    expect(await res.json()).toEqual({ ok: false, error: "too_many_files" });
  });

  it("rejects payloads over 80 MB (413)", async () => {
    const media = new FakeR2();
    const big = new Uint8Array(80 * 1024 * 1024 + 1);
    const res = await postJob(envFor(media, new FakePipeline()), validManifest(), [
      { id: "kf_n2_0", bytes: big, type: "image/jpeg" },
    ]);

    expect(res.status).toBe(413);
    expect(await res.json()).toEqual({ ok: false, error: "payload_too_large" });
  });

  it("fails closed without a workflow binding, storing nothing (500)", async () => {
    const media = new FakeR2();
    const res = await postJob(envFor(media), validManifest());

    expect(res.status).toBe(500);
    expect(await res.json()).toEqual({ ok: false, error: "server_misconfigured" });
    expect(media.store.size).toBe(0);
  });

  it("reports storage failures as 500, not invalid_manifest", async () => {
    const media = new FakeR2();
    media.put = async () => {
      throw new Error("R2 down");
    };
    const res = await postJob(envFor(media, new FakePipeline()), validManifest());

    expect(res.status).toBe(500);
    expect(await res.json()).toEqual({ ok: false, error: "job_create_failed" });
  });
});

describe("GET /v1/jobs/:id", () => {
  const auth = { Authorization: `Bearer ${TOKEN}` };

  it("reports queued with the seeded stage", async () => {
    const media = new FakeR2();
    const pipeline = new FakePipeline();
    const env = envFor(media, pipeline);
    const { jobId } = (await (await postJob(env, validManifest())).json()) as { jobId: string };

    const res = await app.request(`/v1/jobs/${jobId}`, { headers: auth }, env);
    expect(res.status).toBe(200);
    expect(await res.json()).toEqual({ ok: true, status: "queued", stage: "queued" });
  });

  it("maps a running instance and its stage through", async () => {
    const media = new FakeR2();
    const pipeline = new FakePipeline();
    const env = envFor(media, pipeline);
    const { jobId } = (await (await postJob(env, validManifest())).json()) as { jobId: string };
    pipeline.instances.get(jobId)!.statusValue = "running";
    await media.put(`jobs/${jobId}/progress.json`, JSON.stringify({ stage: "scouting", updatedAt: 1 }));

    const res = await app.request(`/v1/jobs/${jobId}`, { headers: auth }, env);
    expect(await res.json()).toEqual({ ok: true, status: "running", stage: "scouting" });
  });

  it("returns the validated result on complete", async () => {
    const media = new FakeR2();
    const pipeline = new FakePipeline();
    const env = envFor(media, pipeline);
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
    const env = envFor(media, pipeline);
    const { jobId } = (await (await postJob(env, validManifest())).json()) as { jobId: string };
    await media.delete(`jobs/${jobId}/result.json`);

    const res = await app.request(`/v1/jobs/${jobId}`, { headers: auth }, env);
    expect(res.status).toBe(500);
    expect(await res.json()).toEqual({ ok: false, error: "result_missing" });
  });

  it("maps errored and terminated to errored with detail", async () => {
    const media = new FakeR2();
    const pipeline = new FakePipeline();
    const env = envFor(media, pipeline);
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
    const env = envFor(new FakeR2(), new FakePipeline());
    const res = await app.request("/v1/jobs/does-not-exist", { headers: auth }, env);
    expect(res.status).toBe(404);
    expect(await res.json()).toEqual({ ok: false, error: "job_not_found" });
  });
});

describe("DELETE /v1/jobs/:id", () => {
  const auth = { Authorization: `Bearer ${TOKEN}` };

  it("removes every job key and terminates a running instance (204)", async () => {
    const media = new FakeR2();
    const pipeline = new FakePipeline();
    const env = envFor(media, pipeline);
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
    const env = envFor(media, pipeline);
    const { jobId } = (await (await postJob(env, validManifest())).json()) as { jobId: string };
    const instance = pipeline.instances.get(jobId)!;
    instance.statusValue = "complete";

    const res = await app.request(`/v1/jobs/${jobId}`, { method: "DELETE", headers: auth }, env);
    expect(res.status).toBe(204);
    expect(instance.terminated).toBe(false);
    expect(media.store.size).toBe(0);
  });

  it("is idempotent for an unknown job (204)", async () => {
    const env = envFor(new FakeR2(), new FakePipeline());
    const res = await app.request("/v1/jobs/nope", { method: "DELETE", headers: auth }, env);
    expect(res.status).toBe(204);
  });

  it("reports a deleted job as not found, not result_missing", async () => {
    const media = new FakeR2();
    const pipeline = new FakePipeline();
    const env = envFor(media, pipeline);
    const { jobId } = (await (await postJob(env, validManifest())).json()) as { jobId: string };
    pipeline.instances.get(jobId)!.statusValue = "complete";

    expect((await app.request(`/v1/jobs/${jobId}`, { method: "DELETE", headers: auth }, env)).status).toBe(204);
    const res = await app.request(`/v1/jobs/${jobId}`, { headers: auth }, env);
    expect(res.status).toBe(404);
    expect(await res.json()).toEqual({ ok: false, error: "job_not_found" });
  });
});

describe("EchoPipeline skeleton", () => {
  it("runs load → scout → ground → assemble and seals a valid empty result", async () => {
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
    const base = envFor(media, new FakePipeline()) as unknown as Record<string, unknown>;
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

    expect(seen).toEqual(["load", "scout", "ground", "assemble"]);
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
    const env = envFor(media, new FakePipeline()) as import("./index").Env;
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
