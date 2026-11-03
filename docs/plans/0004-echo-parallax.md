# Plan 0004 — ECHO Parallax: Visual Displacement & Route-Conditioned Tracking (Plan 4)

Status: **queued** · created 2026-11-02 · every number below is a **target**; nothing in this plan has been measured on a device.

Owning repository: `simt-echo` (this repo), fully enclosed as in 0001–0003. `simt-app` and `simt-space` stay read-only quarries; nothing is linked.
Predecessors: `docs/plans/0003-echo-luminous.md` (Ghost filter, Frame Tap, fingerprints, magnetic), `0002-mvp-cosmos-design.md`, `0001-agentic-flow.md`; spec of record `docs/research/SIMT_ECHO_IMPLEMENTATION_PLAN_V2.md` (*V2 §n*).

0003 gave ECHO a probabilistic navigator that fuses **place** evidence (landmarks, fingerprints, magnetic, turns, level). It has no evidence about **how fast or in which direction the follower is actually moving**: every metre of progress between anchors comes from step count × a stride guess, and every degree of heading comes from a magnetically-fused rotation vector. **This plan makes the camera a velocity and heading sensor, without ever taking the camera away from recording.**

Slice ids continue at **W15–W19**. The `simt.json` id pattern `^W[0-9]{1,2}-[0-9]{2}$` already matches them.

Relationship to 0003: nothing here edits a Complete 0003 row's claim. 0003 §4 puts **ARCore/VIO** out of scope because it needs the camera exclusively; this plan adds **no** second camera owner. It reads the Frame Tap luma that already exists. 0003 rows W11-10, W14-01/02/03/05 stay owned by 0003. This plan's own gates (W19-03/04/06) are separate, and W19-06 depends on W14-05.

## 0. North star — one walk in ECHO Parallax

1. **Create.** Nothing visible changes except honesty: while the creator walks, the phone also reads how the world *expands* past the lens. For every 0.5 m it records one byte — how close the surroundings are — and every 2 m a 96-byte *view strip* (a sliver of the scene that cannot be turned back into a picture). The route now carries a depth-of-corridor profile, not only a polyline.
2. **Review.** "Route polished": the phone re-solves its own recorded path with the visual evidence (heading drift removed leg by leg, per-leg uncertainty stored). Route Health gains *visual coverage* ("Leg 3 has little texture — the follower will rely on steps there").
3. **Follow (airplane mode).** The follower's phone sees the same corridor the creator saw. Because **depth cancels when two people walk the same place**, the follower's speed in the creator's metres falls out of one division — no calibration, no ARCore. Stride error stops compounding between landmarks. At a recorded view the phone says not just "I'm near" but "I'm 1.2 m short, 4° left" and the heading bias snaps to zero. A shaken phone no longer invents steps.
4. **Lost?** The depth profile is a lighting-proof place signature; Relock converges faster.
5. **Always honest.** A small "visual lock" ring inside the σ aura lights only while the camera is actually contributing; when the phone is held flat or the wall is blank, it says so and navigation continues on steps, exactly as today.

### Design principles (binding on every slice)

- **The camera is a guest.** Recording and fingerprint capture keep priority; Parallax reads the same analysed frames, adds no camera use case and no second bind (0003 G4 stays closed).
- **Observation, never authority.** Visual output enters the Ghost filter as soft observations through the existing single-observation seam. Heading and speed may be *down-weighted by evidence*, never silently trusted; the user's confirm remains the only hard anchor.
- **Estimator bias cancels in a ratio.** We never promise metric depth from a monocular camera. Creator and follower run the *same* estimator on the *same* corridor; the route stores the creator's *ratio* profile and the follower divides by it.
- **Know when you don't know.** Every visual output carries a confidence that drops on blank walls, blur, glare, dark, dynamic crowds and bad hold angles; at confidence 0 the system is bit-identical to 0003 Ghost.
- **Perf is a feature.** Zero allocation on the frame path, work scheduled by *information gain* (σ, turns, ambiguity), thermal-adaptive, off while standing still.
- **Calm by default.** Visual assist is opt-in, local, and never blocks the walk.

## 1. Evidence — why this plan exists

Verified by reading the tree on 2026-11-02 (paths: `S/` = `shared/src/commonMain/kotlin/app/simt/echo`, `A/` = `shared/src/androidMain/kotlin/app/simt/echo`). Code-read only; no device driven.

| # | Severity | Finding | Closed by |
|---|---|---|---|
| H1 | MAJOR | **Nothing observes speed.** Every `Observation` is place or heading (`S/nav/ghost/Observation.kt`). The stride scale `k` only learns at anchors (W12-03), so between landmarks an error compounds linearly (0003: stride 12 % off ⇒ ~7 m over 60 m). | W17-02 |
| H2 | MAJOR | **Heading drift is estimated but never applied.** `HeadingQuality.correctedYaw` has no production caller (`S/capture/HeadingQuality.kt:113`; `PdrEngine.kt:81` says "from here"; 0003 §13 W12-01: ±2°/min ⇒ ~20° over 10 min). | W16-03 |
| H3 | MAJOR | **Phone axis ≠ walking direction is unmodelled.** A hand-held phone is pointed a few degrees off the travel direction and wobbles; the filter models only a per-particle bias prior (σ 5°, 0.3°/step random walk in `GhostFilter`). | W16-02, W16-03 |
| H4 | MAJOR | **Steps are accepted on the accelerometer alone.** There is no independent witness, so shaking the phone adds steps and slow shuffles drop them. | W16-04 |
| H5 | MAJOR | **The Frame Tap has one consumer and no duty control.** `FrameTap.setConsumer` is a single sink; the only production caller is `RecordingLedger.attach` (`S/capture/RecordingLedger.kt:37`); the governor's interval is an immutable `val` (`S/capture/FrameTap.kt`, `FrameRateGovernor`). In Navigate nothing consumes frames in production (0003 §13: fingerprints "not wired to the camera"). Visual assist cannot share the tap with the ledger. | W15-03 |
| H6 | MINOR | **The route is treated as exact.** Heading likelihood uses the polyline heading with no per-leg uncertainty; PDR drift in the creator's own recording is invisible to the follower. | W15-02, W18-01 |
| H7 | MINOR | **Fixed particle count.** `PARTICLE_COUNT = 256` (`GhostFilter.kt:403`) is paid at all times, including when σ < 1 m and nothing is ambiguous. | W18-03 |
| H8 | MINOR | **DTW exists in more than one place** (`MagneticMatch.kt`; `GhostFilter.observeTurnSignature`; `Navigator.kt` also references it). Two new consumers here (W17-03/04) would make copies 3 and 4 — a defect under the core refactoring rule. | W15-04 |
| H9 | INFO | **The data to test this honestly does not exist on disk.** `RecordingLedger` keeps keyframes every 0.5 m with distances, but there is no ground-truth moving world, no Monte-Carlo accuracy gate, and 0003 §13 lists W14-03's field A/B as still owed. | W15-01 |

