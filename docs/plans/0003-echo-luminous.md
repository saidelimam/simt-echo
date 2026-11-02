# Plan 0003 — ECHO Luminous: Living Instrument, Budgeted SAM, Local Perception (Plan 3)

Status: **queued** · created 2026-11-02

Owning repository: `simt-echo` (this repo) — fully enclosed, as in 0001/0002.
`simt-app` and `simt-space` stay read-only quarries; nothing is linked.
Predecessors: `docs/plans/0001-agentic-flow.md` (MVP slices),
`docs/plans/0002-mvp-cosmos-design.md` (32 Complete; W9-04/05/06/12 Queued),
spec of record `docs/research/SIMT_ECHO_IMPLEMENTATION_PLAN_V2.md` (*V2 §n*),
`docs/research/cosmos-echo-freeze.md` (*Freeze §n*), `docs/measurements.md`.

0001 made it work, 0002 made it designed and true. **This plan makes it
extraordinary**: an app that feels alive in the hand, sees on the phone,
spends cloud money like it is scarce, and navigates with a real
probabilistic engine instead of a linear guess. It is ECHO 2.0.

Slice ids continue at **W10–W14**. W0–W9 are spent (W6 lives in
`docs/reviews/0006-residual-risks.md`), so the registered pattern in
`simt.json` widens from `^W[0-9]-[0-9]{2}$` to `^W[0-9]{1,2}-[0-9]{2}$`
with this plan; every earlier id still matches.

Relationship to 0002: MVP v1 ends at W9-12 and keeps its own gate. W10–W13
may start now — they touch no file that 0002's queued device slices need.
W14-05 (2.0 release) depends on W9-12. W14-03 re-runs the V2 §11 protocol
on the new navigator as an A/B against v1, so 0002's field evidence becomes
2.0's baseline instead of being repeated.

## 0. North star — one walk in ECHO 2.0

1. **Create.** The void breathes; a slow aurora tilts with the phone. The
   creator taps Record. Each footstep sends a faint ripple along the
   viewfinder's edge; a luminous trail ribbon draws itself under the
   preview; at a turn a node *blooms* with a soft haptic. Gold corner
   brackets flick when the phone captures an unusually sharp, novel view —
   it is curating the best frames on-device, and nothing has left it.
2. **Review.** Finish lands in a living route story: a scrubbable film
   strip of the best views by distance, linked to the minimap. Route
   Health says "Leg 3: 22 m with nothing to see — add a landmark?". With
   AI opted in, a constellation lights up checkpoint by checkpoint as the
   worker streams results; SAM masks trace their own outlines. The result
   lands in under 45 s using about one seventh of v1's SAM frame budget.
3. **Share.** The QR wears the route's generative **sigil**; bytes flow
   between the two phone glyphs as particles at the real transfer rate;
   on the receiver the route *unfolds* from start to destination.
4. **Follow (airplane mode).** The arrow morphs into the coming turn. The
   screen edge on the turning side warms as the turn nears while a haptic
   heartbeat quickens; with headphones, a soft earcon pans toward the
   turn. The phone recognises the red pillar from its on-device
   fingerprint — "That's the red pillar?" — and the uncertainty aura
   around the arrow visibly tightens. Lost? **Relock** finds the follower
   in seconds instead of sending them back to the start. The lock screen
   carries a Live Update; the watch taps the wrist.
5. **Arrive.** A ripple bloom, honest numbers, and "Share this ECHO".

### Design principles (binding on every W13 slice)

- **Honest light.** Every glow, aura, particle and counter is driven by a
  real signal (posterior σ, byte progress, SSE event, step event). No
  decorative fake progress, ever.
- **Echo the body.** Motion syncs to steps and heading, not to timers.
- **Glance, don't read.** The walking UI reads in 300 ms: edge, arrow,
  one number.
- **Calm by default.** Ambient motion fades while walking straight; it
  peaks only at decisions.
- **Local first, cloud on purpose.** Perception runs on the phone; the
  cloud gets the fewest, sharpest frames, only after consent.
- **Cosmos is the floor, not the ceiling.** Contrast pairs, focus, a11y
  and status-never-by-hue stay non-negotiable; expressive motion, shaders
  and new motifs are added on top (W10-07 records each deviation).

## 1. Review verdict and evidence (why this plan exists)

Verdict on the app *as a 2.0 product*: **BLOCK** (ADR-008). As MVP v1,
0002 stands. Findings verified by reading the tree at `28dacf8` on
2026-11-02 (paths: `S/` = `shared/src/commonMain/kotlin/app/simt/echo`,
`A/` = `shared/src/androidMain/kotlin/app/simt/echo`).

| # | Severity | Finding (evidence) | Closed by |
|---|---|---|---|
| G1 | BLOCK | **Frame decoding runs on the main thread.** `AndroidMediaTools` never switches dispatcher; `ensureKeyframes` is called from `viewModelScope` (`ProcessingViewModel.kt:131`), and each frame opens a new `MediaMetadataRetriever` with `OPTION_CLOSEST` (`A/media/AndroidMediaTools.kt:31-81`). | W10-02 |
| G2 | BLOCK | **IMU callbacks land on the main thread** — `registerListener` without a `Handler` (`A/platform/AndroidImuSource.kt:83`). Step detection competes with Compose for the same looper. | W10-02 |
| G3 | MAJOR | **Whole files read to answer "does it exist?"** — `readDraftFile(VIDEO_FILE)` loads all of `video.mp4` (`S/media/KeyframeFiles.kt:36`); photo counting reads every keyframe (`ProcessingViewModel.kt:134-138`). | W10-02 |
| G4 | MAJOR | **Every camera screen binds the full recorder** (Preview + ImageCapture + VideoCapture) — Align and Navigate included (`AlignScreen.kt:85`, `NavigateScreen.kt:321`); `startVideo` busy-waits up to 1 s (`CameraXRecorder.kt:187-190`); no `ImageAnalysis` exists outside QR. | W10-03 |
| G5 | BLOCK | **SAM is fed the most expensive input possible.** Whole 5 s HD clips (no downscale; `setFrameRate(10)` at `AndroidMediaTools.kt:133` targets image inputs and is believed inert on video — W11-03 verifies), re-cut on every Send, end never clamped to duration (`ProcessingViewModel.kt:301-312`). | W11-01…W11-03 |
| G6 | BLOCK | **Each clip is re-encoded and re-sent per candidate**, one phrase per call (`worker/src/ground.ts:174,183-190`), by a string-concatenating `toBase64` (`ground.ts:84-97`, `scout.ts:559`). ≈2N video + up to 2N image SAM calls per route. | W11-06, W11-07 |
| G7 | BLOCK | **One retry re-bills everything**: ground (and scout) is a single Workflow step (`pipeline.ts:36-38`); saved `sam/*.txt` evidence is never read back; no content-hash cache anywhere. | W11-06 |
| G8 | MAJOR | **No budget is enforced** — `samFrames` is reported, never capped (`ground.ts:203`); the only cost control is a 30 req/min rate limit (`index.ts:75-83`). | W11-06 |
| G9 | MAJOR | **Meta calls can hang** — no `AbortSignal`/timeout around `fetch` (`worker/src/meta.ts`). | W11-06 |
| G10 | MAJOR | **Upload is all-or-nothing in RAM under a 20 s request timeout** (`A/platform/HttpClients.android.kt:9-10`, `EchoApiClient.kt:131-168`); retry deletes the job and resends everything; no idempotency. | W11-05, W11-08 |
| G11 | MINOR | **Polling is fixed 2 s × 150**, persisting `job.json` on every poll (`ProcessingViewModel.kt:94-96,254-292`). | W11-08 |
| G12 | MAJOR | **Turn haptics never fire.** `LEFT`/`RIGHT`/`U_TURN` exist (`S/platform/Haptics.kt:7-9`) but `Navigator` emits only TICK/CONFIRM/WARN/ARRIVE (`Navigator.kt:160,245,268,275,282`) — V2 §8.4's turn language is dead code. | W10-08 |
| G13 | MAJOR | **Navigation is a linear guess.** σ = 0.5 + 0.08·d capped at 6 m (`Navigator.kt:97`); no fusion, no relocalisation, barometer unused while navigating; the camera runs in Navigate and no frame is analysed. | W12-01…W12-08 |
| G14 | MAJOR | **The UI is designed but not alive.** No shaders, no shared elements, no `graphicsLayer`, no `animate*AsState`; the atmosphere's parallax hook has no caller (`CosmosBackground.kt:60,187`); Processing is a bare `LinearProgressIndicator` (`ProcessingScreen.kt:234,247`). | W13-01…W13-11 |
| G15 | MINOR | **Wear is a haptic mirror only** — no watch UI, tile or ongoing activity (`wear/`). | W13-12 |
| G16 | MINOR | Inherited audit residuals R1 (LAN token RNG not CSPRNG, `TransferService.kt:37,67`) and R3 (expiry purge at process start only). | W14-04 |

**Residual risk of this review:** code-read only; no device driven, no
live SAM call. Third-party SAM 3.1 pricing (fal.ai lists $0.01 per 16
video frames) is an estimate — W10-01 records Meta's real billing.

Acceptance for this document itself:

- [x] Exists at `docs/plans/0003-echo-luminous.md`, under 1000 lines; the
      `simt.json` id pattern is widened so `simt plan status` reads the
      tracker (verified 2026-11-02).
- [x] Every G-finding names a closing slice in §6.
- [x] Every new contract (bundle v2, worker API v1.1, Luminous freeze) has
      an **S**-owned freeze slice ordered before its consumers, producer
      before consumer (test-gate cross-repo order).
