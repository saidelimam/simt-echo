import { describe, expect, it } from "vitest";
import { app } from "./index";
import {
  allocateRelayCode,
  generateRelayCode,
  RELAY_CODE_ALPHABET,
  RELAY_CODE_LENGTH,
  RELAY_MAX_BYTES,
  RELAY_TTL_MS,
} from "./relay";
import { bearer, seedDevice } from "./testkit";

// R10-01: the bearer is a per-install device key. R12-03: the relay code is
// the capability, so the *receiver* install (a different key) redeems it.
const KEY = "dk_test_install_one";
const OTHER = "dk_test_install_two";

/** In-memory R2 stand-in with the surface the relay uses. */
class FakeBucket {
  objects = new Map<
    string,
    { body: Uint8Array; etag: string; customMetadata?: Record<string, string> }
  >();
  /** The guard recorded by the most recent put, for the create-if-absent test. */
  lastPut?: { key: string; onlyIf?: { etagDoesNotMatch?: string } };
  private seq = 0;

  async head(key: string) {
    return this.objects.has(key) ? { key } : null;
  }

  async put(
    key: string,
    value: ArrayBuffer | Uint8Array | string | ReadableStream<Uint8Array>,
    options?: {
      customMetadata?: Record<string, string>;
      onlyIf?: { etagDoesNotMatch?: string };
    },
  ) {
    const guard = options?.onlyIf;
    if (guard?.etagDoesNotMatch !== undefined) {
      // R2's semantics, mirrored for real: "*" means "only if no object
      // exists". Any other value fails when the stored etag equals it — and
      // a normal etag is never "", which is why the old empty-string guard
      // was a no-op in production.
      const existing = this.objects.get(key);
      const rejected =
        guard.etagDoesNotMatch === "*"
          ? existing !== undefined
          : existing !== undefined && existing.etag === guard.etagDoesNotMatch;
      if (rejected) throw new Error("R2ConditionalPutFailed");
    }
    this.lastPut = { key, ...(guard ? { onlyIf: guard } : {}) };
    const body =
      value instanceof Uint8Array
        ? value
        : value instanceof ReadableStream
          ? new Uint8Array(0)
          : value instanceof ArrayBuffer
            ? new Uint8Array(value)
            : new TextEncoder().encode(value);
    this.seq++;
    this.objects.set(key, {
      body,
      etag: `e${this.seq}`,
      customMetadata: options?.customMetadata,
    });
    return { key };
  }

  async get(key: string) {
    const found = this.objects.get(key);
    if (!found) return null;
    return {
      body: found.body,
      customMetadata: found.customMetadata,
      // The auth gate reads device records through .json().
      json: async <T,>() => JSON.parse(new TextDecoder().decode(found.body)) as T,
    };
  }

  async delete(key: string) {
    this.objects.delete(key);
  }
}

async function env(bucket: FakeBucket) {
  await seedDevice(bucket, KEY);
  return { ECHO_APP_TOKEN: "retired", MEDIA: bucket } as never;
}

/** Fake sealed bundle: ECX1 magic + bytes that are not route JSON. */
function ciphertext(): Uint8Array {
  const bytes = new Uint8Array(64);
  bytes.set(new TextEncoder().encode("ECX1"), 0);
  for (let i = 4; i < bytes.length; i++) bytes[i] = (i * 37) % 251;
  return bytes;
}

async function upload(bucket: FakeBucket, body: Uint8Array, headers = bearer(KEY)) {
  const testEnv = await env(bucket);
  return app.request(
    "/v1/relay",
    { method: "POST", headers, body },
    testEnv,
  );
}

