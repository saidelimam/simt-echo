import { describe, expect, it } from "vitest";
import { app } from "./index";
import {
  allocateRelayCode,
  generateRelayCode,
  RELAY_CODE_ALPHABET,
  RELAY_MAX_BYTES,
  RELAY_TTL_MS,
} from "./relay";

const TOKEN = "test-token-123";
const AUTH = { Authorization: `Bearer ${TOKEN}` };

/** In-memory R2 stand-in with the surface the relay uses. */
class FakeBucket {
  objects = new Map<
    string,
    { body: Uint8Array; customMetadata?: Record<string, string> }
  >();

  async head(key: string) {
    return this.objects.has(key) ? { key } : null;
  }

  async put(
    key: string,
    value: ArrayBuffer | Uint8Array,
    options?: { customMetadata?: Record<string, string> },
  ) {
    const body =
      value instanceof Uint8Array ? value : new Uint8Array(value);
    this.objects.set(key, { body, customMetadata: options?.customMetadata });
    return { key };
  }

  async get(key: string) {
    const found = this.objects.get(key);
    if (!found) return null;
    return { body: found.body, customMetadata: found.customMetadata };
  }

  async delete(key: string) {
    this.objects.delete(key);
  }
}

function env(bucket: FakeBucket) {
  return { ECHO_APP_TOKEN: TOKEN, MEDIA: bucket } as never;
}

/** Fake sealed bundle: ECX1 magic + bytes that are not route JSON. */
function ciphertext(): Uint8Array {
  const bytes = new Uint8Array(64);
  bytes.set(new TextEncoder().encode("ECX1"), 0);
  for (let i = 4; i < bytes.length; i++) bytes[i] = (i * 37) % 251;
  return bytes;
}

async function upload(bucket: FakeBucket, body: Uint8Array) {
  return app.request(
    "/v1/relay",
    { method: "POST", headers: AUTH, body },
    env(bucket),
  );
}

describe("W4-05 relay codes", () => {
  it("generates 6-char codes without ambiguous glyphs", () => {
    let n = 0;
    const code = generateRelayCode(() => (n += 29));
    expect(code).toHaveLength(6);
    for (const ch of code) expect(RELAY_CODE_ALPHABET).toContain(ch);
    expect(code).not.toMatch(/[01IOL]/);
  });

  it("retries allocation on collision and gives up after 5", async () => {
    const taken = new Set(["AAAAAA", "BBBBBB"]);
    const sequence = ["AAAAAA", "BBBBBB", "CCCCCC"];
    let i = 0;
    const code = await allocateRelayCode(
      async (candidate) => taken.has(candidate),
      () => sequence[i++],
    );
    expect(code).toBe("CCCCCC");

    const exhausted = await allocateRelayCode(async () => true, () => "AAAAAA");
    expect(exhausted).toBeNull();
  });
});

describe("W4-05 relay endpoints", () => {
  it("stores ciphertext verbatim and returns code + 30 min expiry", async () => {
    const bucket = new FakeBucket();
    const sealed = ciphertext();
    const before = Date.now();
    const res = await upload(bucket, sealed);
    expect(res.status).toBe(200);
    const json = (await res.json()) as { code: string; expiresAt: number };
    expect(json.code).toMatch(/^[A-HJ-NP-Z2-9]{6}$/);
    expect(json.expiresAt).toBeGreaterThanOrEqual(before + RELAY_TTL_MS - 1000);
    expect(json.expiresAt).toBeLessThanOrEqual(Date.now() + RELAY_TTL_MS + 1000);

    // Server-side inspection sees only the sealed bytes: no plaintext.
    const stored = bucket.objects.get(`relay/${json.code}`);
    expect(stored).toBeDefined();
    expect(stored!.body).toEqual(sealed);
    expect(new TextDecoder().decode(stored!.body)).not.toContain("routeId");
    expect(stored!.customMetadata?.expiresAt).toBe(String(json.expiresAt));
  });

  it("serves an unexpired code back byte-for-byte", async () => {
    const bucket = new FakeBucket();
    const sealed = ciphertext();
    const up = await upload(bucket, sealed);
    const { code } = (await up.json()) as { code: string };
    const res = await app.request(
      `/v1/relay/${code}`,
      { headers: AUTH },
      env(bucket),
    );
    expect(res.status).toBe(200);
    expect(new Uint8Array(await res.arrayBuffer())).toEqual(sealed);
  });

  it("404s unknown, malformed, and expired codes (deleting expired)", async () => {
    const bucket = new FakeBucket();
    const unknown = await app.request(
      "/v1/relay/ZZZZZZ",
      { headers: AUTH },
      env(bucket),
    );
    expect(unknown.status).toBe(404);

    const malformed = await app.request(
      "/v1/relay/not-a-code!",
      { headers: AUTH },
      env(bucket),
    );
    expect(malformed.status).toBe(404);

    await bucket.put("relay/ABCDEF", ciphertext(), {
      customMetadata: { expiresAt: String(Date.now() - 1) },
    });
    const expired = await app.request(
      "/v1/relay/ABCDEF",
      { headers: AUTH },
      env(bucket),
    );
    expect(expired.status).toBe(404);
    expect(bucket.objects.has("relay/ABCDEF")).toBe(false);
  });

  it("rejects empty and oversized uploads", async () => {
    const bucket = new FakeBucket();
    const empty = await upload(bucket, new Uint8Array(0));
    expect(empty.status).toBe(400);

    const big = await upload(bucket, new Uint8Array(RELAY_MAX_BYTES + 1));
    expect(big.status).toBe(413);
    expect(bucket.objects.size).toBe(0);
  });

  it("requires the app token like every /v1 route", async () => {
    const bucket = new FakeBucket();
    const res = await app.request(
      "/v1/relay",
      { method: "POST", body: ciphertext() },
      env(bucket),
    );
    expect(res.status).toBe(401);
    expect(bucket.objects.size).toBe(0);
  });
});
