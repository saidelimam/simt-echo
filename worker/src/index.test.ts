import { describe, expect, it } from "vitest";
import worker, { app, type Env } from "./index";
import { JOB_RETENTION_MS, STAGING_TTL_MS } from "./uploads";
import { RELAY_TTL_MS } from "./relay";

// In-memory R2: only the surface the sweep touches.
class FakeR2 {
  store = new Map<string, { body: string; customMetadata?: Record<string, string> }>();
  async put(
    key: string,
    value: string,
    opts?: { customMetadata?: Record<string, string> },
  ) {
    this.store.set(key, { body: value, customMetadata: opts?.customMetadata });
    return null;
  }
  async get(key: string) {
    const entry = this.store.get(key);
    return entry === undefined
      ? null
      : { json: async () => JSON.parse(entry.body) as unknown };
  }
  head = async (key: string) => (this.store.has(key) ? { key } : null);
  async list({ prefix }: { prefix?: string }) {
    const entries = [...this.store.entries()]
      .filter(([k]) => !prefix || k.startsWith(prefix))
      .sort(([a], [b]) => a.localeCompare(b));
    return {
      objects: entries.map(([key, entry]) => ({ key, customMetadata: entry.customMetadata })),
      truncated: false as const,
    };
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

  it("sweeps a terminal job's prefix and an expired relay object without a GET (R9-02)", async () => {
    const media = new FakeR2();
    // Terminal 24 h ago: the whole prefix goes — inputs, events, manifest, progress.
    await media.put("jobs/done-1/manifest.json", "{}");
    await media.put("jobs/done-1/in/kf_n2_0", "photo");
    await media.put("jobs/done-1/events/e1.json", "{}");
    await media.put(
      "jobs/done-1/progress.json",
      JSON.stringify({ stage: "complete", updatedAt: Date.now() - JOB_RETENTION_MS - 1 }),
    );
    // Terminal just now: inside the 24 h window, it stays.
    await media.put(
      "jobs/done-2/progress.json",
      JSON.stringify({ stage: "errored", updatedAt: Date.now() }),
    );
    // Old but not terminal: the retention window has not started.
    await media.put(
      "jobs/running-1/progress.json",
      JSON.stringify({ stage: "scouting", updatedAt: Date.now() - JOB_RETENTION_MS - 1 }),
    );
    // Abandoned relay code: gone without a GET. A live code stays.
    await media.put("relay/OLD123", "ciphertext", {
      customMetadata: { expiresAt: String(Date.now() - 1) },
    });
    await media.put("relay/NEW456", "ciphertext", {
      customMetadata: { expiresAt: String(Date.now() + RELAY_TTL_MS) },
    });

    await worker.scheduled({} as ScheduledController, { MEDIA: media } as unknown as Env);

    expect([...media.store.keys()].sort()).toEqual([
      "jobs/done-2/progress.json",
      "jobs/running-1/progress.json",
      "relay/NEW456",
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
