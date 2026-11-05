# Review 0015 — Parallax visual assist: stationary phone still "walks"

Status: **complete** · created 2026-11-04 · Verdict: **BLOCK**

Symptom (device, visual assist **on**, well-textured scene): a phone held still
keeps counting steps / advancing — the W16-04 visual step gate does not veto
phantom steps. `0389aeb` fixed a real ordering bug but not the symptom.

Scope, at HEAD `026ab36` (unchanged since `f46bc24`; `S/` = `shared/src/commonMain/kotlin/app/simt/echo`,
`A/` = `shared/src/androidMain/kotlin/app/simt/echo`): `S/capture/{PdrEngine,VisualStepGate,HeadingQuality,FrameTap,FrameQuality,CaptureSession}.kt`,
all of `S/capture/visual/`, `S/nav/Navigator.kt`, `S/nav/ghost/{GhostFilter,VisualSpeedObservation,ViewFix,DepthSignature}.kt`,
`S/ui/screens/navigate/{NavigateViewModel,NavigateScreen}.kt`, `A/platform/{AndroidImuSource,FrameTapAnalyzer,CameraXRecorder}.kt`,
and the tests that claim the behaviour (`VisualMotionConsumerTest`, `StepGateTest`, `sim/SimWorld.kt`).

Method: full code read, then a scratch **host simulation probe** (not committed;
R15-01 turns it into the harness) driving the real `VisualMotionConsumer` →
`EgoMotionFit` → `PdrEngine`/`VisualStepGate` with `SimWorld` frames under
conditions the committed tests do not model (§1). **No device was driven**; sim
evidence never closes a device claim (plan 0004 §2).

Severity: **Critical** — the gate cannot veto phantom steps as reported;
**High** — the fix is unreliable or unverifiable; **Medium** — wrong downstream
behaviour or dishonest state; **Low** — hygiene. Critical and High BLOCK.

## Tracker

| id | state | slice | tier | lane | depends on | evidence |
|---|---|---|---|---|---|---|
| R15-01 | Complete | Realistic stationary/walking harness: `SimWorld` temporal noise + pitch blur, hand-hold kinematics driving camera *and* IMU, frame latency, 8/4/2 Hz; red on HEAD | T (S) | test | — | 436ed86 |
| R15-02 | Complete | F1 confidence ≠ usability: drop angular-rate "stability" from usability and ego confidence, stop double-counting texture, publish `expansionSigmaPerSec` | S | visual | R15-01 | df0601a |
| R15-03 | Complete | F3 gate decision rule: tri-state MOVING/STILL/UNKNOWN on window-mean **signed** expansion vs its standard error; no `abs`, no single-sample pass | S | gait | R15-02 | 9f81e20 |
| R15-04 | Complete | F2 deferred verdict + rejected frames are evidence: hold a candidate until a post-candidate frame lands (≤ 0.6 s), explicit fail-open policy, `unwitnessed` counter | S | gait | R15-03 | 0e2bb44 |
| R15-05 | Complete | F4 single-writer engine: visual ego reaches `PdrEngine` through an SPSC inbox drained on the IMU thread; monotonic update time | T (S) | gait | R15-04 | 16ed508 |
| R15-06 | Complete | F5 witness-rate floor: thermal MODERATE must not take the gate below 4 Hz (or widen the window); scheduler may never pause the witness for "standing" | T | visual | R15-04 | d5fa079 |
| R15-07 | Complete | F7 missed-step suspects: same evidence rule as R15-03, quadrature σ in LINEAR, reset at anchors | T | nav | R15-03 | 47b2197 |
| R15-08 | Complete | F8 honest state + telemetry: recency-based `visualAssistActive`, gate counters on the trial record and research HUD | T | nav | R15-04 | 7e387a7 |
| R15-09 | Complete | F9 tracker honesty: W17-03 ViewFix, W18-02 route memory, W18-03 scheduler are Complete but unwired — reopen or wire | L | plan | — | W17-03 db6d588 · W18-02 8589099 · W18-03 909a4d6 |
| R15-10 | Complete | F10 IMU-side defence in depth: walking-onset confirmation + peak-to-valley amplitude (changes visual-off behaviour — owner decision) | T | gait | R15-05 | 377e994 |
| R15-11 | Queued | Device gate: stationary/bobbing/walking protocol on two named phones (§4) | T (V) | device | R15-01…R15-08 | runbook [docs/testing/r15-11-device-gate.md](../testing/r15-11-device-gate.md) · not yet run |

