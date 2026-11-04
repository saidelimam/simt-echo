# Review 0012 — Worker / Cloudflare full security & legal review

Status: **complete** · created 2026-11-04 · Verdict: **BLOCK**

Scope: all of `worker/` at repo HEAD `da99757` (`src/index.ts`,
`devices.ts`, `jobs.ts`, `uploads.ts`, `relay.ts`, `spend.ts`,
`pipeline.ts`, `scout.ts`, `ground.ts`, `groundCommon.ts`, `samLens.ts`,
`selection.ts`, `samParse.ts`, `meta.ts`, `types.ts`, `wrangler.jsonc`,
`package.json`, `scripts/*.mjs`), the legal authority documents
(`docs/legal/privacy-policy.md`, `docs/legal/terms-of-use.md`,
`docs/research/authorization.md`, `docs/operations/spend.md`), and the
client–worker boundary in `shared/` and `androidApp/` (`EchoApplication.kt`,
`AppGraph.kt`, `EchoApiClient.kt`, `RelayClient.kt`, `ReadyUpload.kt`,
`UploadPreparer.kt`, `ManifestBuilder.kt`, `FacePrivacy.kt`,
`ProcessingViewModel.kt`, `RecordViewModel.kt`, `SettingsScreen.kt`).

Method: full manual audit of every file in scope against the R10-01…R10-14
implementations (review 0010), GDPR Arts. 5/6/9/13/17/25/28/44–46, mobile app
store privacy requirements, and the platform's own legal/consent promises;
plus execution of the worker gates (`npm run typecheck`, `npm test`,
`npm audit`). No live AI/SAM spend was performed.

Severity levels: **Critical** — exploitable cross-install leak, broken
authentication contract, or direct exposure of bearer secrets; **High** —
broken security/retention control, unkept privacy/legal promise, or unbounded
spend/storage amplification; **Medium** — bounded weakness or defense-in-depth
gap; **Low** — hygiene. Critical and High findings BLOCK sign-off.

## Tracker

| id | state | slice | tier | lane | depends on | evidence |
|---|---|---|---|---|---|---|
| R12-01 | Landed | F1 upload-session IDOR in `handleCreateJobV11`: enforce `loaded.meta.owner === c.get("deviceId")` on every `uploadId`/`uploadIds` before head/copy/consume + cross-install rejection tests | Critical | uploads | — | commit f46bc24 |
| R12-02 | Landed | F2 client–worker device-key contract & hash-only R2 storage: store verifier hash (never plaintext `dk_`) in `devices/<id>.json`; wire first-run `POST /v1/device` + persisted key in `shared`/`androidApp` + tests | Critical | auth | — |  |
| R12-03 | Landed | F3 relay cross-install deadlock, broken `etagDoesNotMatch: ""` guard, and modulo bias: capability-based relay read across installs, `etagDoesNotMatch: "*"` on put, rejection-sampled 10-char code, two-install test | High | relay | R12-02 |  |
| R12-04 | Landed | F4 relay cron no-op (`include: ["customMetadata"]` missing) and Workflow state outliving 24h TTL: pass `include: ["customMetadata"]`, delete Workflow instance + `idemKey` in `sweepExpiredJobs`, sweep terminated/stale jobs | High | storage | — |  |
| R12-05 | Landed | F5 cross-install `idempotencyKey` collision & mid-create job hijack: scope `jobIdForKey(deviceId, idempotencyKey)` by `deviceId` + two-install same-key concurrency test | High | uploads | R12-01 |  |
| R12-06 | Landed | F6 daily spend bypass on Scout AI & uncapped staging storage: enforce per-job scout reservation floor, gate `/v1/uploads` on `spendDisabled` + per-device staging quota, fail closed on corrupt spend ledger | High | spend | — |  |
| R12-07 | Landed | F7 unfixed R10-06 — Scout sends `faceFree: false` / bad-quality keyframes to AI vendors & clips lack `faceFree` contract: enforce `frameFailsFlags` in `scout.ts` and `clip.faceFree` in `types.ts`/`samLens.ts` | High | scout | — |  |
| R12-08 | Landed | F8 legal/consent inaccuracies (iOS unscanned uploads, false "blurred" claim, omitted Google Gemma 4, walk-time staging vs. §3.2): align `privacy-policy.md`, `terms-of-use.md`, `consentCopy`, and `ReadyOptInUiState` (or gate iOS upload until Vision scanner lands) | High | legal | R12-07 |  |
| R12-09 | Landed | F9 GDPR Art. 13/17 & app-store compliance gaps: add `DELETE /v1/device` + `expire-devices` R2 lifecycle rule, cascade-delete active cloud jobs/uploads on Settings "Delete all", link Privacy Policy & Terms in-app, add controller/transfer/rights clauses | High | legal | R12-02, R12-08 | merge dd9690b |
| R12-10 | Landed | F10 unbounded body buffering (`formData()` / `req.json()`) & unvalidated media MIME/magic bytes: stream-cap v1 multipart and JSON bodies, verify JPEG (`FF D8 FF`) and MP4 (`ftyp`) headers + strict `Content-Type` | Medium | api | — | merge 772cace |
| R12-11 | Landed | F11 `X-Forwarded-For` spoofing fallback, unthrottled `/v1/device` mint churn, and missing HTTP security headers: drop `X-Forwarded-For` trust, tighten `/v1/device` rate limit, set `X-Content-Type-Options: nosniff` and `Cache-Control: no-store` on SSE/device | Medium | security | — |  |
| R12-12 | Landed | F12 prompt-injection surface in `routeContext`/`samPhrase`, unbounded AI candidate strings, and AI Gateway log/cache retention: constrain `routeContext` & candidate string lengths, sanitize `samPhrase`, pass AI Gateway `skipCache`/`collectLog: false` | Medium | ai | — | commit f46bc24 |