- [x] Every numeric budget in §4 names the slice that measures it.

## 2. Model tiers (L · F · T · S) + the **V** reviewer

Tiers L/F/T/S are unchanged from 0002 §2 (capability classes, cheapest
tier that can finish, escalation after two failed runs on one cause or
immediately on contract/security/privacy/geometry). This plan adds one
**review role**, because 2.0 is judged by eyes and fingers as much as by
assertions:

| Role | What it is | Rules |
|---|---|---|
| **V** — Vision reviewer (new) | A multimodal model that reviews **screenshots, sampled screen-recording frames and Perfetto/JankStats summaries** against the Luminous freeze (W10-07): hierarchy, motion intent per frame, legibility at fontScale 2.0, dark/light parity, "honest light" (is every animated element bound to a real signal?) | Reviewer only — never primary, never decides correctness, privacy or geometry. Mandatory on every W13 slice. Evidence: frames under `docs/screenshots/<slice>/` with timestamps and build SHA. A V `BLOCK` reopens the slice like any other |

Binding additions:

- **Frontier tokens go to:** W10-05/W10-06/W10-07 (freezes), W12-01/W12-02
  (geometry), W14-04 (privacy), W14-05 (release). The particle filter and
  the budget governor are the two places where a subtle error silently
  costs users trust or money.
- **Evidence types** (a slice names which it owes): *unit* (named test
  command), *perf* (macrobenchmark JSON or JankStats summary, device +
  SHA), *cost* (SAM frames billed from `response.completed`, job id),
  *device* (model, SHA, route, outcome), *visual* (V-reviewed frames).
- **One agent, one slice, one worktree** (`simt slice start/finish`,
  `simt plan done <id> <sha> --test "<cmd>"`); §7 names sole owners.

## 3. Pillars and coverage

| Pillar | Today | 2.0 | Slices |
|---|---|---|---|
| **P1 Perception substrate** — the phone watches its own camera cheaply | No frame analysis; keyframes by fixed timestamps from HD video | A throttled Frame Tap on its own thread curates the sharpest, most novel frame per 0.5 m; keyframes and SAM clips come from that ledger, not from seeking video | W10-02, W10-03, W11-01, W11-02 |
| **P2 SAM Lens** — budgeted, cached, streamed grounding | ≈2N whole-HD-clip calls, single retry-everything step, no cap, 2 s polling | 12-frame 480p approach strips, image-first escalation ladder, content-hash cache, per-call Workflow steps, hard frame budget, SSE progress, resumable upload, optional ready-on-arrival | W10-06, W11-03…W11-10 |
| **P3 Ghost Navigator** — probabilistic, self-calibrating, self-healing | Rule state machine on a linear σ | Along-route particle filter fusing steps, heading quality, turns, barometer, magnetic signature and visual fingerprints; learned stride; Relock | W10-08, W12-01…W12-08 |
| **P4 Luminous experience** — alive, glanceable, multisensory | Static Cosmos skin, few springs, waveform-only haptics | AGSL echo field, step ripples, morphing arrow, σ aura, periphery glow, route sigils, constellation, unfold, haptic + sonic language, Live Updates, Wear | W10-07, W13-01…W13-12 |
| **P5 Proof** — measured, not claimed | 0002 gates (unit, a11y, one live envelope) | Perf budgets, a11y + motion parity v2, field A/B v1 vs Ghost, privacy audit v2, release gate | W10-01, W10-04, W14-01…W14-05 |

V2 contracts stay of record except where an S freeze here amends them
additively (bundle v2, worker API v1.1). V2 §6.4 phase semantics are kept;
only the *source* of progress and σ changes (W12-02).

## 4. Scope, budgets, cut order

**ECHO 2.0 is done when** the §0 walk runs on two signed phones from a
fresh install, every §4 budget is met and recorded on named hardware, the
field A/B shows Ghost ≥ v1 (or Ghost ships flag-off and says so), and
every README claim traces to a slice SHA or a recorded run.

Out of scope (do not re-litigate inside slices): ARCore/VIO (exclusive
camera contends with recording — 0001 §4 verdict stands); Wi-Fi RTT or
scan fingerprinting (scan throttling makes it unreliable on stock
Android); streaming live preview to SAM during capture (cost and
privacy — on-device curation replaces it); iOS; accounts; 3D
reconstruction; any cloud call during navigation (`nav/` stays HTTP-free).

Numeric budgets (declared per repo per ADR-006; W10-04 writes them into
`docs/perf.md` as named manual gates — macrobenchmarks need hardware, so
they are not pre-commit gates, per ADR-017):

| Budget | v1 today | 2.0 target | Measured by |
|---|---|---|---|
| SAM billed frames / 60 m route (6–8 checkpoints) | design ≤ 800 (16 × ≥ 50, V2 §7.4) | **≤ 120**, hard cap 240 server-side | W11-10 |
| SAM calls / route; calls on retry | ≈2N video + ≤2N image; all re-billed | ≤ N video + ≤ 2N image; **0 re-billed** (cache) | W11-06, W11-10 |
| Upload bytes / route | 7 HD clips + ~21 keyframes | **≤ 2 MB** total | W11-03 |
| Result latency after Finish | 66 s (W9-02, scout only) | **≤ 45 s**; ≤ 15 s with ready-on-arrival | W11-10 |
| Record jank with Frame Tap + shaders | unmeasured | **≤ +1 pp janky frames vs W10-01 baseline; 0 preview stalls** | W14-01 |
| Frame Tap cost | n/a | **≤ 6 ms median** per analysed frame, ≤ 8 Hz, thermal-adaptive | W10-03 |
| Ghost filter update | n/a | **≤ 1 ms** per step event (256 particles, mid device) | W12-02 |
| Navigate battery, 10 min, screen on | unmeasured | **≤ 6 %** on the mid device | W14-01 |
| Job status requests / job | up to 150 polls | **1 stream** (+ ≤ 5 fallback polls) | W11-08 |
| Visual match precision | n/a | **≥ 0.95** at the chosen τ on the recorded pair set | W12-05 |

Cut order (never improvise): W13-12 Wear → W11-09 ready-on-arrival →
W12-04 magnetic → W13-10 Live Updates → W13-09 sonic half (keep haptics)
→ W13-01 shader (ship the Canvas aurora) → W12-05 fingerprints (Relock
falls back to turn signature only). **Never cut:** W10-02, W10-03,
W10-08, W11-03, W11-06, W11-08, W11-10, W12-02 (may ship flag-off),
W13-04, W13-08, W14-01, W14-02, W14-05.

## 5. Technology map (adopt · why · fallback)

| Need | Technology | Verdict | Fallback |
|---|---|---|---|
| Frame Tap | CameraX `ImageAnalysis` (1.6.2, already in catalog), `YUV_420_888`, `STRATEGY_KEEP_ONLY_LATEST`, `ResolutionSelector` ~960×720 4:3, dedicated single-thread executor, pooled buffers | **Adopt.** Record binds Preview + Video + Analysis and **drops ImageCapture**: the start photo is the ledger's best frame at d≈0 | Preview + Video (today's fallback), ledger off, keyframes from video as today; `capture.json` records `perceptionMode` |
| Frame quality | Pure Kotlin on 4×-decimated luma: Laplacian variance, exposure band, dHash-64, angular rate from the rotation vector at the frame timestamp | **Write new** (commonMain, allocation-free) | — |
| Clip forge | Media3 Transformer **image-sequence input** (frame rate applies to images) → H.264, 480 p short side | **Adopt** | Video transcode with `Presentation.createForHeight(480)` + `FrameDropEffect` |
| Visual fingerprints | LiteRT interpreter (plain LiteRT, not LiteRT-LM) with an int8 image embedder: MobileNetV3-small class (≤ 4 MB) or a distilled DINOv2-small VPR head if ≤ 8 MB and ≤ 15 ms (D²-VPR/MixVPR line of work) — W12-05 benchmarks and picks | **Adopt one, measured** | dHash + 4×4 colour-moment fingerprint at low filter weight |
| Face privacy pre-filter | ML Kit face detection (bundled model) on strip frames before upload | **Adopt** | Scout `privacyRisk` only (today) |
| Particle filter, DTW, stride learning | Own deterministic Kotlin in commonMain (seeded RNG for tests) | **Write new** | v1 `Navigator` path behind `EchoConfig.navigatorEngine` |
| Echo field atmosphere | AGSL `RuntimeShader` via Compose `ShaderBrush` (API 33+); created once and `remember`ed; uniforms only per frame | **Adopt** | Today's Canvas `CosmosBackground` for API 29–32 and Static tier |
| Morphing instruments | `androidx.graphics:graphics-shapes` (`RoundedPolygon`, `Morph`) | **Adopt** (androidMain if no KMP artefact; iOS compile gate stays green via expect/actual) | Rotating chevron (today) |
| Transitions | Compose `SharedTransitionLayout`, predictive back, spring motion tokens | **Adopt** | Fade/slide (today) |
| Haptic language | `VibrationEffect.Composition` primitives (API 30+, `areAllPrimitivesSupported`) | **Adopt** | V2 §8.4 waveforms |
| Sonic cues | Synthesised PCM earcons via `AudioTrack` with stereo pan; `Spatializer` when head-tracked (API 32+) | **Adopt, opt-in** | Off |
| Lock-screen guidance | Android 16 `Notification.ProgressStyle` (segments = legs, points = landmarks) | **Adopt** | Standard ongoing notification |
| Worker grounding | Workflow step per (checkpoint, phrase), `AbortSignal.timeout`, `Buffer` base64 (`nodejs_compat`), R2 content-hash cache, SSE job events | **Write new** in `worker/` | Today's single step (kept behind `GROUND_MODE=legacy` until W11-10 passes) |
| Perf proof | `androidx.benchmark` macrobenchmark + Baseline Profile + JankStats | **Adopt** | JankStats-only summaries |
| Wear guidance | Compose for Wear OS, Ongoing Activity, Tiles | **Adopt** | Haptic mirror (today) |
| On-device interactive segmentation (tap-to-mask in "Draw my own") | EdgeTAM-class model | **Not adopted in 2.0** — model size and chipset support unproven; recorded for a future plan | Manual draw (today) |

