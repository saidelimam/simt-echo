// Shared test helpers for the per-install device credential (R10-01).
//
// Every fake bucket in the suite accepts a `put` of the shape below; seeding
// writes the device record as a Uint8Array so it round-trips through all of
// them (the relay fake wraps its value in `new Uint8Array(value)`, which
// rejects a plain string).

import { deviceKeyForId, deviceIdForKey, deviceVerifier, generateDeviceKey } from "./devices";

/** Minimal bucket surface needed to register a device record. */
export interface DeviceBucket {
  put(
    key: string,
    value: Uint8Array | ArrayBuffer | string,
    opts?: Record<string, unknown>,
  ): Promise<unknown>;
}

/** Registers a fixed test key as a minted install. Deterministic — the tests
 * assert identity across requests, not entropy. R12-02: the record carries
 * the verifier digest, exactly as `POST /v1/device` writes it. Returns the
 * device id. */
export async function seedDevice(bucket: DeviceBucket, key: string): Promise<string> {
  const id = await deviceIdForKey(key);
  await bucket.put(
    deviceKeyForId(id),
    new TextEncoder().encode(
      JSON.stringify({ verifier: await deviceVerifier(key), createdAt: 0 }),
    ),
  );
  return id;
}

/** Mints a fresh key the way `POST /v1/device` does and stores it. */
export async function mintDevice(bucket: DeviceBucket): Promise<string> {
  const key = generateDeviceKey();
  await seedDevice(bucket, key);
  return key;
}

/** Authorization header for a seeded key. */
export const bearer = (key: string): { Authorization: string } => ({
  Authorization: `Bearer ${key}`,
});