---

## 1. Security vulnerabilities

### F1 — Horizontal privilege escalation (IDOR) on staged uploads in `POST /v1/jobs` (Critical → `R12-01`)

`GET /v1/uploads/:uploadId` (`worker/src/uploads.ts:589`), `PUT /v1/uploads/:uploadId/:name` (`uploads.ts:612`), and `DELETE /v1/uploads/:uploadId` (`uploads.ts:659`) all verify `loaded.meta.owner !== c.get("deviceId")`. However, **`handleCreateJobV11` (`uploads.ts:409-456`) never checks `loaded.meta.owner`** when resolving `uploadId` or `uploadIds`:

1. Lines 410–418 load `loaded.meta` and push `{ id: sessionId, meta: loaded.meta }` into `loadedBySession`, then never read `.meta.owner`.
2. Lines 473–477 copy the victim's staged indoor photos and clips (`jobs/staging/<uploadId>/<name>`) into the caller's job (`jobs/<jobId>/in/<name>`), stamping `owner.json` with the caller's `deviceId` (line 480).
3. Lines 515–517 (`deletePrefix`) delete the victim's staging session.
4. The caller can then poll `GET /v1/jobs/:id` to exfiltrate AI-extracted landmark titles, descriptions, verbatim indoor `signText`, and SVG masks derived from the victim's photos.

**Fix (`R12-01`):** Check `if (loaded.meta.owner !== c.get("deviceId")) return c.json({ ok: false, error: "upload_not_found" }, 404);` inside the `for (const sessionId of sessionIds)` loop in `uploads.ts:410-418` before any `stagedNames` list, `head`, copy, or delete. Add vitest cases in `uploads.test.ts` proving a foreign install cannot create a job from another install's `uploadId` or `uploadIds` and leaves the owner's staged files intact.

### F2 — Broken client–worker auth contract & plaintext bearer keys stored in R2 (Critical → `R12-02`)

