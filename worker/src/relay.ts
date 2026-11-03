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
 * without ambiguous glyphs (no 0/O, 1/I/L): ≈ 39.6 bits per code,
 * carried in the QR and guessed only by the receiver's own install
 * (R9-07, review 0009 F6).
 */

export const RELAY_MAX_BYTES = 5 * 1024 * 1024;
export const RELAY_TTL_MS = 30 * 60 * 1000;
export const RELAY_CODE_LENGTH = 8;
export const RELAY_CODE_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
const CODE_PATTERN = new RegExp(
  `^[${RELAY_CODE_ALPHABET}]{${RELAY_CODE_LENGTH}}$`,
);
const MAX_CODE_ATTEMPTS = 5;

/** One random code from a byte source (injectable for tests). */
export function generateRelayCode(nextByte: () => number): string {
  let code = "";
  for (let i = 0; i < RELAY_CODE_LENGTH; i++) {
    code += RELAY_CODE_ALPHABET[nextByte() % RELAY_CODE_ALPHABET.length];
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
 * draw and the put (R2 rejects `onlyIf: { etagDoesNotMatch: "" }`).
 * Returns the stored code, or null after [MAX_CODE_ATTEMPTS] collisions
 * so the caller can fail the upload instead of overwriting someone
 * else's bundle.
 */
export async function allocateRelayCode(
  store: (code: string) => Promise<void>,
  generate: () => string = () => generateRelayCode(cryptoByte),
): Promise<string | null> {
  for (let attempt = 0; attempt < MAX_CODE_ATTEMPTS; attempt++) {
    const code = generate();
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
  // R9-07 (review 0009 F6): cap the read, don't buffer past the cap — an
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
    // R9-07: the guard is on the put, not a preceding head — a rival POST
    // landing between head and put loses its conditional put and retries
    // with a fresh code, so a bundle is never overwritten.
    async (candidate) => {
      // R9-01: the minting install owns the code; a foreign GET is a 404.
      await bucket.put(
        relayKey(candidate),
        body,
        {
          httpMetadata: { contentType: "application/octet-stream" },
          customMetadata: { expiresAt: String(expiresAt), owner: c.get("deviceId") },
          // Only create if absent: a code that appeared since the draw is a
          // collision, not an overwrite.
          onlyIf: { etagDoesNotMatch: "" },
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
  // R9-01: the owner check comes after expiry (expiry sweeps regardless of
  // who asks) and never deletes — a foreign read must not burn the owner's
  // code, and it must not reveal that the code exists. Legacy objects with
  // no owner stamp predate the model; the app pair that produced the QR
  // both hold the code, so an unowned code stays readable.
  const owner = object.customMetadata?.owner;
  if (owner !== undefined && owner !== c.get("deviceId")) {
    return c.json({ ok: false, error: "not_found" }, 404);
  }
  return c.body(object.body, 200, {
    "Content-Type": "application/octet-stream",
    "Cache-Control": "no-store",
  });
}
