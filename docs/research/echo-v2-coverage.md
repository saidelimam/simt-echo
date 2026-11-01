# ECHO V2 coverage matrix (W7-01 runtime truth audit)

Owner: plan `docs/plans/0002-production-ready-cosmos.md` slice W7-01 (T primary, S reviewer).
Spec of record: `docs/research/SIMT_ECHO_IMPLEMENTATION_PLAN_V2.md` (cited as *V2 §n*).
Trackers: `docs/plans/0001-agentic-flow.md` (0001), `docs/plans/0002-production-ready-cosmos.md` (0002).
Reviews: `docs/reviews/0001`–`0005` plus `0006-residual-risks.md` (0006).

## Method

Reconciled 2026-11-01 in worktree `w7-01` by reading V2 §5–§13, both
trackers, all six reviews, and the current tree. Every `file:line`
below was verified to exist with `grep` on this tree; line numbers are
the definition site (`class`/`fun`/`interface`/copy string), not the
whole symbol. Test names are **recorded green runs from the 0001/0002
trackers and reviews** — this slice ran no test suites itself
(docs-only chore), so "Green test" means "named test recorded green on
the integrated tree", never "re-run here".

## Status key (0002 §3, verbatim)

**Works** = implemented and unit-proven (named green test required —
no row is marked Works without one). **Unverified** = implemented,
never proven where it runs (device/live). **Partial** = present but
missing mandated parts. **Broken** = present and wrong for users.

## Global caveats (read first)

- **The present-tree host gate is red (B3).** 0002 §1 records
  `./gradlew :shared:testAndroidHostTest` at 304 tests / 6 failed (5×
  `NoClassDefFoundError: androidx/savedstate/R$id` in the in-flight
  W6-03 Robolectric infra + `ExportSelectionTest.deleteRemovesPlainExportsAndJobIdRecord`)
  and the worker gate not startable in that worktree. Every Works row
  below therefore means "Works on its last recorded green evidence";
  re-proof of the whole gate belongs to **W7-02**, which must run
  before any later slice claims a green tree.
- **Device-unverified is the norm, not the exception (M6).** Unit-green
  was repeatedly recorded as product-green in 0001; this matrix never
  repeats that claim. Anything needing hardware/live proof is at best
  Unverified and names its W9 owner.
- Paths below are relative to repo root unless prefixed `worker/`.
  `commonMain` = `shared/src/commonMain/kotlin/app/simt/echo/`.

## §3 trace table (every 0002 §3 row → matrix rows)

| 0002 §3 row | Matrix rows | Closing slices |
|---|---|---|
| §1 steps 1–3 | A1, A2, A3 | W7-02, W7-05, W7-07, W9-02 |
| §1 steps 4–5 | A4, A5 | W8-08, W9-04 |
| §1 step 6 | A6 | W7-04, W9-04 |
| §1 steps 7–8 | A7, A8 | W8-10, W9-06 |
| §5.1 platform seams | B1–B7 | W9-08 |
| §5.2 domain model, §5.3 storage | C1–C3 | W7-10 |
| §5.4 wire formats | D1–D4 | W9-03, W9-09 |
| §5.5 worker API + schemas | E1–E6 | W9-01, W9-02 |
| §6.1–6.2 heading/PDR, §6.3 segmenter | F1–F3 | W9-05, W9-06 |
| §6.4 navigator, §6.5 turn detector | F4, F5 | W7-04, W9-04 |
| §6.6 track selection/ranking | F6 | W9-02 |
| §7.0–7.2 provider routing, Workers AI scout | G1–G3 | W9-01, W9-02 |
| §7.3 SAM 3.1 ground | G4 | W9-02 |
| §7.4 envelope | G5 | W9-02 |
| §7.5 local models | G6 | W9-07 |
| §8.1 tokens/type/motion | H1 | W8-01, W8-02 |
| §8.2 screen map | H2 | W7-03, W7-04, W8-05 |
| §8.3 screens (each row) | H3–H14 | W7-03…W7-08, W8-06…W8-11 |
| §8.4 haptics & copy | H15, H16 | W7-06, W9-04 |
| §9/§10 slice definitions | I1 | W7-01 (this matrix) |
| §11 testing & field protocol | J1–J3 | W9-06 |
| §12 risks & cut line | K1 | §4 restatement (this plan) |
| §13 iOS port | L1 | out of scope (§4) |
| 0001 residuals | M1–M10 | W9-01…W9-08 (per-slice) |
| 0002 residuals W6-02/W6-03/W6-04 | N5 (+B3 note) | W7-11, W7-02, W9-01/W9-02 |