1. **Client–server auth mismatch:** Slice `R10-01` retired `ECHO_APP_TOKEN` on the worker (`worker/src/index.ts:25-28, 137-143`; `worker/src/auth.test.ts:135`: *"ignores ECHO_APP_TOKEN entirely"*). `docs/research/authorization.md:112-114` required a matching Android/KMP slice to call `POST /v1/device` on first launch and send `Authorization: Bearer dk_…`. That client slice never landed (`grep /v1/device shared/` returns 0 matches): `EchoApplication.kt:20` still passes `BuildConfig.ECHO_APP_TOKEN` to `AppGraph.kt:55,162` → `EchoApiClient.kt:173`. Every authenticated request from the shipped app to the worker fails with `401 unauthorized`.
2. **Plaintext bearer secret in R2:** `postDevice` (`worker/src/devices.ts:86-92`) stores `{ key: "dk_...", createdAt }` in `devices/<sha256(key)>.json`. Because the object key is already `sha256(key)`, storing the raw bearer token inside the JSON body is unnecessary and turns any R2 bucket read exposure or backup leak into a full compromise of every install's bearer credential.

**Fix (`R12-02`):** In `devices.ts`, store only `{ verifier: sha256("verify:" + key), createdAt }` (or `{ createdAt }` after a domain-separated key derivation) so `devices/<id>.json` never contains the bearer string. In `shared/` + `androidApp/`, add a `DeviceCredentialStore` that mints once via `POST /v1/device`, persists the `dk_` key in app-private storage, and supplies it to `EchoApiClient` and `KtorRelayClient`.

### F3 — Zero-knowledge relay broken across installs, ineffective `etagDoesNotMatch: ""` guard, and modulo bias (High → `R12-03`)

1. **Cross-install 404 deadlock:** `postRelay` (`worker/src/relay.ts:92`) stamps `customMetadata.owner = c.get("deviceId")`, and `getRelay` (`relay.ts:127-130`) returns `404` whenever `owner !== c.get("deviceId")`. Because sender and receiver are two different phones (two different installs), `getRelay` **always refuses the receiver**. Consequently, `TransferService.kt:64` had to set `peerRelayQualified = false`, disabling the relay feature promised in `docs/legal/privacy-policy.md` §4.3 and `docs/legal/terms-of-use.md` §5.2.
2. **Broken R2 conditional put in production:** `postRelay` (`relay.ts:95`) passes `onlyIf: { etagDoesNotMatch: "" }`. In Cloudflare R2 (`R2Conditional` / HTTP `If-None-Match`), create-if-absent requires `etagDoesNotMatch: "*"`. Comparing against `""` succeeds whenever an existing object has a normal non-empty ETag, so code collisions overwrite existing bundles in production (masked in `relay.test.ts:38` because `FakeBucket` only checked `!== undefined`).
3. **Modulo bias in `generateRelayCode`:** `relay.ts:30` computes `nextByte() % 31` (`RELAY_CODE_ALPHABET.length === 31`). Since `256 = 8 × 31 + 8`, indices `0..7` (`A–H`) are selected with probability `9/256` while `8..30` have `8/256`.

**Fix (`R12-03`):** Change `onlyIf` to `{ etagDoesNotMatch: "*" }`; use rejection sampling (`while (b >= 248)`) in `generateRelayCode` and widen `RELAY_CODE_LENGTH` to `10` (≈49.5 bits); allow any authenticated install to `GET /v1/relay/:code` (since the 10-char code in the QR/NFC invitation is the capability and the payload is AES-256-GCM ciphertext whose key `k` never touches the server); enable `peerRelayQualified = true` with a two-install integration test.

### F4 — Production relay cron never deletes expired objects (`include: ["customMetadata"]` missing) & Workflow state survives 24h TTL (High → `R12-04`)

