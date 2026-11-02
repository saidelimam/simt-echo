import type { Context } from "hono";
import type { AppEnv } from "./index";

/**
 * Zero-knowledge relay (plan W4-05, §5.5).
 *
 * The relay stores one sealed bundle (`ECX1` ciphertext) per code for
 * 30 minutes. It never receives the bundle key — the key travels in the
 * QR (`k=`) and the relay code in `c=` — so the server can only ever
 * inspect ciphertext. Codes are 6 characters of Crockford-style base32
 * without ambiguous glyphs (no 0/O, 1/I/L).
 */

export const RELAY_MAX_BYTES = 5 * 1024 * 1024;
export const RELAY_TTL_MS = 30 * 60 * 1000;
export const RELAY_CODE_LENGTH = 6;
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
 * Allocates a free code, retrying on collision. Returns null after
 * [MAX_CODE_ATTEMPTS] collisions so the caller can fail the upload
 * instead of overwriting someone else's bundle.
 */
export async function allocateRelayCode(
  exists: (code: string) => Promise<boolean>,
  generate: () => string = () => generateRelayCode(cryptoByte),
): Promise<string | null> {
  for (let attempt = 0; attempt < MAX_CODE_ATTEMPTS; attempt++) {
    const code = generate();
    if (!(await exists(code))) return code;
  }
  return null;
}

function relayKey(code: string): string {
  return `relay/${code}`;
}

export async function postRelay(c: Context<AppEnv>) {
  const body = await c.req.arrayBuffer();
  if (body.byteLength === 0) {
    return c.json({ ok: false, error: "empty_body" }, 400);
  }
  if (body.byteLength > RELAY_MAX_BYTES) {
    return c.json({ ok: false, error: "too_large" }, 413);
  }
  const bucket = c.env.MEDIA;
  const code = await allocateRelayCode(
    async (candidate) => (await bucket.head(relayKey(candidate))) !== null,
  );
  if (code === null) {
    return c.json({ ok: false, error: "code_unavailable" }, 503);
  }
  const expiresAt = Date.now() + RELAY_TTL_MS;
  // R9-01: the minting install owns the code; a foreign GET is a 404.
  await bucket.put(relayKey(code), body, {
    httpMetadata: { contentType: "application/octet-stream" },
    customMetadata: { expiresAt: String(expiresAt), owner: c.get("deviceId") },
  });
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