describe("W4-05 relay codes (R12-03)", () => {
  it("generates 10-char codes without ambiguous glyphs", () => {
    let n = 0;
    const code = generateRelayCode(() => (n = (n + 29) % 248));
    expect(code).toHaveLength(RELAY_CODE_LENGTH);
    expect(RELAY_CODE_LENGTH).toBe(10);
    for (const ch of code) expect(RELAY_CODE_ALPHABET).toContain(ch);
    expect(code).not.toMatch(/[01IOL]/);
  });

  it("rejects the biased tail instead of taking it modulo", () => {
    // 248..255 map onto A–H under `% 31`; a uniform source must never
    // produce them, and a redraw must skip them.
    const draws = [255, 250, 248, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9];
    let i = 0;
    const code = generateRelayCode(() => draws[i++] ?? 0);
    expect(code).toBe(
      [0, 1, 2, 3, 4, 5, 6, 7, 8, 9]
        .map((idx) => RELAY_CODE_ALPHABET[idx])
        .join(""),
    );
    // A source that can only emit rejected bytes fails rather than hangs.
    expect(() => generateRelayCode(() => 255)).toThrow(/entropy/);
  });

  it("retries allocation on a thrown collision and gives up after 5", async () => {
    const taken = new Set(["AAAAAAAAAA", "BBBBBBBBBB"]);
    const sequence = ["AAAAAAAAAA", "BBBBBBBBBB", "CCCCCCCCCC"];
    let i = 0;
    const code = await allocateRelayCode(
      async (candidate) => {
        if (taken.has(candidate)) throw new Error("collision");
      },
      () => sequence[i++],
    );
    expect(code).toBe("CCCCCCCCCC");

    const exhausted = await allocateRelayCode(
      async () => {
        throw new Error("collision");
      },
      () => "AAAAAAAAAA",
    );
    expect(exhausted).toBeNull();

    // An unusable entropy source fails closed instead of looping.
    const noEntropy = await allocateRelayCode(
      async () => undefined,
      () => {
        throw new Error("no entropy");
      },
    );
    expect(noEntropy).toBeNull();
  });
});

