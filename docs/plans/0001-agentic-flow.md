# Plan 0001 — Agentic Flow for Parallel Implementation

Status: **queued** · created 2026-11-01

Owning repository: `simt-echo` (this repo). Secondary repository: `simt-app`
(producer of reused Track / Connect / Iris / core code; see §4).
Hackathon deadline: 2026-11-04 21:00 ET. Slice states use the tracker
vocabulary: `Queued` (ready when its dependencies are `Complete`),
`In progress`, `Complete`. The `Wn-nn` id pattern is registered in
`simt.json` (`plans.id.pattern`) so `simt plan` can index these rows.

## 1. Scope

This plan does not re-slice the work. It takes the 36 slices already defined in
`SIMT_ECHO_IMPLEMENTATION_PLAN_02.md` (§9 tracker, §10 per-slice detail) and adds
the three things that document does not give a multi-agent team:

1. **A model tier per slice** — which slices a small local model can execute
   alone, which need a cloud flash model, and which need a frontier smart model
   (§2, tracker in §6).
2. **Parallel lanes and batches** — the dependency DAG expressed as lanes that
   never edit the same files at the same time (§3, §5).
3. **A reuse map against `simt-app`** — for every ECHO platform seam, the
   concrete `simt-app` class or module to reuse, adapt, or deliberately not
   reuse, with the fallback Plan 02 already specifies (§4). This resolves the
   `_tbd_` rows in Plan 02 §2.1.

Out of scope: changing any Plan 02 contract (§5), algorithm (§6), wire format,
or cut order (§12). Where this plan and Plan 02 disagree, Plan 02 wins on
*what* is built; this plan governs *who* builds it, in what order, and with
which model.

Acceptance for this document itself:

- [x] This file exists at `docs/plans/0001-agentic-flow.md` and
      `simt plan status` reads its tracker (36 queued, 2 ready at creation).
- [x] Every Plan 02 slice id (`W0-01`…`W5-04`, 36 ids) appears exactly once in
      the §6 tracker table, each with tier, lane, dependencies, and an
      evidence column (verified mechanically: 36 rows, 0 duplicate ids).
- [x] No slice whose primary tier is **L** is sole-authored on cryptography,
      privacy, sensor geometry, or the Navigator state machine (each such
      slice carries an **S** reviewer in §6; tier totals: 7 L-primary,
      18 F-primary, 11 S-primary).
- [x] §4 maps every ECHO seam (`MotionSource`, `PeerTransport`, `QrPayload`,
      `CameraRecorder`, design system, Iris overlay, Wear haptics,
      persistence) to a concrete `simt-app` class/module with a
      reuse / adapt / new verdict and a named fallback.

## 2. Model tiers

Tiers are capability classes, not vendor model names. Assign a slice to the
cheapest tier that can finish it; do not assign by habit or by availability.

| Tier | Name | Use when all of these hold | Never alone on |
|---|---|---|---|
| **L** | Small local model | Behaviour is fully specified in Plan 02 (code, thresholds, or JSON shape given verbatim); change is single-module and mostly pure/deterministic; a test oracle already exists in the plan; failure is cheap and local | Crypto, privacy, geometry, Navigator |
| **F** | Cloud flash model | Cross-file Android / Compose / CameraX / Ktor / Worker plumbing; moderate ambiguity resolvable by compiling and iterating; integration with an already-frozen contract | Contract changes, security design |
| **S** | Frontier smart model | Contract or architecture freeze; security/privacy design; sensor-geometry validation; cross-repo reuse decisions; provider routing; merge and release gates | — (this is the ceiling) |

Rules that bind every tier:

- **Escalation.** A slice escalates one tier after two failed test runs on the
  same cause, or immediately when the work touches a contract, security,
  privacy, or geometry — regardless of who started it.
- **Review pairing.** The §6 tier column reads `Primary (Reviewer)`. An
  **L**-primary slice on a sensitive surface carries an **S** reviewer who
  reads the staged diff before commit; **F**-primary contract-adjacent work
  carries an **S** reviewer at the same point.
- **Where frontier tokens go.** Contract freezes (W0-04, W2-02, W2-03),
  geometry validation (W1-01, W2-05), and merge/release gates (W3-06, W4-06).
  Frontier models do not write boilerplate that an **L** slice can finish
  against a frozen contract.
- **One agent, one slice, one worktree.** `simt slice start <id>` before any
  edit; `simt slice finish <id>` runs the gates and commits from the worktree;
  `simt plan done <id> <sha> --test "<cmd>"` records the evidence. Never direct
  two agents at the same files concurrently.
