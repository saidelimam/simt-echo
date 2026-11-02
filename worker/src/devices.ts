// Per-install device credentials (review 0009, finding F1 / slice R9-01).
//
// One opaque, high-entropy key per install, minted by `POST /v1/device` on
// first run. The key is the sole bearer for that install: the shared
// `ECHO_APP_TOKEN` is retired as a secret (it stays deployed only as a public
// client identifier during the migration window). Every job, upload, and relay
// object is stamped with the minting device id — the sha256 of the key — and a
// read or delete by any other id is a 404, so a foreign access leaks nothing
// about existence. See docs/research/authorization.md for the decision.

import type { Context } from "hono";
import type { AppEnv, Env } from "./index";

interface DeviceRecord {
  key: string;
  createdAt: number;
}

/** Constant-time string compare (S-reviewed surface). Returns false on a
 * length mismatch without an early byte exit, so a short/long bearer cannot
 * time out of the comparison and reveal a prefix. */
export function timingSafeEqual(a: string, b: string): boolean {
  const ab = new TextEncoder().encode(a);
  const bb = new TextEncoder().encode(b);
  if (ab.length !== bb.length) return false;
  let diff = 0;
  for (let i = 0; i < ab.length; i++) diff |= ab[i] ^ bb[i];
  return diff === 0;
}

/** sha256 hex of a device key: the stable, opaque per-install id. Fixed-length
 * and uniform, so it doubles as the R2 lookup key and the ownership stamp
 * without the key itself appearing in a path. */
export async function deviceIdForKey(key: string): Promise<string> {
  const bytes = new TextEncoder().encode(key);
  const digest = await crypto.subtle.digest(
    "SHA-256",
    bytes as unknown as ArrayBuffer,
  );
  return [...new Uint8Array(digest)]
    .map((b) => b.toString(16).padStart(2, "0"))
    .join("");
}

/** R2 key for a device record, addressed by id (never by the key). */
export function deviceKeyForId(id: string): string {
  return `devices/${id}.json`;
}

/** Mint a fresh 256-bit device key: `dk_` + 43 base64url characters. The
 * prefix marks the credential at a glance; the entropy is the secret. */
export function generateDeviceKey(
  random: Uint8Array = crypto.getRandomValues(new Uint8Array(32)),
): string {
  let b64 = btoa(String.fromCharCode(...random));
  b64 = b64.replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
  return `dk_${b64}`;
}

/** Resolve a bearer to a device id, or null. Looks the record up by the id
 * derived from the bearer, then confirms the stored key in constant time — a
 * lookup miss and a key mismatch are indistinguishable to the caller. */
export async function resolveDevice(
  env: Env,
  bearer: string,
): Promise<string | null> {
  const id = await deviceIdForKey(bearer);
  const obj = await env.MEDIA.get(deviceKeyForId(id));
  if (!obj) return null;
  let rec: DeviceRecord | null = null;
  try {
    rec = (await obj.json()) as DeviceRecord;
  } catch {
    return null;
  }
  if (typeof rec?.key !== "string") return null;
  return timingSafeEqual(rec.key, bearer) ? id : null;
}

/** `POST /v1/device` — mint one key for a new install. Unauthenticated (the
 * chicken-and-egg of first run) but throttled by the pre-auth IP limit, so a
 * single source cannot mint unboundedly. */
export async function postDevice(c: Context<AppEnv>): Promise<Response> {
  const key = generateDeviceKey();
  const id = await deviceIdForKey(key);
  const rec: DeviceRecord = { key, createdAt: Date.now() };
  // Uint8Array, not a string: every fake bucket in the suite accepts it,
  // while `new Uint8Array("…")` (the relay fake) would store zero bytes.
  await c.env.MEDIA.put(
    deviceKeyForId(id),
    new TextEncoder().encode(JSON.stringify(rec)),
  );
  return c.json({ ok: true, device: key });
}