describe("W4-05 relay endpoints", () => {
  it("stores ciphertext verbatim under the create-if-absent guard", async () => {
    const bucket = new FakeBucket();
    const sealed = ciphertext();
    const before = Date.now();
    const res = await upload(bucket, sealed);
    expect(res.status).toBe(200);
    const json = (await res.json()) as { code: string; expiresAt: number };
    expect(json.code).toHaveLength(RELAY_CODE_LENGTH);
    for (const ch of json.code) expect(RELAY_CODE_ALPHABET).toContain(ch);
    expect(json.expiresAt).toBeGreaterThanOrEqual(before + RELAY_TTL_MS - 1000);
    expect(json.expiresAt).toBeLessThanOrEqual(Date.now() + RELAY_TTL_MS + 1000);

    // R12-03: the allocation used R2's real create-if-absent guard.
    expect(bucket.lastPut?.key).toBe(`relay/${json.code}`);
    expect(bucket.lastPut?.onlyIf).toEqual({ etagDoesNotMatch: "*" });

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
      { headers: bearer(KEY) },
      await env(bucket),
    );
    expect(res.status).toBe(200);
    expect(new Uint8Array(await res.arrayBuffer())).toEqual(sealed);
  });

  it("lets another install redeem the code (the code is the capability)", async () => {
    const bucket = new FakeBucket();
    const testEnv = await env(bucket);
    const sealed = ciphertext();
    const up = await upload(bucket, sealed);
    const { code } = (await up.json()) as { code: string };

    // The receiver is a different phone: a different device key entirely.
    await seedDevice(bucket, OTHER);
    const foreign = await app.request(
      `/v1/relay/${code}`,
      { headers: bearer(OTHER) },
      testEnv,
    );
    expect(foreign.status).toBe(200);
    expect(new Uint8Array(await foreign.arrayBuffer())).toEqual(sealed);

    // A freshly minted third install can redeem it too — possession of the
    // high-entropy code is the whole authorization.
    const minted = await app.request("/v1/device", { method: "POST" }, testEnv);
    const { device } = (await minted.json()) as { device: string };
    const stranger = await app.request(`/v1/relay/${code}`, { headers: bearer(device) }, testEnv);
    expect(stranger.status).toBe(200);

    // Reading never burns the code: the owner still gets it.
    const own = await app.request(`/v1/relay/${code}`, { headers: bearer(KEY) }, testEnv);
    expect(own.status).toBe(200);
  });

  it("404s a code nobody knows, a malformed code and an expired code", async () => {
    const bucket = new FakeBucket();
    const testEnv = await env(bucket);
    const unknown = await app.request(
      "/v1/relay/ZZZZZZZZZZ",
      { headers: bearer(KEY) },
      testEnv,
    );
    expect(unknown.status).toBe(404);

    const malformed = await app.request(
      "/v1/relay/not-a-code!",
      { headers: bearer(KEY) },
      testEnv,
    );
    expect(malformed.status).toBe(404);

    // A code one character short of the contract is not a code.
    const short = await app.request(
      "/v1/relay/ABCDEFGHJ",
      { headers: bearer(KEY) },
      testEnv,
    );
    expect(short.status).toBe(404);

    await bucket.put("relay/ABCDEFGHJK", ciphertext(), {
      customMetadata: { expiresAt: String(Date.now() - 1) },
    });
    const expired = await app.request(
      "/v1/relay/ABCDEFGHJK",
      { headers: bearer(KEY) },
      testEnv,
    );
    expect(expired.status).toBe(404);
    expect(bucket.objects.has("relay/ABCDEFGHJK")).toBe(false);
  });

  it("never overwrites a bundle when two POSTs race the same code", async () => {
    const bucket = new FakeBucket();
    const testEnv = await env(bucket);
    const rival = new Uint8Array(64);
    rival.set(new TextEncoder().encode("ECX1"), 0);
    rival.fill(200, 4);
    // A rival POST claims the code our allocation will draw first.
    bucket.objects.set("relay/HHHHHHHHHH", {
      body: rival,
      etag: "rival",
      customMetadata: { expiresAt: String(Date.now() + RELAY_TTL_MS) },
    });
    const originalPut = bucket.put.bind(bucket);
    bucket.put = async (...args: Parameters<typeof originalPut>) => {
      const [key] = args;
      if (key === "relay/HHHHHHHHHH") throw new Error("R2ConditionalPutFailed");
      return originalPut(...args);
    };
    const mine = new Uint8Array(64);
    mine.set(new TextEncoder().encode("ECX1"), 0);
    mine.fill(7, 4);
    const res = await app.request(
      "/v1/relay",
      { method: "POST", headers: bearer(KEY), body: mine },
      testEnv,
    );
    expect(res.status).toBe(200);
    const { code } = (await res.json()) as { code: string };
    expect(code).not.toBe("HHHHHHHHHH");
    // The rival's bundle is intact; ours lives under its own code.
    expect(bucket.objects.get("relay/HHHHHHHHHH")!.body).toEqual(rival);
    expect(bucket.objects.get(`relay/${code}`)!.body).toEqual(mine);
  });

  it("rejects empty and oversized uploads", async () => {
    const bucket = new FakeBucket();
    const empty = await upload(bucket, new Uint8Array(0));
    expect(empty.status).toBe(400);

    const big = await upload(bucket, new Uint8Array(RELAY_MAX_BYTES + 1));
    expect(big.status).toBe(413);
    // No relay object was stored (the seeded device record is not a relay).
    expect([...bucket.objects.keys()].filter((k) => k.startsWith("relay/"))).toEqual([]);
  });

  it("requires a device key like every /v1 route", async () => {
    const bucket = new FakeBucket();
    const res = await app.request(
      "/v1/relay",
      { method: "POST", body: ciphertext() },
      await env(bucket),
    );
    expect(res.status).toBe(401);
    expect([...bucket.objects.keys()].filter((k) => k.startsWith("relay/"))).toEqual([]);
  });
});