- **Producer before consumer, across repos too.** If a slice needs a new
  capability in `simt-app` (notably a byte-transfer path in Connect, §4), the
  `simt-app` change lands and is committed first; the ECHO adapter slice
  starts only after that SHA exists.

## 3. Parallel lanes

| Lane | Owns | Slices |
|---|---|---|
| A — Foundation & contracts | Scaffold, domain model, version/DI decisions | W0-01…W0-04, W5-03 |
| B — Capture & sensors | Motion, camera, segmentation, media | W1-01…W1-05 |
| C — Core loop | Bundle, transfer, navigator (offline-critical path) | W2-02, W2-03, W2-05, W2-07 |
| D — Worker & AI | Cloudflare worker, scout/ground pipeline, relay | W0-05, W3-01…W3-09, W4-05, W5-01 |
| E — UI & UX | Screens, review editor, navigation UX, polish | W2-01, W2-04, W2-06, W4-04, W5-02 |
| F — QA, field & release | Trials, calibration, robustness, release | W4-01…W4-03, W4-06, W5-04 |

Parallel batches (a batch starts when every dependency in §6 is done):

| Batch | Slices that run concurrently | Why it is safe |
|---|---|---|
| 0 | W0-01, W0-05 | Recon touches no code; worker scaffold is a separate repo area |
| 1 | W0-02 | Everything else waits on the scaffold and its version decision |
| 2 | W0-03, W0-04, W3-03 | UI shell, domain contract, and worker jobs touch disjoint files; W0-04 freeze is reviewed by **S** before Batch 3 starts |
| 3 | W1-01, W1-02, W2-02, W2-05, W3-04 | Sensors, camera, crypto, navigator, and scout chain share only the frozen W0-04 model |
| 4 | W1-03, W1-05, W2-03, W2-04*, W3-05 | Record flow, keyframes, transport, grounding (*W2-04 UI starts against the W2-03 interface, finishes against its implementation) |
| 5 | W1-04, W2-01, W2-06*, W3-02, W3-07* | Draft route, review editor, consent/processing (*integration slices; they serialise on the Review screen, §5) |
| 6 | W2-07, W3-06, W3-08, W4-01…W4-05 | Fallbacks, privacy, trials, calibration, polish — mostly independent leaves |
| 7 | W4-06, then W5-* | Release gate first; all P2 stretch work is cut before any P0 slice (Plan 02 §12) |

Dependency wave referenced by the release slices in §6 (`wave P0` = every
P0 build slice — i.e. all P0 rows except the dependent release slice itself —
must be `Complete` before the dependent slice is ready):

| Wave | Slices |
|---|---|
| P0 | W0-01, W0-02, W0-03, W0-04, W0-05, W1-01, W1-02, W1-03, W1-04, W1-05, W2-01, W2-02, W2-03, W2-04, W2-05, W2-06, W2-07, W3-01, W3-02, W3-03, W3-04, W3-05, W3-06, W3-07, W3-08, W4-01, W4-02, W4-03, W4-04 |

Critical path (no parallelism can shorten it):
`W0-02 → W0-04 → W1-01 → W1-04 → W2-01 → W2-06`, with the worker chain
`W0-05 → W3-03 → W3-04 → W3-05 → W3-06 → W3-07` converging at W3-07/W2-01.
Staff the critical path first; an idle **S** agent takes the next critical-path
slice, not a leaf.

## 4. Reuse map: `simt-app` → ECHO

`simt-app` is an Android (non-KMP) app: modules `app.simt.feature.*` on
`:core:di`, `:core:ui`, `:core:ui-camera`, `:core:common`, `:core:domain`,
Hilt DI, AGP 9.5.0 / Kotlin 2.4.21. ECHO's `shared/` is KMP. That mismatch
decides the reuse mechanism, and W0-01 must settle it before W0-02 finishes:

- **Android-only code** (Track, Connect, Iris, camera) can be consumed from
  `androidApp` as Android libraries, or source-ported into `simt-echo`.
- **KMP `shared/` code cannot import Hilt or Android library modules.** Reuse
  inside `shared/` means porting the *design* (interfaces, sealed results,
  pure algorithms), not linking the module.
- Recommended default for W0-01: link, don't copy, where `androidApp` is the
  consumer (Track, Connect transport, Iris, camera); port where `shared/` is
  the consumer (domain patterns, QR payload shape). Copying Track's PDR into
  ECHO would fork a tested sensor stack three days before a deadline.

