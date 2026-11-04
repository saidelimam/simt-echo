import type { Context } from "hono";
import type { AppEnv } from "./index";
import { readBodyCapped } from "./uploads";

/**
 * Zero-knowledge relay (plan W4-05, §5.5).
 *
 * The relay stores one sealed bundle (`ECX1` ciphertext) per code for
 * 30 minutes. It never receives the bundle key — the key travels in the
 * QR (`k=`) and the relay code in `c=` — so the server can only ever
 * inspect ciphertext. Codes are 8 characters of Crockford-style base32
 * without ambiguous glyphs (no 0/O, 1/I/L): 10 characters, ≈ 49.5 bits.
 *
 * R12-03 (review 0012, F3): the code itself is the capability. Sender and
 * receiver are different installs, so an owner check on the read refused
 * every real transfer; possession of the high-entropy code — carried only
 * in the invitation — authorizes a download, and it unlocks ciphertext
 * alone because the bundle key never reaches the worker.
 */

export const RELAY_MAX_BYTES = 5 * 1024 * 1024;
export const RELAY_TTL_MS = 30 * 60 * 1000;
export const RELAY_CODE_LENGTH = 10;
export const RELAY_CODE_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";

/**
 * Largest multiple of the alphabet size that fits in a byte: a draw at or
 * above it would favour the low indices, so it is rejected (rejection
 * sampling). Derived from the alphabet so it stays correct if it changes.
 */
const CODE_SAMPLE_LIMIT =
  Math.floor(256 / RELAY_CODE_ALPHABET.length) * RELAY_CODE_ALPHABET.length;
/** A source that cannot produce an acceptable byte within this many draws is
 * broken, not unlucky: fail rather than loop forever. */
const MAX_DRAWS_PER_CODE = 64;
const CODE_PATTERN = new RegExp(
  `^[${RELAY_CODE_ALPHABET}]{${RELAY_CODE_LENGTH}}$`,
);
const MAX_CODE_ATTEMPTS = 5;

/**
 * One random code from a byte source (injectable for tests). Bytes in the
 * biased tail are redrawn, so every character is uniform over the alphabet
 * (R12-03: `% 31` made A–H 12.5 % more likely than the rest).
 */
export function generateRelayCode(nextByte: () => number): string {
  let code = "";
  for (let draw = 0; code.length < RELAY_CODE_LENGTH; draw++) {
    if (draw >= MAX_DRAWS_PER_CODE) {
      throw new Error("relay code entropy unavailable");
    }
    const b = nextByte();
    if (!Number.isInteger(b) || b < 0 || b >= CODE_SAMPLE_LIMIT) continue;
    code += RELAY_CODE_ALPHABET[b % RELAY_CODE_ALPHABET.length];
  }
  return code;
}

function cryptoByte(): number {
  const buf = new Uint8Array(1);
  crypto.getRandomValues(buf);
  return buf[0];
}

/**
 * Draws codes until one stores. `store` performs the allocation's
 * conditional put and must throw when the code was taken between the
 * draw and the put (R2 rejects `onlyIf: { etagDoesNotMatch: "*" }`).
 * Returns the stored code, or null after [MAX_CODE_ATTEMPTS] collisions
 * so the caller can fail the upload instead of overwriting someone
 * else's bundle.
 */
export async function allocateRelayCode(
  store: (code: string) => Promise<void>,
  generate: () => string = () => generateRelayCode(cryptoByte),
): Promise<string | null> {
  for (let attempt = 0; attempt < MAX_CODE_ATTEMPTS; attempt++) {
    let code: string;
    try {
      code = generate();
    } catch {
      // The entropy source is unusable: a retry cannot succeed.
      return null;
    }
    try {
      await store(code);
      return code;
    } catch {
      // Collision (or a storage failure): draw a fresh code.
    }
  }
  return null;
}

function relayKey(code: string): string {
  return `relay/${code}`;
}

export async function postRelay(c: Context<AppEnv>) {
  const bucket = c.env.MEDIA;
  // R10-07 (review 0010 F6): cap the read, don't buffer past the cap — an
  // oversized body is never fully read into the worker's memory.
  const body = await readBodyCapped(c.req.raw.body, RELAY_MAX_BYTES);
  if (body === null) {
    return c.json({ ok: false, error: "too_large" }, 413);
  }
  if (body.byteLength === 0) {
    return c.json({ ok: false, error: "empty_body" }, 400);
  }
  const expiresAt = Date.now() + RELAY_TTL_MS;
  const code = await allocateRelayCode(
    // R10-07: the guard is on the put, not a preceding head — a rival POST
    // landing between head and put loses its conditional put and retries
    // with a fresh code, so a bundle is never overwritten.
    async (candidate) => {
      await bucket.put(
        relayKey(candidate),
        body,
        {
          httpMetadata: { contentType: "application/octet-stream" },
          customMetadata: { expiresAt: String(expiresAt) },
          // R12-03: create-if-absent is `etagDoesNotMatch: "*"`. Comparing
          // against "" matched every object carrying a normal etag, so the
          // old guard overwrote a rival bundle instead of rejecting it. A
          // code that appeared since the draw is a collision, not an
          // overwrite.
          onlyIf: { etagDoesNotMatch: "*" },
        },
      );
    },
  );
  if (code === null) {
    return c.json({ ok: false, error: "code_unavailable" }, 503);
  }
  return c.json({ ok: true, code, expiresAt });
}

export async function getRelay(c: Context<AppEnv>) {
  const code = c.req.param("code") ?? "";
  if (!CODE_PATTERN.test(code)) {
    return c.json({ ok: false, error: "not_found" }, 404);
  }
  const bucket = c.env.MEDIA;
  const object = await bucket.get(relayKey(code));
  if (object === null) {
    return c.json({ ok: false, error: "not_found" }, 404);
  }
  const expiresAt = Number(object.customMetadata?.expiresAt ?? 0);
  if (!Number.isFinite(expiresAt) || expiresAt <= Date.now()) {
    // Expired codes are gone, and the object goes with them.
    await bucket.delete(relayKey(code));
    return c.json({ ok: false, error: "not_found" }, 404);
  }
  // R12-03: the code is the capability. The receiver is a different
  // install from the sender, so an owner check here would refuse every
  // real transfer; expiry (above) is the only access rule the relay needs
  // beyond knowing the code, and a read never burns it.
  return c.body(object.body, 200, {
    "Content-Type": "application/octet-stream",
    "Cache-Control": "no-store",
  });
}