R15-11 is the only open slice: no device was driven, so the verdict stays
**BLOCK** until its two-phone evidence lands. R15-01…R15-10 are complete with
the SHAs above.

---

## 0. Why it still fails — the causal chain

1. A hand-held phone that "bobs" enough to fire the accelerometer step detector
   (peak > 0.9 m/s² after the 0.25 low-pass, `PdrEngine.kt:197-217`) is also
   **rotating** (±2° at 2 Hz ≈ 0.44 rad/s peak in the probe).
2. That rotation is already removed from the flow by the gyro de-rotation
   (`VisualMotionConsumer.kt:104-118`, `VisualFlow` warp), but the angular rate
   is *also* multiplied into usability as `FrameQuality.stability(ω) = 1/(1+ω/0.6)`
   (`VisualUsability.kt:42-45`, `FrameQuality.kt:95-96`) and usability is
   multiplied again into the ego confidence (`EgoMotionFit.kt:107-108`).
3. Confidence therefore collapses from ~0.95 to **~0.5** exactly when the phone
   bobs — the gate's threshold is `MIN_VISUAL_CONFIDENCE = 0.5`
   (`VisualStepGate.kt:154`).
4. Every sub-threshold, rejected, missing or late frame is treated as "no
   witness", and no witness means **ACCEPT** (`VisualStepGate.kt:120`,
   `VisualMotionConsumer.kt:127-131` never calls `onEgo` for rejected frames, and
   the `+0.4 s` half of the window can never be populated at decision time).
5. Net: the gate abstains whenever the phone moves the way that creates
   phantom steps, and the committed test that "proves" ≥ 95 % rejection
   (`VisualMotionConsumerTest.kt:302-374`) never moves the camera at all.

The visual signal itself is good: still |expansion| p90 ≤ 0.010 s⁻¹ versus
walking ≥ 0.10 s⁻¹ in every environment probed — a 10× margin. The defect is
in how confidence is computed and how the gate interprets absence of evidence.

## 1. Evidence — host simulation probe