| ECHO seam (Plan 02 §5) | `simt-app` source | Verdict | Fallback if reuse fails |
|---|---|---|---|
| `MotionSource` | `feature/track`: `pdr/PdrEngine.kt` (StateFlows for steps, `currentPosition`, `headingDegrees`, `accuracy`; `start(stepDetectionEnabled)`), plus `MahonyAhrs`, `WeinbergEstimator`, `TiltProjector`, calibration classes — all unit-tested; `vio/VioProvider`, `ArCorePositionProvider`, `VioFusionManager` | **Adapt.** Build `TrackPlusMotionSource` in `androidApp` mapping `PdrEngine` flows to `Flow<MotionUpdate>`. Do not reimplement Plan 02 §6.1–6.2 as new code. VIO only if it does not contend with ECHO's video recording for the camera; both phones run the same `MotionSource` | `BuiltInPdrMotionSource` exactly as specified in Plan 02 §6.1–6.2 (that spec becomes the implementation, not a duplicate) |
| `PeerTransport` (bulk bytes) | `feature/link` `offline/OfflineTransport` (+ `OfflineTransportRouter`, Wi-Fi Aware → Wi-Fi Direct), `network/LinkTcpSession`, `LinkTcpHandshake`, `SecurityManager` | **Adapt.** Reuse transport-up-to-IP, discovery, and the TCP session layer behind ECHO's `PeerTransport` (`offer`/`fetch: ByteArray`). This is the strongest reuse in the repo | `KtorLanTransport` (Plan 02 W2-03) behind the same seam |
| Peer identity / pairing trust | `feature/link`: `LinkRepository` (`core/domain/link`), `security/LinkIdentitySecurity`, `handoff/LinkBootstrap*`, admission policies | **Adapt for trust, reject for bytes.** `LinkRepository` is peer-*message* oriented (`sendMessageToPeer(peerId, String)`, pulses, locations) with no arbitrary-byte/file API. Reuse discovery, pairing, and identity; do not tunnel bundle bytes through short validated text messages. A byte path is either a new Connect capability in `simt-app` (producer-first, §2) or ECHO's own transport | ECHO token-in-QR trust model (Plan 02 §5 wire format) with no Connect dependency |
| `QrPayload` | `feature/link` `qr/LinkQrPayload` (`DecodeResult.Valid(bootstrap: LinkBootstrap)`), `qr/LinkQrWidgets`; `core/ui-camera` `scanner/QrCodeScanner` | **Adapt.** Reuse the scanner component and the payload-codec *pattern*. `LinkQrPayload` carries a `LinkBootstrap`, not ECHO's `simt-echo://r?v=1&h=&p=&t=&k=&s=&n=` URI (≤200 chars), so ECHO keeps its own codec — a good **L**-tier slice against the frozen format | Plan 02 `QrPayload` codec as specified (this is the primary; the adaptation is in scanning/rendering only) |
| `CameraRecorder`, `MediaTools` | `core/ui-camera`: `capture/SystemImageCaptureController`, `CameraUriUtils`, preview/overlay components | **Reuse where the API fits** (still capture, URI handling, preview); CameraX silent-video recording itself is ECHO code in W1-02 if the controller cannot express it | Raw CameraX in W1-02 as planned |
| Design system, nav shell (W0-03) | `core/ui`: theme (`Color`, `Theme`, `Type`, `Shape`, `Motion`), components, `PermissionGate`; `core/navigation`: `AppRouter`/`ScreenRoute` | **Adapt tokens, own the shell.** Port ECHO's dark-first tokens (Plan 02 §8: bg `#0B0D10`, Echo Lime `#C6F432`) onto `core/ui` conventions and reuse components/`PermissionGate`. `core/navigation` is `simt-app`'s router; ECHO's Compose nav shell stays ECHO's to avoid importing an app graph into a KMP scaffold | Plan 02 W0-03 design system written fresh |
| Iris overlay (W5-02) | `feature/iris`: `IrisScreen`, `IrisViewModel`, `IrisProjection`, `IrisTargetSpatialCalculator`, components (`IrisCameraView`, `TargetDirectionArrow`, `TargetInViewMarker`, `IrisTargetHud`) | **Reuse as the base** for Iris-lite; ECHO adds landmark anchoring, not a new overlay stack | Drop W5-02 (P2; first in the Plan 02 §12 cut order) |
| Wear haptics (W5-04) | `data/wear`: `WearDataLayerManager`, `WearDataLayerRepository`, `WearSyncProtocol`, `DataLayerPaths` | **Reuse** the Data Layer transport; ECHO adds the haptic-pattern messages (`Haptics` enum) | Drop W5-04 (P2) |
| Persistence & domain patterns | `core/domain`: dependency-free repositories returning sealed `RepositoryResult`; `data/local` `SettingsDataStore`; `feature/track` `TrackRepository` session/checkpoint patterns | **Port the pattern** into `shared/` (`RouteRepository`, `FileStore`); do not link `core/domain` from KMP. Track's recovery-checkpoint pattern is the model for W4-03 process-death recovery | Plan 02 §5 storage layout (`drafts/`, `routes/`, `trials/`) as specified |
| Haptics (device) | No dedicated haptics helper exists in `simt-app` core | **New.** Small Android actual for the `Haptics` seam; waveforms from Plan 02 §8 | — |
| DI | `simt-app` uses Hilt (`:core:di`, `di/LinkModule`) | **Do not reuse across the KMP boundary.** `androidApp` may use Hilt for Android actuals; `shared/` uses the manual `AppGraph` from Plan 02. One DI system per module, never both in `shared/` | Manual `AppGraph` everywhere |

