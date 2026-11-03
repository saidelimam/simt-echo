# Authorization: per-install device keys

**Status:** decided (review 0010, finding F1; slice R10-01)

## Problem

One static `ECHO_APP_TOKEN` was the entire authorization model. It is compiled
into every shipped APK (`BuildConfig` string, minification off), so it is
extractable with `strings`. Possession authorized everything: creating jobs
(spending Workers AI and paid Meta SAM frames), reading any job, deleting any
job, writing any upload session, and reading any relay code. There is no
per-user state anywhere in the system — the app is signed in anonymously, and
the privacy promise ("your route never leaves the device except as sealed
ciphertext") was not enforced by the backend at all.

## Decision

Each **install** mints exactly one opaque, high-entropy **device key** on
first run. The key is the sole bearer for that install; the shared
`ECHO_APP_TOKEN` is retired as a secret (it stays deployed only as a public
client identifier during the migration window). Every job, upload session, and
relay object is **stamped with the minting device id**, and a read or delete by
any other id is a `404` — a foreign access leaks nothing about existence.

## Design

### Credential

- `POST /v1/device` mints one 256-bit key per call: `dk_` + 43 base64url
  characters. The prefix marks the credential at a glance; the entropy is the
  secret. The client persists it (e.g. in `EncryptedSharedPreferences`) and
  sends it as `Authorization: Bearer dk_…` on every request.
- The route is unauthenticated (the chicken-and-egg of first run) but sits
  behind the pre-auth IP limit, so a single source cannot mint unboundedly.
  One key per install is a client invariant: re-minting yields a *new*
  install, whose old objects become foreign.

### Identity

- Device id = `sha256(key)` hex. Fixed-length and uniform, so it doubles as
  the R2 lookup key and the ownership stamp without the key itself appearing
  in any path.
- The record `devices/<id>.json` stores `{ key, createdAt }`. Resolution
  looks the record up by the id derived from the bearer, then confirms the
  stored key in constant time (`timingSafeEqual`) — a lookup miss and a key
  mismatch are indistinguishable to the caller.
- The compare is constant-time over the full length; a length mismatch returns
  false without an early byte exit, so a short bearer cannot time out of the
  comparison and reveal a prefix.

### Auth gate (order matters)

1. **Pre-auth IP limit** (`pre:<ip>`), in front of any credential check —
   unauthenticated traffic and device minting are throttled too, not just
   holders of the old shared token.
2. `/v1/health` and `/v1/device` pass through here.
3. Bearer → device id resolution; a miss is `401 unauthorized`.
4. **Per-install limit** (`dev:<id>:<ip>`), behind auth — one install's burst
   cannot starve the others, and the old `token:ip` keying is replaced by the
   device id.

### Ownership

| Object | Stamp | Read by foreign id | Delete by foreign id |
|---|---|---|---|
| Job (`jobs/<id>/`) | `jobs/<id>/owner.json` `{owner}` | `404 job_not_found` (GET, events) | `404 job_not_found` |
| Upload session (`jobs/staging/<id>/`) | `meta.json` gains `owner` | `404 upload_not_found` (GET, PUT) | `404 upload_not_found` |
| Relay code (`relay/<code>`) | R2 `customMetadata.owner` | `404 not_found`, **no delete** | n/a (no relay DELETE) |

Semantics worth noting:

- **No existence leak.** Every foreign read is the same `404` as a missing
  object; there is no `403` anywhere in the model.
- **Legacy / unknown owner.** A job with no `owner.json` (pre-R10-01, or swept)
  keeps the idempotent `DELETE → 204`; a relay object with no `owner` metadata
  stays readable (the QR pair both hold the code). Unknown ≠ foreign.
- **Replay path is owner-checked.** An idempotent job create returns the
  creating install's job id — to every other install the key is simply unknown
  (`404 job_not_found`), so the id cannot be probed across installs.
- **`resumeOf` requires manifest + owner match.** A foreign job's SAM cache is
  not adoptable: resuming it would spend the resumer's budget on a stranger's
  work.
- **Relay expiry precedes ownership.** Expiry sweeps the object regardless of
  who asks; the owner check then runs on what is left and never deletes — a
  foreign read must not burn the owner's code.
- **Upload meta validation** requires `owner` to be a string; a meta without
  one is treated as corrupt/missing, not as public.

## Alternatives considered

- **Keep the shared token, add per-object capability tokens.** Capability URLs
  solve read-after-write for a *known* reader, but the reader here is the same
  install that wrote — a self-binding stamp is smaller, needs no token
  issuance flow, and also covers delete.
- **HMAC over a device id with a server secret.** The server would hold a
  secret that authorizes everyone; a leak re-creates F1 with more ceremony.
- **Full accounts (OAuth / Apple Sign-In).** The right long-term answer, and
  nothing here blocks it — the device id is an opaque string and the stamp
  shape is unchanged if the id later becomes an account-scoped identifier.
  For this product's anonymous, offline-first flow it is scope the hackathon
  backend does not need.

## Migration

- `ECHO_APP_TOKEN` remains in `Env` (deployed) but is no longer compared;
  auth resolves only minted device keys.
- Existing installs must call `POST /v1/device` once and persist the key;
  until they do, they authenticate as nobody (`401`). Objects they created
  before the change have no owner stamp and stay readable/deletable in the
  legacy sense (see "Unknown owner" above).
- The app-side change is a new `POST /v1/device` on first launch plus
  switching the `Authorization` header from the `BuildConfig` token to the
  persisted key; the Android slice lands with this one.

## What this does not fix (separate slices)

- Retention (R10-02): stamps bind ownership, not lifetime.
- Fan-out and spend bounds (R10-03…R10-05): the per-install limit throttles
  request rate, not manifest size or AI spend.