Real `VisualMotionConsumer(onEgo = engine::onVisualEgo)` + `PdrEngine(visualAssist = true)`,
`SimWorld(seed = 1234567, pixelSamplesPerAxis = 4)`, perfect gyro (orientation
from the rendered pose), `PoseMode.HELD_UP` forced, accelerometer
`az = 9.81 + 2.2·sin(2π·2t)` at 50 Hz for 12 s (≈ 24 phantom candidates).
"Bob" = camera z displaced consistently with that acceleration (13.9 mm), pitch
±0.035 rad at 2 Hz, 1 cm fore/aft sway at 0.5 Hz; "noise" = per-frame temporal
noise ≈ 2 DN (SimWorld's own noise is fixed-pattern, see F6).

| Scenario | CORRIDOR | OPEN_HALL | JUNCTION |
|---|---|---|---|
| A — existing test conditions (still camera, 8 Hz, no noise) | conf 0.99 · **0/24** phantom accepted | 0.96 · **0/24** | 0.98 · **0/24** |
| B — still + temporal noise, 4 Hz | 0.97 · 0/24 | 0.93 · 0/24 | 0.96 · 0/24 |
| C — hand bob + noise, 4 Hz | 0.58 · **3/22** | 0.49 · **17/24** | 0.53 · 0/24 |
| D — C + 120 ms frame latency | 0.58 · **6/23** | 0.49 · **24/24** | 0.53 · **2/20** |
| E — D at 2 Hz (thermal MODERATE) | 0.60 · 1/20 | 0.55 · **7/22** | 0.57 · **5/21** |
| F — walking 1.2 m/s + bob, 4 Hz, 120 ms | 0.49 · 24/24 accepted *blind* (8/48 frames ≥ 0.5) | 0.49 · 24/24 *blind* (1/48) | 0.49 · 24/24 *blind* (8/48) |
| G — C with angular rate reported as 0 | 0.93 · **0/24** | 0.79 · **0/24** | — |
| H/I — walking (no bob / bob, rate 0) | 0.83 / 0.79 · 24/24 seen moving | 0.82 / 0.78 · 24/24 | — |

"conf" is the median ego confidence. |expansion| p90: still/bob 0.002–0.010 s⁻¹,
walking p50 0.104 (OPEN_HALL) – 0.268 (CORRIDOR). Plan budget W16-04 is
**≥ 95 % phantom rejection / ≤ 1 % true-step false-reject**; rows C–E miss it by
up to 100 points. Row G isolates the cause (F1): same motion, same pixels, only
the reported ω differs. Row F shows the gate is also blind while walking — it
keeps real steps only because it abstains.

## 2. Findings

### F1 — Angular rate double-penalises confidence after it was already compensated (Critical → R15-02)

- `VisualUsability.evaluate` (`VisualUsability.kt:40-45`) multiplies
  `sharpness × exposure × texture × stability(ω)`; frames with usability < 0.5
  are rejected outright (`VisualMotionConsumer.kt:97-100`).
- `EgoMotionFit.fit` multiplies that usability into confidence again
  (`EgoMotionFit.kt:107-108`), on top of per-block weights that already contain
  `textureFactor01` (`EgoMotionFit.kt:35`) — texture is counted twice, ω twice.
- Rotation is removed geometrically before matching, and the blur ω would
  predict is already measured by `sharpness`. Confidence is an uncalibrated
  product of 6–8 sub-unity factors; with rolling shutter and real blur a
  well-textured device frame sits *below* the probe's 0.49–0.58.

### F2 — The gate fails open on every gap, including the half of its window it can never see (Critical → R15-04)

- `evaluateStepCandidate` accepts when no confident sample is inside ±0.4 s
  (`VisualStepGate.kt:104-129`). It is called synchronously at the accelerometer
  zero-crossing (`PdrEngine.kt:221-226`), so samples from `t … t+0.4 s` do not
  exist yet; with 4 Hz frames and 80–150 ms camera+analysis latency the usable
  window is ≈ `[t−0.4, t−0.12]` — sometimes one frame, sometimes none (rows C→D).
- Rejected frames publish to the ring but never reach the engine
  (`VisualMotionConsumer.kt:127-131`; `onEgo` only at `:124`). The gate cannot
  tell "camera says unusable" from "no camera", and a reject also resets the
  flow pair (`flow.reset()`), so the *next* frame is unmeasured too.
- No written fail-open policy: pocket/flat, blank wall and "frame late" all collapse to ACCEPT.

### F3 — Decision rule: unsigned, single-sample, fixed floor (Critical → R15-03)

- `abs(expansions[i]) >= 0.015` (`VisualStepGate.kt:113`) counts contraction
  (phone pulled toward the body) as walking.
- One sample above the floor accepts (`break` at `:115`); one noisy frame in the
  window defeats every other STILL frame.
- `MOTION_EXPANSION_FLOOR_PER_SEC = 0.015` (`:157`) sits 1.5× above the probe's
  still p90 (0.010) in ideal sim; body sway of 1 cm at 2 m already reaches
  ≈ 0.016 s⁻¹. `EgoMotionFit` publishes no expansion uncertainty, so the gate
  cannot scale the threshold to the measurement.

### F4 — `0389aeb` made `PdrEngine` multi-writer (High → R15-05)

- `onEgo` runs `engine.onVisualEgo` on the **analysis thread**
  (`AndroidImuSource.kt:95`) while sensors run on `echo-imu`
  (`AndroidImuSource.kt:194-201`). It mutates `HeadingQuality` (`PdrEngine.kt:164`,
  `HeadingQuality.kt:160-188` — the bias read by `correctedYaw` on the IMU thread),
  the gate ring/counters (`VisualStepGate.kt:66-95` vs `:104-129`, plain fields,
  torn slot reads possible), and `correctNs`'s first-event state (`PdrEngine.kt:122-130`).
- `MISSED_STEP_SUSPECT` is emitted from the analysis thread with the *frame*
  time (`PdrEngine.kt:165-167`), older than the IMU updates already emitted;
  `Navigator.onMotion` assigns `lastMotionTNs = update.tNs` and feeds
  `turnDetector.onSample` (`Navigator.kt:315`, `:359`) with time going backwards.

### F5 — The witness rate silently halves (High → R15-06)

- `FrameRateGovernor.shouldEmit` doubles the interval at MODERATE
  (`FrameTap.kt:144-152`): Navigate's `VISUAL_GATE_INTERVAL_NS` (250 ms,
  `FrameTap.kt:176`) becomes 500 ms and the ±0.4 s window is often empty (row E).