1. **Relay cron is a no-op in production R2:** `sweepExpiredRelayObjects` (`worker/src/uploads.ts:302-306`) calls `env.MEDIA.list({ prefix: "relay/", cursor })` and reads `o.customMetadata?.expiresAt`. In the Cloudflare Workers R2 runtime, `R2Bucket.list()` **omits `customMetadata` unless `include: ["customMetadata"]` is passed in `R2ListOptions`**. In production, `o.customMetadata` is always `undefined`, `expiresAt` is `NaN`, and `sweepExpiredRelayObjects` deletes 0 objects — leaving uncollected relay ciphertexts past the 30-minute TTL promised in `docs/legal/privacy-policy.md` §4.3.
2. **Cloudflare Workflow instance state survives the 24h job sweep:** `DELETE /v1/jobs/:id` (`worker/src/jobs.ts:403-412`) deletes `idemKey(id)` and calls `await handle.delete()` on the `PIPELINE` Workflow instance. By contrast, `sweepExpiredJobs` (`uploads.ts:259-290`) only deletes R2 keys under `jobs/<id>/` — it **never deletes `jobs/idem/<id>.json` and never deletes the `PIPELINE` Workflow instance**. Cloudflare Workflows persists every step's return value (`Manifest`, `ScoutResult`, landmark titles, descriptions, verbatim indoor `signText`, `maskSvgPath`, and `JobResult` in `pipeline.ts:41-56`). Leaving the Workflow instance alive past 24 hours retains route metadata and extracted indoor sign text in Cloudflare storage after the 24h R2 sweep.
3. **Stuck/terminated jobs skipped by cron:** `sweepExpiredJobs` (`uploads.ts:285`) skips any job whose `progress.json` is missing or whose `stage` is not `"complete"` or `"errored"`. If a Workflow instance is `terminated` or times out without running `putError`, its `progress.json` stays `"scouting"`/`"grounding"` and `sweepExpiredJobs` ignores it.

**Fix (`R12-04`):** Pass `include: ["customMetadata"]` to `env.MEDIA.list` in `sweepExpiredRelayObjects` (and make `FakeR2.list` enforce `include`); in `sweepExpiredJobs`, also delete `idemKey(id)`, call `env.PIPELINE?.get(id)` to `terminate()`/`delete()` the Workflow instance, and sweep job prefixes whose oldest R2 object `uploaded` timestamp exceeds `JOB_RETENTION_MS` even if `progress.json` is missing or non-terminal.

### F5 — Cross-install `idempotencyKey` collision & mid-create job hijack (High → `R12-05`)

`jobIdForKey(idempotencyKey)` (`worker/src/uploads.ts:100-104`) derives the UUID `jobId` from `sha256(idempotencyKey)` **without including `deviceId`**. If two installs submit the same `idempotencyKey` (either intentionally or via a colliding manifest hash):

- After Install A completes job creation, Install B's `POST /v1/jobs` hits `uploads.ts:373-375` and receives `404 job_not_found`, leaking that another install used that key and breaking Install B's job creation.
- Worse, while Install A's create is in flight (between `writeJobOwner` at line 480 and `MEDIA.put(idemKey(jobId))` at line 509), `idemKey(jobId)` does not exist yet: Install B bypasses lines 364–383 and overwrites `jobs/<jobId>/in/*`, `manifest.json`, and `owner.json` (`writeJobOwner`, line 480), hijacking Install A's job!

**Fix (`R12-05`):** Change `jobIdForKey(deviceId: string, idempotencyKey: string)` to hash `${deviceId}\0${idempotencyKey}`. Add a vitest proving two installs sending identical `idempotencyKey` values (sequentially and concurrently) receive distinct `jobId`s and never interfere.

### F6 — Daily spend cap bypass on Scout AI & uncapped staging storage (High → `R12-06`)