Version drift to settle in W0-01/W0-02: Plan 02 pins Kotlin 2.4.20 / AGP 9.2.0
/ CMP 1.12.0; `simt-app` builds with AGP 9.5.0 / Kotlin 2.4.21. If ECHO links
`simt-app` Android modules, ECHO's Android build must align to the newer pins.
Staying on Plan 02's pins is only coherent with source-porting. This is the
one decision that changes W0-02's build files, so it is made by the **S** agent
on W0-01 and recorded in the W0-02 commit body.

## 5. Shared-file ownership

Two agents never edit these at once; the named lane owns the file for the
whole plan, and other lanes consume it through its interface:

| File / contract | Sole owner | Consumers wait for |
|---|---|---|
| Domain model & `EchoJson` (Plan 02 §5) | Lane A (W0-04) | The W0-04 commit; no lane invents a field |
| `gradle/libs.versions.toml`, root build files | Lane A (W0-02) | The version decision in §4 |
| Worker `types.ts` / API contract | Lane D (W3-03) | The frozen request/response shapes |
| Review screen | Lane E (W2-01), then W3-07 by handoff only | W3-07 starts only after W2-01 is committed; it is the one planned serial handoff |
| `EchoNavHost` / `AppGraph` | Lane A (W0-03) | Screen slices register routes, they do not edit the graph |
| `EchoConfig` (thresholds, step-K) | Lane F (W4-02) after W2-06 | Algorithms read config; they do not hard-code past it |

## 6. Tracker