**Residual risk of this review:** no device, no recorded walk, no Meta/SAM call. Monocular expansion only equals Δs/Z for a fronto-parallel surface; corridor side walls break that in detail. §9 R1 owns this and says what we do if it fails.

## 2. Model tiers, evidence

Tiers L/F/T/S and the **V** reviewer are as in 0003 §2. Binding additions:

- **Frontier tokens go to:** W15-02/W15-03 (contracts), W16-01/W16-02 (the maths), W16-03 (heading), W17-01…W17-03 (the route-conditioned core), W18-01, W18-03, W19-05/W19-06.
- Evidence types as 0003 §2: *unit*, *perf*, *cost* (n/a), *device*, *visual*. **Synthetic evidence (SimWorld, W15-01) is its own type — *sim* — and never closes a device claim.** A slice that claims a field accuracy number without *device* evidence is not Complete.
- One agent, one slice, one worktree (`simt slice start/finish`, `simt plan done <id> <sha> --test "<cmd>"`).

## 3. Pillars

| Pillar | Today (0003) | Parallax | Slices |
|---|---|---|---|
| **Q1 Seam & lab** — measure before building | One Frame Tap consumer, immutable rate; scenarios are hand-written | Dynamic-duty fan-out tap; a **SimWorld** ray-cast world + Monte-Carlo accuracy gate that runs in `commonTest` | W15-01, W15-03, W15-04, W15-05 |
| **Q2 Visual displacement** — the camera as velocity & heading witness | No frame-derived motion | De-rotated block flow → robust expansion/FOE fit → speed, travel direction, yaw residual | W16-01, W16-02, W16-05 |
| **Q3 Heading & gait** — three witnesses, one vote | Rotation vector only; drift estimated, unused | Gyro + mag-gated rotation vector + visual yaw; travel-direction offset; visual step gate | W16-03, W16-04 |
| **Q4 Route-conditioned tracking** — the frontier | Place observations only | **Parallax profile** (ratio depth map) → follower metric speed; **ViewFix** (Δheading, Δs from a 96-byte strip); depth-profile signature | W17-01…W17-04 |
| **Q5 Route learning** — the route improves | Route is exact and immutable | Offline **Route Polish** with per-leg σ; on-device **route memory** | W18-01, W18-02 |
| **Q6 Efficiency** — pay only for information | 256 particles, fixed analysis rate | Adaptive particle budget; information-gain scheduler | W18-03 |
| **Q7 Proof** | — | Perf gate, field A/B, privacy audit, release gate | W19-03…W19-06 |

## 4. Scope, budgets, cut order

**Parallax is done when** the synthetic gate holds on every scenario, the device gate (W19-03) is recorded on two named phones, the field A/B (W19-04) shows GHOST+VISUAL ≥ GHOST (otherwise it ships flag-off and says so), and every README claim traces to a slice SHA or a recorded run.

Out of scope (do not re-litigate inside slices): ARCore/VIO/SLAM, depth or segmentation models for tracking, 3D reconstruction, any cloud call during navigation (`nav/` stays HTTP-free), streaming frames anywhere, iOS capture (kernels are `commonMain` and compile for iOS; the iOS Frame Tap stays `NoOpFrameTap`, so visual assist is Android-only), obstacle/collision warnings (safety is out of scope per 0002 §4), and a lens-correct metric camera model (intrinsics are *approximate by design*; see R3).

Numeric budgets (declared per repo per ADR-006; hardware gates are *manual* named gates, ADR-017):

| Budget | Today | Target | Measured by |
|---|---|---|---|
| Visual frame cost: pyramid + flow + fit, 120×90 level, mid device | n/a | **≤ 0.8 ms median, ≤ 2 ms p99** | W16-05, W19-03 |
| Heap allocation per visual frame after warm-up | n/a | **0 bytes** (asserted by a JVM allocation probe) | W16-05 |
| ViewFix strip match (±12 cols × 5 scales × 64) | n/a | **≤ 0.1 ms** | W17-03 |
| Ghost update at N=256 / N=96 converged | ≤ 1 ms / n/a | **unchanged / ≤ 0.4 ms** | W18-03 |
| Analyzer duty in Navigate when σ < 2 m, straight | 2 Hz | **≤ 1 Hz average; 0 Hz while standing** | W18-03 |
| Visual-assist battery, 10 min Navigate | n/a | **≤ +1.0 pp over the W14-01 baseline** | W19-03 |
| Bundle growth per 60 m route | 0 | **≤ +6 KB** (perception stays ≤ 64 KB) | W17-01 |
| Arrival error p95, SimWorld 200 seeded walks | GHOST baseline (W15-01) | **≥ 30 % lower; no scenario > 5 % worse** | W17-02, W17-03 |
| Heading drift after a 10 min SimWorld walk | ≈ 20° (0003 §13 estimate) | **≤ 5°** | W16-03 |
| Stride 12 % off, 60 m, SimWorld | within 2 m (0003) | **within 1 m** | W17-02 |
| Phantom-step rejection / true-step false-reject (shake scenario) | 0 % / 0 % | **≥ 95 % / ≤ 1 %** | W16-04 |
| Relock distance after mid-route drop, SimWorld | ≤ 10 m (0003) | **≤ 6 m** | W17-04 |

Cut order (never improvise): W19-02 Trace Lab → W18-02 route memory → W17-04 profile signature → W18-01 Route Polish → W16-04 step gate. **Never cut:** W15-01, W15-02, W15-03, W16-01, W16-02, W16-05, W17-01, W17-02, W19-03, W19-05, W19-06. W16-03 (heading) and W17-03 (ViewFix) may ship flag-off.

## 5. Technology map (adopt · why · fallback)

| Need | Technology | Verdict | Fallback |
|---|---|---|---|
| Frame source | Existing Frame Tap luma (4× decimated, ~240×180), 8 Hz Record / 2 Hz Navigate | **Adopt**; add dynamic interval, fan-out and `wantsFrame` (W15-03) | Visual assist off |
| Motion estimation | Pyramid block matching: 12 blocks of 16×16 on a 120×90 level, SAD, finite-rotation inverse warp + ±3 px fine window with bounded radial expansion steps when the fine search lacks an interior minimum (8 Hz Record / 2 Hz Navigate walking envelope; 1 Hz duty not claimed here), parabolic sub-pixel + 32×32 / central 16×16 full-res refinement | **Write new**, commonMain, allocation-free | none (assist off) |
| De-rotation | Finite pinhole rotational warp (`PinholeRotation`, Rodrigues on normalised rays) from the rotation delta between frame timestamps (W10-03 REALTIME alignment is a precondition) | **Write new** | none |
| Ego-motion fit | Depth-free pairwise translation-flow intersection consensus (`TranslationConsensus`) + linear least squares for expansion `d` and focus-of-expansion with 3 Huber IRLS passes over ≤ 12 blocks; confidence from inliers, residual RMS, texture | **Write new** | none |
| Illumination invariance | Zero-mean/unit-variance normalised 1-D profiles + census compare on column bands | **Write new** | raw NCC (lower τ) |
| Strip matching | Normalised cross-correlation over ±12 columns × 5 stretch factors; sequence score over the last 4 strips (SeqSLAM-style) | **Write new**; shared `Dtw` extracted first (W15-04) | single-strip match |
| Kernel speed-up | 8-pixel SWAR absolute-difference in a `Long` | **Adopt only if ≥ 2× on the mid device** (W16-01 benchmarks it) | plain Int loop |
| Zero-alloc proof | `com.sun.management.ThreadMXBean.getThreadAllocatedBytes` in an `androidHostTest` probe | **Adopt** | n/a |
| World simulator | Procedural textured planes ray-cast to luma in `commonTest`; seeded noise; moving billboards; exposure/gain/FOV per "phone" | **Write new** (W15-01) | recorded keyframe fixtures only |
| Adaptive filter size | `GhostFilter` capacity fixed, active N variable (96–512), systematic down/up-sampling | **Write new** (W18-03) | N = 256 (today) |
| Nav↔frame thread hand-off | Fixed-size single-producer ring of primitive structs (no queues, no boxing) | **Write new** | direct call under lock |
| ML for odometry (learned VO, depth nets) | — | **Not adopted**: size, thermals, privacy, and the ratio trick makes it unnecessary | — |

