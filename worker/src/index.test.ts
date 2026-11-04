import { describe, expect, it } from "vitest";
import worker, { app, type Env } from "./index";
import { JOB_RETENTION_MS, STAGING_TTL_MS } from "./uploads";
import { RELAY_TTL_MS } from "./relay";
import { bearer, mintDevice } from "./testkit";

// In-memory R2: only the surface the sweep touches.
class FakeR2 {
  store = new Map<string, { body: string; customMetadata?: Record<string, string> }>();
  async put(
    key: string,
    value: string | Uint8Array | ArrayBuffer,
    opts?: { customMetadata?: Record<string, string> },
  ) {
    const body =
      typeof value === "string"
        ? value
        : new TextDecoder().decode(
            value instanceof Uint8Array ? value : new Uint8Array(value),
          );
    this.store.set(key, { body, customMetadata: opts?.customMetadata });
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
      // R10-01: a live session meta carries its owner, or the sweep treats it
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

  it("sweeps a terminal job's prefix and an expired relay object without a GET (R10-02)", async () => {
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

// R10-12 (review 0010, F11): the error contract — an unhandled throw answers
// the same {ok:false} envelope every other route uses, and every response
// carries a request id that correlates it with the invocation logs.
describe("R10-12 (F11) error contract", () => {
  it("answers an unhandled throw with the 500 envelope, never the raw error", async () => {
    class ExplodingR2 extends FakeR2 {
      async get(key: string) {
        // The jobs GET's owner read is not locally guarded: the throw escapes
        // the handler and reaches app.onError.
        if (key.startsWith("jobs/")) throw new Error("R2 backend exploded (vendor text)");
        return super.get(key);
      }
    }
    const media = new ExplodingR2();
    const key = await mintDevice(media);
    const res = await app.request(
      "/v1/jobs/job-x",
      { headers: bearer(key) },
      { MEDIA: media, PIPELINE: {} } as unknown as Env,
    );
    expect(res.status).toBe(500);
    const text = await res.text();
    // The raw message (vendor text) is the log site, not the client's.
    expect(text).not.toContain("exploded");
    const body = JSON.parse(text) as { ok: boolean; error: string; requestId: string };
    expect(body).toEqual({ ok: false, error: "internal_error", requestId: expect.any(String) });
    // The body's id and the response header are the same correlation id.
    expect(res.headers.get("x-request-id")).toBe(body.requestId);
  });

  it("generates a request id when none arrives, echoes a valid one, replaces an invalid one", async () => {
    const none = await app.request("/v1/health");
    expect(none.headers.get("x-request-id")).toMatch(
      /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/,
    );
    const echoed = await app.request("/v1/health", { headers: { "X-Request-Id": "trace-42" } });
    expect(echoed.headers.get("x-request-id")).toBe("trace-42");
    // A space is outside the allowed charset: replaced, not echoed.
    const replaced = await app.request("/v1/health", { headers: { "X-Request-Id": "bad id" } });
    expect(replaced.headers.get("x-request-id")).not.toBe("bad id");
    expect(replaced.headers.get("x-request-id")).toMatch(
      /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/,
    );
  });
});

describe("R12-11 rate-limit keys and response headers", () => {
  class FakeLimiter {
    keys: string[] = [];
    constructor(private readonly ok: boolean) {}
    async limit({ key }: { key: string }) {
      this.keys.push(key);
      return { success: this.ok };
    }
  }

  it("never trusts X-Forwarded-For: a missing CF header is one bucket", async () => {
    const limiter = new FakeLimiter(true);
    const env = { RL: limiter, MEDIA: new FakeR2() } as unknown as Env;
    await app.request("/v1/health", { headers: { "X-Forwarded-For": "1.1.1.1" } }, env);
    await app.request("/v1/health", { headers: { "X-Forwarded-For": "2.2.2.2" } }, env);
    expect(limiter.keys).toEqual(["pre:unknown", "pre:unknown"]);

    await app.request("/v1/health", { headers: { "CF-Connecting-IP": "9.9.9.9" } }, env);
    expect(limiter.keys.at(-1)).toBe("pre:9.9.9.9");
  });

  it("gives device minting its own stricter bucket", async () => {
    const strict = new FakeLimiter(false);
    const shared = new FakeLimiter(true);
    const env = {
      RL: shared,
      RL_MINT: strict,
      MEDIA: new FakeR2(),
    } as unknown as Env;
    const res = await app.request(
      "/v1/device",
      { method: "POST", headers: { "CF-Connecting-IP": "5.5.5.5" } },
      env,
    );
    expect(res.status).toBe(429);
    expect(strict.keys).toEqual(["mint:5.5.5.5"]);
    // The shared bucket was not spent on a mint.
    expect(shared.keys).toEqual([]);
  });

  it("sets nosniff on every response and no-store on the mint", async () => {
    const ok = new FakeLimiter(true);
    const env = { RL: ok, MEDIA: new FakeR2() } as unknown as Env;
    const health = await app.request("/v1/health", {}, env);
    expect(health.headers.get("X-Content-Type-Options")).toBe("nosniff");
    const minted = await app.request("/v1/device", { method: "POST" }, env);
    expect(minted.status).toBe(200);
    expect(minted.headers.get("X-Content-Type-Options")).toBe("nosniff");
    expect(minted.headers.get("Cache-Control")).toBe("no-store");
    const missing = await app.request("/v1/nope", {}, env);
    expect(missing.headers.get("X-Content-Type-Options")).toBe("nosniff");
  });
});