## A. MVP loop (V2 §1 steps 1–8)

| ID | Requirement | State | Code evidence | Green test (recorded) | Closes in |
|---|---|---|---|---|---|
| A1 | Step 1: record 30–80 m walk (sensors + silent video, marks) | Works headless; **Broken as a journey** (B3 red gate; M3 handoff maze) | `commonMain/capture/CaptureSession.kt:50` (`class CaptureSession`); `commonMain/platform/CameraRecorder.kt:1` (seam) | `CaptureSessionTest` 3/3, `RecorderLogicTest` 7/7 (W1-02/W1-03) | W7-02 (gate), W7-05 (handoff), W9-05/W9-06 (device) |
| A2 | Step 2: segment into legs/turns/checkpoints (deterministic) | Works on fixtures; **Unverified on walks** | `commonMain/capture/Segmenter.kt:30` (`fun segment`) | `SegmenterTest` 6/6 (W1-04) | W9-05, W9-06 |
| A3 | Step 3: consent → Worker AI landmarks (Workers AI default; Spark/SAM only with key; local only if configured) | Works in vitest + one live synthetic check; **Broken as a journey** (B4: unreachable in a normal build, no honest unavailable path) | `commonMain/transfer/EchoApiClient.kt:115` (`class EchoApiClient`); `worker/src/scout.ts:631` (`SCOUT_ORDER` walk) | `EchoApiClientTest` 10/10, `ProcessingViewModelTest` 10/10 (W3-02); worker `scout.test.ts` 19/19 (W3-04) | W7-07 (honesty), W9-01/W9-02 (deploy + measure) |
| A4 | Step 4: creator review (accept/swap/edit/draw) + save | Works headless; oversized screen (m1); trials/AI copy caveats | `commonMain/ai/CandidateMerger.kt:94` (`fun merge`); review UI under `commonMain/ui/screens/review/` | `ReviewViewModelTest` 7/7, `RouteCuesTest` 6/6 (W2-01); `CandidateMergerTest` (W3-07); suite 254/254 merged-main | W8-08, W9-04 |
| A5 | Step 5: QR show/scan + LAN/hotspot encrypted download | Works headless; **Unverified on two phones** (ML Kit scan + live transfer never run) | `commonMain/transfer/TransferService.kt:54` (`fun offer`); `:87` (`fun receive`); `commonMain/transfer/QrPayload.kt:22` (`fun build`), `:64` (`fun parse`) | `TransferServiceTest` 9/9→13/13, `QrPayloadTest` 7/7, `ShareViewModelTest`, `ReceiveViewModelTest` 6/6 (W2-03/W2-04) | W8-08 (Share/Receive polish), W9-04 (two-phone proof) |
| A6 | Step 6: airplane-mode align + navigate to ARRIVED | **Broken from UI** (B2: no Navigate entry from any real route); engine Works (7 scenarios + ALIGN) | `commonMain/nav/Navigator.kt:36` (`class Navigator`); `RouteDetailScreen.kt:38` (`onShare`, no `onNavigate` — grep finds no Navigate action); `EchoNavHost.kt:150-171` (Align→Navigate exists, unreachable) | `NavigatorTest` 8/8, `TurnDetectorTest` 5/5, `ReplayMotionSourceTest` 3/3 (W2-05); `NavigateViewModelTest` (W2-06) | W7-04 (wiring), W9-04 (device ARRIVED) |
| A7 | Step 7: turns/landmarks re-anchor; uncertainty shown | Works (engine); trials leak into product UI (M4) | `commonMain/nav/Navigator.kt:36` (UNCERTAIN/OFF_ROUTE/ARRIVED phases); `ui/screens/navigate/NavigateScreen.kt:256` ("At marker" trial button in product UI) | `NavigatorTest` 8/8 incl. overshoot/uncertainty (W2-05/W2-06) | W8-10 (trial leak), W9-06 (field) |
| A8 | Step 8: trials logged (sensor-only vs ECHO comparison) | Works (flat `trials/<id>.json` + single CSV); field protocol never executed | `commonMain/data/TrialRepository.kt:16` (`class TrialRepository`); `:47` (`fun exportCsv`) | `TrialRepositoryTest`, `NavigateViewModelTest` 6/6 (W4-01); suite 208/208 merged-main | W9-06 (§11 5+5) |