## 6. Tracker

Evidence naming as 0002 §6: `./gradlew :shared:testAndroidHostTest --tests
"<class>"`, `./gradlew :androidApp:assembleDebug`,
`./gradlew :shared:compileKotlinIosArm64 :shared:compileKotlinIosSimulatorArm64`,
worker `npm ci && npm test -- <file>` in `worker/`, plus §2 evidence types.
No slice is Complete on a partial pass or on unit evidence for a device claim.

| ID | State | Slice | Tier | Lane | Depends on | Evidence |
|---|---|---|---|---|---|---|
| W10-01 | Complete | 2.0 baselines: jank, battery, media timings, SAM billing | T | F | — | c6018e6718ceb974a19557cbb9d38333f99d4785 |
| W10-02 | Complete | Off-main-thread media & sensors; no whole-file reads | F (T) | B | — | 95d36be396e0d8927dde87ef40c94354220279b8 |
| W10-03 | Complete | Camera graph per screen + Frame Tap seam | T (S) | B | W10-02 | 152bea0f8c5663656fe3651099c8bbdb5b6e1036 |
| W10-04 | Complete | Perf harness: macrobenchmark, Baseline Profile, JankStats | F (T) | F | W10-01 | 10d6a37750c2587f4d4f44a0c1cc31315b65e33c |
| W10-05 | Complete | Bundle v2 contract freeze (perception, sigil, compat) | S | A | — | 0970cb16058de7faeb17a16e440f8df91c339074 |
| W10-06 | Complete | Worker API v1.1 freeze (uploads, budget, events, partials) | S | D | — | bc0fd7f0232c2ac73c9e341eab33c5e29558de60 |
| W10-07 | Complete | Luminous design freeze (motion, motifs, haptic/sonic grammar) | S (V) | E | — | 88d2a75 · test: ./gradlew :shared:testAndroidHostTest --tests "app.simt.echo.ui.brand.EchoMarkTest" --tests "app.simt.echo.ui.shell.EchoShellTest" |
| W10-08 | Complete | Turn haptics emitted (LEFT/RIGHT/U_TURN) | T (S) | C | — | 3d4cb54584a6f3712368799dcbb1d67ce8852e42 · test: ./gradlew :shared:testAndroidHostTest --tests "app.simt.echo.nav.NavigatorTest" --tests "app.simt.echo.ui.screens.navigate.NavigateViewModelTest" |
| W11-01 | Complete | Glimpse ledger: on-device frame quality per 0.5 m | L (T) | B | W10-03 | 5919d24ef85a89bacc523b4e52adfd771fe27b8f |
| W11-02 | Complete | Approach strips + ledger keyframes (no video seeking) | T (S) | B | W11-01 | 0d4985cebc70e9c90bdacf858e1ff35627f87bb3 · test: ./gradlew :shared:testAndroidHostTest --tests "app.simt.echo.capture.ApproachStripTest" --tests "app.simt.echo.media.MediaToolsTest" |
| W11-03 | Complete | Clip forge: 12-frame 480 p strips, exact frame↔distance map | F (T) | B | W11-02 | f4b0699a9c9352861dbdf66a00a517f930c98857 |
| W11-04 | Complete | On-device privacy pre-filter (faces, blur) before upload | T (S) | B | W11-02 | 6bfb21577923e1beb440f22ec82c9eea9a5adea9 · test: ./gradlew :shared:testAndroidHostTest --tests "app.simt.echo.capture.FacePrivacyTest" --tests "app.simt.echo.media.ManifestFrameFlagsTest" |
| W11-05 | Complete | Worker intake v1.1: content-addressed resumable uploads | F (S) | D | W10-06 | b74143be9eb7030b20667f25fe1e47397bdb0faa |
| W11-06 | Complete | Worker SAM Lens: per-call steps, cache, governor, ladder | T (S) | D | W10-06, W11-07 | 539e885070fb40c75f860523d416f7d52784aa9e |
| W11-07 | Complete | SAM live probe: multi-phrase, strip clips, SSE hardening | T (S) | D | W10-06 | d7c988df1727df6ca86f48e64ec7b92f3826e62c |
| W11-08 | Complete | App client v1.1: resumable upload, event stream, partials | F (T) | D | W11-03, W11-05, W11-06 | 9f205d978ca5108c836ef0ad4941c7bd0e318896 · test: ./gradlew :shared:testAndroidHostTest --tests "app.simt.echo.transfer.EchoApiClientTest" --tests "app.simt.echo.ui.screens.processing.ProcessingViewModelTest" |
| W11-09 | Complete | Ready-on-arrival: opt-in upload while walking (P1) | T (S) | D | W11-08, W11-04 | 8c9b2fc8f0bf2c9e03accdada329427fbdf2ac58 |
| W11-10 | Queued | Live SAM envelope v2 on a walked route | T (S) | F | W11-08, W11-04 | — |
| W12-01 | Complete | Heading quality: stillness, gyro-bias, quality score | S | C | W10-02 | 44cb4fd3fb25ac23b721146a323313827852be13 |
| W12-02 | Complete | Ghost filter: along-route particle filter behind a flag | S | C | W12-01, W10-08, W10-05 | 5fa18caece1cead41ce6a0bc8b27811dac21f2b1 · test: ./gradlew :shared:testAndroidHostTest --tests "app.simt.echo.nav.*" |
| W12-03 | Complete | Self-calibrating stride from anchored legs | T (S) | C | W12-02 | 037139c25ebbb8b8ace828500eb950745570955f · test: ./gradlew :shared:testAndroidHostTest --tests "app.simt.echo.nav.StrideLearnerTest" --tests "app.simt.echo.data.ConfigRepositoryTest" |
| W12-04 | Complete | Magnetic signature capture + DTW observation (P1) | T (S) | C | W12-02 | 065bcee33eaf95f71d6bde9ad2891314d2b09d35 |
| W12-05 | Complete | Visual fingerprints: capture, match, "That's it?" | T (S) | C | W12-02, W11-02 | 3790205bb6e17e6b530a96b61678f88d56d5d3bc |
| W12-06 | Complete | Lost → Relock (global re-spread, turn-signature DTW) | T (S) | C | W12-05 | e3a160725f0a1eb7ae31aadd5c881d8fb4110834 |
| W12-07 | Complete | Route Health score + nudges (pure) | L (T) | C | W10-05 | 75e44eaa179938655397ebf919ac3bbe2f7061f3 |
| W12-08 | Complete | Live level awareness (barometer, stairs vs lift) | T (S) | C | W12-02 | 83993aea57a0880855773ad68c7bccc56df1874f · test: ./gradlew :shared:testAndroidHostTest --tests "app.simt.echo.nav.LevelAwarenessTest" --tests "app.simt.echo.nav.NavigatorTest" |
| W13-01 | Complete | Echo field: AGSL atmosphere, tilt parallax, ripple uniforms | T (V) | E | W10-07 | 6c2daf8 · test: ./gradlew :shared:testAndroidHostTest --tests "app.simt.echo.ui.atmosphere.*" --tests "app.simt.echo.ui.shell.EchoShellTest" --tests "app.simt.echo.ui.components.*" |
| W13-02 | Complete | Motion system: springs, shared elements, predictive back, morph kit | F (T, V) | A | W10-07 | 4fc3fe3 · test: ./gradlew :shared:testAndroidHostTest --tests "app.simt.echo.ui.motion.*" --tests "app.simt.echo.ui.shell.*" --tests "app.simt.echo.ui.screens.*" |
| W13-03 | Complete | Route sigils: generative glyph from route geometry | T (V) | E | W10-05, W10-07 | 9178aad · test: ./gradlew :shared:testAndroidHostTest --tests "app.simt.echo.ui.sigil.*" --tests "app.simt.echo.ui.screens.*" |
| W13-04 | Complete | Record "Echo walk": ripples, trail ribbon, blooms, brackets | T (V) | B | W13-01, W13-02, W11-01 | 26d2f4f292cc24e9ac6f0b52ef8c95315f3c0373 · test: ./gradlew :shared:testAndroidHostTest --tests "app.simt.echo.ui.screens.record.HostRecordEchoWalkTest" --tests "app.simt.echo.ui.screens.record.HostRecordInstrumentTest" --tests "app.simt.echo.capture.GlimpseLedgerTest" |
| W13-05 | Complete | Review "Route story": film strip, mask trace, health | T (S, V) | E | W13-02, W11-02, W12-07, W11-08 | c760581d626d4f626a3991dc877efe54e9a0bf79 |
| W13-06 | Complete | Processing "Constellation" from live events | F (T, V) | E | W13-02, W11-08 | 134720be05978b3f8dea5fd033e9299c92e27c60 · test: ./gradlew :shared:testAndroidHostTest --tests "app.simt.echo.ui.screens.processing.ProcessingViewModelTest" --tests "app.simt.echo.ui.screens.processing.ProcessingConstellationTest" --tests "app.simt.echo.ui.screens.ProcessingShareReceiveCosmosTest" --tests "app.simt.echo.transfer.EchoApiClientTest" |
| W13-07 | Complete | Share/Receive "Handshake": sigil QR, beacon, unfold (flow split to W13-14) | F (T, V) | E | W13-02, W13-03 | e73ba91 · test: ./gradlew :shared:testAndroidHostTest --tests "app.simt.echo.ui.screens.share.*" --tests "app.simt.echo.ui.screens.receive.*" --tests "app.simt.echo.ui.sigil.*" |
| W13-08 | Complete | Navigate instrument 2.0: morph arrow, σ aura, periphery glow | T (S, V) | E | W13-02, W12-02, W12-05 | 6f56d53a2ce82e2e7309a2d4f3806535be437f96 |
| W13-09 | Complete | Haptic & sonic language, eyes-free mode | T (V) | E | W10-08, W10-07 | b9fe372be2739f3e523778fda411525d16256ef7 |
| W13-10 | Complete | Live Updates on lock screen (navigation, processing) | F (S) | A | W13-08 | d62de6bad3b0865e1fd70200cc615ceaceadd9f4 |
| W13-11 | Complete | Home sigil records, Arrived bloom (tour split to W13-13) | F (T, V) | E | W13-03, W13-02 | 263fbed · test: ./gradlew :shared:testAndroidHostTest --tests "app.simt.echo.ui.motion.*" --tests "app.simt.echo.ui.screens.*" --tests "app.simt.echo.ui.components.*" |
| W13-12 | Complete | Wear guidance: ongoing activity + tile (P1) | F (T) | F | W13-08 | 86ebb11fe921584e218f648a7e93cf5d0c18c88e · test: :wear:testDebugUnitTest (EchoNavCodecTest 7/7, EchoWearHapticsTest 2/2); :shared:testAndroidHostTest --tests app.simt.echo.nav.WearNavSyncTest (16/16) --tests app.simt.echo.ui.screens.navigate.NavigateViewModelTest (8/8) --tests app.simt.echo.platform.WearHapticsTest (6/6); :wear:assembleDebug + :shared:compileKotlinIosSimulatorArm64 BUILD SUCCESSFUL |
| W13-13 | Complete | Guided practice tour through the real Navigate instrument | T (S, V) | E | W13-08, W13-11 | ca147af3ac665556f57d4f692a7d1ca833d02238 · test: ./gradlew :shared:testAndroidHostTest --tests "app.simt.echo.ui.screens.navigate.PracticeTourTest" --tests "app.simt.echo.ui.screens.PracticeTourUiTest" --tests "app.simt.echo.ui.screens.HomeScreenTest" --tests "app.simt.echo.ui.screens.HostSimulateJourneyTest" |
| W13-14 | Complete | Transfer progress seam + real-rate particle flow | T (S, V) | C | W13-07 | fdd7e9e · test: ./gradlew :shared:testAndroidHostTest --tests "app.simt.echo.transfer.TransferServiceTest" --tests "app.simt.echo.transfer.RelayClientTest" --tests "app.simt.echo.ui.screens.share.ShareViewModelTest" --tests "app.simt.echo.ui.screens.receive.ReceiveViewModelTest" --tests "app.simt.echo.ui.components.TransferParticlesTest" |
| W14-01 | Queued | Performance gate on two devices | T | F | W10-04, W13-04, W13-08, W12-05 | — |
| W14-02 | Queued | Accessibility & motion parity gate v2 | T (V) | F | W13-04…W13-11 | — |
| W14-03 | Queued | Field A/B: v1 vs Ghost on the V2 §11 protocol | T (S) | F | W12-06, W12-08, W13-08 | — |
| W14-04 | Complete | Security & privacy audit v2 | S | D | W11-09, W12-05, W12-04 | 8c9416c786df0853a050d188a07f0a309d81fe71 |
| W14-05 | Queued | ECHO 2.0 release gate | S | F | W9-12, W11-10, W14-01…W14-04 | — |

