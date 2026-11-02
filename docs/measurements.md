# ECHO measurements (plan §7.4 envelope, slice W3-06)

## Envelope (per 60 m route, 6–8 checkpoints)

| Budget | Target |
|---|---|
| AI cost | ~$0.16 / route (SAM ≤ 16 clip calls × ≥ 50 billed frames; Workers AI scout calls are cents) |
| End-to-end latency | < 2 min |

## How cost/latency is recorded

`jobs/<id>/result.json` carries `usage` (frozen §5.5 shape):

- `scoutMs` / `samMs` — wall-clock milliseconds measured around the
  Workflow `scout` / `ground` steps in `EchoPipeline.run`.
- `samFrames` — clip frames sent to SAM 3.1 (sum of checkpoint
  `clip.frameCount` when `models.ground` is `sam-3.1`; absent on the
  box-only path). This is a billing proxy: the text-only SSE collector
  (`meta.ts`) does not surface `response.completed` usage, so billed
  frames are counted at send time. Vendor-metered cost = scout calls
  (cents) + `samFrames` × per-frame price.

## Runs

### Local box-only chain (2026-11-01, no keys)

- Path: manifest → Workers-AI-shaped scout fixture → `runGround` box-only
  (`GROUND_ORDER=none`) → `assembleFromGround`.
- Result: 2 candidates ranked §6.6 (`Strong` before `Weak`),
  `result.json` sealed, progress `complete`. No frames sent, no AI cost.