1. **`budget: { maxSamFrames: 1 }` bypasses the daily cap for Scout AI:** `handleCreateJobV11` (`worker/src/uploads.ts:462`) reserves `budget?.maxSamFrames ?? MAX_SAM_FRAMES` against `DAILY_MAX_SAM_FRAMES` (default `1200`). A caller passing `budget: { maxSamFrames: 1 }` with a 40-checkpoint manifest (`MAX_CHECKPOINTS = 40`) reserves only `1` frame per job, allowing **1,200 jobs per UTC day**. Every one of those 1,200 jobs executes `runScoutPhase` (`scout.ts`) across all 40 checkpoints — up to **48,000 checkpoint scout runs/day** (up to 192,000 Workers AI Vision calls + 3,600 paid Meta Muse Spark calls with 48 images each) before the SAM governor is ever reached.
2. **Staging routes ignore `SPEND_KILL_SWITCH` and have no storage quota:** `POST /v1/uploads` and `PUT /v1/uploads/:uploadId/:name` (`uploads.ts:539-645`) do not check `spendDisabled(c.env)` and impose no per-device staging cap. Even when `SPEND_KILL_SWITCH=on`, any device key can upload 80 MB per session × 30 req/min into R2 (`2.4 GB/min` per IP).
3. **Corrupt spend ledger fails open:** In `worker/src/spend.ts:76-82`, if `await obj.json()` throws or returns an unexpected shape on an existing `spend/daily/<YYYY-MM-DD>.json`, `reserved` is reset to `0` and overwritten, wiping the day's accumulated spend reservation.

**Fix (`R12-06`):** Check `spendDisabled(c.env)` on `POST /v1/uploads` and `PUT /v1/uploads/:uploadId/:name`; cap active staged sessions per `deviceId` (e.g., ≤12 active sessions); reserve `Math.max(budget?.maxSamFrames ?? MAX_SAM_FRAMES, manifest.checkpoints.length * MIN_FRAMES_PER_CHECKPOINT)` in `reserveDailySpend`; and fail closed (`reason: "contention"`) when an existing ledger object cannot be parsed.

### F7 — Unfixed R10-06: Scout sends `faceFree: false` and bad-quality keyframes to AI vendors, and clips lack a `faceFree` contract (High → `R12-07`)

Slice `R10-06` from Review 0010 (`docs/reviews/0010-worker-backend-review.md:40`) remains `Queued`:

1. While `samLens.ts:386-393` filters candidates via `frameFailsFlags` (`ref.faceFree === false || ref.quality === "bad" || ref.exposureOk === false`), **`worker/src/scout.ts` (`metaSparkScout` at lines 436–443 and `runScout` at lines 790–798) ignores all three flags** and sends the first 3 keyframes of every checkpoint to Cloudflare Workers AI (Llama 4 Scout, Gemma 4) and Meta Muse Spark.
2. `ClipRefSchema` (`worker/src/types.ts:43-49`) has no `faceFree` property, and `samLens.ts:570-578` sends `cp.clip` to Meta SAM 3.1 video segmentation without checking whether the clip was forged from face-cleared frames or an unscanned video fallback.

**Fix (`R12-07`):** Filter `cp.keyframes` through `!frameFailsFlags(cp, ref.id)` in both `runScout` and `metaSparkScout` before reading R2 bytes (if a checkpoint has 0 eligible keyframes, emit empty candidates for that checkpoint without calling the model); add `faceFree: z.boolean().optional()` to `ClipRefSchema` and skip SAM video escalation in `samLens.ts` when `cp.clip.faceFree === false`.

---

## 2. Legal, privacy policy & regulatory compliance findings

### F8 — Material inaccuracies between `docs/legal/privacy-policy.md`, in-app consent copy, and actual code (High → `R12-08`)

1. **Unconditional face-filter & "blurred" claim vs. unscanned iOS and video paths (`docs/legal/privacy-policy.md:43-48`):**
   - `privacy-policy.md` §3.1 claims: *"Before any image or video clip is prepared for cloud transmission, SIMT ECHO executes an on-device privacy pre-filter utilizing bundled ML Kit face detection algorithms... Frames containing detected faces... are automatically excluded from generated video clips or blurred prior to packaging. The resulting manifest carries explicit boolean flags (`faceFree: true`) confirming that privacy verification passed on the local device."*
   - In reality:
     - **iOS has no face scanner** (`AppGraph.kt:67-72`, `FacePrivacy.kt:22-31`): `faceDetector` is `null`, so `UploadPreparer.kt:88-90` leaves `frameFlags` empty and `ManifestBuilder.kt:69` defaults every keyframe to **`faceFree: true`** without scanning a single pixel, and `UploadPreparer.kt:177-191` uploads unscanned 3-second video transcodes!
     - **No blurring exists anywhere in the codebase** (`FacePrivacy.kt:11-16`: *"Decision record: exclude, not blur"*).