## 6. Tracker

Evidence naming as 0003 §6: `./gradlew :shared:testAndroidHostTest --tests "<class>"`, `./gradlew :androidApp:assembleDebug`, `./gradlew :shared:compileKotlinIosArm64 :shared:compileKotlinIosSimulatorArm64`, plus §2 evidence types. No slice is Complete on a partial pass or on *sim* evidence for a device claim. The pre-commit hook owns format/lint; slices do not re-run it (ADR-004).

| ID | State | Slice | Tier | Lane | Depends on | Evidence |
|---|---|---|---|---|---|---|
| W15-01 | Complete | SimWorld + Monte-Carlo accuracy gate + GHOST baselines | F (S) | F | — | origin fb8c059a501981bbe8141836039b339a6cc57962 · main 9da794c1e90a5abe276e22cf149a1c0a92e6106d · fix 3eb3cc55ab0d7eb7ebc9f1afc667e342258f1284 · test: ./gradlew --no-configuration-cache :shared:testAndroidHostTest --tests app.simt.echo.sim.SimHandsetTest --tests app.simt.echo.sim.MonteCarloGateTest --tests app.simt.echo.sim.SimWorldTest :shared:simWorldFastBaseline :shared:simWorldFullBaseline |
| W15-02 | Complete | Bundle perception v2.1 contract freeze (parallax, strips, leg σ) | S | A | — | origin e527028f82f35fc7ab04ea881bc528de735b801a · main c75ebd868d461b7510ec29237b12231ba6bfc004 · test: ./gradlew :shared:testAndroidHostTest --tests app.simt.echo.model.ModelJsonTest --tests app.simt.echo.model.ParallaxProfileTest --tests app.simt.echo.bundle.BundleCodecTest --tests app.simt.echo.transfer.ExportSelectionTest :shared:compileKotlinIosArm64 :shared:compileKotlinIosSimulatorArm64 |
| W15-03 | Complete | Frame Tap v2: dynamic duty, fan-out mux, intrinsics | S | G | — | origin 24099f2518e50a6397b6d04f4183818134e16d6b · main 8309ae277c9fdac41a94edc51315de26dd13d1ea · test: ./gradlew :shared:testAndroidHostTest --tests app.simt.echo.capture.FrameTapMuxTest --tests app.simt.echo.capture.FrameTapMuxAllocationTest --tests app.simt.echo.capture.FrameRateGovernorTest --tests app.simt.echo.capture.CameraIntrinsicsTest --tests app.simt.echo.platform.FrameTapAnalyzerTest --tests app.simt.echo.platform.FrameTapSourceTest --tests app.simt.echo.capture.RecordingLedgerTest --tests app.simt.echo.capture.CaptureSessionTest :shared:compileKotlinIosArm64 :shared:compileKotlinIosSimulatorArm64 |
| W15-04 | Complete | Generic `Dtw` extraction (copies 3 and 4 never made) | T (S) | C | — | origin 0f1b287d6f0f1354b33957098b681948eff869cd · main 54c5dd8b188eea74ebf17499261ce021260af2cc · test: ./gradlew :shared:testAndroidHostTest --tests app.simt.echo.nav.DtwTest --tests app.simt.echo.nav.DtwAllocationTest --tests app.simt.echo.nav.MagneticMatchTest --tests app.simt.echo.nav.RelockTest --tests app.simt.echo.nav.GhostFilterTest --tests app.simt.echo.nav.NavigatorTest :shared:simWorldFastBaseline |
| W15-05 | Complete | Pose mode + visual usability (hold angle, blur, texture, light) | T | G | W15-03 | origin 0c4fcc6084fb03b97796fe547f8dcad3ffb9964d · main 103cbbb5d396b002bf6c4b4c61d29a2948a71ab8 · test: ./gradlew --no-configuration-cache :shared:testAndroidHostTest --tests app.simt.echo.capture.visual.PoseModeTest --tests app.simt.echo.capture.visual.VisualUsabilityTest --tests app.simt.echo.capture.GlimpseLedgerTest |
| W16-01 | Complete | Visual flow kernel: pyramid, block SAD, de-rotation | S | G | W15-01, W15-03 | origin 18ea87c79b067c8d756aa8c03d1d635c22084fec · main 96d3360bee2fe58f6429dbe7cda828727a830cb8 · fix 14a377bc6b6b1221346ee37cbe9540091ab405e4 · test: ./gradlew --no-configuration-cache :shared:testAndroidHostTest --tests app.simt.echo.capture.visual.VisualFlowTest --tests app.simt.echo.capture.visual.VisualFlowAllocationTest --tests app.simt.echo.sim.SimPixelIntegrationTest --tests app.simt.echo.sim.SimWorldTest --tests app.simt.echo.capture.visual.VisualUsabilityTest :shared:simWorldFastBaseline |
| W16-02 | Complete | Ego-motion fit: expansion, FOE, yaw residual, confidence | S | G | W16-01, W15-05 | origin 91617e0e95d26ca45f0f47e72ce13eeae723708a · main 91617e0e95d26ca45f0f47e72ce13eeae723708a · fix 2080f673f1516b8f798f5c8fe8dc2804f9e0bc8d · test: ./gradlew --no-configuration-cache :shared:testAndroidHostTest --tests app.simt.echo.capture.visual.EgoMotionFitTest --tests app.simt.echo.capture.visual.EgoMotionFitAllocationTest --tests app.simt.echo.capture.visual.VisualUsabilityTest |
| W16-03 | Complete | Heading triangulation: visual gyro-bias, `correctedYaw` applied, travel-direction offset | S | H | W16-02 | 68649688d1c4c444dc09505bc6971744a7c5d26d · test: ./gradlew :shared:testAndroidHostTest --tests app.simt.echo.capture.HeadingTriangulationTest --tests app.simt.echo.capture.HeadingQualityTest --tests app.simt.echo.capture.PdrEngineTest --tests app.simt.echo.nav.NavigatorTest --tests app.simt.echo.nav.GhostFilterTest --tests app.simt.echo.capture.visual.VisualSessionWiringTest :shared:simWorldFastBaseline |
| W16-04 | Complete | Visual step gate: phantom and missed steps | T (S) | H | W16-02 | 147632f9700796ce3fea36650b75c1bc1bf7e74c · test: ./gradlew :shared:testAndroidHostTest --tests app.simt.echo.capture.StepGateTest --tests app.simt.echo.capture.PdrEngineTest --tests app.simt.echo.capture.SegmenterTest --tests app.simt.echo.capture.HeadingTriangulationTest --tests app.simt.echo.nav.NavigatorTest --tests app.simt.echo.nav.GhostFilterTest :shared:simWorldFastBaseline |
| W16-05 | Complete | Android wiring: tap consumer, ring to nav thread, alloc/time proof | F (T) | G | W16-02, W15-03 | 6810116a5206748c10a6ee2a66f1ec310a5c757f · test: ./gradlew --no-configuration-cache :shared:testAndroidHostTest --tests app.simt.echo.capture.visual.VisualMotionConsumerTest --tests app.simt.echo.capture.visual.VisualImuHistoryTest --tests app.simt.echo.capture.visual.VisualSessionWiringTest --tests app.simt.echo.capture.visual.VisualAllocationHostTest --tests app.simt.echo.capture.visual.VisualImuHistoryAllocationTest --tests app.simt.echo.capture.visual.VisualCostBenchmarkTest --tests app.simt.echo.platform.BuiltInVisualMotionSourceTest --tests app.simt.echo.capture.RecordingLedgerTest --tests app.simt.echo.capture.CaptureSessionTest :shared:visualCostBenchmark :androidApp:assembleDebug :shared:compileKotlinIosArm64 :shared:compileKotlinIosSimulatorArm64 |
| W17-01 | Complete | Parallax profile capture on Record (ρ, τ, view strips) | S | G | W15-02, W16-02 | a7f2d3fce3c227beb5867881016ac26665ef5820 · test: ./gradlew :shared:testAndroidHostTest --tests app.simt.echo.capture.visual.ParallaxCaptureTest --tests app.simt.echo.nav.RouteHealthTest --tests app.simt.echo.bundle.BundleCodecTest --tests app.simt.echo.capture.CaptureSessionTest --tests app.simt.echo.capture.visual.VisualSessionWiringTest |
| W17-02 | Queued | Route-conditioned visual speed → stride `k` observation | S | C | W17-01, W15-04 | — |
| W17-03 | Queued | ViewFix: strip sequence match → Δheading, Δs | S | C | W17-01, W15-04 | — |
| W17-04 | Queued | Depth-profile signature + Relock integration | T (S) | C | W17-01, W15-04 | — |
| W18-01 | Queued | Route Polish: offline smoothing, per-leg σ, loop closure | S | I | W16-03, W17-01 | — |
| W18-02 | Queued | Route memory overlay: follower-side extra strips | T (S) | I | W17-03 | — |
| W18-03 | Queued | Adaptive particle budget + information-gain scheduler | S | C | W17-02, W15-03 | — |
| W19-01 | Queued | Honest-light visual UI: lock ring, hold-phone nudge, consent | T (V) | E | W17-02, W17-03, W18-03 | — |
| W19-02 | Queued | Trace Lab: local developer trace export and replay (P2) | T | F | W15-01, W16-05 | — |
| W19-03 | Queued | Performance gate on two devices | T | F | W16-05, W18-03 | — |
| W19-04 | Queued | Field A/B: GHOST vs GHOST+VISUAL, taped course | T (S) | F | W17-03, W18-03 | — |
| W19-05 | Queued | Security & privacy audit v3 | S | D | W17-01, W18-02, W19-02 | — |
| W19-06 | Queued | Parallax release gate (default decision) | S | F | W14-05, W19-01, W19-03, W19-04, W19-05 | — |

