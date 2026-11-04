// Per-install device credentials (review 0010, finding F1 / slice R10-01).
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
  /** R12-02 (review 0012, F2): `sha256("verify:" + key)` — never the bearer
   * itself, so reading the bucket yields no usable credential. */
  verifier: string;
  createdAt: number;
  /** Last authenticated use, so the lifecycle rule measures idleness rather
   * than the age of a record an install still depends on (R12-09). */
  lastSeenAt?: number;
}

/** How stale a record may get before a request rewrites it. Well under the
 * 365-day `expire-devices` rule, so an install that keeps working keeps its
 * record; R2 lifecycle expiration counts from object creation, not access. */
const DEVICE_TOUCH_MS = 30 * 24 * 60 * 60 * 1000;

/** Rewrites the record with a fresh timestamp, at most once a month. Best
 * effort: a failed touch must never deny a request the verifier accepted. */
async function touchDeviceRecord(env: Env, id: string, record: DeviceRecord): Promise<void> {
  const now = Date.now();
  const last = record.lastSeenAt ?? record.createdAt;
  if (now - last < DEVICE_TOUCH_MS) return;
  try {
    await env.MEDIA.put(
      deviceKeyForId(id),
      new TextEncoder().encode(JSON.stringify({ ...record, lastSeenAt: now })),
    );
  } catch {
    // The next request retries; the record simply ages until then.
  }
}

async function hexSha256(text: string): Promise<string> {
  const digest = await crypto.subtle.digest(
    "SHA-256",
    new TextEncoder().encode(text) as unknown as ArrayBuffer,
  );
  return [...new Uint8Array(digest)]
    .map((b) => b.toString(16).padStart(2, "0"))
    .join("");
}

/** Domain-separated digest of a device key. The prefix keeps the verifier
 * from ever colliding with the device id (`sha256(key)`). */
export async function deviceVerifier(key: string): Promise<string> {
  return hexSha256(`verify:${key}`);
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
 * derived from the bearer, then compares the stored verifier in constant time
 * — a lookup miss and a verifier mismatch are indistinguishable to the caller.
 * A pre-R12-02 record that still holds the plaintext key is upgraded in place
 * on its first use, so no install is locked out and the plaintext stops
 * living in R2. */
export async function resolveDevice(
  env: Env,
  bearer: string,
): Promise<string | null> {
  const id = await deviceIdForKey(bearer);
  const obj = await env.MEDIA.get(deviceKeyForId(id));
  if (!obj) return null;
  let rec: unknown = null;
  try {
    rec = await obj.json();
  } catch {
    return null;
  }
  const verifier = await deviceVerifier(bearer);
  const record = rec as Partial<DeviceRecord> | null;
  const stored = record?.verifier;
  if (typeof stored === "string") {
    if (!timingSafeEqual(stored, verifier)) return null;
    await touchDeviceRecord(env, id, record as DeviceRecord);
    return id;
  }
  const legacy = rec as { key?: unknown; createdAt?: unknown } | null;
  if (typeof legacy?.key !== "string" || !timingSafeEqual(legacy.key, bearer)) {
    return null;
  }
  const createdAt = typeof legacy.createdAt === "number" ? legacy.createdAt : Date.now();
  try {
    await env.MEDIA.put(
      deviceKeyForId(id),
      new TextEncoder().encode(JSON.stringify({ verifier, createdAt })),
    );
  } catch {
    // A failed upgrade must not deny the install: the next request retries.
  }
  return id;
}

/** `POST /v1/device` — mint one key for a new install. Unauthenticated (the
 * chicken-and-egg of first run) but throttled by the pre-auth IP limit, so a
 * single source cannot mint unboundedly. */
export async function postDevice(c: Context<AppEnv>): Promise<Response> {
  const key = generateDeviceKey();
  const id = await deviceIdForKey(key);
  const rec: DeviceRecord = { verifier: await deviceVerifier(key), createdAt: Date.now() };
  // Uint8Array, not a string: every fake bucket in the suite accepts it,
  // while `new Uint8Array("…")` (the relay fake) would store zero bytes.
  await c.env.MEDIA.put(
    deviceKeyForId(id),
    new TextEncoder().encode(JSON.stringify(rec)),
  );
  const res = c.json({ ok: true, device: key });
  // R12-11: the body carries a credential — never cache it.
  res.headers.set("Cache-Control", "no-store");
  return res;
}

/** `DELETE /v1/device` — erasure of this install's record (review 0012,
 * R12-09 / GDPR Art. 17). Authenticated, so only the holder of the key can
 * remove the record it identifies; the media a device created is deleted by
 * the client through the job and upload routes first, because those deletes
 * carry the same ownership check. Deleting an absent record is not an error,
 * so the route answers 204; a repeated call with the forgotten key is a 401,
 * which the client treats as already erased. */
export async function deleteDevice(c: Context<AppEnv>): Promise<Response> {
  const id = c.get("deviceId");
  try {
    await c.env.MEDIA.delete(deviceKeyForId(id));
  } catch {
    return c.json({ ok: false, error: "device_delete_failed" }, 500);
  }
  return new Response(null, { status: 204 });
}