Evidence naming: `./gradlew :shared:allTests` (KMP slices — note Plan 02's
`:echo:shared:allTests` path is stale for this repo's layout), the exact
worker command `npm test` in `worker/`, or a named manual check for field
slices. A slice is `done` only with its evidence recorded, per Plan 02 §0.

| ID | State | Slice | Tier | Lane | Depends on | Evidence |
|---|---|---|---|---|---|---|
| W0-01 | Queued | Repo recon: settle Track/Connect reuse + version alignment (§4) | S | A | — | — |
| W0-02 | Complete | KMP scaffold (verify existing root `shared/` + `androidApp`) | F (S) | A | W0-01 | aa6d306af993833ba654563a653af16a2b82435e · test: JAVA_HOME=/Users/saidelimam/.sdkman/candidates/java/21.0.12-tem DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer ./gradlew clean :shared:testAndroidHostTest :shared:compileKotlinIosArm64 :shared:compileKotlinIosSimulatorArm64 :shared:compileTestKotlinIosSimulatorArm64 :androidApp:assembleDebug :androidApp:lintDebug (BUILD SUCCESSFUL, ScaffoldSmokeTest 2/2); worker: npm run typecheck + npm test (1/1) |
| W0-03 | Queued | Design system + nav shell on `core/ui` conventions | F | A | W0-02 | — |
| W0-04 | Queued | Domain model, `FileStore`, `RouteRepository`, `L_ROUTE` fixture — contract freeze | S | A | W0-02 | — |
| W0-05 | Queued | Worker scaffold: Hono, auth, rate limit, R2, deploy | F (S) | D | — | — |
| W1-01 | Queued | `TrackPlusMotionSource` adapter over `PdrEngine` (+ built-in fallback) | S | B | W0-04 | — |
| W1-02 | Queued | `CameraRecorder`: CameraX silent video, `core/ui-camera` where it fits | F | B | W0-03 | — |
| W1-03 | Queued | Record screen + `CaptureSession` + Finish | F | B | W1-01, W1-02 | — |
| W1-04 | Queued | `Segmenter` → draft `EchoRoute` + minimap | L (S) | B | W1-03 | — |
| W1-05 | Queued | `MediaTools`: keyframes + crop | L (F) | B | W1-02 | — |
| W2-01 | Queued | Review screen + manual landmark editor + Save | F (S) | E | W1-04, W1-05 | — |
| W2-02 | Queued | `BundleCodec`: `ECHO` container, SHA-256, AES-256-GCM `ECX1` | S | C | W0-04 | — |
| W2-03 | Queued | `PeerTransport` over Connect offline transport + `QrPayload` codec | S | C | W2-02 | — |
| W2-04 | Queued | Share + Receive QR screens (`QrCodeScanner` reuse) | F | E | W2-03 | — |
| W2-05 | Queued | `Navigator` + `TurnDetector` + `ReplayMotionSource` + 7 scenario tests | S | C | W0-04 | — |
| W2-06 | Queued | Align / Navigate / Arrived screens + haptics | F (S) | E | W2-05, W2-01 | — |
| W2-07 | Queued | `.echo` file fallback: share/import after privacy warning | L (S) | C | W2-02 | — |
| W3-01 | Queued | Clips (Media3) + `ManifestBuilder` | L (F) | D | W1-05 | — |
| W3-02 | Queued | Consent + `EchoApiClient` + Processing screen | F (S) | D | W3-01, W0-05 | — |
| W3-03 | Queued | Worker jobs API + Workflow skeleton (contract freeze) | F (S) | D | W0-05 | — |
| W3-04 | Queued | Workers AI scout chain (Llama Scout → Gemma fallback) | F (S) | D | W3-03 | — |
| W3-05 | Queued | Grounding: box-only path + SAM 3.1, track selection/ranking | S | D | W3-04 | — |
| W3-06 | Queued | Assemble pipeline + vitest + live latency/cost check | F | D | W3-05 | — |
| W3-07 | Queued | Merge AI candidates into Review (mask overlay) | F (S) | E | W3-02, W3-06, W2-01 | — |
| W3-08 | Queued | Privacy export preview, expiry, delete-job | L (S) | D | W3-07 | — |
| W3-09 | Queued | Muse Spark scout provider (optional, P1) | F (S) | D | W3-05 | — |
| W4-01 | Queued | Trials: baseline, drift, CSV export | L (F) | F | W2-06 | — |
| W4-02 | Queued | Calibration: step-K + `EchoConfig` | F (S) | F | W2-06 | — |
| W4-03 | Queued | Robustness: permissions, process-death recovery | F | F | W2-06 | — |
| W4-04 | Queued | UX polish, accessibility, app icon | L (F) | E | W3-07 | — |
| W4-05 | Queued | Relay fallback: zero-knowledge R2 relay (P1) | S | D | W2-04, W0-05 | — |
| W4-06 | Queued | README, limitations, demo script, release APK | S | F | wave P0 | — |
| W5-01 | Queued | Local-model provider (P2) | S | D | W3-07 | — |
| W5-02 | Queued | Iris-lite overlay on `feature/iris` (P2) | F (S) | E | W2-06 | — |
| W5-03 | Queued | iOS actuals (P2) | S | A | wave P0, W4-06 | — |
| W5-04 | Queued | Wear OS haptic bridge on `data/wear` (P2) | F | F | W0-01 | — |

## 7. Slice sections

Each section states the tier rationale and the reuse hook; the build steps,
files, and done-when criteria are Plan 02 §10 under the same id and are not
repeated here.

### W0-01 — Repo recon and reuse decision
**S**, Lane A. The Plan 02 §2.1 adapter table is `_tbd_` because its author
could not read `simt-app`; §4 of this plan is the inventory that replaces it.
Done when W0-01's commit records: Track adapter viability against the Plan 02
decision rule (per-step or ≥5 Hz distance + relative yaw, ≤2 h to adapt, no
exclusive camera conflict), the Connect byte-path verdict, and the version
alignment (§4). Acceptance: the §2.1 table filled with named classes, no
`_tbd_` remaining.

### W0-02 — KMP scaffold
**F (S review)**, Lane A. The repo already contains a root scaffold
(`shared/`, `androidApp/`, `worker/`); this slice verifies and aligns it
rather than regenerating it — including the AGP/Kotlin pins decided in W0-01.
Acceptance: `./gradlew :shared:allTests` green on the scaffold.

### W0-03 — Design system and nav shell
**F**, Lane A. Port ECHO's §8 tokens onto `simt-app` `core/ui` conventions;
reuse components and `PermissionGate`; keep ECHO's own Compose nav shell.
Acceptance: shell navigates every §8 route with placeholder screens.

### W0-04 — Domain model and contract freeze
**S**, Lane A. The single most load-bearing slice in the plan: every lane
consumes `EchoRoute`/`RouteNode`/`RouteLeg`/`Landmark`, storage layout, and
`EchoJson` from here. Frontier-owned because a contract change after Batch 3
starts invalidates four lanes at once. Acceptance: model + `L_ROUTE` fixture
round-trip through `EchoJson` in `commonTest`.

### W0-05 — Worker scaffold
**F (S review of auth)**, Lane D. Independent of the Android scaffold; starts
in Batch 0 so the worker chain never blocks on Gradle. Acceptance:
`GET /v1/health` live, auth and rate limit enforced on all other routes.

### W1-01 — MotionSource from Track
**S**, Lane B. Geometry is a hard gate: a wrong yaw or step length silently
corrupts every downstream slice. The work is an adapter over the tested
`simt-app` `PdrEngine` (§4), plus validation that its flows satisfy the
`MotionUpdate` contract — not a reimplementation of Plan 02 §6.1–6.2, which
survives only as the built-in fallback. Acceptance: recorded-walk fixture
produces monotonic distance and stable relative yaw on both implementations.

### W1-02 — CameraRecorder
**F**, Lane B. CameraX plumbing with `core/ui-camera` reuse where the API
fits. Acceptance: silent video records alongside W1-01 motion with no frame
starvation on the target device.

### W1-03 — Record screen and CaptureSession
**F**, Lane B. Joins motion + camera into one session with a Finish flow.
Acceptance: a 30–80 m walk produces one complete `CaptureLog` on disk.

### W1-04 — Segmenter
**L (S review)**, Lane B. The algorithm is fully specified in Plan 02 §6.3
(resample 0.5 m, turn ≥45°, level Δh > 2.2 m, merge < 3 m, drop nodes < 4 m),
pure, and testable against fixtures — exactly the local-tier profile. The
**S** reviewer checks the geometry edge cases (hysteresis, mark merging)
before commit. Acceptance: §6.3 fixture walks segment to the expected nodes.

### W1-05 — MediaTools
**L (F review)**, Lane B. Keyframe extraction and crop against frozen input
/output shapes. Acceptance: keyframes land at the §7 timestamps and sizes.

### W2-01 — Review screen and landmark editor
**F (S review)**, Lane E. Owns the Review screen file for the whole plan
(§5); W3-07 later extends it by explicit handoff. The **S** review covers the
creator-verification semantics (`CREATOR_VERIFIED` vs `AI_SUGGESTED`).
Acceptance: manual landmark create/edit/delete round-trips to disk.

### W2-02 — BundleCodec
**S**, Lane C. Cryptography is never local-tier and never unreviewed:
`ECHO` container, SHA-256 integrity, AES-256-GCM `ECX1` encryption per §5.
Acceptance: round-trip + tamper-detection tests; wrong key fails closed.

### W2-03 — PeerTransport and QrPayload
**S**, Lane C. Security-sensitive (token + key in the QR URI) and the slice
where the Connect reuse decision (§4) becomes code: offline transport and TCP
session from `feature/link` behind the `PeerTransport` seam, ECHO's own
`QrPayload` codec for the URI itself. Acceptance: bundle bytes move between
two devices in airplane mode; QR URI ≤ 200 chars and rejects tampering.

### W2-04 — Share and Receive screens
**F**, Lane E. UI over the frozen W2-03 interfaces, reusing
`QrCodeScanner`. Acceptance: scan-to-receive completes against W2-03's
implementation, not a mock.

### W2-05 — Navigator
**S**, Lane C. The state machine (Plan 02 §6.4: ALIGN/WALK/APPROACH/TURNING/
CONFIRM/UNCERTAIN/OFF_ROUTE/ARRIVED, σ growth, corridor snapping) plus
`TurnDetector` (§6.5) and the 7 replay scenario tests. Frontier-owned: honest
uncertainty and wrong-start behaviour are acceptance criteria A5/A6 of the
proposal, not details. Acceptance: all 7 scenarios pass on
`ReplayMotionSource` with sensors only — no HTTP import in `nav/` (§0 rule).

### W2-06 — Navigation screens and haptics
**F (S review)**, Lane E. Screens over the W2-05 state machine; the new
Android `Haptics` actual (§4 — no reusable helper exists). **S** reviews that
UI states map 1:1 to Navigator states, especially UNCERTAIN. Acceptance:
replay-driven navigation reaches ARRIVED with correct haptic sequence.

### W2-07 — `.echo` file fallback
**L (S review)**, Lane C. A codec-shaped slice against the frozen W2-02
format; the **S** review is for the plain-file privacy warning only.
Acceptance: export/import round-trip; warning shown before any plain export.

### W3-01 — Clips and ManifestBuilder
**L (F review)**, Lane D. Deterministic manifest JSON from frozen shapes.
Acceptance: manifest validates against the §5 schema for the `L_ROUTE`
fixture media.

### W3-02 — Consent, API client, Processing screen
**F (S review)**, Lane D. The **S** review covers consent and what leaves
the device (proposal A7). Acceptance: no upload occurs before explicit
consent; Processing reflects real job states.

### W3-03 — Worker jobs API and Workflow skeleton
**F (S review)**, Lane D. Freezes the worker contract (`types.ts`, §5 owner)
that W3-02 already consumes. Acceptance: job lifecycle
create → poll → result → delete under auth, in vitest.

### W3-04 — Workers AI scout chain
**F (S review)**, Lane D. Provider plumbing against the §7 routing order
(Llama 4 Scout, then Gemma fallback via AI Gateway); **S** reviews the
strict-schema prompt/SCHEMA handling. Acceptance: a real clip yields
schema-valid candidates or a typed failure, never free text.

### W3-05 — Grounding (box-only + SAM 3.1)
**S**, Lane D. The §6.6 selection/ranking formulas and the SAM response
parsing decide what a creator is shown; a subtle ranking error reads as an
AI error in the demo. Acceptance: fixture SAM responses produce ranked
candidates with `maskSvgPath` null-degrading to box-only, never crashing.

### W3-06 — Pipeline assembly and live check
**F**, Lane D. End-to-end worker assembly, vitest, and the live
latency/cost measurement against the §7 envelope (~$0.16/route, < 2 min).
Acceptance: one real route processes inside the envelope, cost recorded.

### W3-07 — Merge candidates into Review
**F (S review)**, Lane E. The planned serial handoff on the Review screen
(§5): starts only after W2-01 and W3-06 are committed. **S** reviews that AI
candidates can never mutate geometry — they attach to sensor-owned nodes or
they do not attach (Plan 02 §0 rule). Acceptance: candidates appear as
`AI_SUGGESTED` landmarks with mask overlay; graph unchanged by merge.

### W3-08 — Privacy preview, expiry, delete
**L (S review)**, Lane D. Mechanical against W3-07's shapes; **S** review is
mandatory because this slice *is* the privacy surface (proposal A7).
Acceptance: preview shows exactly what exports; delete removes job + media.

### W3-09 — Muse Spark scout (P1)
**F (S review)**, Lane D. Optional provider behind the §7 routing; first AI
slice cut if the schedule slips (§8). Acceptance: Spark candidates are
indistinguishable in shape from W3-04 candidates downstream.

### W4-01 — Trials and CSV
**L (F review)**, Lane F. Baseline/drift computation and CSV export against
frozen trial shapes. Acceptance: the §11 field protocol (5 + 5 runs)
produces a complete CSV with no hand-editing.

### W4-02 — Calibration and EchoConfig
**F (S review)**, Lane F. Step-K calibration changes geometry inputs, hence
the **S** review; this slice also takes ownership of `EchoConfig` (§5).
Acceptance: calibrated K measurably reduces drift on the W4-01 baseline.

### W4-03 — Robustness
**F**, Lane F. Permissions and process-death recovery, following `simt-app`
Track's recovery-checkpoint pattern (§4). Acceptance: kill-mid-capture and
kill-mid-navigation both recover to a resumable state.

### W4-04 — UX polish and accessibility
**L (F review)**, Lane E. Copy (≤ 6 words, §8), contrast, touch targets.
Accessibility is a hard gate, so the **F** reviewer verifies it rather than
the slice self-certifying. Acceptance: §8 screens pass the a11y checklist.

### W4-05 — Relay fallback (P1)
**S**, Lane D. Zero-knowledge relay over R2: the server must be unable to
read bundles, which is a security design, not plumbing. Acceptance: relayed
bundle is ciphertext end-to-end; server-side inspection shows no plaintext.

### W4-06 — Release: README, demo, APK
**S**, Lane F. The release gate is frontier-owned: limitations and
non-claims (no offline AI generation, no 3D reconstruction, no arbitrary
routing, no safety/evacuation use) must match what the APK actually does.
Acceptance: demo script runs end-to-end on the release APK; README's claims
each trace to a passing slice.

### W5-01 — Local-model provider (P2)
**S**, Lane D. New provider behind the §7 routing (on-phone LiteRT-LM /
LAN server class hardware). Frontier-owned for the provider-contract fit.
Cut before any P0 work (§8).

### W5-02 — Iris-lite overlay (P2)
**F (S review)**, Lane E. Built on `simt-app` `feature/iris` (§4), adding
landmark anchoring to its existing projection/HUD components. Cut before
any P0 work (§8).

### W5-03 — iOS actuals (P2)
**S**, Lane A. Largest single estimate in Plan 02 (6 h) and pure stretch;
starts only when every P0 row above is `Complete`.

### W5-04 — Wear OS haptic bridge (P2)
**F**, Lane F. Reuses `simt-app` `data/wear` Data Layer transport (§4);
ECHO contributes only the haptic-pattern messages.

## 8. Review of the source documents

Findings from reading the proposal and Plan 02 against both repos. None of
these block Batch 0; each names the slice that resolves it.

1. **AI provider story differs between the two documents.** The proposal
   (v1.0) puts Meta Model API (Muse Spark 1.3 + SAM 3.1) on the creation
   path and names existing Track+/Iris/Connect as foundations. Plan 02 (v1.2)
   makes a Cloudflare Worker with Workers AI the default and the Meta API
   optional (`META_API_KEY`). Plan 02 is the later, more specific document
   and this plan follows it — but the demo script (W4-06) must tell one
   story, and "Push the Frontier / Meta models" judging makes the choice
   visible. Resolved by: W4-06, informed by W3-06's live cost/latency and
   W3-09's status.
2. **Module location and test path are stale in Plan 02.** It plans an
   `echo/` tree inside `simt-app` and gates on `./gradlew :echo:shared:allTests`;
   this repo is already a standalone scaffold with root `shared/` and
   `androidApp/`, so the gate is `./gradlew :shared:allTests`. Resolved by:
   W0-02 verifying the existing scaffold instead of regenerating it.
3. **Plan 02 §2.1 (adapter mapping) is entirely `_tbd_`.** The inventory now
   exists (§4): Track's `PdrEngine` is a mature, tested sensor stack that
   exceeds the §6.2 spec; Connect's `LinkRepository` has no byte-transfer
   API, so bundle transfer reuse is real at the transport layer and absent
   at the repository layer. Resolved by: W0-01.
4. **Version drift.** Plan 02 pins AGP 9.2.0 / Kotlin 2.4.20; `simt-app`
   builds on AGP 9.5.0 / Kotlin 2.4.21. Linking `simt-app` modules forces
   alignment; source-porting does not. Resolved by: W0-01 decision, applied
   in W0-02.
5. **Architecture mismatch limits reuse to `androidApp`.** `simt-app` is
   Android-library + Hilt; ECHO's `shared/` is KMP. Any plan that assumes
   `shared/` can import Track or Connect modules is wrong; §4 splits reuse
   into *link* (Android side) and *port the pattern* (KMP side).
6. **Proposal acceptance A1–A8 is fully covered by Plan 02 slices** —
   A5/A6 by W2-05/W2-06, A7 by W3-02/W3-08, A1–A4/A8 by W3-05/W2-03/W4-01/
   W4-06. No proposal requirement is unowned; no Plan 02 P0 slice lacks a
   proposal reason. The proposal's illustrative landmark JSON remains
   explicitly non-normative; Plan 02 §5/§7 schemas govern.

## 9. Cut order and escalation

Plan 02 §12 governs and is restated here so agents under time pressure do
not improvise: cut Wave 5 → W3-09 → W4-05 → mask overlays → SAM video
grounding → AI entirely. Never cut: capture (W1-01…W1-03), segmenter
(W1-04), bundle (W2-02), transfer (W2-03/W2-04), navigator (W2-05),
alignment/uncertainty (W2-06), trials (W4-01). A no-AI ECHO that walks a
route from Phone A to Phone B offline is a complete demo; an AI-only ECHO
that cannot is not.

Blocked-slice rule (Plan 02 §0): after 45 minutes blocked, apply the slice's
named fallback and note it in the evidence column — for reuse slices the
fallback is always the §4 right-hand column, which is why every reuse row
has one.