Tier totals: **L 2 · F 11 · T 25 · S 7** (primary; reviewers in
parentheses; V reviews 14 slices; verified mechanically: 45 rows, 0
duplicate ids, 45 `####` slice sections; W13-13 and W13-14 split from
W13-11 and W13-07 on 2026-11-02). Lanes: A — Foundation & contracts;
B — Capture & perception; C — Navigation intelligence; D — Worker & SAM;
E — Experience; F — Proof & release.

Parallel batches (a batch starts when its rows' dependencies are Complete):

| Batch | Slices | Why it is safe |
|---|---|---|
| 10a | W10-01, W10-02, W10-05, W10-06, W10-07, W10-08 | Measurement docs, android media/IMU, domain model, worker `types.ts`, design doc, `Navigator` haptic emission — disjoint files |
| 10b | W10-03, W10-04 | Camera seam after W10-02 (same platform package); benchmark module is new |
| 11a | W11-01, W11-05, W11-07, W12-01, W12-07, W13-01, W13-02, W13-03 | Ledger (capture/), worker intake (jobs/index), SAM probe (meta.ts), heading (PdrEngine), pure health scorer, atmosphere, shell, sigil — disjoint |
| 11b | W11-02, W11-06, W12-02, W13-09 | Strips (capture), governor (ground/pipeline), filter (`nav/ghost/`), haptics/audio (platform) |
| 11c | W11-03, W11-04, W12-03, W12-04, W12-08, W13-04 | Clip forge vs privacy filter touch different files; nav slices serialise on `nav/ghost/` per §7 |
| 12 | W11-08, W12-05, W13-07, W13-11 | Client v1.1, fingerprints, Share/Receive, Home/Arrived |
| 13 | W11-09, W11-10, W12-06, W13-05, W13-06, W13-08 | Review and Navigate screens owned by one slice each |
| 14 | W13-10, W13-12, W13-13, W13-14, W14-01…W14-04, then W14-05 alone | Gates last; release gate alone |

Critical paths (staff these first; an idle S takes the next one, not a leaf):
`W10-02 → W10-03 → W11-01 → W11-02 → W11-03 → W11-08 → W11-10 → W14-01 → W14-05`;
`W10-06 → W11-07 → W11-06 → W11-08`;
`W10-08 + W12-01 → W12-02 → W12-05 → W12-06 → W14-03 → W14-05`;
`W10-07 → W13-02 → W13-08 → W14-02`.

## 7. Shared-file ownership

| File / contract | Sole owner | Consumers wait for |
|---|---|---|
| `A/platform/CameraXRecorder.kt`, new `FrameTap` seam | Lane B: W10-03, then W11-02 by handoff | Seam frozen in W10-03; W12-05/W13-04 consume, never edit |
| `PdrEngine`, `AndroidImuSource`, `HeadingMath` | W10-02 (threading only), then W12-01 (S) | No other slice edits sensor maths |
| `nav/Navigator.kt`, `TurnDetector`, new `nav/ghost/*` | Lane C, serial: W10-08 → W12-02 → W12-03/W12-04/W12-08 (one at a time) → W12-05 → W12-06 | Geometry-touching edits carry an S reviewer; no HTTP import in `nav/` |
| Domain model, `EchoJson`, `BundleCodec` | W10-05 only | v2 fields land only through the freeze |
| `worker/src/types.ts` | W10-06 | Any later shape change is a new S slice |
| `worker/src/meta.ts`, `ground.ts`, `pipeline.ts`, `selection.ts` | W11-07 (meta.ts SSE hardening) → W11-06 | — |
| `worker/src/jobs.ts`, `index.ts` | W11-05 | W11-06 consumes uploads by id |
| `transfer/EchoApiClient.kt`, `ProcessingViewModel` | W10-02 (media calls only) → W11-08 → W11-09 | — |
| `ui/theme/*`, `ui/atmosphere/*`, motion tokens | W10-07 (doc) → W13-01 (atmosphere) and W13-02 (motion), disjoint files | Screens never hard-code a spring, colour or duration |
| `EchoNavHost`, `EchoShell` | W13-02 | Screen slices register routes only |
| Each screen package (`record/`, `review/`, `processing/`, `share/`, `receive/`, `navigate/`, `arrived/`, Home `Screens.kt`) | Exactly one W13 slice each (§6) | Review keeps the 0001 serial-handoff rule |

## 8. Slice sections

Each section: tier rationale, what to build, acceptance. Blast radius is
what is named here; widening it is an escalation (ADR-002).

### Wave 10 — Foundations (make it fast and frozen)

#### W10-01 — 2.0 baselines
**T**, Lane F. Measure before changing: on a named low-end and a named
mid/flagship phone, record JankStats on Record (60 s) and Navigate
(Simulate, 60 s), keyframe/clip preparation time and main-thread stalls
(StrictMode log), Navigate battery over 10 min, and one keyed SAM job's
real `response.completed` billed frames on today's pipeline (if the Meta
key is configured; otherwise recorded as unavailable). Acceptance: a
"2.0 baselines" section in `docs/measurements.md` with device, SHA and
numbers; every §4 "today" cell either filled or marked unmeasurable with
reason.

#### W10-02 — Off-main-thread media and sensors
**F (T review)**, Lane B. Closes G1–G3 without behaviour change.
`AndroidImuSource` registers on a dedicated `HandlerThread("echo-imu")`
and delivers through a buffered channel (steps need every sample — no
conflation). `MediaTools` suspend functions run on `Dispatchers.IO`
(decode) / `Default` (scale); one `MediaMetadataRetriever` per batch;
existence and counting use `FileStore` size/exists, never `readBytes`;
clip end clamped to video duration; cut clips cached per
(draftId, nodeId, window). Remove the `startVideo` busy-wait (suspend on
the Start event with timeout). Acceptance: new host test with a
main-thread-asserting fake proves no `MediaTools` call runs on the main
dispatcher; `ProcessingViewModelTest`, `MediaToolsTest`,
`CaptureSessionTest`, `PdrEngineTest` green; StrictMode run on one device
shows zero main-thread disk reads across Record → Processing (recorded).