Tier totals: **F 2 · T 9 · S 12 · L 0** (primary; 23 rows). Lanes: A — contracts; C — navigation intelligence (`nav/`); D — audit; E — experience; F — proof; **G — visual perception (new)**; **H — heading & gait (new)**; **I — route learning (new)**.

Parallel batches (a batch starts when its rows' dependencies are Complete):

| Batch | Slices | Why it is safe |
|---|---|---|
| 15a | W15-01, W15-02, W15-03, W15-04 | Test fixtures, model/`EchoJson`, `FrameTap.kt`, `nav/ghost` DTW — disjoint files |
| 15b | W15-05 | After the tap seam |
| 16a | W16-01 → W16-02 | Same files, serial |
| 16b | W16-03, W16-04, W16-05 | `PdrEngine`/`HeadingQuality` vs `PdrEngine` step path serialise by owner (§7); Android wiring is disjoint |
| 17 | W17-01, then W17-02, W17-03, W17-04 | Capture first; the three `nav/ghost` slices serialise per §7 |
| 18 | W18-01, W18-02, W18-03 | Offline polish, persistence, filter budget — disjoint, except W18-03 serialises after W17 on `nav/ghost` |
| 19 | W19-01, W19-02, W19-05, then W19-03, W19-04, W19-06 | Gates last; release gate alone |

Critical paths (staff first): `W15-03 → W16-01 → W16-02 → W17-01 → W17-02 → W18-03 → W19-03 → W19-06`; `W15-01 → W16-01` (the lab precedes the kernel it judges); `W15-02 → W17-01 → W17-03 → W19-04`.

## 7. Shared-file ownership

| File / contract | Sole owner | Consumers wait for |
|---|---|---|
| `S/capture/FrameTap.kt` (+ `A/platform/FrameTapAnalyzer.kt`) | W15-03 (S) | W16-05, W17-01, W18-03 consume, never edit |
| `RoutePerception.kt`, `EchoJson`, `BundleCodec` | W15-02 only | W17-01 writes only through the freeze |
| `S/nav/ghost/*` and `Navigator.kt` | Lane C serial: W15-04 → W17-02 → W17-03 → W17-04 → W18-03 | Geometry-touching edits carry an S reviewer; no HTTP in `nav/` |
| `S/capture/PdrEngine.kt`, `HeadingQuality.kt`, `AndroidImuSource` | W16-03 (S), then W16-04 | No other slice edits sensor maths |
| New `S/capture/visual/*` (flow, fit, usability, scheduler) | Lane G: W15-05 → W16-01 → W16-02 → W16-05 | Pure kernels never import platform types |
| `shared/src/commonTest/.../sim/*` | W15-01 | Later slices add scenarios by *new files*, never edit the generator; W16-01 scoped exception: pixel-footprint integration with independent edge-coverage/convergence regressions, preserving geometry, seeded materials and accuracy thresholds |
| `docs/perf/accuracy-baseline.json` | W15-01 creates; updating it needs an S slice | A silent baseline edit is a BLOCK |
| `EchoConfig` (`visualAssist`, `navigatorEngine`) | W16-05 adds `visualAssist`; W19-06 decides default | — |
| Navigate screen and Settings consent surface | W19-01 | Visual-lock chip is read-only over the nav state |

## 8. Slice sections

Each section: tier rationale, what to build, acceptance. Blast radius is what is named here; widening it is an escalation (ADR-002).

### Wave 15 — Seam, contract, lab (measure before building)

#### W15-01 — SimWorld + Monte-Carlo accuracy gate
**F (S review of the metrics)**, Lane F. The reason this plan can be trusted before a walk exists. In `commonTest/.../sim/`: a deterministic procedural world (corridor, junction, open hall, stairwell landing) of textured planes with hash-noise albedo, ray-cast straight to the Frame Tap luma format (240×180 decimated) at the *true* camera pose; a gait generator (step cadence 1.6–2.2 Hz, bob, sway, hand-held yaw/pitch wobble, hold-angle offset), IMU and magnetometer synthesis (gyro bias random walk, ±2°/min rotation-vector drift, steel anomalies), motion blur on fast rotation, exposure/gamma/FOV per simulated phone, moving billboards as dynamic occluders. A `MonteCarloGate` runs N seeded walks per scenario and reports arrival error, σ calibration (is truth inside ±2σ 95 % of the time?), heading drift and relock distance. `LINEAR` and `GHOST` baselines are committed to `docs/perf/accuracy-baseline.json`; the gate fails on any regression > 5 % or a miscalibrated σ. Fast mode (100 walks) runs in CI under a named command; full (500) is manual. Acceptance: `SimWorldTest` (flow of a known translation/rotation recovered by an oracle; determinism: same seed ⇒ identical frames), `MonteCarloGateTest` (baselines reproduce; a deliberately broken filter fails the gate), baselines recorded. Explicitly **sim** evidence, not device.

#### W15-02 — Bundle perception v2.1 contract freeze
**S**, Lane A. Additive to W10-05, same compatibility rules (v1 apps ignore unknown keys; `schemaVersion` stays 2; a `parallaxVersion` inside the new object lets a v2.0 reader skip it). New `RoutePerception.parallax: ParallaxProfile?` = { `parallaxVersion`, `stepM` 0.5, `inverseDepth` base64 int8 per bucket (log₂ scale, 1/16-octave steps, 0 = unknown), `trackability` base64 uint8 per bucket 0–255, `viewStrips` every 2 m and at nodes: { `atDistanceM`, base64 96 B (64 normalised column values + 32 row values) }, `legSigmaRad` per leg (route-heading uncertainty), `estimatorId` + `intrinsicsClass` }. Decisions to record: base64 blobs over JSON number arrays (size); strips are **not reconstructable images** (one dimension, normalised, quantised) — S states and tests the argument; an unknown `estimatorId` ⇒ the follower ignores `inverseDepth` (a different estimator would not cancel); size cap stays 64 KB for the whole perception with the producer-side `validate()` extended; privacy: inherits the bundle's encryption, expiry and export-preview disclosure, and `PERCEPTION_DISCLOSURE` is extended in the same slice. Acceptance: `ModelJsonTest`/`BundleCodecTest` extended: v2.0 fixture reads under v2.1; v2.1 round-trip; v2.1→v2.0 behaviour (ignore); estimator-mismatch case; size-cap rejection; iOS compiles.

#### W15-03 — Frame Tap v2: dynamic duty, fan-out, intrinsics
**S**, Lane G. Closes H5 and freezes the seam additively. (1) `FrameRateGovernor.baseIntervalNs` becomes settable atomically (`setBaseInterval`) so a scheduler can slow or stop the tap; the thermal rules are unchanged. (2) `FrameTapMux`: a fixed-capacity (4) fan-out that implements `FrameConsumer`, registered once on the tap, with an **allocation-free** dispatch loop; consumers join/leave without reallocating. (3) `FrameConsumer.wantsFrame(tNs: Long): Boolean = true` (default method on the fun-interface): the analyzer asks the mux before decimating, so when no consumer wants a frame the cost is one comparison. (4) `CameraIntrinsics(fPx, cx, cy, source)` delivered with each tap session from Camera2 characteristics (sensor size, focal length, active array); when unavailable, a labelled 65° horizontal-FOV default (`source = DEFAULT`) — the approximation is *recorded*, not hidden (R3). (5) Document the clock precondition: visual rotation compensation requires REALTIME timestamp alignment (W10-03); otherwise the tap reports no usable visual channel. Acceptance: `FrameTapMuxTest` (fan-out order, add/remove during dispatch, zero-allocation dispatch asserted, `wantsFrame` short-circuit), `FrameRateGovernorTest` extended, existing `RecordingLedgerTest` and `CaptureSessionTest` untouched and green, `FrameTapAnalyzer` change reviewed by S for recording being unchanged.

#### W15-04 — Generic `Dtw`
**T (S review)**, Lane C. Starts with an inventory (`rg -n -i dtw shared/src`) classifying every hit in `MagneticMatch.kt`, `GhostFilter.kt` and `Navigator.kt` as implementation or reference. Extracts one allocation-free `Dtw` (Sakoe–Chiba band, caller-supplied distance function as an inline lambda so no boxing, caller-supplied scratch rows) into `nav/ghost/Dtw.kt`, and migrates both existing consumers in the same slice with no orphaned helpers or constants. Behaviour is bit-identical: `MagneticMatchTest` and `RelockTest` pass without a single assertion edited. Acceptance: new `DtwTest` (identity, shift, stretch, band limit, scratch-reuse allocation probe), `MagneticMatchTest`, `RelockTest`, `GhostFilterTest`, `NavigatorTest` green.

#### W15-05 — Pose mode and visual usability
**T**, Lane G. Pure, in `capture/visual/`. **Pose mode** from gravity direction and accelerometer spectrum: `HELD_UP` (camera forward, pitch 20–80° above horizon-down), `FLAT`, `SWINGING` (arm swing, 1–2 Hz dominant), `POCKET` (dark + stationary orientation), `EAR`. **Usability** `u ∈ [0,1]` multiplies: pose-mode factor, sharpness (existing `FrameQuality.sharpness`), exposure fraction (`exposureFraction`), texture (min-eigenvalue of the 2×2 structure tensor over the central region, a pure function of the luma plane), and a motion-blur proxy from angular rate (`FrameQuality.stability`). Output carries a **reason** (`FLAT`, `BLANK_WALL`, `BLUR`, `DARK`, `GLARE`, `POCKET`) so the UI can be honest. Reuses `FrameQuality`; adds no copy of its scoring. Acceptance: `PoseModeTest` and `VisualUsabilityTest` on synthetic gravity/accel traces and SimWorld frames: a blank wall scores `BLANK_WALL` with u < 0.2, a textured corridor with u > 0.7; flat hold never reports usable.

### Wave 16 — Visual displacement engine

#### W16-01 — Visual flow kernel
**S**, Lane G. Builds the 120×90 and 60×45 levels from the tap's decimated plane into two ping-ponged, preallocated `LumaView`s (no per-frame allocation). Twelve 16×16 blocks in a 4×3 grid; a block participates only if its structure-tensor min-eigenvalue clears the texture gate. **De-rotation first:** the rotation delta between the two frame timestamps (from the rotation vector the engine already has) inverse-warps each block by the finite pinhole rotation (`PinholeRotation` with `CameraIntrinsics`); the integer SAD search uses a ±3 px fine window around that prediction, with bounded radial expansion steps when the fine window lacks an interior minimum (covering 0.9–1.1 m/s walking at 8 Hz Record and 2 Hz Navigate spacings; 1 Hz straight-walk duty is not claimed here), then a parabolic sub-pixel refinement and two-window (32×32 and central 16×16) full-resolution refinement that drops border-clipped or crease-straddling blocks. The output per block is the *residual* — translation-only — flow, in focal-length-normalised coordinates so the result is FOV-independent. The inner SAD loop is written plain first; an 8-pixel `Long` SWAR variant is **adopted only if it is ≥ 2× on the mid device** (benchmark recorded, otherwise deleted — no dead alternative left in the tree). Acceptance: `VisualFlowTest` on SimWorld oracle sequences (pure forward translation, pure yaw, pure pitch, yaw + translation, blur, exposure step, ±0.15 rad rotation domain, 8 Hz / 2 Hz speed envelope): rotational flow cancelled to ≤ 0.15 px RMS, block flow within 0.2 px of the oracle; blank blocks excluded; `VisualFlowAllocationTest` (0 bytes after warm-up).

#### W16-02 — Ego-motion fit
**S**, Lane G. Recovers a depth-free focus of expansion from pairwise translation-flow intersections (`TranslationConsensus`) before fitting `r_i = d·(p_i − p_foe)` over the supporting residual flows by linear least squares with three Huber IRLS passes (dynamic occluders become outliers, not noise; mixed-depth oblique walls publish the intersection focus directly without single-depth yaw bias). Outputs `VisualEgo(expansionPerSec, foeX, foeY, yawResidualRadPerSec, confidence01, inliers)`. Confidence combines inlier count (≥ 6 of 12 for > 0.5), residual RMS, texture and `VisualUsability`; at `expansion ≈ 0` (standing, or sideways) or under tied rival direction clusters the FOE is **not** reported. The FOE's horizontal offset gives the angle between the camera axis and the travel direction: `travelOffsetRad = atan(foeX)` in normalised coordinates. Acceptance: `EgoMotionFitTest` on SimWorld: expansion within 8 % for forward walks, FOE within 2° for a held-off-axis phone (including rotated `SimPose` holds on mixed-depth walls), 30 % of blocks replaced by a moving billboard still within 15 % across all 4-of-12 placements, and confidence < 0.3 on a blank wall and on a flat hold; 0-allocation probe.

#### W16-03 — Heading triangulation
**S**, Lane H. Three independent witnesses of yaw rate: the **gyro** (high-rate, biased), the **magnetically-fused rotation vector** (absolute, corrupted near steel — gated by the field-magnitude variance already measured by `MagProfiler`/mag capture), and the **visual yaw residual** (from W16-02, bias-free but low-rate and confidence-gated). Fuse: (a) a visual gyro-bias estimator that works *while walking* (today only stillness estimates drift, W12-01); (b) apply `HeadingQuality.correctedYaw` to the engine yaw (closing the 0003 §13 owed row) behind `EchoConfig.visualAssist`, with quality `q` including a `magAnomaly` factor; (c) a slowly-varying travel-direction offset from the FOE added to the filter's heading alignment so the particle bias `b` no longer has to absorb the hold angle. The Ghost heading observation keeps its von Mises form; only its inputs get better. When confidence is 0, output is bit-identical to today. Acceptance: `HeadingTriangulationTest` (SimWorld 10 min walk: drift ≤ 5° with visual, ≈ 20° without; steel-anomaly corridor: magnetic gate lowers `q` and heading holds; blank-wall walk: visual silent, behaviour identical to baseline), `HeadingQualityTest`, `PdrEngineTest` green, no change to default-off behaviour proven by a golden-trace test.

#### W16-04 — Visual step gate
**T (S review)**, Lane H. The visual expansion rate is an independent *is-the-body-moving* witness. A step candidate is accepted when the accelerometer says step **and** (visual confidence is low **or** expansion is above a motion floor within ±0.4 s); steps are *added* only as a flagged `MISSED_STEP_SUSPECT` that raises σ rather than inventing distance (visual evidence is never converted into metres here — W17-02 does that). Acceptance: `StepGateTest`: shake-in-place scenario rejects ≥ 95 % of phantom steps with ≤ 1 % true-step false rejects; slow-shuffle scenario raises σ and logs suspects; with visual off, `PdrEngineTest`/`SegmenterTest` unchanged.

#### W16-05 — Android wiring and proof
**F (T review)**, Lane G. A `VisualMotionConsumer` joins `FrameTapMux` (Record and Navigate), runs W16-01/02 on the analysis thread, and publishes `VisualEgo` into a **fixed-size single-producer ring of primitive slots** (no boxing, no queues) that the navigation thread drains per step event. `EchoConfig.visualAssist` (default off) and `capture.json`'s `perceptionMode` record whether a walk used it. Thermal and `wantsFrame` hooks from W15-03 are honoured; with the setting off, the consumer is not registered and the analyzer does no extra work. Acceptance: `VisualMotionConsumerTest` (ring overflow drops oldest, never blocks), `VisualAllocationHostTest` (JVM `getThreadAllocatedBytes` shows 0 bytes per frame after warm-up), `VisualCostBenchmarkTest` on the host (relative cost recorded; the absolute ≤ 0.8 ms median is a W19-03 device number), existing `RecordingLedgerTest` green with the mux, `:androidApp:assembleDebug` and both iOS compile tasks.

### Wave 17 — Route-conditioned tracking (the frontier)

#### W17-01 — Parallax profile capture
**S**, Lane G. During Record, for each frame pair with confidence ≥ 0.5, compute `ρ = expansionPerFrame / Δs_odometer` where `Δs_odometer` is the PDR distance between the two frame timestamps. This is **expansion per metre travelled — a pure geometric quantity (≈ 1/Z for the dominant surface), independent of how fast the creator walked and independent of their stride error**, which is why the route is self-consistent in the creator's own metres. Take the median per 0.5 m bucket, quantise log₂ at 1/16 octave to one byte, 0 = unknown; trackability τ = mean confidence per bucket. Every 2 m and at each node, store a **view strip**: 64 column values (band-mean of horizontal gradient over rows 20–70 of the 90-row level) and 32 row values, each normalised zero-mean/unit-variance and quantised to int8. Everything feeds Route Health as *visual coverage* (extends W12-07 additively). The perception growth is capped at +6 KB per 60 m. Acceptance: `ParallaxCaptureTest` on SimWorld: recovered 1/Z within 15 % in the straight corridor and ≥ 70 % of buckets known; deterministic bytes for a seed; two simulated phones with different FOV and holding height produce profiles within 20 % of each other (the cancellation claim, tested rather than asserted); `RouteHealthTest` extended; `BundleCodecTest` round-trip through W15-02.

#### W17-02 — Route-conditioned visual speed
**S**, Lane C. The follower measures `expansionPerSec` and divides by the route's ρ at its estimated position (median over s ± (3σ + 2 m)): `v_visual = expansionPerSec / ρ(s)`, **in the creator's metres by construction**. `Observation.VisualSpeed(nominalMps, visualMps, sigmaMps)` is a Gaussian likelihood on each particle's stride scale `k` (particle speed `k·nominalMps` against `v_visual`), so `k` is learned within a few seconds, not only at anchors; `sigmaMps` carries confidence, ρ uncertainty over the s-window and |dρ/ds|·σ_s. It also feeds `StrideLearner` as a *weak, per-leg* prior so the persisted stride improves between anchors. Gate: τ ≥ 0.4 on both sides, `VisualUsability` ≥ 0.5, estimator ids equal. Acceptance: `VisualSpeedObservationTest`: stride 12 % off converges to ≤ 1 m arrival error (SimWorld), walking-faster-than-creator and slower-than-creator each recover the ratio within 8 %, a wrong-estimator route is ignored, a blank-wall leg leaves the filter unchanged, `NavigatorTest`/`GhostFilterTest` green on both engines; Monte-Carlo gate: arrival error p95 ≥ 30 % lower than the W15-01 GHOST baseline with no scenario > 5 % worse.

#### W17-03 — ViewFix
**S**, Lane C. At a stored view strip within s ± (3σ + 5 m), correlate the live strip over ±12 columns and five stretch factors (0.85–1.15). Best shift ⇒ `Δheading` (columns × FOV/64); best stretch ⇒ `Δs = (1 − 1/stretch)/ρ` — the fix is *geometric*, not just "similar". A **sequence** score over the last four live strips against the diagonal of stored strips (SeqSLAM-style, using the generic `Dtw` from W15-04 for speed tolerance) gates the observation; a margin over the runner-up is required, so repeated corridors do not alias. `Observation.ViewFix(nodeS, dHeadingRad, dSM, confidence)` drives heading *and* position softly (Gaussian on s, von Mises on the heading offset) — never a hard anchor and never an auto-confirm. Cost ≤ 0.1 ms. Acceptance: `ViewFixTest`: pure offset recovery (±3° and ±0.5 m on SimWorld), lighting change (gamma/gain) and 20 % moving occluders still match, a repeated-corridor decoy is rejected by the margin, a two-phone FOV mismatch within tolerance, allocation probe 0 bytes; heading drift scenario with ViewFix bounded ≤ 3° at each fix.

#### W17-04 — Depth-profile signature
**T (S review)**, Lane C. The creator's ρ(s) is a lighting-proof geometric signature of the corridor (door gaps, junctions, openings change it). The follower accumulates the last 10 m of (ρ, s-odometer) pairs and matches the subsequence by the shared `Dtw` against the route profile, emitting a **soft** `Observation.Magnetic`-style offset only when profile standard deviation clears a gate and the DTW cost beats a margin (same discipline as W12-04). Feeds Relock (0003 W12-06) as a fourth source after turn signature, fingerprints and magnetic. Acceptance: `DepthSignatureTest` (distinctive corridor recovers offset ± 1 m; uniform corridor emits nothing); `RelockTest` extended: mid-route drop relocks within 6 m on SimWorld, non-converging case still falls back honestly.

### Wave 18 — The route learns

#### W18-01 — Route Polish
**S**, Lane I. At Review, an offline pass over the recorded draft using what the live system could not: the whole trace, forward *and* backward. Per leg, the visual yaw residual removes heading drift (a leg-wise linear drift fit with the W12-01 estimator as prior); leg lengths stay the creator's PDR metres (no metric claim). A leg-wise heading σ is stored (`legSigmaRad`, W15-02), which the follower filter uses to cap its heading κ — the route is no longer treated as exact (H6). **Loop closure:** when start and destination views match (round trips), the closure error is distributed along the path proportionally to leg length and uncertainty. An *optional* soft Manhattan prior on turn angles is evaluated in SimWorld and **ships only if** it helps orthogonal scenarios without hurting the non-orthogonal one (otherwise deleted). UI shows only measured facts ("polished: heading drift −x°"), never a marketing score. Acceptance: `RoutePolishTest` on SimWorld: recorded-vs-truth path error reduced ≥ 25 % on a 10-leg route and not increased on any scenario; loop closure distributes error; idempotent (polishing twice changes nothing); deterministic; existing `SegmenterTest`/`ReviewViewModelTest` unchanged.

#### W18-02 — Route memory overlay (P1)
**T (S review)**, Lane I. After a successful follow (arrival σ < 2 m and visual lock for ≥ 60 % of the walk), store the *follower's* own view strips at the filter-confirmed positions in a local `RouteMemory` keyed by `routeId`: up to 3 extra strips per waypoint, LRU, exactly the route's expiry, deleted with the route and by "Delete all data" (already the single erasure path). Nothing leaves the device; the bundle is never rewritten. A later follow merges stored and overlay strips as additional candidates, so the route gets better at *this follower's phone, in this light*. Acceptance: `RouteMemoryRepositoryTest` (expiry, LRU, deletion paths), `ViewFixTest` extended: with an overlay recorded under changed lighting a decoy-lit match improves; a corrupted overlay entry is ignored, never a crash.

#### W18-03 — Adaptive particle budget and information-gain scheduler
**S**, Lane C. `GhostFilter` keeps fixed-capacity arrays (512) and a variable **active N** (96–512): shrink by systematic subsampling after σ < 1.0 m and q ≥ 0.7 for 10 s; grow on relock or σ > 3 m by jittered resampling; determinism preserved (seeded, N change is a function of state). `VisualScheduler` (pure) chooses the tap interval from σ, `VisualUsability`, turn proximity (a turn node within 8 m raises the rate), ambiguity (the route has a same-direction turn within 6 m), thermal and Battery Saver: 0 Hz while standing, 1 Hz when σ < 2 m straight, 2–4 Hz near turns/at high σ. Acceptance: `GhostFilterTest` (N change keeps the posterior mean within 0.2 m, σ monotone sane, same seed ⇒ same trace), `VisualSchedulerTest` (table-driven), Monte-Carlo gate shows no accuracy regression with the adaptive N and analyzer duty ≤ 1 Hz average on the straight-corridor scenario; no new allocation in `predict`/`observe` (probe).

### Wave 19 — Experience and proof

#### W19-01 — Honest-light visual UI
**T (V review)**, Lane E. Reads only existing navigation state. A thin **visual-lock ring** inside the σ aura lights only while `VisualEgo.confidence ≥ 0.5` *and* the filter actually used a visual observation in the last 2 s; its brightness is that confidence (honest light). When usability reports `FLAT`/`BLANK_WALL`/`DARK`, a quiet single-line nudge ("Hold the phone upright and ahead" / "Blank wall — using steps") appears and auto-clears; no modal, no sound. Settings: **Visual assist** toggle with the exact disclosure — uses the camera while you walk, analysed on the phone, no images stored or sent; the route stores only tiny non-image profiles. Static tier shows the same facts as text. Acceptance: `VisualLockUiTest`, `SettingsViewModelTest` extended, a11y semantics (state announced, contrast pairs and fontScale 2.0 inherited from the Luminous freeze), V-reviewed frames in `docs/screenshots/W19-01/` with SHA; no animation without a bound signal.

#### W19-02 — Trace Lab (P2)
**T**, Lane F. A developer-only, local, opt-in recorder of the *derived* signals (rotation, steps, `VisualEgo`, strips — **no JPEGs, no frames**) with an exporter and a replay source, so a walk that fails in the field becomes a regression test: replay through `ReplayMotionSource` + a `ReplayFrameTap`, compare engines side by side. Acceptance: `TraceReplayTest` (record → replay → identical filter trace), export is a local file the user shares deliberately, and the privacy audit (W19-05) signs the field list.

#### W19-03 — Performance gate on two devices
**T**, Lane F — hardware-bound; stays Queued, never faked (0003 W14-01 discipline). Two named phones, a 10-minute Navigate walk: visual frame cost ≤ 0.8 ms median / ≤ 2 ms p99, zero allocation (device `Debug.getThreadAllocCount` delta), analyzer duty, battery ≤ +1.0 pp vs the W14-01 baseline, thermal step-down, Record jank ≤ +1 pp vs the W10-01 baseline with the mux. Evidence: `docs/perf.md` rows with device, SHA, numbers; absent hardware ⇒ the row stays Queued.

#### W19-04 — Field A/B
**T (S review)**, Lane F — hardware-bound; stays Queued. Reuse the V2 §11 taped course: 5 alternating runs each of GHOST and GHOST+VISUAL by two walkers on two phones, in a place with texture and a place without. Metrics: arrival error, σ calibration, relock count, heading error at checkpoints. Decision rule written before the runs (as 0003 W14-03): `visualAssist` defaults on only if p95 arrival error improves ≥ 20 % **and** no walk is worse by > 1 m; otherwise it ships flag-off and says so.

#### W19-05 — Security & privacy audit v3
**S**, Lane D. Reviews the new surfaces: strips and ρ in the bundle (not reconstructable; encrypted container; expiry), `RouteMemory` (local, expiring, erasable), Trace Lab fields, camera use disclosure, **no new HTTP payload field** (grep `transfer/` and `worker/` types for `strip|parallax|inverseDepth`: zero), the LAN token R1/R3 residuals from 0003 W14-04 re-verified. Verdict is exactly BLOCK / APPROVE WITH RESIDUAL RISK (listing what was not reviewed) / APPROVE.

#### W19-06 — Parallax release gate
**S**, Lane F. Decides the default of `visualAssist` from W19-03/04 evidence, reconciles README claims to slice SHAs or recorded runs, and refuses to mark anything Complete on *sim* evidence alone. Depends on 0003 W14-05 so there is one release gate for the product, not two.

## 9. Risks and what we do about them

| Risk | Mitigation |
|---|---|
| **R1** Expansion ≈ Δs/Z only for a fronto-parallel surface; side walls and floors bias it | The estimator runs identically on both phones, so bias enters ρ and `v_visual` the same way and cancels in the ratio to first order. W17-01 tests *two simulated phones* against each other; if a hold-angle/FOV pair drifts past 20 %, W17-02 falls back to the 2-parameter *relative* speed (follower expansion ÷ creator expansion at the same s) and drops the pose/FOV-sensitive path |
| **R2** Blank concrete garages, glass, glare, crowds | Usability and confidence gate everything; at confidence 0 behaviour is bit-identical to 0003 Ghost; Route Health surfaces low-τ legs at record time |
| **R3** Intrinsics are approximate (default FOV when Camera2 cannot report them) | `CameraIntrinsics.source` is recorded; rotational de-rotation error is bounded by tests at ±15 % focal length; ratio use is FOV-independent (normalised coordinates) |
| **R4** Time alignment of frames and IMU | W15-03 requires REALTIME timestamp source (W10-03); otherwise the visual channel reports itself unavailable instead of guessing an offset |
| **R5** Battery and thermals | Information-gain scheduler, `wantsFrame` skip, 0 Hz while standing, SEVERE ⇒ off; W19-03 owns the number |
| **R6** Visual evidence over-trusted by the filter | Observations are soft with explicit σ; the Monte-Carlo gate includes adversarial scenarios (decoy corridor, crowd, wrong estimator); a σ-calibration check fails the build on over-confidence |
| **R7** Image-derived data leaking | One-dimensional normalised int8 profiles, no frames in the bundle or in traces, encryption/expiry inherited, W19-05 audit |
| **R8** Synthetic gains do not transfer | Every accuracy number is labelled *sim* until W19-04; the default is flag-off until a field A/B says otherwise |
| **R9** `GhostFilter` growth (file-size limits, `simt.json` `fileLines` 400 warn / 1000 error) | Visual observations land in `nav/ghost/` files of their own (`VisualSpeed*`, `ViewFix*`), not in `GhostFilter.kt`; W18-03 checks the line budget before editing it |

## 10. What this plan deliberately does not do

It does not add a camera use case or a second camera owner. It does not claim metric depth. It does not let visual evidence confirm a landmark or move the user without the filter's own uncertainty. It does not send a pixel, a strip or a profile to any server, and it adds no network call to `nav/`. It does not change V2 §6.4 phase semantics, thresholds or copy. It does not mark anything Complete on synthetic evidence for a field claim, and it does not widen any 0003 row's Complete claim.

## 11. References (named, not fetched in this session — slice authors verify before citing)

- Block-matching/pyramidal optical flow and the focus-of-expansion least-squares model: Bouguet, *Pyramidal Implementation of the Lucas-Kanade Feature Tracker*; Longuet-Higgins & Prazdny, *The Interpretation of a Moving Retinal Image*.
- Texture/trackability gate: Shi & Tomasi, *Good Features to Track* (min-eigenvalue criterion).
- Illumination-invariant census/rank transforms: Zabih & Woodfill, *Non-parametric Local Transforms for Computing Visual Correspondence*.
- Sequence-based place recognition under appearance change: Milford & Wyeth, *SeqSLAM*.
- Robust fitting: Huber M-estimators with iteratively reweighted least squares.
- Internal: `docs/plans/0003-echo-luminous.md` §3–§5, §12, §13; `docs/research/SIMT_ECHO_IMPLEMENTATION_PLAN_V2.md` §6.2, §6.4, §11; the simt-app Track VIO fusion (`VioFusionManager`) as the *contrast* case — an exclusive-camera design this plan deliberately does not copy.