2. **Undisclosed Google Gemma 4 AI model (`privacy-policy.md:52,63`, `terms-of-use.md:48`, `ProcessingViewModel.kt:98`):**
   - Both the in-app consent copy (`ProcessingViewModel.kt:98`: `"Meta's Llama 4 Scout on Workers AI; Meta Model API if enabled"`) and the legal docs (`privacy-policy.md:63`, `terms-of-use.md:48`) name only Meta models (Llama 4 Scout, Muse Spark 1.3, SAM 3.1).
   - However, `worker/wrangler.jsonc:57` and `worker/src/scout.ts:194` configure **`@cf/google/gemma-4-26b-a4b-it` (Google Gemma 4)** as the automatic fallback model receiving user indoor photos whenever Llama 4 Scout times out or fails schema validation.
3. **Walk-time staging ("Find landmarks while I walk") contradicts Privacy Policy §3.2 (`privacy-policy.md:50-54`, `RecordViewModel.kt:49-54`):**
   - `privacy-policy.md` §3.2 promises that *before any data leaves the mobile device*, the Consent Sheet details *"The exact number of keyframe photos and silent, 5-second 480p approach clips to be processed."*
   - When the user enables *"Find landmarks while I walk"* on `RecordScreen` (`RecordViewModel.kt:49-54`, `ReadyUpload.kt`), `WalkStager` uploads keyframes and clips to Cloudflare R2 (`POST /v1/uploads` + `PUT`) **during the walk**, before the walk's photo/clip counts exist, under a short sheet (`ReadyConsentSheet`) that does not state the 1h staging / 24h job retention window, the AI providers/models, or the training-tier exclusion.

**Fix (`R12-08`):** Update `docs/legal/privacy-policy.md`, `docs/legal/terms-of-use.md`, `ProcessingViewModel.consentCopy`, and `RecordViewModel.ReadyOptInUiState.consentBody` so every statement matches code: remove `"or blurred"`, state that ML Kit face filtering applies on Android (and either block cloud upload when `faceDetector == null` or explicitly disclose unscanned upload on platforms without a scanner and set `faceFree` to `null`/omitted rather than `true`), list Google Gemma 4 alongside Llama 4 Scout / Muse Spark 1.3 / SAM 3.1, and document both the pre-walk staging consent (1h TTL) and post-walk job creation consent (24h TTL).

### F9 — GDPR Arts. 13/17 & app-store compliance gaps: no in-app legal links, incomplete "Delete all", and missing controller/transfer disclosures (High → `R12-09`)

1. **Privacy Policy and Terms of Use are unreachable inside the app:** `docs/legal/privacy-policy.md` and `docs/legal/terms-of-use.md` are linked only in `README.md:14-15`. Neither `SettingsScreen.kt` (`Privacy & data` / `About`) nor `ProcessingScreen.kt` / `RecordScreen.kt` links to or displays them. Both Google Play and Apple App Store require in-app access to the Privacy Policy, and GDPR Art. 13 requires providing privacy notice access at the point of data collection (the Consent Sheet).
2. **"Delete all" in Settings leaves cloud data and device records on the server (`SettingsViewModel.kt`, `SettingsScreen.kt:66-78`, `worker/src/devices.ts`):**
   - `SettingsViewModel.deleteAll()` deletes local files (`routes/`, `drafts/`, `trials/`, `exports/`), but **does not call `api.delete(jobId)` or `api.deleteUpload(uploadId)` for active cloud jobs or walk-staged uploads stored in local draft records before erasing `drafts/`**. Once local draft records are wiped, the user loses the `jobId`/`uploadId` handle needed to trigger immediate server deletion (`privacy-policy.md` §3.3), leaving their indoor photos on Cloudflare R2 for up to 24 hours.
   - Furthermore, `devices/<id>.json` in R2 has **no `DELETE /v1/device` endpoint and no lifecycle rule in `wrangler.jsonc:26-30`**, so per-install device records are retained indefinitely in R2.