- `VisualScheduler` returned `OFF_INTERVAL_NS` for `isStanding`
  (`VisualScheduler.kt:11-12`) — wiring it as designed would have switched the
  witness off precisely when the gate must veto, and "standing" inferred from
  step absence is circular when phantom steps are the problem. R15-06 capped
  standing at 2 Hz before R15-09 wired the scheduler.

### F6 — Tests prove only the ideal case (High → R15-01)

- `frameTimeEgoFeedsTheGateSoStaticBobbingIsRejected`
  (`VisualMotionConsumerTest.kt:302-323`): the accelerometer bobs but the camera
  pose is constant (`bobForTwelveSeconds`, `:354-374`), `TestInput` reports
  `angularRate = 0.0` and `HELD_UP` forever (`:398-430`), frames arrive at 8 Hz
  with zero latency on the same thread. That is probe row A — 100 % by construction.
- `SimWorld` noise is fixed-pattern only — `pixelBias` hashes `(seed, x, y)`
  with no time term (`sim/SimWorld.kt:331-333`) — so two frames of a still
  pose are bit-identical; motion blur is yaw-only (`:90-98`); no rolling shutter.
- No test drives the real `VisualImuHistory`/`PoseModeEstimator` with the same
  kinematics as the accelerometer, so the ω path of F1 is untested end to end.

### F7 — Missed-step suspects can push a still walker to UNCERTAIN (Medium → R15-07)

- A suspect needs only 2 consecutive frames with expansion ≥ 0.015 s⁻¹ and no
  accepted step for 0.8 s (`VisualStepGate.kt:74-93`) — sway-level noise (F3).
- LINEAR mode adds the 0.45 m inflation **linearly** (`Navigator.kt:319-326`):
  14 suspects (≈ 8.4 s at the 0.6 s cooldown) exceed `navUncertaintyMaxM = 6.0`
  (`EchoConfig.kt:110`). GHOST adds in quadrature (`GhostFilter.kt:269-273`).
- `missedStepSigmaBonusM` resets only on Align (`Navigator.kt:277`), never at an anchor.

### F8 — "Visual lock" state is a latch, and there is no field telemetry (Medium → R15-08)

- `hasUsableFrame` is set once and never cleared (`VisualMotionConsumer.kt:52`,
  `:122`); `visualAssistActive` (`AndroidImuSource.kt:79-80`) and the capture's
  `perceptionMode` therefore claim visual use for a whole session after one frame.
