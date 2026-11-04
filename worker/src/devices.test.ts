import { describe, expect, it } from "vitest";
import { app, type Env } from "./index";

/** The three bucket calls the device routes use, over a Map. */
class Bucket {
  store = new Map<string, string>();

  async put(key: string, value: string | Uint8Array) {
    this.store.set(
      key,
      typeof value === "string" ? value : new TextDecoder().decode(value),
    );
    return null;
  }

  async get(key: string) {
    const body = this.store.get(key);
    return body === undefined ? null : { json: async () => JSON.parse(body) as unknown };
  }

  async delete(key: string) {
    this.store.delete(key);
  }

  async head(key: string) {
    return this.store.has(key) ? { key } : null;
  }
}

function envFor(media: Bucket): Env {
  return { ECHO_APP_TOKEN: "retired", MEDIA: media as never } as unknown as Env;
}

async function mint(env: Env): Promise<string> {
  const res = await app.request("/v1/device", { method: "POST" }, env);
  expect(res.status).toBe(200);
  const body = (await res.json()) as { device: string };
  return body.device;
}

const authed = (key: string) => ({ Authorization: `Bearer ${key}` });

describe("DELETE /v1/device (R12-09)", () => {
  it("erases the caller's record and leaves every other install alone", async () => {
    const media = new Bucket();
    const env = envFor(media);
    const mine = await mint(env);
    const other = await mint(env);
    expect(media.store.size).toBe(2);

    const res = await app.request("/v1/device", { method: "DELETE", headers: authed(mine) }, env);
    expect(res.status).toBe(204);
    expect(media.store.size).toBe(1);

    // The erased key no longer resolves; the other install is untouched.
    const gone = await app.request("/v1/uploads/00000000-0000-0000-0000-000000000000", {
      headers: authed(mine),
    }, env);
    expect(gone.status).toBe(401);
    const alive = await app.request("/v1/uploads/00000000-0000-0000-0000-000000000000", {
      headers: authed(other),
    }, env);
    expect(alive.status).toBe(404);
  });

  it("never deletes a record without the key that names it", async () => {
    const media = new Bucket();
    const env = envFor(media);
    await mint(env);
    expect(media.store.size).toBe(1);

    const anon = await app.request("/v1/device", { method: "DELETE" }, env);
    expect(anon.status).toBe(401);
    const forged = await app.request("/v1/device", {
      method: "DELETE",
      headers: authed(`dk_${"z".repeat(43)}`),
    }, env);
    expect(forged.status).toBe(401);
    expect(media.store.size).toBe(1);
  });

  it("refreshes the record's age so the lifecycle rule measures idleness", async () => {
    const media = new Bucket();
    const env = envFor(media);
    const key = await mint(env);
    const name = [...media.store.keys()][0];
    const record = JSON.parse(media.store.get(name)!) as { verifier: string; createdAt: number };
    // A record last used more than the touch interval ago.
    const stale = Date.now() - 40 * 24 * 60 * 60 * 1000;
    media.store.set(name, JSON.stringify({ ...record, createdAt: stale, lastSeenAt: stale }));

    const res = await app.request(
      "/v1/uploads/00000000-0000-0000-0000-000000000000",
      { headers: authed(key) },
      env,
    );
    expect(res.status).toBe(404);

    const after = JSON.parse(media.store.get(name)!) as { lastSeenAt: number; createdAt: number };
    expect(after.lastSeenAt).toBeGreaterThan(stale);
    expect(after.createdAt).toBe(stale);
  });

  it("does not rewrite a record it touched recently", async () => {
    const media = new Bucket();
    const env = envFor(media);
    const key = await mint(env);
    const name = [...media.store.keys()][0];
    const before = media.store.get(name);
    await app.request(
      "/v1/uploads/00000000-0000-0000-0000-000000000000",
      { headers: authed(key) },
      env,
    );
    expect(media.store.get(name)).toBe(before);
  });
});