- Evidence: `worker/src/pipeline.test.ts` ("assembles the box-only ground
  chain end to end"), full suite `npm test` green in `worker/`.

### Live 60 m keyed run — blocked

Not yet executed in this environment: no `META_API_KEY` is configured and
the production deploy is blocked on R2 bucket-create permission
(`echo-media` absent) — the same residuals recorded by W3-04 (Workers AI
real-clip check deferred) and W3-05 (live SAM check blocked). To record it:
set `META_API_KEY`, deploy the worker, POST one 60 m job (6–8
checkpoints), then append the measured `scoutMs`/`samMs`, `samFrames`,
vendor-billed cost, and total latency to this file.

### Addendum (review 0004, 2026-11-01) — what has actually run live since

- **SAM 3.1 (W3-05 path):** real grounding through `runGround` with the
  `.env` Meta key: `AI_GROUNDED` in 693 ms, mask raster 120×799,
  confidence 0.9298, box within 2 px of the synthetic ground truth.
  This run found and fixed the done-event collection bug (648e722).
- **Muse Spark (W3-09 path):** real call through `metaSparkScout`:
  schema-valid sealed result in ~1.6–2.9 s; zero candidates proposed
  for the synthetic photo (the model's honest answer to fake content).
- **Workers AI scout / deployed 60 m job:** attempted via the account
  REST API with the environment's Cloudflare token — authentication
  error (code 10000, token lacks Workers AI scope), and deploys remain
  blocked on R2 bucket-create permission. The full deployed-job
  latency/cost envelope (~$0.16/route, < 2 min) is therefore still
  unmeasured and unclaimed; usage fields in `result.json` are now
  measured (vendor frames per send, real Meta time) for when it runs.

## Wave 7 exit gate (W7-12, 2026-11-02) — offline core loop on emulator

- Device: Android emulator `sdk_gphone64_arm64` (emulator-5554,
  API 36.1 AVD image), fresh install (`pm clear`), build SHA `867e556`
  + W7-12 fix-forward (CameraX parent dirs, below). No AI, single
  device, airplane mode for the navigation leg.
- Run: Home empty state ("Walk it once. Share the way.", no demo
  buttons) → Record (live preview, 0:31, 1 spot) → Finish form →
  Review → Save → RouteDetail (Navigate/Share/Simulate on a real
  route) → Share screen (preview + QR render) → airplane on → Align
  (reference photo matched) → Navigate (0.0 m, Leg 1 of 1, ±0.5 m
  chip) → ARRIVED ("Destination", Done). No crash, no stuck state.
- Route: "Gate-Walk" 0.0 m, 2 nodes, 0 landmarks (emulator has no
  PDR motion; destination label left empty by driver mis-tap and the
  UI showed honest fallbacks, never a raw id).
- Initial BLOCK then fix: first run failed at Start with "Could not
  start recording" — `ImageCapture` ENOENT because
  `CameraXRecorder.takePhoto` handed CameraX a path in a
  never-created `drafts/<draftId>/` dir (FileStore creates parents;
  the Android seam did not; a fresh-install phone would hit the
  same). Fixed in-slice (`parentFile?.mkdirs()` in `takePhoto` and
  `startVideo`), host suite 342 green, re-ran the full loop above.
- Verdict: **APPROVE WITH RESIDUAL RISK**. Residuals and owners:
  emulator, not a physical device, and a 0 m route — guidance
  quality, haptics and a real walk belong to W9-04/W9-06; dark-scheme
  heading contrast is poor on-device (headings near-unreadable) —
  owned by W8-02's contrast gate; "Start: Start" node-label
  duplication — owned by W8-10. Wave 8 proceeds on this verdict.

## Live AI envelope (W9-02, 2026-11-02) — measured, not claimed

- Job `56a0b9d4-4587-4d55-979f-8c04d8433ccd` on
  `https://api.echo.simt.app` (deploy `47544eee`, AI Gateway `echo`):
  POST 202 → scouting (~50 s) → grounding (box-only fallthrough) →
  `complete`, result fetched (readable), `DELETE` 204 → `GET` 404
  `job_not_found` (workflow delete is eventual, ~45 s — same as
  W9-01). Full lifecycle on production code and data.
- Route honesty: fixture-built, NOT walked — 60.0 m, 7 checkpoints
  (TURN/CHECKPOINT/LEVEL_CHANGE/DESTINATION) from the
  `jobs.test.ts validManifest` pattern, keyed 1:1 to checkpoints.
  All 7 keyframes reuse one real emulator-camera door photo
  (199,845-byte JPEG that completed two single-checkpoint probes;
  three UI-screenshot JPEGs were tried first and the live models
  returned no parseable `response` for them — content-dependent
  model behavior, recorded, not retried further). What is genuinely
  measured: the cloud pipeline over 7 checkpoints (scout latency,
  cost basis, grounding fallthrough). Real-walk proof stays with
  W9-06.
- Measured: `scoutMs: 53175` (7 live Llama 4 Scout image calls, no
  Gemma fallback needed), `samMs/samFrames` absent (no META_API
  key anywhere — SAM grounding off by config, `ground: none`),
  end-to-end POST→complete 66 s.
- Envelope vs §7.4 (~$0.16/route, <2 min): latency 66 s ✓ within;
  cost is 7 Workers AI Scout image calls on postpaid gateway
  billing (cents scale — exact invoice cents meter in the
  dashboard, not the API) ✓ within the cents-scale scout budget;
  Spark not enabled (not run); SAM path unmeasured by config
  (recorded, not claimed).
- Review model-naming: verified against `CandidateMerger.modelsLine`
  — it renders server-supplied `models.scout/ground` verbatim
  ("@cf/meta/llama-4-scout-17b-16e-instruct (box-only)"), matching
  exactly what ran; `consentCopy` matches deployed reality. No
  copy change needed.
- Evidence archived: `res/measurements/w9-02-56a0b9d4-result.json`
  (live result), `res/measurements/w9-02-manifest.json` (the exact
  submitted manifest). Diagnostic/errored jobs (`2f59d100`
  media_missing arg bug, `69240608` + `a378a2cf` model no-response
  on UI screenshots, `85dc0c79` known-good probe) were all
  deleted (204 → 404).

## 2.0 baselines (W10-01, 2026-11-02)

- Baseline SHA: `e99c133` (main at slice time).
- Devices: low-end **TBD**, mid/flagship **TBD** — named phones, not yet
  assigned. Nothing below was measured in this environment (no Android
  device or `adb`; no `META_API_KEY`; the JankStats/StrictMode/
  macrobenchmark harness lands in W10-04), so every device- or
  key-dependent cell is `unmeasured` with the exact command that fills
  it. No numbers invented.

| §4 budget | v1 today (W10-01) | Reproduce |
|---|---|---|
| SAM billed frames / 60 m route | unmeasurable here: no `META_API_KEY` (design ≤ 800 = 16 × ≥ 50, §7.4) | `META_API_KEY=… wrangler dev` → POST one 60 m job (6–8 checkpoints) → read `usage.videoFrames` (+ `videoFramesProcessed` from `response.completed` in `worker/src/meta.ts`) in `jobs/<id>/result.json` |
| SAM calls / route; calls on retry | ≈2N video + ≤2N image, all re-billed (§4; code: per-candidate `groundOne` + hero-image fallback in `worker/src/ground.ts:183-190`) | same keyed job as above; count Meta calls in the worker log |
| Upload bytes / route | unmeasured: no device (design: 7 HD clips + ~21 keyframes) | weigh `jobs/<id>/in/` via `wrangler r2 object list` / dashboard after a walked-record upload |
| Result latency after Finish | **66 s** POST→complete, scout-only (W9-02, measured above) | already recorded; re-run per W11-10 on the walked route |
| Record jank (JankStats, 60 s) | unmeasurable here: no device; harness lands in W10-04 | W10-04 `:benchmark` macrobenchmark (or debug JankStats summary) — 60 s Record on each named phone |
| Frame Tap cost | n/a — no Frame Tap in v1 (first measured W10-03) | — |
| Ghost filter update | n/a — new in 2.0 (first measured W12-02) | — |
| Navigate battery, 10 min screen-on | unmeasurable here: no device | 10 min Navigate on the mid phone → `adb shell dumpsys batterystats` delta |
| Job status requests / job | **≤ 150 polls** (`maxPolls = 150` @ 2 s in `shared/src/commonMain/kotlin/app/simt/echo/ui/screens/processing/ProcessingViewModel.kt:94-95`) | static; live count from logcat when the walked route runs |
| Visual match precision | n/a — new in 2.0 (first measured W12-05) | — |

W10-01 measurement slots (all TBD — commands to run on the named phones):

- JankStats Record 60 s: TBD (W10-04 harness).
- JankStats Navigate Simulate 60 s: TBD (W10-04 harness).
- Keyframe/clip prep time + main-thread stalls: TBD
  (`adb logcat | grep StrictMode` across Record → Processing).
- Navigate battery 10 min: TBD (batterystats, mid phone).
- Keyed SAM job `response.completed` billed frames: unavailable —
  no `META_API_KEY` in this environment (collector ready:
  `metaResponsesDetailed` in `worker/src/meta.ts:32`).

## SAM live probe (W11-07, ≤ $1 budget) — NOT RUN here

No live call was made from this environment: no `META_API_KEY` is
configured, so spend is $0 and every cell below is TBD. The hardening
half of W11-07 (golden SSE fixtures in `worker/src/meta.test.ts`, the
`npm run canary:sam` one-frame image call) is implemented and green;
the probe half must run where a key exists. If multi-phrase works,
W11-06 sends one video call per checkpoint for all surviving phrases.

| Fact for the ladder (W11-06) | Probe (exact) | Result |
|---|---|---|
| Multi-phrase in one call? Output attribution per phrase? | `META_API_KEY=…`: POST `https://api.meta.ai/v1/responses` with `model: sam-3.1`, one message whose content holds **two** `input_text` phrases + one `input_image` (the canary 1-px PNG), `stream: true`; record whether both objects are segmented and how the output attributes them | TBD — job/request ids: TBD |
| 12-frame 4 fps 480p clip accepted? Billed per frame? | Same endpoint, `input_video` with a 12-frame 4 fps 480p clip + one phrase; read `response.completed` → `response.usage.video_frames_processed` (surfaced by `metaResponsesDetailed`) and compare with frames sent | TBD — job/request ids: TBD |
| Image-call billing | `META_API_KEY=… npm run canary:sam` (worker/; dry-runs with no key) — one 1-frame image call; record billed frames/price and wall latency | TBD — job/request ids: TBD |
| Latency per shape | Wall ms around each of the three calls above (`Date.now()` delta, as `ground.ts` records `samMs`) | TBD |

Procedure to fill the table (keyed environment only, stop at $1):

1. `cd worker && npm run canary:sam` dry run first (no key) — must exit 0 without sending.
2. `META_API_KEY=… npm run canary:sam` — record latency, output chars, any usage; append row 3.
3. Send the two-phrase image call and the 12-frame clip call (rows 1–2) with `curl` or a scratch script mirroring `scripts/canary-sam.mjs`; save each raw SSE body and note the request/job ids.
4. Append measured numbers + ids to the table above; total spend must stay ≤ $1.

## Face pre-filter on device (W11-04, 2026-11-02)

Real bundled ML Kit (`face-detection` 16.1.7) on a Pixel 8a (SDK 37),
debug build, `MlKitFaceScanner` as shipped (accurate mode, minimum face 5%).
Gate: `./gradlew :androidApp:connectedDebugAndroidTest` (7/7 in
`MlKitFaceScannerDeviceTest`), a named manual gate, never pre-commit.
Fixtures are public-domain NASA/ESA images at ledger scale (960 px class,
`androidApp/src/androidTest/assets/faces/`).

| Fixture | Head width / frame | Faces found | Per scan (mean of 10, warm) |
|---|---|---|---|
| `face_large.jpg` | ~30% (512 px portrait) | 1 | 180 ms |
| `face_small.jpg` | ~6% | 1 | 333 ms |
| `face_tiny.jpg` | ~2.5% | 1 | 300 ms |
| `no_face.jpg` | none (Hubble field, 960x720) | 0 | 333 ms |

- First scan after process start (model load): 231 ms.
- Scanning on the main thread fails closed to UNKNOWN (`Tasks.await` refuses the
  main thread) rather than blocking it; garbage bytes throw.
- Reading: the 5% minimum is not the limiting factor here (a 2.5% face was
  found), so it is a recall-safe setting, not a tight one. Cost is the accurate
  mode: about 0.3 s per frame, so a 20-frame upload spends about 6 s scanning,
  once, off the main thread, before bytes leave the phone.
- Not measured: a face on a printed page or screen in a live camera frame,
  turned or covered faces, a second phone, APK size delta.

## Macrobenchmarks on device (W10-04, 2026-11-02)

Pixel 8a (SDK 37), `benchmarkRelease` build (non-debuggable, debug-signed),
screen on and unlocked, battery 80%, not in airplane mode, one phone. Driven by
`am instrument` (see `docs/perf.md` for why not Gradle).

| Scenario (`EchoBenchmarks`) | Iterations | Result |
|---|---|---|
| `coldStart`, uncompiled (`CompilationMode.None`) | 5 | time to initial display: min 517 ms, **median 562 ms**, max 653 ms |
| `homeScroll`, partial profile | 5 | 84 frames median; CPU frame time P50 6.9 / P90 10.7 / P95 12.0 / **P99 28.2 ms**; overrun P50 0.2 / P90 6.9 / P95 10.8 / **P99 31.5 ms** |
| `record30s`, camera granted, take started | 2 | ~2,015 frames; CPU frame time P50 5.3 / P90 7.8 / P95 9.1 / **P99 12.5 ms**; overrun P50 -7.3 / P90 -4.2 / P95 -2.6 / **P99 4.4 ms** |
| `navigateSimulate60s` = Home dwell, **not Navigate** | 2 | ~3,595 frames; CPU frame time P50 8.2 / P90 10.9 / P95 11.9 / **P99 13.5 ms**; overrun P50 1.5 / P90 8.0 / P95 11.1 / **P99 14.4 ms** |

Reading:
- Record holds its frame budget: P99 overrun is 4.4 ms while the camera and the
  Frame Tap run. Home scroll's P99 (28 / 32 ms) is the fling's first frames, a
  few janky frames out of ~84, and is the one jank number worth a closer look.
- The 60 s run measures Home's animated backdrop (3,595 frames is 60 fps), not
  Navigate-Simulate, because a benchmark cannot seed a saved route yet. G-PERF-05
  for Navigate and G-PERF-08 (battery) remain unmeasured.
- These are single-device numbers with no pre-2.0 baseline run on the same phone,
  so they are evidence the harness works and a first data point, not a pass/fail
  against a baseline. Frame Tap <= 6 ms, StrictMode and the 2 MB upload check
  have no probe in this harness and are still unmeasured.