#### W10-03 — Camera graph per screen + Frame Tap
**T (S review)**, Lane B. Introduce a commonMain `FrameTap` seam
(`frames: Flow<FrameSample>` with `tNs`, decimated luma view, lazy JPEG
encode of the full frame) and a `CameraGraph` per screen: **Record** =
Preview + Video + Analysis (ImageCapture dropped); **Align** = Preview +
Analysis; **Navigate** = Preview + Analysis with overlay, Analysis-only
otherwise (W12-05 decides when it runs at all). Fallback ladder by real
bind attempt: [P,V,A] → [P,V] with ledger off, recorded in `capture.json`
as `perceptionMode`. Analysis on a single-thread executor, three pooled
buffers, zero per-frame allocation in the hot path; rate governor 8 Hz
(Record) / 2 Hz (Navigate), halved at `THERMAL_STATUS_MODERATE`, off at
`SEVERE`. Timestamps: verify `SENSOR_INFO_TIMESTAMP_SOURCE` is REALTIME
so frames align with IMU `event.timestamp`; if not, record the offset
and correct it. S reviews that recording itself is unchanged. Acceptance:
rate-governor and buffer-pool unit tests; `CaptureSessionTest` untouched
and green; device evidence: Record 60 s with Frame Tap on shows ≤ +1 pp
janky frames vs W10-01 and analysis median ≤ 6 ms.

#### W10-04 — Perf harness
**F (T review)**, Lane F. New `:benchmark` module: macrobenchmarks for
cold start, Home scroll, Record 30 s, Navigate Simulate 60 s;
Baseline Profile generated and shipped; JankStats summaries in debug.
`docs/perf.md` declares the §4 budgets as named manual gates with the
exact commands (they need hardware — not pre-commit gates, ADR-017).
Acceptance: `./gradlew :benchmark:connectedBenchmarkAndroidTest` runs on
one named device and writes JSON (recorded); release build includes the
profile (`assembleRelease` artefact inspected).

#### W10-05 — Bundle v2 contract freeze
**S**, Lane A. Additive, versioned: `EchoRoute.perception:
RoutePerception?` = { `fingerprints`: per node, ≤ 3 × base64 int8[256]
with `embedderId` and projection-seed version; `waypointPrints` every
5 m; `magProfile`: {stepM 0.5, magnitude & vertical µT×10 as int16
arrays}; `sigilSeed`; `health` }. Decide and record: `EchoJson`
unknown-key behaviour, how a v1 app meets a v2 bundle (ignore, or honest
"Update ECHO to open this route" — never a crash), size cap (perception
≤ 64 KB). Fingerprints are image-derived and inherit the bundle's
encryption, expiry and export-preview disclosure. Acceptance:
`ModelJsonTest`/`BundleCodecTest` extended: v1 fixture reads in v2; v2
round-trip; v2→v1 behaviour as decided; size-cap rejection test; iOS
compiles.

#### W10-06 — Worker API v1.1 freeze
**S**, Lane D. Producer contract, additive to the W3-03 freeze:
`POST /v1/uploads` {files: [{name, sha256, bytes}]} → {uploadId,
missing[]}; `PUT /v1/uploads/:uploadId/:name` (sha verified);
`POST /v1/jobs` gains {uploadId, budget: {maxSamFrames ≤ 240},
idempotencyKey} (old multipart kept for v1 clients); `GET
/v1/jobs/:id/events` (SSE: `stage`, `checkpoint` {nodeId, status
scouted|grounded|skipped, compact candidates}, `usage`, `complete`,
`error`; `Last-Event-ID` resume; server holds ≤ 25 s then the client
reconnects) backed by append-only R2 event records (Durable Object only
if R2 tailing fails the latency budget — recorded). Cache scoping
decision for SAM outputs: default **per job, deleted with the job**;
cross-job reuse only via explicit `resumeOf`. Acceptance: `types.ts`
zod schemas + contract tests (`npm test -- src/jobs.test.ts`) for every
new shape, including v1 multipart still accepted.

#### W10-07 — Luminous design freeze
**S (V review)**, Lane E. `docs/research/luminous-freeze.md`, extending
the Cosmos freeze (all contrast pairs, focus and status rules inherited
unchanged). Freezes: spring tokens (fast/default/slow spatial + effects,
damping/stiffness values), the signature motifs and their triggers —
**ripple** (step, confirm), **bloom** (node, arrival), **trace** (mask
or path draw), **unfold** (received route), **constellation**
(processing), **morph** (arrow), **aura** (σ), **periphery** (turn
side) — with Full/Reduced/Static behaviour for each; shader tiers; the
haptic grammar (Composition primitives per V2 §8.4 cue + heartbeat
curve) and the sonic grammar (earcon pitches, pan law, when silent);
sigil rules; and a "deviations from Cosmos" table with reasons (e.g.
ambient motion allowed on Record's edges, never behind copy). Acceptance:
doc exists; every value marked inherited or Luminous-local; V reviewer
checks the motif sheets (static storyboards) for legibility.

#### W10-08 — Turn haptics emitted
**T (S review)**, Lane C. Closes G12: `Navigator` emits LEFT/RIGHT/U_TURN
on entering APPROACH for the next turn's direction (TICK stays for
straight checkpoints), and again on TURNING. No geometry change.
Acceptance: `NavigatorTest` asserts the exact haptic sequence for left,
right and U-turn scenarios; `NavigateViewModelTest` green; no HTTP
import in `nav/`.

### Wave 11 — SAM Lens (make the cloud brilliant and cheap)

#### W11-01 — Glimpse ledger
**L (T review)**, Lane B. Pure, fully specified: for each `FrameSample`,
on 4×-decimated luma: `sharp` = variance of the 3×3 Laplacian
[0,1,0; 1,−4,1; 0,1,0]; `exposureOk` = fraction of pixels in [16,239] ≥
0.85; `stable` = 1/(1 + |ω|/0.6 rad/s) with ω from the rotation vector
at the frame time; `hash` = dHash-64 on a 9×8 resample; `novelty` =
Hamming(hash, last kept)/64. `score` = min(sharp/(routeMedian+ε), 2) ×
exposureOk × stable × (0.5 + 0.5·novelty). The ledger keeps the best
frame per 0.5 m odometer bucket (metadata always; JPEG persisted to
`drafts/<id>/glimpse/` at ≤ 960 px, quality 80). Acceptance:
`GlimpseLedgerTest` with synthetic luma fixtures (blurred vs sharp,
dark, duplicate) proves ordering, bucket replacement and determinism;
allocation-free hot path (code-audited).

#### W11-02 — Approach strips and ledger keyframes
**T (S review of what is stored)**, Lane B. After segmentation, each
checkpoint at distance d gets an **approach strip**: up to 12 ledger
frames in [d − 8 m, d + 1 m], chosen by score with ≥ 0.4 m spacing,
time-ordered. Keyframes become distance-based picks from the strip
(nearest to d − 4 m, d − 1 m, d + 0.75 m for TURN; d − 3 m, d for
others), replacing timestamp seeks. Video stays the creator's private
record and the fallback source when `perceptionMode` lacks the ledger.
Start photo = best frame at d ≤ 1 m. Acceptance: `ApproachStripTest`
(fixture ledger → expected frames per node type); `MediaToolsTest`
fallback path still green; Review shows ledger keyframes for a real
recording (device evidence).

#### W11-03 — Clip forge
**F (T review)**, Lane B. Strip JPEGs → H.264 MP4 at 4 fps, 480 p short
side, ~600 kbps, via Media3 Transformer image-sequence input; each clip
carries a sidecar `frames.json` mapping frame index → (tNs, odometer m).
That map makes `visibleFromM` exact: d_checkpoint − d(first frame where
SAM tracks the object), replacing the fps estimate. Verify G5's
`setFrameRate` suspicion and record it. Fallback: transcode from video
with `Presentation.createForHeight(480)` + `FrameDropEffect` to 12 frames.
Every artefact gets a SHA-256. Acceptance: host test for the frame map
and `visibleFromM` maths; device evidence: a 60 m route's total upload
≤ 2 MB, clip frame counts exactly as planned (ffprobe output recorded).

#### W11-04 — On-device privacy pre-filter
**T (S review)**, Lane B. Before anything is offered for upload: ML Kit
face detection on strip frames; frames with faces are excluded from
clips (or the face region blurred — S decides which, default exclude),
and the manifest carries per-frame `quality` and `faceFree` flags so the
worker drops scout boxes on bad frames before any SAM spend. Export
preview discloses the filter. Acceptance: unit tests on the manifest
flags and exclusion logic (detector faked at the seam); consent copy
unchanged except the added disclosure (S-reviewed); device evidence on a
frame with a printed face.

#### W11-05 — Worker intake v1.1
**F (S review)**, Lane D. Implement W10-06 uploads: sha-verified PUTs to
`jobs/staging/<uploadId>/`, `missing[]` lets clients resume, job create
moves staged blobs under the job (idempotent on `idempotencyKey`),
staged uploads expire after 1 h, caps unchanged (60 files, 80 MB).
Acceptance: `npm test -- src/jobs.test.ts` covers resume, sha mismatch
(400), idempotent double-create (same job id), expiry, v1 multipart
unchanged; typecheck clean.