3. **Missing mandatory GDPR Art. 13 & entity consistency in legal docs:**
   - **Entity mismatch:** `SettingsScreen.kt:277-284` identifies the maker as *"Simt Space, a company of Quazar Media"* (Founder: Said Elimam), whereas `docs/legal/terms-of-use.md:10,27,89,96` names only *"Said Elimam"* individually and `docs/legal/privacy-policy.md:116` provides no controller name, postal/email contact, or DPO/privacy contact at all.
   - **Missing GDPR disclosures in `privacy-policy.md`:** Lawful basis per processing activity (Art. 6(1)(a) explicit consent for cloud AI; local execution for on-device PDR), subprocessor list + locations (Cloudflare Inc. — R2 `WEUR` & Workers AI; Meta Platforms — US/global API), international transfer safeguards (Art. 46 SCCs / EU-US DPF), and Data Subject Rights (Art. 15–21: access, erasure, withdrawal of consent, right to lodge a complaint with an EU/national supervisory authority).

**Fix (`R12-09`):** Add `DELETE /v1/device` in `worker/src/devices.ts` and an `expire-devices` lifecycle rule in `worker/wrangler.jsonc`; make `SettingsViewModel.deleteAll()` best-effort delete any persisted `JobRecord` / `UploadRecord` / `ReadyStagedRecord` on the server (and rotate/delete the device key) before wiping local storage; add Privacy Policy and Terms links to `SettingsScreen` and the Consent Sheets; complete GDPR Art. 13/17/46 disclosures and align controller/licensor naming across `privacy-policy.md` and `terms-of-use.md`.

---

## 3. API, memory & defense-in-depth findings

### F10 — Unbounded request buffering on v1 multipart / JSON routes & unvalidated media MIME/magic bytes (Medium → `R12-10`)

- `POST /v1/jobs` v1 multipart (`worker/src/jobs.ts:221`) calls `await c.req.formData()` before checking `Content-Length` or bounding the body stream; a >128 MB multipart stream exhausts the Worker isolate's 128 MB memory limit before line 258 (`totalBytes > MAX_TOTAL_BYTES`) executes. Similarly, `uploads.ts:326` and `uploads.ts:541` call `await c.req.json()` without `readBodyCapped`.
- `PUT /v1/uploads/:uploadId/:name` (`uploads.ts:635-639`) and `POST /v1/jobs` (`jobs.ts:276-278`) store caller-supplied `Content-Type` headers verbatim and never check that keyframe bytes start with JPEG magic bytes (`FF D8 FF`) or clip bytes contain an MP4 `ftyp` box (`bytes[4..7] === "ftyp"`).

**Fix (`R12-10`):** Read JSON bodies via `readBodyCapped(c.req.raw.body, 256 * 1024)`; reject v1 multipart requests with missing or `> MAX_TOTAL_BYTES + 1_048_576` `Content-Length` and cap the stream before `formData()`; validate JPEG/MP4 magic bytes and enforce `image/jpeg` / `video/mp4` content types on upload PUT and v1 multipart intake.

### F11 — `X-Forwarded-For` rate-limit spoofing fallback, `/v1/device` mint churn, and missing HTTP security headers (Medium → `R12-11`)

- `clientIp` (`worker/src/index.ts:62-68`) falls back to `X-Forwarded-For.split(",")[0]` when `CF-Connecting-IP` is absent; any request reaching the worker without `CF-Connecting-IP` can spoof a random IP per call via `X-Forwarded-For` to bypass `pre:${ip}` and `dev:${deviceId}:${ip}`.
- `POST /v1/device` (`index.ts:133-135`) shares the 30 req/min `pre:${ip}` limit, allowing a single IP to mint 30 R2 device records per minute per Cloudflare PoP and rotate `deviceId` on every request to evade `dev:${deviceId}:${ip}`.
- Responses omit `X-Content-Type-Options: nosniff`, and `GET /v1/jobs/:id/events` (`jobs.ts:362`) sets `Cache-Control: no-cache` instead of `no-store`.

