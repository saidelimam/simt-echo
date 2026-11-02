import { describe, expect, it } from "vitest";
import worker, { app, type Env } from "./index";
import { STAGING_TTL_MS } from "./uploads";

// In-memory R2: only the surface the sweep touches.
class FakeR2 {
  store = new Map<string, string>();
  async put(key: string, value: string) {
    this.store.set(key, value);
    return null;
  }
  async get(key: string) {
    const body = this.store.get(key);
    return body === undefined ? null : { json: async () => JSON.parse(body) as unknown };
  }
  head = async (key: string) => (this.store.has(key) ? { key } : null);
  async list({ prefix }: { prefix?: string }) {
    const keys = [...this.store.keys()].filter((k) => !prefix || k.startsWith(prefix)).sort();
    return { objects: keys.map((key) => ({ key })), truncated: false as const };
  }
  async delete(keys: string | string[]) {
    for (const k of Array.isArray(keys) ? keys : [keys]) this.store.delete(k);
  }
}

describe("scheduled() expiry backstop", () => {
  it("sweeps expired staged uploads and orphaned idem records, keeping live ones", async () => {
    const media = new FakeR2();
    const meta = (createdAt: number) =>
      // R9-01: a live session meta carries its owner, or the sweep treats it
      // as corrupt and deletes it.
      JSON.stringify({ files: [], owner: "idx-test-install", createdAt });
    await media.put("jobs/staging/old/meta.json", meta(Date.now() - STAGING_TTL_MS - 1));
    await media.put("jobs/staging/old/kf_n2_0", "blob");
    await media.put("jobs/staging/fresh/meta.json", meta(Date.now()));
    await media.put("jobs/idem/gone.json", JSON.stringify({ jobId: "gone-job" }));
    await media.put("jobs/live/manifest.json", "{}");
    await media.put("jobs/idem/live.json", JSON.stringify({ jobId: "live" }));

    await worker.scheduled({} as ScheduledController, { MEDIA: media } as unknown as Env);

    expect([...media.store.keys()].sort()).toEqual([
      "jobs/idem/live.json",
      "jobs/live/manifest.json",
      "jobs/staging/fresh/meta.json",
    ]);
  });
});

// Scaffold smoke test: the health route every later worker slice builds on.
// The jobs API itself lands in W3-03; keep this file free of route logic.
describe("GET /v1/health", () => {
  it("reports ok without any bindings configured", async () => {
    const res = await app.request("/v1/health");
    expect(res.status).toBe(200);
    const body = (await res.json()) as {
      ok: boolean;
      version: string;
      metaKeyConfigured: boolean;
    };
    expect(body.ok).toBe(true);
    expect(body.version).toBe("0.1.0");
    expect(body.metaKeyConfigured).toBe(false);
  });
});
