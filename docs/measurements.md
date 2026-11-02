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