**Fix (`R12-11`):** Remove the client-controlled `X-Forwarded-For` fallback in `clientIp`; enforce a stricter rate limit or cap on `POST /v1/device`; set `X-Content-Type-Options: nosniff` globally in the top-level middleware and `Cache-Control: no-store` on `/v1/jobs/:id/events` and `/v1/device`.

### F12 — Prompt-injection surface in `routeContext` / `samPhrase`, unbounded AI candidate strings, and AI Gateway logging/caching (Medium → `R12-12`)

- `ManifestSchema.routeContext` (`worker/src/types.ts:95`) accepts any 200-char string (including newlines and prompt instructions) and is interpolated directly into the system/user prompt in `scout.ts:380,622`, whereas `ManifestBuilder.kt:150-155` only ever produces `"Indoor walk, <len> m, <turns> turns, <levels> level changes"`.
- `ScoutCandidateSchema` (`scout.ts:45-57`) and `CandidateSchema` (`types.ts:156-185`) put no `.max(...)` caps on `title`, `samPhrase`, `description`, `signText`, `cue`, or `maskSvgPath`, and `samPhrase` from the scout model is forwarded unchecked into the SAM 3.1 prompt (`samLens.ts:357,374`).
- `ai.run(..., { gateway: { id: gateway } })` (`scout.ts:296`) does not set AI Gateway `skipCache: true` or log-collection opt-out headers/options, risking caching or logging of base64 indoor photos in Cloudflare AI Gateway beyond the 24h R2 retention window.

**Fix (`R12-12`):** Restrict `routeContext` to a single-line printable regex (or structured numeric fields), cap `title`/`samPhrase` (≤80 chars), `cue`/`description`/`signText` (≤300 chars), and `maskSvgPath` (≤65,536 chars) in `ScoutCandidateSchema` and `CandidateSchema`, and pass `skipCache: true` / disable payload logging on the AI Gateway call in `scout.ts:296` (documenting the gateway policy in `docs/operations/spend.md`).

---

## 4. What is already good (verified, keep)

- Constant-time credential comparison (`devices.ts:22-29`), foreign-owner `404` non-disclosure on `GET`/`DELETE /v1/jobs/:id` and `GET`/`PUT`/`DELETE /v1/uploads/:id`, and `resumeOf` owner+manifest verification (`uploads.ts:389-394`).
- Per-checkpoint Workflow steps in `scout.ts:786-826` and per-call steps + synchronous budget governor + in-flight deduplication in `samLens.ts:313-345, 628-690`.
- Sanitized client error envelopes (`app.onError` in `index.ts:99-104`, `error: "job_failed"` in `jobs.ts:328` and `pipeline.ts:69`) and `X-Request-Id` correlation (`index.ts:85-93`).
- `parseSam` pixel-bounds validation (`samParse.ts:64-66`) and Zod validation of all model outputs (`scout.ts:313,505`).

## 5. Residual risk & evidence (all run 2026-11-04)

- **Residual risk:** Live Cloudflare dashboard settings (AI Gateway log/cache toggles, R2 bucket lifecycle rule attachment on `echo-media`, WAF rules on `api.echo.simt.app`) were not mutated or re-queried in this review; verify via Cloudflare MCP / `wrangler` when landing `R12-04`, `R12-09`, and `R12-12`.
- **Executed gates (`worker/`):**
  - `npm run typecheck` → exit 0 (clean).
  - `npm test` → exit 0, **12 files, 221/221 tests pass**.
  - `npm audit` → exit 0, **0 vulnerabilities**.

Verdict: **BLOCK** — `R12-01`…`R12-09` (Critical/High) must land before backend/legal sign-off.