## B. Platform seams (V2 §5.1)

| ID | Requirement | State | Code evidence | Green test (recorded) | Closes in |
|---|---|---|---|---|---|
| B1 | `MotionSource` / `MotionUpdate` (~20 Hz + every step) | Works | `commonMain/platform/MotionSource.kt:12` (`data class MotionUpdate`); `:30` (`interface MotionSource`); `commonMain/capture/ReplayMotionSource.kt:24` | `ReplayMotionSourceTest` 3/3, `PdrEngineTest` 7/7 (W1-01/W2-05) | — (frozen) |
| B2 | `CameraRecorder` (preview, silent video, start photo, clock sync) | Works headless; PiP **Unverified** on device | `commonMain/platform/CameraRecorder.kt` (seam); `RecorderLogicTest` target | `RecorderLogicTest` 7/7 (W1-02) | W9-04/W9-05 |
| B3 | `MediaTools` (frames, clips, cropJpeg) | Works | `commonMain/platform/MediaToolsProvider.kt` (provider); W1-05 impl | `MediaToolsTest` 22/22 (W1-05) | — |
| B4 | `PeerTransport` (LAN offer/fetch ≥ 1 MB, no internet) | Works headless; live two-phone **Unverified** | `commonMain/platform/PeerTransport.kt` (seam); `TransferService.kt:33` | `TransferServiceTest` (W2-03) | W9-04 |
| B5 | `Haptics` (7 patterns, §8.4 waveforms verbatim) | Waveforms Works; feel **Unverified** | `commonMain/platform/Haptics.kt:5` (TICK), `:6` (CONFIRM), `:10` (WARN), `:9` (U_TURN), `:11` (ARRIVE) | `NavigateViewModelTest` haptic-sequence (W2-06, review 0001 fix `573224f`) | W9-04 (feel) |
| B6 | `Platform` (dataDir, nowMs, isOnline, deviceLabel, shareFile, KeepScreenOn) | Works except Android actuals **Unverified** (`isOnline`/KeepScreenOn/PiP) | `commonMain/platform/Platform.kt`; `commonMain/platform/DeviceServices.kt`; `commonMain/platform/FileSharing.kt` | `DeviceServicesUiTest`, `CameraPermissionGateUiTest` — **red on this tree (B3)**; re-proof in W7-02 | W7-02, W9-05 |
| B7 | `LocalVision` optional seam (same `AiCandidate` output) | Implemented; **Unverified** on any device/server | `commonMain/ai/LocalVision.kt:16` (`interface LocalVision`); `commonMain/ai/OpenAiCompatVision.kt`; `commonMain/ai/OnPhoneVision.kt` | `OpenAiCompatVisionTest` 3/3, `AiRouterTest` 3/3 (W5-01, suite 287/287) | W9-07 (prove or remove claims) |

## C. Domain model + storage (V2 §5.2–§5.3, frozen W0-04)

| ID | Requirement | State | Code evidence | Green test (recorded) | Closes in |
|---|---|---|---|---|---|
| C1 | Domain model (`EchoRoute`, nodes `n0..nN`, landmarks, `EchoJson` settings) | Works (frozen; do not re-open except via S) | `commonMain/model/EchoRoute.kt`; `commonMain/model/EchoJson.kt`; `commonMain/model/CaptureLog.kt` | `ModelJsonTest` 8/8, `RepositoryTest` 7/7 (W0-04, suite 33/33) | W7-10 (corrupt-state hardening only) |
| C2 | Storage layout (`drafts/`, `routes/`, `trials/`) + `FileStore`/`RouteRepository` | Works | `commonMain/data/FileStore.kt`; `commonMain/data/RouteRepository.kt`; `commonMain/data/DraftRepository.kt` | `RepositoryTest` 7/7 (W0-04) | W7-10 |
| C3 | `L_ROUTE` fixture + `EchoConfig` tunables (K band 0.15–1.5) | Works | `commonMain/model/EchoConfig.kt:12` (`data class EchoConfig`); `:19` (`stepK = 0.48`) | `CalibrationTest`, engine-fixture drift test (W4-02, suite 208/208) | W9-06 (field calibration check) |