- Gate counters (`rejectedPhantomCount`, `acceptedStepCount`,
  `missedStepSuspectCount`) exist (`VisualStepGate.kt:39-49`) but reach no
  trial record or research surface, so the device budget cannot be measured.

### F9 — Plan rows marked Complete are not wired at runtime (Medium → R15-09)

- W17-03 ViewFix: `Navigator.onViewStrip` (`Navigator.kt:568`) has no production caller.
- W18-02 route memory: `RouteMemoryRepository` is constructed only to purge
  (`AppGraph.kt:87`, `:99`); nothing reads or writes entries during a walk.
- W18-03 scheduler: `VisualScheduler.intervalNs` has no production caller.
- The depth-signature fix is injected as `Observation.Magnetic`
  (`Navigator.kt:622`), reusing the magnetic likelihood for a visual fix.

**Fixed (R15-09):** `NavigateViewModel` now drains the live strip into
`Navigator.onViewStrip`, seeds the matcher from
`RouteMemoryRepository.loadOverlayStrips` and records the follower view strips
through `recordFollow` on arrival, and drives
`VisualScheduler.intervalForRouteNs` from each `NavUi`. The depth-signature
fix still uses `Observation.Magnetic`; its own type remains open.

### F10 — The accelerometer detector has no onset rule (Low, decision → R15-10)

- The first candidate after start or a > 2 s gap is accepted (`PdrEngine.kt:209`),
  any later one ≥ 280 ms after the last (`:216`), on `amax > 0.9` alone — no
  valley depth, no cadence consistency. With visual assist off this is the
  baseline's behaviour; it is also why the visual gate carries the whole veto.

Checked and sound: Navigate attaches at 4 Hz before motion starts
(`NavigateScreen.kt:148-151`); frames are on the IMU clock or analysis is not
bound (`FrameTapAnalyzer.kt:109-112`); `drainVisual` no longer double-feeds
(`AndroidImuSource.kt:118-119`); a ±2° bob stays `HELD_UP` (variance ≈ 6·10⁻⁴).

## 3. Slices, fixes and acceptance tests

Commands use the repo runner (`./gradlew :shared:testAndroidHostTest --tests …`).
Every slice names its test classes; a partial pass blocks. Existing assertions are
never weakened — where a behaviour moves (F1 stability), the old test is replaced
by one asserting the new contract, and the commit says why.

### R15-01 — Realistic harness (red on HEAD)

Change (test-only): `SimWorld` gains `temporalNoiseDn` (seeded per `tNs`),
pitch-rate blur next to the yaw blur, optional `readoutSeconds` rolling shutter;
new `sim/SimHandHold.kt` emits one consistent 50 Hz stream of camera pose,
accelerometer, rotation-vector matrix and gyro for `STILL_IN_HAND` (tremor +
1 cm sway), `BOB_IN_PLACE` (2 Hz, 13.9 mm, ±2°), `SHAKE` (3 Hz, ±5°),
`WALK(v)` (natural bob); new `capture/visual/GateScenarioRunner.kt` feeds the
real `VisualImuHistory` (so `PoseModeEstimator` and ω are real), delays frames
by a configurable latency, and runs at 8/4/2 Hz.

Acceptance tests — `app.simt.echo.capture.visual.StationaryGateAcceptanceTest`
(all three of CORRIDOR, OPEN_HALL, JUNCTION):

