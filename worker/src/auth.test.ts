import { describe, expect, it } from "vitest";
import { app } from "./index";
import { bearer, mintDevice, seedDevice } from "./testkit";

// R9-01: the bearer is a per-install device key resolved against R2.
// Fixed literal keys stand in for minted ones wherever a test asserts
// identity; mintDevice() exercises the real entropy path.

const KEY = "dk_test_install_one";

// In-memory R2 stand-in: the surface the auth gate touches (device records).
class FakeR2 {
  store = new Map<string, Uint8Array>();

  async put(key: string, value: Uint8Array | ArrayBuffer | string) {
    const body =
      typeof value === "string"
        ? new TextEncoder().encode(value)
        : value instanceof Uint8Array
          ? value
          : new Uint8Array(value);
    this.store.set(key, body);
    return null;
  }

  async get(key: string) {
    const body = this.store.get(key);
    if (!body) return null;
    return { json: async <T,>() => JSON.parse(new TextDecoder().decode(body)) as T };
  }
}

function envFor(media: FakeR2, rl?: { limit: (args: { key: string }) => Promise<{ success: boolean }> }) {
  return { MEDIA: media, ...(rl ? { RL: rl } : {}) } as never;
}

describe("W0-05 auth + rate limit gate (R9-01 device keys)", () => {
  it("leaves /v1/health public without credentials", async () => {
    const res = await app.request("/v1/health", {}, envFor(new FakeR2()));
    expect(res.status).toBe(200);
  });

  it("rejects protected routes without a bearer (401)", async () => {
    const env = envFor(new FakeR2());
    const res = await app.request("/v1/jobs", {}, env);
    expect(res.status).toBe(401);
    expect(await res.json()).toEqual({ ok: false, error: "unauthorized" });
  });

  it("rejects an unknown bearer (401)", async () => {
    const env = envFor(new FakeR2());
    const res = await app.request("/v1/jobs", { headers: bearer("dk_never_minted") }, env);
    expect(res.status).toBe(401);
    expect(await res.json()).toEqual({ ok: false, error: "unauthorized" });
  });

  it("rejects a bearer whose record no longer matches it (401)", async () => {
    const media = new FakeR2();
    await seedDevice(media, KEY);
    // A record under a *different* id than the one derived from the bearer.
    const res = await app.request("/v1/jobs", { headers: bearer("dk_test_install_other") }, envFor(media));
    expect(res.status).toBe(401);
  });

  it("mints a key at public POST /v1/device, usable without a second request", async () => {
    const media = new FakeR2();
    const minted = await app.request("/v1/device", { method: "POST" }, envFor(media));
    expect(minted.status).toBe(200);
    const { device } = (await minted.json()) as { ok: boolean; device: string };
    expect(device).toMatch(/^dk_/);

    // The minted key passes the gate on the very next request.
    const res = await app.request("/v1/no-such-route", { headers: bearer(device) }, envFor(media));
    expect(res.status).toBe(404);
  });

  it("returns 429 when the pre-auth IP limiter refuses, before any credential check", async () => {
    const refused: string[] = [];
    const env = envFor(new FakeR2(), {
      limit: async ({ key }) => {
        refused.push(key);
        return { success: false };
      },
    });
    const res = await app.request("/v1/jobs", {}, env);
    expect(res.status).toBe(429);
    expect(await res.json()).toEqual({ ok: false, error: "rate_limited" });
    // The refusal happened on the IP key, ahead of auth: no device was
    // resolved and no per-install key was touched.
    expect(refused).toEqual(["pre:unknown"]);
  });

  it("returns 429 when the per-install limiter refuses after auth", async () => {
    const media = new FakeR2();
    await seedDevice(media, KEY);
    const refused: string[] = [];
    // The pre-auth limit passes; only the install's own limit refuses.
    const env = envFor(media, {
      limit: async ({ key }) => {
        refused.push(key);
        return { success: !key.startsWith("dev:") };
      },
    });
    const res = await app.request("/v1/jobs", { headers: bearer(KEY) }, env);
    expect(res.status).toBe(429);
    // Two limits, in order: the IP one in front of auth, then the install's.
    expect(refused).toHaveLength(2);
    expect(refused[0]).toBe("pre:unknown");
    expect(refused[1]).toMatch(/^dev:[0-9a-f]{64}:unknown$/);
  });

  it("passes a seeded key through the gate (falls to 404, no such route)", async () => {
    const media = new FakeR2();
    await seedDevice(media, KEY);
    const allowed = { limit: async () => ({ success: true }) };
    const res = await app.request("/v1/no-such-route", { headers: bearer(KEY) }, envFor(media, allowed));
    // No such route in the API: reaching notFound proves the gate passed.
    expect(res.status).toBe(404);
  });

  it("accepts a freshly minted key (the entropy path) on the next request", async () => {
    const media = new FakeR2();
    const key = await mintDevice(media);
    expect(key).toMatch(/^dk_[A-Za-z0-9_-]{43}$/);
    const allowed = { limit: async () => ({ success: true }) };
    const res = await app.request("/v1/no-such-route", { headers: bearer(key) }, envFor(media, allowed));
    expect(res.status).toBe(404);
  });

  it("ignores ECHO_APP_TOKEN entirely: the retired token authenticates nothing", async () => {
    const media = new FakeR2();
    const env = { MEDIA: media, ECHO_APP_TOKEN: "retired-shared-secret" } as never;
    const res = await app.request(
      "/v1/jobs",
      { headers: bearer("retired-shared-secret") },
      env,
    );
    expect(res.status).toBe(401);
  });
});