## D. Wire formats (V2 §5.4)

| ID | Requirement | State | Code evidence | Green test (recorded) | Closes in |
|---|---|---|---|---|---|
| D1 | Container `C = "ECHO"‖0x01‖SHA-256(body)‖body`, hash over received bytes | Works | `commonMain/bundle/BundleCodec.kt:39` (`MAGIC = "ECHO"`); `:60` (`fun open` verifies hash) | `BundleCodecTest` 10/10 (W2-02, suite 53/53) | — |
| D2 | Encrypted file `E = "ECX1"‖AES-256-GCM(key, C)` | Works | `commonMain/bundle/BundleCodec.kt:40` (`ENCRYPTED_MAGIC = "ECX1"`); `:53` (`fun seal`) | `BundleCodecTest` 10/10 (W2-02) | W9-09 (audit) |
| D3 | QR URI ≤ 200 chars (`simt-echo://r?v=1&h&p&t&k&s&n[&c=]`) — relay `c=` never exercised live (B5) | Works headless; relay-param path **Unverified** live | `commonMain/transfer/QrPayload.kt:22` (`fun build`); `:64` (`fun parse`); `commonMain/AppGraph.kt:88-90` (relay stays unconfigured → LAN-only) | `QrPayloadTest` 7/7 (W2-03) | W9-03 (relay live), W9-09 |
| D4 | File fallback: plain container `<name>.echo` after explicit privacy warning | Works | `commonMain/transfer/EchoFileTest.kt` target; `TransferService.kt:133` (`fun saveImported`) | `EchoFileTest`, suite 229/229 (W2-07, review 0003 fix `f260190`) | — |

## E. Worker HTTP API + schemas (V2 §5.5, contract frozen W3-03)

| ID | Requirement | State | Code evidence | Green test (recorded) | Closes in |
|---|---|---|---|---|---|
| E1 | `GET /v1/health` (+ `providers` map) | Works in vitest; **never deployed** (B4) | `worker/src/index.ts`; `worker/src/jobs.ts` | worker `npm test` 96/96 merged-main (W3-06) | W9-01 (deploy) |
| E2 | `POST /v1/jobs` (manifest + `kf_*`/`clip_*`, consent-gated, 202 `{jobId}`) | Works in vitest + local workerd; never deployed | `worker/src/jobs.ts`; consent rule per V2 §10 W3-03 detail | worker `npm test` incl. `jobs.test.ts` (W3-03: live local POST 202 → complete ~4s → DELETE 204) | W9-01, W9-02 |
| E3 | `GET /v1/jobs/:id` (queued/running/complete/errored + stage) / `DELETE` | Works in vitest + local workerd; never deployed | `worker/src/jobs.ts`; `worker/src/pipeline.ts` (stage → `progress.json`) | worker `npm test` (W3-03/W3-06) | W9-01, W9-02 |
| E4 | `POST /v1/relay` / `GET /v1/relay/:code` (P1, ≤ 5 MB ciphertext, 30-min expiry) | Works in vitest; **dead in product** (B5: no `RelayClient` wired) | `worker/src/relay.ts`; `commonMain/transfer/RelayClient.kt`; `AppGraph.kt:88-90` | `relay.test.ts` 7/7 + `TransferServiceTest` 13/13 (W4-05) | W9-03 |
| E5 | Manifest schema (zod-validated; `ManifestBuilder` in app) | Works | `worker/src/types.ts`; `commonMain/media/ManifestBuilder.kt` | `ManifestBuilderTest` 18/18 (W3-01, suite 128/128) | — (frozen) |
| E6 | Result schema (`JobResult`: candidates, boxes, masks, `usage`) | Works in vitest; usage measured honestly (no SAM → no `samMs`) | `worker/src/types.ts`; `worker/src/pipeline.ts` (assemble+sort) | `pipeline.test.ts` + `ground.test.ts` 22/22 (W3-05/W3-06, review 0004 fix `e75e05e`) | W9-02 |

## F. Algorithms (V2 §6, pure Kotlin, unit-tested)