| AT | Scenario | Must hold | Closed by |
|---|---|---|---|
| AT-1 | `BOB_IN_PLACE`, 4 Hz, 120 ms latency | phantom acceptance ≤ 5 % of candidates | R15-04 |
| AT-2 | AT-1 at 2 Hz (thermal MODERATE) | ≤ 5 % | R15-06 |
| AT-3 | AT-1 at 8 Hz with a `RecordingLedger` + `ParallaxProfiler` on the tap | ≤ 5 % | R15-04 |
| AT-4 | `SHAKE`, 4 Hz, 120 ms | ≤ 20 %, every accepted candidate counted `unwitnessed` | R15-04 |
| AT-5 | `STILL_IN_HAND` 60 s | 0 steps (R15-04); 0 missed-step suspects (R15-07) | R15-07 |
| AT-6 | `WALK(1.2)` + bob, 4 Hz and 2 Hz, 120 ms | false-reject ≤ 1 %; ≥ 90 % of steps witnessed MOVING | R15-04 |
| AT-7 | `WALK(0.5)` OPEN_HALL | false-reject ≤ 1 % (UNKNOWN allowed, STILL not) | R15-04 |

Each lands as `@Ignore("R15-01: red until R15-0n — <HEAD numbers>")` (the written
pre-existing justification the test gate requires); the closing slice removes it.
The existing ideal-case test stays.
Command: `./gradlew :shared:testAndroidHostTest --tests "app.simt.echo.capture.visual.StationaryGateAcceptanceTest" --tests "app.simt.echo.sim.*"`.

### R15-02 — Confidence measures the fit, not the hand

Fix: remove `stabilityFactor01` from `VisualUsability.value01`; keep a hard
reject only where compensation is unreliable (ω above a declared limit, e.g.
1.5 rad/s, and the existing `MAX_ROTATION_RAD` per pair in `VisualFlow`). Use the
frame texture factor for usability *or* per-block weights, not both. Make
`EgoMotionFit` confidence a function of inlier count, mean block weight and
residual RMS only (usability gates; it does not multiply). Add
`VisualEgo.expansionSigmaPerSec` from the solved normal matrix (same method as
`profileYawSigma`), carried through `VisualEgoRing`.

Tests: `VisualUsabilityTest` (replace the ω-scaling assertion with: identical
luma at ω 0 and 0.45 rad/s → identical usability; ω ≥ limit → `BLUR` reject),
`EgoMotionFitTest.expansionSigmaMatchesMonteCarloSpread` (200 noise seeds on a
still scene: empirical std within 0.7–1.4× mean reported σ),
`VisualMotionConsumerTest.rotationCompensatedBobKeepsConfidence` (AT-1 pose
stream: median confidence ≥ 0.7 in all three environments), and blank wall /
dark / flat still yield confidence 0 (existing tests stay green). Plus
`HeadingTriangulationTest` and `:shared:simWorldFastBaseline` unchanged.

### R15-03 — Gate decides on evidence, in three states

Fix in `VisualStepGate`: per candidate window, compute the confidence-weighted
mean **signed** expansion `ē` and its standard error `σ̄` from
`expansionSigmaPerSec`. `MOVING` if `ē ≥ max(MOVING_FLOOR, 3σ̄)`; `STILL` if every
window frame is confident, `ē < STILL_CEILING` and `σ̄ ≤ STILL_SIGMA_MAX`;
otherwise `UNKNOWN`. Starting constants from §1 (tune in R15-01, declare once):
`MOVING_FLOOR = 0.04 s⁻¹`, `STILL_CEILING = 0.02 s⁻¹`, `STILL_SIGMA_MAX = 0.01 s⁻¹`.
Only `STILL` rejects. Contraction is never MOVING (backward walking is out of scope; say so in KDoc).

Tests: `StepGateTest` new cases — one noisy frame among STILL frames does not
accept; sustained contraction does not accept; `ē` between ceiling and floor is
UNKNOWN; σ̄ too large is UNKNOWN, not STILL.

### R15-04 — Deferred verdict; rejected frames are evidence

Fix: `PdrEngine` records a candidate (time, Weinberg length from the current
`amax/amin`) in a fixed-capacity pending queue (capacity 4, allocation-free)
instead of deciding immediately; it is resolved on the IMU thread when a frame
captured at ≥ `t + 0.15 s` has been delivered, or at IMU time `t + 0.6 s`.
The emitted update carries the candidate's `tNs`; refractory timing treats a
pending candidate as provisional and reverts on reject. `VisualMotionConsumer.reject`
delivers an UNKNOWN-with-reason sample to the gate. Written policy (KDoc + test):