#### W11-06 — Worker SAM Lens
**T (S review)**, Lane D. Closes G6–G9. (1) **Per-call Workflow steps**
`ground:<nodeId>:<phraseHash>` so a retry re-bills one call, never all.
(2) **Cache**: key = sha256(media) + phrase + model + params → R2 text
under the job (W10-06 scoping); a hit costs zero frames. (3) **Escalation
ladder** per checkpoint: drop candidates on frames failing quality/face
flags → SAM **image** on the hero frame for top-2 candidates (1 frame
each) → SAM **video** on the 12-frame strip only for the top candidate
at TURN/DESTINATION, or when image confidence is in [0.5, 0.85). (4)
**Budget governor**: reserve frames by node priority DESTINATION > TURN
> LEVEL_CHANGE > CHECKPOINT; never exceed `budget.maxSamFrames`; record
`usage.samFramesBilled` from `response.completed` and `budget.exhausted`.
(5) Bounded concurrency (4), `AbortSignal.timeout(60 s)` plus 20 s
idle-between-events, `Buffer` base64. (6) Emit W10-06 events. Legacy
path behind `GROUND_MODE=legacy` until W11-10. Acceptance: `npm test --
src/ground.test.ts src/pipeline.test.ts` cover ladder decisions, budget
exhaustion, cache hit = 0 frames, single-call retry, abort; usage
asserted from fixture `response.completed`.

#### W11-07 — SAM live probe and SSE hardening
**T (S review of spend)**, Lane D. Spend ≤ $1 of live calls to settle
facts the ladder depends on, each recorded in `docs/measurements.md`:
does `input` accept several `input_text` phrases in one call (SAM 3.1
Object Multiplex) and how output attributes them; is a 12-frame 4 fps
480 p clip accepted and billed per frame; image-call billing; latency of
each. If multi-phrase works, W11-06 sends one video call per checkpoint
for all surviving phrases. Harden `meta.ts`: golden SSE fixtures for
chunk splits across `\n\n`, CRLF, `:` keep-alive comments,
`response.incomplete`, done-only text (the 9833aef lesson). Add
`npm run canary:sam` (one 1-frame image call) for W14-05. Acceptance:
`npm test -- src/meta.test.ts` green on all fixtures; probe results
recorded with job/request ids.

#### W11-08 — App client v1.1
**F (T review)**, Lane D. Hash-first upload: `POST /v1/uploads`, PUT only
`missing[]`, per-request timeout sized to the file (≥ 30 s + 1 s per
100 KB; the 20 s client default stays for everything else), resume after
process death from the persisted uploadId; job create with
idempotencyKey = sha256(manifest). Progress over the SSE stream
(`Last-Event-ID` resume) with exponential-backoff polling fallback (2 →
4 → 8 → 16 s); `job.json` persisted on stage change only. Partial
`checkpoint` events flow into Review as they land. Acceptance:
`EchoApiClientTest`/`ProcessingViewModelTest` cover resume-skips-present,
idempotent retry, stream reconnect, fallback polling, partial merge (AI
candidates still never mutate geometry — existing test untouched).

#### W11-09 — Ready-on-arrival (P1)
**T (S review of consent ordering)**, Lane D. Opt-in on Record's
pre-start overlay: "Find landmarks while I walk · Wi-Fi only". The
consent sheet appears *before Start*; strips upload as each checkpoint
window closes (privacy filter first); Finish creates the job against the
already-uploaded blobs. Cancel or discard deletes staged uploads. Default
off. Acceptance: tests prove no byte leaves before consent, metered
network blocks upload, discard deletes; device evidence: result ready
≤ 15 s after Finish on a 60 m walk.

#### W11-10 — Live SAM envelope v2
**T (S review of claims)**, Lane F. A real walked 60 m route (6–8
checkpoints) through the deployed v1.1 worker with SAM enabled. Record
billed frames, calls, cache hits on a forced retry (must be 0 re-billed
frames), upload bytes, latency after Finish, and Record jank during the
walk. Compare against the §4 targets and v1. Then remove
`GROUND_MODE=legacy` or record why it stays. Acceptance: archived
`result.json` + event log under `res/measurements/`; every §4 SAM row
met or the miss recorded with cause — unmeasured is not Complete.

### Wave 12 — Ghost Navigator (make it know where you are)

#### W12-01 — Heading quality
**S**, Lane C. Stillness = accel variance < 0.02 g² over 0.5 s and no
step; during stillness estimate yaw drift rate from the rotation vector
and subtract it (bounded ±2°/min); heading quality q ∈ [0,1] from recent
drift residual, axis-switch events (`HeadingMath.kt:42-52`) and turn
rate. q feeds the filter's heading likelihood (W12-02) and the σ aura.
Acceptance: `HeadingQualityTest` with synthetic drift fixtures (bias
recovered within 0.3°/min); `PdrEngineTest` untouched and green.

#### W12-02 — Ghost filter
**S**, Lane C. The heart of 2.0. Particles (N = 256, struct-of-arrays,
no per-update allocation) carry `s` (m along the route polyline), `k`
(stride scale, prior N(1, 0.08)) and `b` (heading bias, prior N(0, 5°)).
**Predict** per step: s += k·L + N(0, 0.05·L); b random walk 0.3°/step.
**Weight**: heading — von Mises on (yaw + yawOffset + b − routeHeading(s))
with κ from q; turn event — Gaussian (σ 2 m) on distance to the nearest
node with the matching turn, near-zero elsewhere; landmark confirm —
hard anchor at the node ± 1 m; later slices add barometer, magnetic and
visual observations through one `Observation` interface. **Resample**
systematic when N_eff < N/2. Outputs posterior mean `s` and σ (weighted
std), which *replace* the linear σ and odometer progress in the V2 §6.4
state machine — phase rules, thresholds and copy are unchanged.
`EchoConfig.navigatorEngine = LINEAR | GHOST`, default LINEAR until
W14-03. Acceptance: all 7 existing scenarios pass on **both** engines;
new scenarios: stride 12 % off (GHOST arrives within 2 m, LINEAR
reported), early turn, wrong start; seeded determinism; ≤ 1 ms per
update benchmarked on the mid device; no HTTP in `nav/`.

#### W12-03 — Self-calibrating stride
**T (S review)**, Lane C. Each anchored leg yields r = leg.lengthM /
follower odometer Δ — the *relative* gait between creator and follower,
which is exactly what navigation needs. Update a log-normal K posterior
(conjugate in log space, variance ∝ 1/leg length), clamp to the W4-02
band 0.15–1.5, persist per device; the filter's `k` prior uses it.
Settings shows "Stride learned from 6 legs". Acceptance:
`StrideLearnerTest` converges on fixtures; `ConfigRepository`
sanitisation tests green.

#### W12-04 — Magnetic signature (P1)
**T (S review)**, Lane C. Capture `TYPE_MAGNETIC_FIELD` at GAME rate;
per 0.5 m bucket store |B| and the gravity-aligned vertical component
(orientation-invariant, phone-independent after zero-mean
normalisation) into `magProfile`. Navigate: the last 10 m observed vs
the route profile around s ± 3σ by subsequence DTW (Sakoe–Chiba 10 %);
emit an offset observation only when profile std > 3 µT and the DTW
cost beats the gate. Acceptance: `MagneticMatchTest` (synthetic
corridor with steel anomalies: offset recovered ± 1 m; flat corridor:
no observation); device trace recorded on one real corridor.

#### W12-05 — Visual fingerprints
**T (S review of privacy and filter weight)**, Lane C. Benchmark ≤ 2
embedder candidates (§5) on the mid device; pick by precision ≥ 0.95 at
τ on a recorded pair set (same spot from creator and follower phones,
different light) and ≤ 15 ms. Capture: 3 prints per checkpoint from the
strip + waypoint prints every 5 m (int8, 256-d after a fixed seeded
random projection). Navigate: Frame Tap at 2 Hz only while walking and
thermals allow; cosine vs prints within s ± (3σ + 5 m); a match ≥ τ is
a filter observation plus a "That's the red pillar?" chip and soft
haptic — the user, or a consistent turn event, confirms; never a silent
auto-confirm. Acceptance: `FingerprintMatchTest` (fixture embeddings,
gating, window); precision/latency table recorded; prints never leave
the bundle (grep: no fingerprint field in any HTTP payload type).

#### W12-06 — Lost → Relock
**T (S review)**, Lane C. "Lost" re-spreads particles uniformly over the
route, then converges from the next ≤ 10 s / 8 m of turn signature
(DTW over heading-change sequence), fingerprints and magnetic offsets.
UI: "Finding you…" with a tightening ring; at σ < 3 m, "Found you —
near the red pillar". If no convergence in 20 m, today's sheets
(go back / restart) appear. Acceptance: replay scenarios where the
follower is dropped mid-route and relocks within 10 m; non-converging
scenario falls back honestly.

#### W12-07 — Route Health
**L (T review)**, Lane C. Pure function, fully specified. Start at 100;
−15 per leg > 20 m with no landmark; −10 per TURN without landmark; −10
per ambiguous turn (another node with the same turn direction within
6 m and no landmark on either); −5 per LEVEL_CHANGE without cue; −20 if
DESTINATION has neither landmark nor label; floor 0. Bands: ≥ 80 "Ready
to share", 50–79 "Followable — could be clearer", < 50 "Add landmarks
first". Up to three nudges from the largest penalties ("Add a landmark
near 23 m"). Stored in `perception.health` on save. Acceptance:
`RouteHealthTest` covers every penalty and band on fixtures.