| ID | Requirement | State | Code evidence | Green test (recorded) | Closes in |
|---|---|---|---|---|---|
| F1 | §6.1 heading (game rotation vector, hysteresis, yaw0-relative) | Works on fixtures; **Unverified on walks** | `commonMain/capture/HeadingMath.kt:42` (`fun headingRad`); `:15` (`wrapPi`); `:23` (`circularMean`) | `PdrEngineTest` 7/7 (W1-01, suite 40/40) | W9-05, W9-06 |
| F2 | §6.2 PDR (Weinberg K=0.48, [0.35,0.95] m clamp, 20 Hz + per-step emit) | Works on fixtures; **Unverified on walks** | `commonMain/capture/PdrEngine.kt:33` (`class PdrEngine`) | `PdrEngineTest` 7/7; drift-reduction test (W4-02) | W9-05, W9-06 |
| F3 | §6.3 segmenter (0.5 m resample, 35°/1.5 m open, 45° keep, level Δh > 2.2 m) | Works on fixtures; **Unverified on walks** | `commonMain/capture/Segmenter.kt:30` (`fun segment`) | `SegmenterTest` 6/6 (W1-04) | W9-05, W9-06 |
| F4 | §6.4 navigator (ALIGN/WALK/APPROACH/TURNING/CONFIRM/UNCERTAIN/OFF_ROUTE/ARRIVED + corridor snapping) | Works (7 scenarios + ALIGN); **Unverified to ARRIVED on device** | `commonMain/nav/Navigator.kt:36` (`class Navigator`) | `NavigatorTest` 8/8 (W2-05) | W7-04 (reachability), W9-04 (device ARRIVED) |
| F5 | §6.5 turn detector (6 s window, 45° fire, 12° std, 2 s cooldown) | Works | `commonMain/nav/TurnDetector.kt:30` (`class TurnDetector`) | `TurnDetectorTest` 5/5 (W2-05) | W9-04 |
| F6 | §6.6 track selection/ranking (hero ±5, `score = 0.6·iou + …`, rank formula) | Works on fixtures; live SAM proven **once on a synthetic image only** | `worker/src/selection.ts:114` (`export function selectTrack`); `worker/src/ground.ts` | `ground.test.ts` 22/22 (W3-05); live synthetic: AI_GROUNDED 693 ms, conf 0.9298 (review 0003, fix `648e722`) | W9-02 (real-clip proof) |

## G. AI pipeline (V2 §7)

| ID | Requirement | State | Code evidence | Green test (recorded) | Closes in |
|---|---|---|---|---|---|
| G1 | §7.0 provider routing (`SCOUT_ORDER`/`GROUND_ORDER`, skip-no-cred, `AiCandidate` uniformity, Review footnote) | Works in vitest; live Workers AI **blocked on token scope** | `worker/src/scout.ts:612-631` (order walk); `commonMain/ai/AiRouter.kt` | `scout.test.ts` 19/19; `AiRouterTest` 3/3 (W3-04/W5-01) | W9-01, W9-02 |
| G2 | §7.2 Workers AI scout (Llama 4 Scout → Gemma 4 fallback, ≤3 images/call, zod) | Works in vitest; live real-clip check deferred (deploy blocked) | `worker/src/scout.ts` | `scout.test.ts` 19/19 (review 0001 fix `158713d`: invalid-output fallback) | W9-01, W9-02 |
| G3 | W3-09 Spark scout (one call, all keyframes, `reasoning.effort low`) | Works in vitest + one live schema-valid call (zero candidates on synthetic photo) | `worker/src/meta.ts` (`metaResponses` SSE collector, V2 §7.3) | `scout.test.ts` (review 0004 fix `f384d21` + `71456d0`); live ~1.6–2.9 s in `docs/measurements.md` | W9-02 |
| G4 | §7.3 SAM 3.1 ground + `parseSam` + hero mask→SVG (fallbacks a/b) | Works; **one live synthetic check only** | `worker/src/samParse.ts:27` (`parseSam`); `worker/src/ground.ts` | `ground.test.ts` 22/22 (review 0002 fix `c439e63`: real `@meta-sam/parser` decode) | W9-02 |
| G5 | §7.4 envelope (~$0.16/route, < 2 min) | **Unmeasured, unclaimed** (`docs/measurements.md:57-58`) | `docs/measurements.md:7` (cost table, estimate not claim) | none (explicitly none — must stay Unmeasured) | W9-02 (measure on deployed 60 m job) |
| G6 | §7.5 local models (LAN `OpenAiCompatVision`, on-phone LiteRT) | Implemented; **Unverified** on any device/server | `commonMain/ai/OpenAiCompatVision.kt`; `commonMain/ai/OnPhoneVision.kt`; `commonMain/ai/LocalVision.kt:16` | `OpenAiCompatVisionTest` 3/3, `AiRouterTest` 3/3 (W5-01, suite 287/287) | W9-07 (prove or strip claims) |