| Window evidence | Verdict |
|---|---|
| visual assist off | ACCEPT immediately (bit-identical to today) |
| MOVING | ACCEPT |
| STILL | REJECT |
| UNKNOWN — pose FLAT/POCKET/EAR/SWINGING, or BLANK_WALL/DARK/GLARE | ACCEPT (camera cannot see) |
| UNKNOWN — frames late/missing while armed (confident frame < 2 s ago) | wait to deadline, then ACCEPT + `unwitnessed++` |

Tests: `StepGateTest.deferredVerdictUsesPostCandidateFrame`,
`PdrEngineTest.deferredStepsEmitInOrderWithCandidateTime` (distance monotonic,
lag ≤ 0.6 s), `PdrEngineTest.visualOffIsBitIdentical`,
`VisualMotionConsumerTest.rejectedFrameReachesGateAsUnknown`; remove the
R15-01 `@Ignore`s on AT-1, AT-3, AT-4, AT-6, AT-7 (and AT-5's step assertion) — all pass;
`:shared:simWorldFastBaseline` unchanged.

### R15-05 — One writer for `PdrEngine`

Fix: `onEgo` copies primitives into an SPSC inbox (fixed capacity, overwrite
oldest, sequence-guarded like `VisualEgoRing`); `PdrEngine` drains it at the top
of `onAccelerometer`/`onRotation` on the IMU thread, so `HeadingQuality`,
`VisualStepGate` and `correctNs` have one writer. Suspect updates are emitted
from the IMU thread with `tNs = max(lastEmittedNs, frameNs)`.

Tests: `PdrEngineThreadingTest.visualEgoIsAppliedOnlyOnImuThread` (enqueue
changes no gate state until the next IMU sample), `…concurrentFeedsKeepUpdatesMonotonic`
(two threads, 10 000 IMU + 2 000 ego samples: no exception, non-decreasing
`tNs`, counters sum to candidates), `NavigatorTest.suspectUpdateNeverRewindsTime`;
`BuiltInVisualMotionSourceTest`, `VisualSessionWiringTest` green.

### R15-06 — Keep the witness awake

Fix: while visual assist is attached, the gate interval is exempt from the
MODERATE doubling (SEVERE still stops analysis, and the gate then reports
UNKNOWN), or `WINDOW_NS` widens to `1.5 ×` the effective interval — pick one and
assert it. Constrain `VisualScheduler` before anyone wires it: `isStanding` may
lower the rate to ≥ 2 Hz but never `OFF` while visual assist is on, and
"standing" must come from visual STILL, not from step absence.

Tests: `FrameRateGovernorTest.visualGateIntervalSurvivesModerate`,
`VisualSchedulerTest.standingNeverPausesTheStepWitness`; AT-2 passes.

### R15-07 — Suspects on the same evidence

Fix: a suspect requires R15-03 `MOVING` over ≥ 3 frames; LINEAR inflation goes
in quadrature like `GhostFilter.inflateUncertainty`; the bonus resets at anchors
and confirms. `Navigator.kt` is already 1198 lines (over the 1000 ceiling):
the new policy goes in its own file (e.g. `nav/MissedStepPolicy.kt`), not into it.

Tests: `StepGateTest.swayIsNotASuspect`, `NavigatorTest.stillWalkerNeverGoesUncertain`
(60 s `STILL_IN_HAND`, LINEAR and GHOST: phase stays WALK, mean `s` moves < 0.5 m),
`NavigatorTest.anchorClearsSuspectBonus`; AT-5 un-ignored; existing slow-shuffle suspect test green.

### R15-08 — Honest lock, measurable gate

Fix: `visualAssistActive` = a confident frame within the last 2 s (no latch);
`Trial` gains gate counters (accepted / rejected / unwitnessed / suspects, additive
JSON field, default 0) written by `NavigateViewModel`; research HUD shows them.

Tests: `VisualMotionConsumerTest.lockDecaysWithoutConfidentFrames`,
`ModelJsonTest.trialGateCountersRoundTripAndDefault`, `NavigateViewModelTest`
asserting counters reach the saved trial; `VisualLockUiTest` green.

### R15-09 — Tracker honesty (landed)

Wired the three slices instead of relabelling them, each in its own worktree and
with its own acceptance test: ViewFix receives the live strip from the navigate
frame path (`db6d588`), route memory is read and written on a successful follow
(`8589099`), and the scheduler runs from each `NavUi` snapshot under R15-06's
constraint (`909a4d6`). The plan rows now read "wired at runtime" and the depth
fix keeps `Observation.Magnetic` until the next slice that touches it.

### R15-10 — IMU onset confirmation (owner decision)

Proposal: credit the first candidate after a gap only when a second one follows
within 0.3–1.2 s (then credit both), and require peak-to-valley `amax − amin`
above a declared floor. It changes visual-off step counts and the sim
baseline, so it needs an explicit decision and a recorded baseline update; it
is not needed to pass AT-1…AT-7.

**Decision (2026-11-05): accepted and implemented.** The detector holds the
first candidate after start or a > 2 s gap, credits both when the second lands
in 300–1200 ms, and requires `amax − amin ≥ 1.2 m/s²` as well as `amax > 0.9`.
`PdrEngineTest` covers a lone candidate, a credited pair, a late second
candidate, a gap onset and the amplitude floor. The 100- and 500-walk seeded
baseline gates pass unchanged; the committed accuracy baseline drives
`SimWalk`'s generated motion rather than `PdrEngine`, so no baseline artifact
edit was warranted. Visual-off step counts change only for an unconfirmed
onset.

## 4. Device acceptance (R15-11, manual named gate)

Two named phones (Pixel-class, mid-range Samsung), visual assist on, overlay on
and off, textured scene ≥ 2 m away, R15-08 counters recorded with phone and SHA.

1. **Still in hand, 60 s**, natural posture: ≤ 1 accepted step; no UNCERTAIN.
2. **Bob in place, 60 s**, deliberate 1–2 Hz vertical bob: rejected ≥ 95 % of
   candidates; Record HUD distance grows ≤ 0.7 m; Navigate never advances a leg.
3. **Same under thermal MODERATE** (warm device or forced): ≥ 95 %.
4. **Walk 50 m taped**, normal and slow pace: step count within ±2 % of a
   visual-off walk on the same course; `unwitnessed` ≤ 10 % of steps in a
   textured corridor.
5. **Flat on a table / in pocket**: verdicts are UNKNOWN; behaviour equals
   visual off.

Failing 2 or 4 on either phone keeps W16-04 flag-off per plan 0004 §4.

## 5. Residual risk and what this review did not do

- No device run; the sim lacks rolling shutter, lens distortion, auto-exposure
  and IMU noise/bias, and the probe forced `HELD_UP` and a perfect gyro (R15-01
  removes both). Expect device confidence below the sim's.
- Not reviewed in depth: `RoutePolish`, `DepthSignatureMatch`, `ViewFix` maths,
  `TranslationConsensus` (read, not stress-tested), iOS (no tap).
- Environment: here Maven Central returns 403 for `atomicfu:0.33.0` (added in
  `134ff88`); the probe ran offline with a temporary, reverted switch to the
  cached 0.28.0. Running the R15 tests needs that artefact reachable.

## 6. Open decisions

- R15-10: **resolved 2026-11-05 — accepted and implemented**; visual-off step
  counts change only for an unconfirmed onset.
- R15-06: **resolved — exempt at MODERATE, stop at SEVERE**, implemented in
  `FrameRateGovernor`/`VisualScheduler`.