#### W12-08 — Live level awareness
**T (S review)**, Lane C. Barometer during navigation: Δh from the leg
start (relative pressure, no sea-level assumption); a LEVEL_CHANGE node
becomes a filter observation when |Δh| crosses 2.2 m; stairs vs lift by
whether steps occurred during the change; cue confirms itself ("Down 1
level ✓"). Acceptance: `LevelAwarenessTest` on pressure fixtures
(stairs, lift, noise); `NavigatorTest` LEVEL scenarios green on both
engines.

### Wave 13 — Luminous experience (make it alive)

Every W13 slice owes *visual* evidence (V-reviewed) plus its unit tests,
keeps W8-12's a11y guarantees, and binds every animated element to a
named signal (§0 "honest light").

#### W13-01 — Echo field
**T (V review)**, Lane E. AGSL atmosphere: low-frequency domain-warped
aurora (≤ 3 octaves) in scheme colours, uniforms for time (Full only),
tilt (rotation vector low-passed at 2 Hz, ±12 dp parallax — the unused
hook finally wired) and up to 4 ripple centres used by other motifs.
Shader created once and remembered; no recomposition per frame (reads
deferred to draw). Daylight stays flat per the freeze; nothing moves
behind body copy. Acceptance: tier tests (API < 33 → Canvas; Reduced →
static frame; Static → flat); GPU frame cost ≤ 2 ms recorded; V frames
in both schemes.

#### W13-02 — Motion system
**F (T, V review)**, Lane A. Spring tokens from W10-07 as the only
motion source; `SharedTransitionLayout` across Home record → RouteDetail
→ Align → Navigate (route sigil and keyframe as shared elements);
predictive back with in-app transitions that follow the system gesture;
shared utilities: rolling tabular digits, path-trace (PathMeasure
segment, `Path.rewind` in hot loops), bloom, ripple, `Morph` wrapper.
Acceptance: shell tests (back vs close, focus on destination change)
still green; motion-tier parity test (Static settles instantly with the
same final state); V frames of each transition.

#### W13-03 — Route sigils
**T (V review)**, Lane E. Deterministic glyph from geometry: resample
the route polyline to 64 points, normalise into a unit circle, draw as a
tapered ribbon with node blooms; accent from `sigilSeed` among the
freeze's allowed channels (never Cyan — reserved for live). Same route
renders identically on both phones. Decorative to TalkBack; never the
only identifier. Acceptance: `SigilTest` (same input → same path
hash; different routes → different hashes over the fixture set); V frames.

#### W13-04 — Record "Echo walk"
**T (V review)**, Lane B. Step ripples from PdrEngine step events (edge
band only, never over the centre 60 %); bottom trail ribbon in
pseudo-perspective, heading-up, nodes blooming at online turns and
marks; gold corner brackets for 300 ms when a ledger frame is
top-decile and novel (honest: "great view captured", no object claim);
rolling distance digits; heading tick kept. Acceptance: Record UI tests
green, `CaptureSession` untouched; device perf evidence within the W10-03
budget with everything on; V frames mid-walk and at a turn.

#### W13-05 — Review "Route story"
**T (S review of creator-verification semantics, V)**, Lane E.
Horizontal film strip of ledger frames by distance, scrubbing linked to a
minimap cursor and the node timeline; landmark masks *trace* in (600 ms);
shared-element keyframe → full-screen inspector with pinch zoom; Route
Health header with nudges that jump to the place; AI cards slide in as
`checkpoint` events land. Acceptance: `ReviewViewModelTest` +
draw-gesture test green; immutability test untouched; files stay under
the 400-line warning by using W13-02 utilities; V frames.

#### W13-06 — Processing "Constellation"
**F (T, V review)**, Lane E. The route drawn as a constellation; each
checkpoint a star: queued dim → scouting twinkle → grounded bright with a
tiny mask outline → skipped hollow (shape + label, never hue alone).
Header: honest stage, frame budget meter ("38 / 120 frames"), Cancel,
Continue without AI. Driven only by W10-06 events. Acceptance:
`ProcessingViewModelTest` maps each event to a star state; V frames.

#### W13-07 — Share/Receive "Handshake"
**F (T, V review)**, Lane E. QR at error-correction H with the sigil in
≤ 15 % of the centre (decode asserted in a host test with a test-only
ZXing core dependency); a beacon pulse while the offer listens and a
success bloom on Sent; Receive *unfolds* the route's sigil before the
import sheet copy. Acceptance: `ShareViewModelTest`/`ReceiveViewModelTest`
green; QR decode test; V frames. *Amended 2026-11-02:* the real-rate
particle flow moved to W13-14 — it needs a transport progress seam.

#### W13-14 — Transfer progress seam + real-rate particle flow
**T (S review of the transport change, V)**, Lane C. Additive progress
flow (bytes sent / total) on the `PeerTransport` seam for LAN and relay,
surfaced in `ShareUiState`/`ReceiveUiState`; particles flow between two
phone glyphs at the measured bytes/s, and nothing moves without bytes
(honest light). No change to the wire format, keys or tokens.
Acceptance: `TransferServiceTest` progress cases (monotonic, ends at
total, relay and LAN); Share/Receive view-model progress tests; device
evidence of one two-phone transfer with the flow visible.

#### W13-08 — Navigate instrument 2.0
**T (S review of UNCERTAIN/OFF_ROUTE honesty, V)**, Lane E. Arrow
`Morph`s straight → bend (angle-proportional) → U → check; σ **aura**
whose radius and softness track the posterior σ (UNCERTAIN = dashed +
label); **periphery glow** on the turning side when ≤ 8 m from a turn,
intensity ∝ 1 − remaining/8; rolling distance digits; "Look for this"
card with the traced mask; fingerprint match chip from W12-05; Relock
ring from W12-06; calm mode dims decorative chrome while walking
straight (text contrast never reduced). Acceptance: phase → UI mapping
test covers all 8 phases on both engines; overlay tests green; V frames
for WALK, APPROACH, TURNING, UNCERTAIN, OFF_ROUTE, ARRIVED.

#### W13-09 — Haptic and sonic language
**T (V review of the eyes-free flow)**, Lane E. Composition primitives
per the W10-07 grammar (e.g. LEFT = QUICK_RISE → CLICK, RIGHT = CLICK ·
CLICK → QUICK_RISE, U_TURN = SLOW_RISE → THUD, ARRIVE = bloom
sequence) with waveform fallback; distance **heartbeat** (TICK interval
1.2 s → 0.4 s over the last 8 m); opt-in earcons, 80 ms synthesised
tones panned by sin(arrow), only with headphones or explicit opt-in,
never over TalkBack speech; eyes-free mode limits announcements to
decision points. Wear mirror receives the same cues. Acceptance:
`HapticsTest` (pattern per cue, fallback when primitives unsupported);
pan-law unit test; device evidence: cues judged distinguishable by two
people (recorded).

#### W13-10 — Live Updates
**F (S review of permissions and service type)**, Lane A. Ongoing
`ProgressStyle` notification (segments = legs, points = landmarks,
"Turn left in 12 m") during navigation and processing; standard ongoing
notification below Android 16; `POST_NOTIFICATIONS` primed in context.
S decides whether screen-off navigation gets a foreground service (and
which type, with its Play declaration) or 2.0 stays screen-on and says
so. Acceptance: notification-builder unit tests; device evidence on an
Android 16 phone and one older phone.

#### W13-11 — Home and Arrived
**F (T, V review)**, Lane E. Home records carry sigils (shared element
with RouteDetail) and reveal in sequence; first run is the Echo Step mark
with its idle echo, sized so the one command stays above the fold;
Arrived plays the arrival bloom (Full tier) above honest stats, trial
details still behind disclosure. Acceptance: Home journey tests from
W7-09 re-green; V frames. *Amended 2026-11-02:* the guided tour moved to
W13-13 — it needs a Navigate data-seam change, not presentation.

#### W13-13 — Guided practice tour
**T (S review of the no-write guarantee, V)**, Lane E. First run offers
a tour that replays a bundled fixture walk through the real Navigate
instrument (labelled "Practice", never listed among routes — 0002's
demo-data purge stands). Navigate must accept an ephemeral route and a
null trial sink, so the tour writes neither `routes/` nor `trials/`.
Acceptance: test that the tour never writes to `routes/` or `trials/`;
Home journey tests green; V frames of the tour's first turn and arrival.

#### W13-12 — Wear guidance (P1)
**F (T review)**, Lane F. Compose for Wear ongoing activity: arrow,
distance, next cue, synced over a new `/echo/nav` Data Layer path
(change-driven, ≤ 1 Hz); a tile for the active route; haptics from
W13-09. applicationId parity kept; wear/phone target SDKs not aligned
(stack rule). Acceptance: `:wear:testDebugUnitTest` for the message
codec; on-watch run recorded (watch model, SHA) — or the slice is cut
and the README says so.

### Wave 14 — Proof (make it true)

#### W14-01 — Performance gate
**T**, Lane F. Run W10-04's benchmarks and a 10-minute real walk on the
low-end and mid devices with every 2.0 feature on: §4 jank, Frame Tap,
filter update, battery and cold-start budgets, plus thermal behaviour
(the governor must step down, not stutter). Any miss reopens its slice;
this gate does not fix forward. Acceptance: perf table in
`docs/perf.md` with device, SHA and JSON paths.

#### W14-02 — Accessibility and motion parity v2
**T (V review)**, Lane F. W8-12 repeated over the new surfaces: WCAG
pairs on every new colour use, fontScale 2.0, TalkBack order and names
(sigils/ripples decorative), 48 dp targets, Reduced/Static parity
(shader, morph, constellation, unfold all settle to the same
information), eyes-free mode run end-to-end by a TalkBack user flow.
Acceptance: named UI tests + recorded manual TalkBack pass per primary
screen.

#### W14-03 — Field A/B
**T (S review of the verdict)**, Lane F. V2 §11 protocol on one taped
course: 5 SENSOR_ONLY + 5 LINEAR + 5 GHOST runs, alternating, same
followers; arrivals, drift at taped markers, time, Lost events, Relock
time, mistaken confirmations, fingerprint suggestions accepted/declined.
GHOST becomes the default only if arrivals ≥ LINEAR and median drift ≤
LINEAR; otherwise it ships flag-off and the README says why. Acceptance:
CSV + report in `docs/measurements.md`, failures included.

#### W14-04 — Security and privacy audit v2
**S**, Lane D. Audit the new data and flows: fingerprints and magnetic
profiles (image-/place-derived; bundle-only, encrypted, expiring,
disclosed), ready-on-arrival consent ordering, staged-upload expiry, SAM
cache scoping and deletion, face pre-filter efficacy, Live Update
contents on the lock screen (no landmark photos), and the inherited
R1 (CSPRNG for the LAN token) and R3 (purge on resume) — both fixed
here. Acceptance: `docs/reviews/` entry with the three-state verdict;
BLOCK findings become slices before W14-05.

#### W14-05 — ECHO 2.0 release gate
**S**, Lane F. README rewritten for 2.0: what perception runs where,
what leaves the phone and when, the measured SAM budget and latency,
navigator engine default and its A/B evidence, limitations and
non-claims (no live-frame AR projection, no safety use, no automatic
global relocalisation outside a known route). `npm run canary:sam`
green; the §0 walk run end-to-end on two signed phones and filmed;
every claim traced to a slice SHA or recorded run. Acceptance: verdict
recorded on this row. Push/release is a separate approved decision.

## 9. Risks and escalation

| Risk | Mitigation / fallback |
|---|---|
| Device cannot bind Preview + Video + Analysis | W10-03 ladder: [P,V] with ledger off; strips come from video transcode (W11-03 fallback); recorded per capture |
| SAM rejects short image-sequence clips or bills a minimum per call | W11-07 probes first; clip forge falls back to 12-frame transcodes; governor maths uses the measured billing |
| Multi-phrase calls unsupported | One phrase per call plus the cache and image-first ladder still meet ≤ 120 frames (8 video × 12 + ≤ 16 image + headroom) |
| Meta pricing differs from third-party estimates | W10-01/W11-07 measure real billing; the governor caps frames regardless of price |
| Particle filter regresses navigation | Flag-gated, both engines tested on every scenario, A/B decides the default (W14-03) |
| Embedder too slow, too big, or imprecise | W12-05 picks by measurement; fallback dHash fingerprint at low weight; cut order removes it without breaking Relock |
| Shaders jank on older GPUs or drain battery | Tiered; Canvas fallback; motion tier drops on power save/thermal; W14-01 gate |
| Fingerprints or magnetic profiles read as tracking data | Bundle-only, never uploaded, expiring, disclosed; S freeze (W10-05) and S audit (W14-04) |
| Ready-on-arrival erodes the consent-first model | Opt-in, consent before Start, Wi-Fi only, default off, S-reviewed, first in the cut order after Wear |
| Expressive motion fights accessibility | Freeze inherits every Cosmos a11y rule; V + W14-02 gates; Static tier always complete |
| Scope creep (AR projection, on-device SAM, iOS) | §4 out-of-scope list; ideas go to plan 0004, not new W1x rows |

## 10. What this plan deliberately does not do

It does not change V2 §6.4 phase semantics, thresholds or copy; the Ghost
filter only replaces where progress and σ come from, behind a flag until
evidence says otherwise. It does not put any network call in `nav/`;
offline navigation stays absolute. It does not let AI or fingerprints
confirm a landmark on their own; the user or consistent sensor evidence
does. It does not stream live preview frames to any cloud. It does not
animate anything that is not driven by a real signal. And, as in 0002,
it does not mark anything Complete on tests that never touched the
surface the slice claims.

## 11. References (research of 2026-11-02, for slice authors)

- SAM 3.1 release and Object Multiplex (up to 16 objects per pass):
  https://ai.meta.com/blog/segment-anything-model-3/ ·
  https://www.labellerr.com/blog/sam-3-vs-sam-3-1-performance-comparison/
- Third-party SAM 3.1 video pricing ($0.01 / 16 frames, estimate only):
  https://fal.ai/models/fal-ai/sam-3-1/video/llms.txt
- On-device video segmentation (not adopted in 2.0): EdgeTAM, CVPR 2025 —
  https://cvpr2023.thecvf.com/virtual/2025/poster/34242
- Lightweight visual place recognition: MixVPR (https://www.arxiv.org/abs/2303.02190),
  D²-VPR, AAAI 2026 (https://ojs.aaai.org/index.php/AAAI/article/view/38303)
- PDR + particle-filter map matching: https://www.ncbi.nlm.nih.gov/pmc/articles/PMC4721747/ ·
  https://forschungsportal.hcu-hamburg.de/en/publications/particle-filter-based-indoor-localization-with-learning-based-pdr/
- Compose graphics/animation performance (RuntimeShader caching, deferred
  reads, `Path.rewind`):
  https://developer.android.com/agents/skills/jetpack-compose/jetpack-compose-performance/references/graphics-performance ·
  https://developer.android.com/agents/skills/jetpack-compose/jetpack-compose-performance/references/animation
- Blur and duplicate scoring (Laplacian variance, dHash): https://arxiv.org/pdf/2512.19107 ·
  https://docs.rs/crate/oximedia-dedup/latest/source/src/video_dedup_pipeline.rs

## 12. Build wave-14 deferral (2026-11-02, coordinator note — not a slice)

40/45 rows are Complete on main (all unit-testable work, each in its own
slice worktree, merged). The remaining 5 rows cannot pass honestly without
named hardware, a SAM key, or plan 0002's release slice, so they stay Queued
for a smarter model / a device session instead of being faked:

| ID | Blocked on |
|---|---|
| W11-10 | Walked 60 m route + deployed v1.1 worker + SAM key (billing, bytes, latency, retry cache-hit, jank). Scaffolding present: `npm run canary:sam` (W11-07), forge budget maths (W11-03), lens governor + cache (W11-06), client v1.1 (W11-08). |
| W14-01 | Two named devices + 10-minute walk (jank, Frame Tap ≤ 6 ms, filter ≤ 1 ms, battery ≤ 6 %, cold start, thermal step-down). Harness present: `:benchmark` module + `docs/perf.md` gates (W10-04). |
| W14-02 | Manual TalkBack pass per primary screen + V-reviewed frames at fontScale 2.0 (W8-12 repeated over W13-04…W13-11). Static-tier parity is unit-pinned per slice; the human pass is owed. |
| W14-03 | Taped course + 15 alternating runs (5 SENSOR_ONLY + 5 LINEAR + 5 GHOST). Both engines are unit-pinned on all 7 scenarios (W12-02); the field verdict decides the default. |
| W14-05 | Plan 0002 W9-12 (still Queued) + W11-10 + W14-01…W14-04. Audit W14-04 returned APPROVE WITH RESIDUAL RISK (no BLOCK). |

"Unmeasured is not Complete" (W11-10) applies to all five: no numbers were
invented for any of them. The `GROUND_MODE=legacy` flag stays until W11-10
passes; GHOST stays default-off until W14-03 passes.

## 13. Post-build review of Complete rows (2026-11-02)

Six read-only reviewers audited the 34 Complete rows not already reworked
(W10-07, W13-01/02/03/07/11 excluded). Confirmed defects were fixed, one
commit each (`git log 2d53cf5..` · `fix(…)`), and the worker, shared and wear
suites pass on main. Headline fixes: Record could not start on a device
(`takePhoto` had no still source) and the ledger was never fed — the Frame Tap
is now a synchronous consumer feeding a per-recording ledger; the worker had
no `events` route and lost SAM results on Workflow replay; the face pre-filter
failed open; the Navigate aura camouflaged the arrow; learned stride was never
persisted; the perception disclosure was never shown.

These seams exist and are unit-tested but have **no production caller or no
device evidence**; the rows above stay Complete only for what is wired, and the
remainder is owed:

| Row | Still owed |
|---|---|
| W10-02/03/04, W11-03 | StrictMode, Frame Tap ≤ 6 ms, jank and 2 MB upload device evidence; `:benchmark` not compiled on the build machine; Navigate-Simulate benchmark needs a seeded fixture route |
| W11-04 | A shippable face detector (ML Kit); production uses `NoOpFaceDetector`, so no frame is filtered yet |
| W11-07 | Live probe table (no `META_API_KEY`) |
| W11-09 | `ReadyUploader` has no caller, and wiring one is deliberately held: it fails closed without a `FaceDetector` (W11-04, still `NoOpFaceDetector`), so a walk-time trigger would stage nothing, or upload unfiltered faces if that gate were loosened. Owed in order: a real detector (W11-04), a production `NetworkGate`, the Record consent sheet and window trigger, Finish-manifest coordination, then device evidence (ready ≤ 15 s after Finish on a 60 m walk) |
| W12-01 | `HeadingQuality.correctedYaw` is not applied to `PdrEngine` yaw: a ±2°/min estimator error compounds to ~20° over 10 min, so it waits for W14-03 field evidence |
| W12-04/05 | Device magnetic trace; fingerprint embedder and capture are not wired to the camera; precision table and τ unmeasured |
| W13-05 | The shared-element flight is unasserted. Partials reach Review by replaying the ones Processing persisted (`onCheckpointEvent` stays the live-event seam; Review is not on screen while a job streams) |
| W13-12 | Plain-View watch screen, not a Wear ongoing activity or tile |

W14-01/02/03/05 and W11-10 remain Queued as in §12. The W14-02 manual TalkBack
pass and V review of frames at fontScale 2.0 are still owed.