## H. UX / UI (V2 §8; Cosmos state per 0002 §1 M1)

| ID | Requirement | State | Code evidence | Green test (recorded) | Closes in |
|---|---|---|---|---|---|
| H1 | §8.1 tokens/type/motion (dark-first, lime accent, 4 type styles, 28 dp radii, no shapes, no icons) | **Partial** (lime theme, 4 type styles, no shapes) | `commonMain/ui/theme/` (`EchoColors.kt`, `EchoTheme.kt` per 0002 §1 M1) | `EchoThemeGatesTest` (W4-04: light-danger 5.01:1, touch 56 dp) | W8-01, W8-02 |
| H2 | §8.2 screen map (Home→Record→Finish→Review→RouteDetail→Share; Home→Receive→RouteDetail→Align→Navigate→Arrived; Settings) | All routes exist; Home/RouteDetail **violate it** (B1, B2) | `commonMain/ui/EchoNavHost.kt` (graph); `commonMain/ui/EchoRoutes.kt` | `ScaffoldSmokeTest` 2/2; journey test pending | W7-03, W7-04, W8-05 |
| H3 | Home (route cards, empty state, Record+Receive, gear) | **Broken** — debug menu (B1) | `commonMain/ui/screens/Screens.kt:87-93` (five demo buttons, `demo-draft`/`demo-route`) | none (must add journey test) | W7-03, W8-06 |
| H4 | Record (preview, pre-start overlay, time/steps chips, giant distance, heading tick) | **Partial** (no heading tick, no sheet) | Record screen under `commonMain/ui/screens/record/`; `EchoNavHost.kt:64` (Finish pops to Home — M3) | `CaptureSessionTest` 3/3 | W7-05, W8-07 |
| H5 | Finish sheet (name, destination → Continue into Review) | **Broken as flow** (M3: inline form + pop-to-Home) | `commonMain/ui/EchoNavHost.kt:64` (`onSaved = popBackStack`) | none (W7-05 adds nav-result test) | W7-05 |
| H6 | Review (minimap, timeline, Use/Next/Draw/Skip, footnote, Find landmarks → Save) | Works headless; oversized (m1) | `commonMain/ui/screens/review/`; `CandidateMerger.kt:94` | `ReviewViewModelTest` 7/7 (W2-01/W3-07) | W8-08 |
| H7 | Processing (4-step progress, Cancel; unconfigured → honest state) | **Broken when unconfigured** (B4: network error, no first-class unavailable path) | `commonMain/ui/screens/processing/ProcessingViewModel.kt` (consumes `ManifestBuilder.kt`) | `ProcessingViewModelTest` 10/10 (configured path only) | W7-07 |
| H8 | RouteDetail (name/length/nodes/landmarks/expiry + Navigate/Share/Simulate) | **Partial** (no Navigate/Simulate/expiry line — B2) | `commonMain/ui/screens/detail/RouteDetailScreen.kt:85-86` (Share only); `:90-92` (delete-media) | none (W7-04 adds journey test) | W7-04 |
| H9 | Share (white QR card, status, file fallback; relay hidden while unconfigured) | **Partial** (QR buried below export preview — m1; relay never offered — B5) | `commonMain/ui/screens/share/ShareScreen.kt`; `TransferService.kt:151-165` (export selection/summary) | `ShareViewModelTest` 4/4 (W2-07/W4-05) | W7-08, W8-09 |
| H10 | Receive (scanner cutout, hint, import file/sheet) | **Partial** (epoch expiry text, small 280 dp scanner — M2/m1) | `commonMain/ui/screens/receive/ReceiveScreen.kt:140-143` (`"Expires $expiresAt"` raw epoch) | `ReceiveViewModelTest` 6/6 | W7-06, W8-09 |
| H11 | Align (start photo 60% + PiP + I'm aligned) | **Partial** (120 dp PiP — m1; camera PiP device-unverified) | Align screen (route `Align`, host `EchoNavHost.kt:150-171`) | `NavigateViewModelTest` (state mapping) | W8-10, W9-04 |
| H12 | Navigate (offline+σ chips, 200 dp arrow, cards, progress; I see it/Lost) | Works + trial leak (M4) | `commonMain/ui/screens/navigate/NavigateScreen.kt:256` ("At marker" leak) | `NavigateViewModelTest` 6/6, `NavigateOverlayTest` 5/5 (W2-06/W5-02) | W8-10 |
| H13 | Arrived (summary + Done saves trial; no trial form in product) | **Partial** (half §11 form — M4) | Arrived screen under `commonMain/ui/screens/arrived/` | `NavigateViewModelTest` (honest summary, review 0001 fix) | W8-10, W8-11 |
| H14 | Settings (sections, step-K + calibrate, mode, API base, CSV export, delete-all, local model) | **Partial** (flat panel; no delete-all/API base — M5/B4) | `commonMain/ui/screens/settings/SettingsScreen.kt`; `SettingsViewModel.kt` (+ `exportTrials` W6-01) | `SettingsViewModelTest` (W4-02/W6-01 `6c523a8`) | W7-07, W8-11 |
| H15 | §8.4 haptics waveforms | Works (verbatim) | `commonMain/platform/Haptics.kt:5-11` | haptic-sequence test (W2-06) | — |
| H16 | §8.4 copy (verb-first ≤6 words, no ids/epochs/exceptions, redundant text+arrow+haptic) | **Broken** (M2: raw ids, epoch ms, exception text) | `ReceiveScreen.kt:143`; `Screens.kt:87-93` (id buttons); M2 witnesses in 0002 §1 | none (W7-06 adds grep gate + snapshots) | W7-06 |

## I. Slices as sequencing (V2 §9/§10)

| ID | Requirement | State | Code evidence | Green test | Closes in |
|---|---|---|---|---|---|
| I1 | V2 §9/§10 contracts of record; sequencing superseded by 0001 + 0002 | N/A (process, not code) | 0002 §3 row; this matrix keeps them traceable | — (docs) | W7-01 (this slice) |

## J. Testing + field protocol (V2 §11, acceptance A1–A8)

| ID | Requirement | State | Code evidence | Green test (recorded) | Closes in |
|---|---|---|---|---|---|
| J1 | Unit layers (HeadingMath/PDR/Segmenter/TurnDetector/Navigator/BundleCodec/QrPayload/Merger/JSON; worker samParse/selectTrack/schema/fallback) | Works (headless) | test files under `shared/src/commonTest/` (nav, capture, bundle, transfer, ai, model) + `worker/src/*.test.ts` | suites cited per row above (up to 287/287 shared, 96/96 worker merged-main) | W7-02 (re-green present tree) |
| J2 | Field protocol (5 SENSOR_ONLY + 5 ECHO runs, airplane B, tape markers, CSV report) | **Never executed** | `docs/measurements.md` (no field runs recorded) | none | W9-06 |
| J3 | A1–A8 acceptance mapping (perception, path fidelity, offline import, replay, wrong-start, uncertainty, export choice, setup guide) | A2/A5/A6/A7 unit-proven; A1/A3/A4/A8 **Unverified** end-to-end (A3/A4 need airplane two-phone; A8 needs W4-06 README/APK, still Queued) | Navigator wrong-start rule (`Navigator.kt:36`); W3-08 export toggles (`TransferService.kt:158-165`) | `NavigatorTest`, export-selection tests | W9-04, W9-06, W9-12 |

## K. Risks + cut line (V2 §12)

| ID | Requirement | State | Code evidence | Green test | Closes in |
|---|---|---|---|---|---|
| K1 | Fallbacks mostly implemented; "never cut" core was cut by B2 in practice | **Partial** (fallbacks exist; loop unreachable) | E4/D4/G-fallbacks (relay/file/box-only/manual); B2 witness `RouteDetailScreen.kt` | fallback unit tests (relay/box-only/image) | 0002 §4 cut order restates it (no slice) |

## L. iOS port (V2 §13)

| ID | Requirement | State | Code evidence | Green test | Closes in |
|---|---|---|---|---|---|
| L1 | iOS actuals (CoreMotion/AVFoundation/Ktor-or-NWListener/QR/CoreHaptics/NSDocumentDirectory) | Out of scope (W5-03 Accepted/postponed); shared code must not rot | 0001 W5-03 Accepted; iOS compile gates pass (`compileKotlinIosArm64`) | iOS compile gates (no iOS tests claimed) | — (0002 §4) |

## M. 0001 residuals (ten markers + new risks; 0006 of record)

| ID | Marker | Disposition | Evidence / owner |
|---|---|---|---|
| M1 | W2-01 draw gesture device-unverified | Headless coverage added (W6-02 pure `cropBoxFromRect` + W6-03 `DrawOverlayUiTest` — **red on this tree, B3**); feel stays device | W7-02 (re-green), W9-04 (feel) |
| M2 | W2-06 KeepScreenOn/online chip/haptic feel | UI tests added in W6-03 (red on this tree); haptic feel → W9-04 | W7-02, W9-04 |
| M3 | W4-03 device abuse pass (kill/deny/airplane) | VM/permission paths covered (`RecoveryAdvisorTest`, permission-gate UI test); hardware pass open | W9-05 |
| M4 | W2-04 two-phone transfer + ML Kit scan | Open, unchanged | W9-04 |
| M5 | W4-02 calibrated walk (two demo phones) | Open, unchanged | W9-06 |
| M6 | W5-04 on-watch delivery (no paired watch) | Open; Wear seam orphaned from this build (M7) | W9-08 |
| M7 | W3-02/W3-04 production deploy blocked (R2 perms) | Still blocked at plan writing; remote mutation approved for close-out | W9-01 (was 0002 W6-04 scope) |
| M8 | W3-05 live SAM (was: no key) | Closed-with-evidence: live synthetic AI_GROUNDED 693 ms / 0.9298 (review 0003) | — (real-clip proof → W9-02) |
| M9 | W4-01 CSV export UI gap | Closed-with-evidence: W6-01 `6c523a8` (`SettingsViewModelTest`) | — |
| M10 | W2-06 on-device replay-to-ARRIVED | Open | W9-04 |
| M11 | NR-1 stub-`R` harness (S3 accepted-with-mitigation) | Open-with-owner; failure mode loud (`NoClassDefFoundError` — the exact B3 symptom) | revisit if AGP ships `androidHostTest` resource pipeline |
| M12 | NR-2 Robolectric SDK pin 36 vs compileSdk 37 (S3) | Open-with-owner | raise pin when Robolectric supports 37 |

## N. 0002 review findings + prior-review verdicts (nothing quietly dropped)

| ID | Finding | State at plan writing | Closes in |
|---|---|---|---|
| N1 | B1 Home debug menu; B2 dead-end loop; B4 unreachable cloud; B5 dead relay | All open, each with owning slice | W7-03, W7-04, W7-07/W9-01, W9-03 |
| N2 | B3 red gate (6 failed) + worker deps | Open | W7-02 |
| N3 | M1 no Cosmos; M5 flat Settings; m1 oversized Review/buried QR/small scanner; m2 font/splash/`Placeholder.kt` | Open | W8-01…W8-12, W9-11, W7-03 |
| N4 | M2 copy; M3 handoff maze; M4 trial leak; M8 repo truth drift (README/W6-02 tracker/CI/signing) | Open | W7-06, W7-05, W8-10/W8-11, W7-11/W9-10/W9-12 |
| N5 | 0002 W6-02 code landed (`1c51afa`) but tracker stale; W6-03 red (B3); W6-04 not started | Tracker hygiene + gate | W7-11 (truth), W7-02 (gate), W9-01/W9-02 (deploy/measure) |
| N6 | Reviews 0001–0005 verdicts: every BLOCK was fixed on its slice branch with a named fix commit and merged-main green re-run (e.g. `573224f`/`784975c`/`158713d`/`c439e63`/`e75e05e`/`29376d4`/`c3de128`/`78b56c09`), each carrying explicit residuals — all residuals appear above as M1–M12 or H-rows | Closed-with-evidence except the residuals listed | — (audit only) |

## What this matrix does NOT claim

- No on-device, live-worker, or two-phone result is claimed anywhere
  above; every such cell says Unverified/Broken and names a W9 owner.
- No Works cell was re-proven here; each cites the slice + test that
  recorded it green. Re-proof of the present tree is W7-02's job.
- S-reviewer check: every §3 row traces to ≥1 matrix row (§3 trace
  table); every 0001 residual marker (ten + NR-1/NR-2) has a disposition
  (M1–M12); every 0002 B/M finding has an owner (N1–N5); V2 §9/§10 and
  §13 are explicitly dispositioned (I1, L1), not silently dropped.
