# Plan 0001 — Agentic Flow for Parallel Implementation

Status: **queued** · created 2026-11-01

Owning repository: `simt-echo` (this repo) — fully enclosed. `simt-app` is a
read-only reference: solved code is copied from it into this repo and becomes
ECHO code (see §4). It is never a dependency, never linked, never modified.
Hackathon deadline: 2026-11-04 21:00 ET. Slice states use the tracker
vocabulary: `Queued` (ready when its dependencies are `Complete`),
`In progress`, `Complete`. The `Wn-nn` id pattern is registered in
`simt.json` (`plans.id.pattern`) so `simt plan` can index these rows.

## 1. Scope

This plan does not re-slice the work. It takes the 36 slices already defined in
`docs/research/SIMT_ECHO_IMPLEMENTATION_PLAN_V2.md` (§9 tracker, §10 per-slice detail) and adds
the three things that document does not give a multi-agent team:

1. **A model tier per slice** — which slices a small local model can execute
   alone, which need a cloud flash model, and which need a frontier smart model
   (§2, tracker in §6).
2. **Parallel lanes and batches** — the dependency DAG expressed as lanes that
   never edit the same files at the same time (§3, §5).
3. **A copy map against `simt-app`** — for every ECHO platform seam, the
   concrete `simt-app` code to copy into this repo (vendored, renamed as
   ECHO code), or deliberately not copy, with the fallback Plan already
   specifies (§4). This resolves the `_tbd_` rows in Plan §2.1.

Out of scope: changing any Plan contract (§5), algorithm (§6), wire format,
or cut order (§12). Where this plan and Plan disagree, Plan wins on
*what* is built; this plan governs *who* builds it, in what order, and with
which model.

Acceptance for this document itself:

- [x] This file exists at `docs/plans/0001-agentic-flow.md` and
      `simt plan status` reads its tracker (36 queued, 2 ready at creation).
- [x] Every Plan slice id (`W0-01`…`W5-04`, 36 ids) appears exactly once in
      the §6 tracker table, each with tier, lane, dependencies, and an
      evidence column (verified mechanically: 36 rows, 0 duplicate ids).
- [x] No slice whose primary tier is **L** is sole-authored on cryptography,
      privacy, sensor geometry, or the Navigator state machine (each such
      slice carries an **S** reviewer in §6; tier totals: 7 L-primary,
      18 F-primary, 11 S-primary).
- [x] §4 maps every ECHO seam (`MotionSource`, `PeerTransport`, `QrPayload`,
      `CameraRecorder`, design system, Iris overlay, Wear haptics,
      persistence) to a concrete `simt-app` source to copy from, with a
      copy / write-new verdict and a named fallback.

## 2. Model tiers

Tiers are capability classes, not vendor model names. Assign a slice to the
cheapest tier that can finish it; do not assign by habit or by availability.

| Tier | Name | Use when all of these hold | Never alone on |
|---|---|---|---|
| **L** | Small local model | Behaviour is fully specified in Plan (code, thresholds, or JSON shape given verbatim); change is single-module and mostly pure/deterministic; a test oracle already exists in the plan; failure is cheap and local | Crypto, privacy, geometry, Navigator |
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
- **Enclosed copying only.** Nothing in this repo depends on `simt-app`: no
  Gradle module, no library, no import, no runtime link. When a slice needs
  solved code, it copies the needed files/logic from `simt-app` into
  `simt-echo` in the same commit, renamed into ECHO packages with no
  Track+/Connect/Iris type names, and from then on it is ECHO code —
  maintained, tested and committed here. Gaps in the copied source (e.g. no
  byte-transfer API, §4) are filled with new ECHO code, never by modifying
  `simt-app`.

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
| 7 | W4-06, then W5-* | Release gate first; all P2 stretch work is cut before any P0 slice (Plan §12) |

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

## 4. Copy map: `simt-app` → ECHO (vendored, enclosed)

`simt-app` is a separate Android app and stays untouched. It is a quarry,
not a dependency: when part of a problem is already solved there, the
slice copies the needed code into `simt-echo` instead of reinventing it.
Rules for every copy:

- **Enclosed.** No build file in this repo references `simt-app`; copied
  code imports only ECHO and third-party types. If it doesn't compile
  standalone here, it wasn't copied completely.
- **Renamed.** Copied code lands in ECHO packages under ECHO names.
  No type is named after a `simt-app` feature (`TrackPlus*`, `Link*`,
  `Iris*` as class names, `simt_track_plus` as a source string, …).
  Provenance is credited in the commit body / code comment, not in names.
- **Owned.** From the copying commit onward the code is maintained and
  tested here; upstream fixes in `simt-app` are never picked up
  automatically — re-copy deliberately if ever needed.
- **Minimum slice.** Copy the smallest part that solves the problem
  (an algorithm, a transport class), not a whole feature module.

| ECHO seam (Plan §5) | `simt-app` source to copy from | Verdict | Fallback if copying fails |
|---|---|---|---|
| `MotionSource` | `feature/track/pdr/`: step detection, `WeinbergEstimator`, heading/`MahonyAhrs`, `TiltProjector`, calibration classes — all unit-tested; `feature/track/vio/` for camera-motion fusion | **Copy the needed algorithm pieces** into `capture/` under ECHO names (done in W1-01: §6.1–6.2 engine following those conventions; copy further pieces only if a gap appears). Do not copy VIO: ARCore's exclusive camera would contend with `CameraXRecorder` | ECHO's §6.1–6.2 specification implemented natively (that is what W1-01 shipped) |
| `PeerTransport` (bulk bytes) | `feature/link`: `offline/OfflineTransport` (+ router, Wi-Fi Aware → Wi-Fi Direct), `network/LinkTcpSession`, `LinkTcpHandshake`, `SecurityManager` | **Copy the transport/session code** into `transfer/` renamed as ECHO code (`LanPeerTransport`), behind ECHO's `PeerTransport` seam (`offer`/`fetch: ByteArray`) | `KtorLanTransport` (Plan W2-03) behind the same seam |
| Peer identity / pairing trust | `feature/link`: `core/domain/link` repository, `security/LinkIdentitySecurity`, `handoff/LinkBootstrap*`, admission policies | **Copy the trust pieces; write bytes new.** The copied source is peer-*message* oriented (`sendMessageToPeer(peerId, String)`, pulses, locations) with no arbitrary-byte/file API — copy identity/pairing logic if needed, never tunnel bundle bytes through short validated text messages; the byte path is new ECHO code behind the same seam | ECHO token-in-QR trust model (Plan §5 wire format), ECHO-owned |
| `QrPayload` | `feature/link` `qr/LinkQrPayload`, `qr/LinkQrWidgets`; `core/ui-camera` `scanner/QrCodeScanner` | **Copy the scanner; write the codec.** The copied payload type carries a bootstrap record, not ECHO's `simt-echo://r?v=1&h=&p=&t=&k=&s=&n=` URI (≤200 chars), so ECHO's codec is new code (good **L**-tier slice); the scanner component and widgets are copied where they fit | Plan `QrPayload` codec as specified (this is the primary; copying covers scanning/rendering only) |
| `CameraRecorder`, `MediaTools` | `core/ui-camera`: `capture/SystemImageCaptureController`, `CameraUriUtils`, preview/overlay components | **Copy where the API fits** (still capture, URI handling, preview); CameraX silent-video recording itself is ECHO code in W1-02 (done — the controller could not express it) | Raw CameraX in W1-02 as planned |
| Design system, nav shell (W0-03) | `core/ui`: theme (`Color`, `Theme`, `Type`, `Shape`, `Motion`), components, `PermissionGate`; `core/navigation`: `AppRouter`/`ScreenRoute` | **Copy conventions, own the shell.** ECHO's dark-first tokens (Plan §8: bg `#0B0D10`, Echo Lime `#C6F432`) follow the copied theme conventions; components/`PermissionGate` copied where reused. The nav shell is ECHO's own (done in W0-03) | Plan W0-03 design system written fresh |
| Camera overlay (W5-02) | `feature/iris`: projection, spatial calculator, components (`IrisCameraView`, `TargetDirectionArrow`, `TargetInViewMarker`, `IrisTargetHud`) | **Copy the projection/HUD components** into `ui/` renamed as ECHO code; ECHO adds landmark anchoring, not a new overlay stack | Drop W5-02 (P2; first in the Plan §12 cut order) |
| Wear haptics (W5-04) | `data/wear`: `WearDataLayerManager`, `WearDataLayerRepository`, `WearSyncProtocol`, `DataLayerPaths` | **Copy the Data Layer transport code** into ECHO; ECHO adds the haptic-pattern messages (`Haptics` enum) | Drop W5-04 (P2) |
| Persistence & domain patterns | `core/domain`: dependency-free repositories returning sealed `RepositoryResult`; `data/local` `SettingsDataStore`; `feature/track` `TrackRepository` session/checkpoint patterns | **Copy the pattern** into `shared/` (`RouteRepository`, `FileStore`, done in W0-04). The copied recovery-checkpoint pattern is the model for W4-03 process-death recovery | Plan §5 storage layout (`drafts/`, `routes/`, `trials/`) as specified |
| Haptics (device) | No dedicated haptics helper exists in `simt-app` core | **New.** Small Android actual for the `Haptics` seam; waveforms from Plan §8 | — |
| DI | `simt-app` uses Hilt (`:core:di`, `di/LinkModule`) | **Do not copy.** ECHO uses the manual `AppGraph` from Plan everywhere; no DI framework is vendored in | Manual `AppGraph` everywhere |

Version alignment was settled in W0-01/W0-02: ECHO independently pins
AGP 9.5.0 / Kotlin 2.4.21 / CMP 1.12.0 (Plan originally pinned AGP 9.2.0 /
Kotlin 2.4.20). The pins happen to match `simt-app`'s, which keeps copied
code compiling without translation — but nothing requires them to stay
aligned, because nothing is linked.

## 5. Shared-file ownership

Two agents never edit these at once; the named lane owns the file for the
whole plan, and other lanes consume it through its interface:

| File / contract | Sole owner | Consumers wait for |
|---|---|---|
| Domain model & `EchoJson` (Plan §5) | Lane A (W0-04) | The W0-04 commit; no lane invents a field |
| `gradle/libs.versions.toml`, root build files | Lane A (W0-02) | The version decision in §4 |
| Worker `types.ts` / API contract | Lane D (W3-03) | The frozen request/response shapes |
| Review screen | Lane E (W2-01), then W3-07 by handoff only | W3-07 starts only after W2-01 is committed; it is the one planned serial handoff |
| `EchoNavHost` / `AppGraph` | Lane A (W0-03) | Screen slices register routes, they do not edit the graph |
| `EchoConfig` (thresholds, step-K) | Lane F (W4-02) after W2-06 | Algorithms read config; they do not hard-code past it |

## 6. Tracker

Evidence naming: `./gradlew :shared:allTests` (KMP slices — note Plan's
`:echo:shared:allTests` path is stale for this repo's layout), the exact
worker command `npm test` in `worker/`, or a named manual check for field
slices. A slice is `done` only with its evidence recorded, per Plan §0.

| ID | State | Slice | Tier | Lane | Depends on | Evidence |
|---|---|---|---|---|---|---|
| W0-01 | Complete | Repo recon: settle copy sources + version alignment (§4) | S | A | — | 966420632bfde4cbfcb3d85ada94bd4dd3d0fb4c · check: 0 _tbd_ rows in Plan §2.1; decisions verified against simt-app source (PdrEngine StateFlows, Link String-only send, AGP 9.5.0/Kotlin 2.4.21) |
| W0-02 | Complete | KMP scaffold (verify existing root `shared/` + `androidApp`) | F (S) | A | W0-01 | 864ffb8bf1ced51be6bb2fc6a745a7721a9c8bdd · test: JAVA_HOME=/Users/saidelimam/.sdkman/candidates/java/21.0.12-tem DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer ./gradlew clean :shared:testAndroidHostTest :shared:compileKotlinIosArm64 :shared:compileKotlinIosSimulatorArm64 :shared:compileTestKotlinIosSimulatorArm64 :androidApp:assembleDebug :androidApp:lintDebug (BUILD SUCCESSFUL, ScaffoldSmokeTest 2/2); worker: npm run typecheck + npm test (1/1) |
| W0-03 | Complete | Design system + nav shell on `core/ui` conventions | F | A | W0-02 | 8f54218dd12f731ef0ea179548751feb86d86bf0 · test: :shared:testAndroidHostTest --rerun-tasks (11/11: ScaffoldSmokeTest 2, RouteSerializationTest 3, ShellMathTest 6), :androidApp:assembleDebug, :shared:compileKotlinIosArm64, :shared:compileKotlinIosSimulatorArm64 — BUILD SUCCESSFUL |
| W0-04 | Complete | Domain model, `FileStore`, `RouteRepository`, `L_ROUTE` fixture — contract freeze | S | A | W0-02 | 1b080e6aacef38b79481df65e5f1644f00221b34 · test: :shared:testAndroidHostTest (ModelJsonTest 8/8, RepositoryTest 7/7, suite 33/33), :androidApp:assembleDebug, :shared:compileKotlinIosArm64, :shared:compileKotlinIosSimulatorArm64 — BUILD SUCCESSFUL |
| W0-05 | Complete | Worker scaffold: Hono, auth, rate limit, R2, deploy | F (S) | D | — | 9cee55627d31577e1f0fce2f79c90000ab80ef40 + review fix 87b2709, merged 5473252 · test: worker npm run typecheck clean, npm test 7/7, wrangler deploy --dry-run binds EchoPipeline — S review APPROVE WITH RESIDUAL RISK: types.ts schemas are scaffold shapes, W3-03 must freeze its own |
| W1-01 | Complete | `MotionSource`: built-in PDR engine (§6.1–6.2) + Android IMU source | S | B | W0-04 | 583e7b0f833f8c46383e3cef853f83f727965401 · test: :shared:testAndroidHostTest (PdrEngineTest 7/7, suite 40/40), :androidApp:assembleDebug, :shared:compileKotlinIosArm64, :shared:compileKotlinIosSimulatorArm64 — BUILD SUCCESSFUL |
| W1-02 | Complete | `CameraRecorder`: CameraX silent video, `core/ui-camera` where it fits | F | B | W0-03 | e4eb190ee97ab7bd692532b85e554592e089313a · test: :shared:testAndroidHostTest (RecorderLogicTest 7/7, suite 18/18), :androidApp:assembleDebug, :shared:compileKotlinIosArm64, :shared:compileKotlinIosSimulatorArm64 — BUILD SUCCESSFUL |
| W1-03 | Complete | Record screen + `CaptureSession` + Finish | F | B | W1-01, W1-02 | 502de7f0a5152cd75d3db5be4e33c08f29fa9a5f · test: :shared:testAndroidHostTest (CaptureSessionTest 3/3, suite 43/43), :androidApp:assembleDebug, :shared:compileKotlinIosArm64, :shared:compileKotlinIosSimulatorArm64 — BUILD SUCCESSFUL |
| W1-04 | Complete | `Segmenter` → draft `EchoRoute` + minimap | L (S) | B | W1-03 | d0c705b835b2c0e3d34702002a78d87e6f + review fix 224f482, merged 6a4d9f5 · test: :shared:testAndroidHostTest (SegmenterTest 6/6, branch suite 49/49), :shared:compileKotlinIosArm64, :shared:compileKotlinIosSimulatorArm64, :androidApp:assembleDebug — BUILD SUCCESSFUL; S review APPROVE WITH RESIDUAL RISK: EchoConfig pre-created here, W4-02 extends it |
| W1-05 | Complete | `MediaTools`: keyframes + crop | L (F) | B | W1-02 | c925793bb665a42988f2b7f16462269457ba77f0 · test: :shared:testAndroidHostTest (MediaToolsTest 22/22, suite 88/88), :androidApp:assembleDebug — BUILD SUCCESSFUL |
| W2-01 | Complete | Review screen + manual landmark editor + Save | F (S) | E | W1-04, W1-05 | 3f172714c6ae7c5637140b200a473c6807bca4d2 · test: :shared:testAndroidHostTest (RouteCuesTest 6/6, ReviewViewModelTest 7/7, suite 124/124), :shared:compileKotlinIosArm64, :shared:compileKotlinIosSimulatorArm64, :androidApp:assembleDebug — BUILD SUCCESSFUL | S review APPROVE WITH RESIDUAL RISK (post-merge): creator-verification confined to explicit confirmDraw (MANUAL/CREATOR_VERIFIED only, no AI_SUGGESTED in slice), edit/delete cannot alter source/confirmation, save preserves geometry and nulls captureTMs — fresh gate ReviewViewModelTest 7/7, RouteCuesTest 6/6. Residual: Compose draw gesture device-unverified |
| W2-02 | Complete | `BundleCodec`: `ECHO` container, SHA-256, AES-256-GCM `ECX1` | S | C | W0-04 | db67a3b76925327c35d80a279d74eb0a16b710cc · test: :shared:testAndroidHostTest (BundleCodecTest 10/10, suite 53/53), :androidApp:assembleDebug, :shared:compileKotlinIosArm64, :shared:compileKotlinIosSimulatorArm64 — BUILD SUCCESSFUL; catalog cryptography pin corrected 0.8.0 → 0.6.0 (0.8.0 unpublished) |
| W2-03 | Complete | `PeerTransport` LAN transport (`KtorLanTransport`) + `QrPayload` codec | S | C | W2-02 | 1ba5bfa674aac3a8e41f4e30238f079cd0ad6c45 · test: :shared:testAndroidHostTest (QrPayloadTest 7/7, TransferServiceTest 6/6, suite 66/66), :androidApp:assembleDebug, :shared:compileKotlinIosArm64, :shared:compileKotlinIosSimulatorArm64 — BUILD SUCCESSFUL |
| W2-04 | Complete | Share + Receive QR screens (`QrCodeScanner` reuse) | F | E | W2-03 | b2f1e8335770f3135830d68954bcd5b50e97fdfe · test: :shared:testAndroidHostTest (TransferServiceTest 9/9, ShareViewModelTest 3/3, ReceiveViewModelTest 6/6, suite 78/78), :shared:compileKotlinIosArm64, :shared:compileKotlinIosSimulatorArm64, :androidApp:assembleDebug — BUILD SUCCESSFUL | Review: merged 0879eef (EchoNavHost conflict resolved - Review + Share/Receive kept); gate re-run fresh 78/78. Residual: ML Kit scan + two-phone transfer device-unverified |
| W2-05 | Complete | `Navigator` + `TurnDetector` + `ReplayMotionSource` + 7 scenario tests | S | C | W0-04 | f2a35ad819344c9e96c6abae19452e8bd7f6f24d, merged eaeaf69 · test: :shared:testAndroidHostTest --tests app.simt.echo.nav.NavigatorTest (8/8, 7 scenarios + ALIGN) --tests app.simt.echo.nav.TurnDetectorTest (5/5) --tests app.simt.echo.capture.ReplayMotionSourceTest (3/3), branch host suite 127/127, :shared:compileKotlinIosArm64, :shared:compileKotlinIosSimulatorArm64, :androidApp:assembleDebug — BUILD SUCCESSFUL; merged-main focused gate re-run green. No HTTP import in nav/ |
| W2-06 | Complete | Align / Navigate / Arrived screens + haptics | F (S) | E | W2-05, W2-01 | 70233b963bc45a38f1b885844d13686312863602 · test: :shared:testAndroidHostTest (NavigateViewModelTest 3/3, NavigatorTest 8/8, suite 165/165), :shared:compileKotlinIosArm64, :shared:compileKotlinIosSimulatorArm64, :androidApp:assembleDebug — BUILD SUCCESSFUL · S review APPROVE WITH RESIDUAL RISK: UI states map 1:1 to NavPhase incl. UNCERTAIN (structural render + overshoot test), all 8 phases wired, §8.4 waveforms verbatim, no HTTP in nav/, Arrived summary-only (trial write deferred to W4-01). Residual: on-device replay-to-ARRIVED + haptic feel, isOnline/KeepScreenOn Android actuals, Align camera PiP device-unverified · Review 0001 (BLOCK → fixed): 9c1d271 back-stack-scoped NavigateViewModel, exact haptic-sequence + OFF_ROUTE tests, honest Arrived summary; merged 2783ae9 · merged-main test: :shared:testAndroidHostTest suite 186/186 (NavigateViewModelTest 4/4), :shared:compileKotlinIosArm64, :shared:compileKotlinIosSimulatorArm64, :androidApp:assembleDebug — BUILD SUCCESSFUL |
| W2-07 | Complete | `.echo` file fallback: share/import after privacy warning | L (S) | C | W2-02 | 7f55789ec28e24c5c56d4d44be4235e13331e70c · test: :shared:testAndroidHostTest (suite 171/171), :androidApp:assembleDebug — BUILD SUCCESSFUL  · Review 0003 (BLOCK → fixed): 3a9f191 id validation (component-wise; nested crop ids still save), 50 MB caps, expiry enforced, clean duplicate replace, picker failures as errors, exports purged per share + route-id filenames; merged 48e5fda · merged-main test: :shared:testAndroidHostTest suite 229/229, iOS compiles, :androidApp:assembleDebug — BUILD SUCCESSFUL |
| W3-01 | Complete | Clips (Media3) + `ManifestBuilder` | L (F) | D | W1-05 | 919ec5d4d8f10fca8ed2839120718c60225fde57 · test: :shared:testAndroidHostTest (ManifestBuilderTest 18/18, suite 128/128), :androidApp:assembleDebug — BUILD SUCCESSFUL — 3f48472b35ec9eda6f58484292a3720bf740d586 |
| W3-02 | Complete | Consent + `EchoApiClient` + Processing screen | F (S) | D | W3-01, W0-05 | 973953ac0ffb895549ce6c56a01a869eb351c839 · Review 0001 (BLOCK → fixed): 700f857 consent-gated atomic send, retry returns to Consent, reachable + honest delete-now, back-stack-scoped ProcessingViewModel, CancellationException propagated, HTTPS-or-loopback only, transient poll tolerance · test: :shared:testAndroidHostTest (ProcessingViewModelTest 10/10, EchoApiClientTest 10/10, merged suite 186/186), :shared:compileKotlinIosArm64, :shared:compileKotlinIosSimulatorArm64, :androidApp:assembleDebug — BUILD SUCCESSFUL. Residual: live job against deployed worker device-unverified (jobs API landed on main with W3-04 merge e80f719) |
| W3-03 | Complete | Worker jobs API + Workflow skeleton (contract freeze) | F (S) | D | W0-05 | 29f077c5a9018d2dbd1665a8e4962e0e9b832bec + live-verify fix 2ed6fa5 · test: worker npm run typecheck clean, npm test 26/26 — BUILD SUCCESSFUL; S review APPROVE WITH RESIDUAL RISK: freeze/consent-before-storage/error-scope reviewed; live local workerd run: POST 202 → complete in ~4s → DELETE 204 → GET 404; production deploy blocked: token lacks R2 bucket-create permission (echo-media absent) |
| W3-04 | Complete | Workers AI scout chain (Llama Scout → Gemma fallback) | F (S) | D | W3-03 | 23a3cf68ddebce127942ef3e490b15a98e45f321 · test: worker npm run typecheck clean, npm test 47/47 (scout.test.ts 14/14, jobs 19/19, auth 6/6, relay 7/7, index 1/1) — BUILD SUCCESSFUL · Review 0001 (BLOCK → fixed): 17e67b6 invalid-output (schema/wrong-nodeId/parse/timeout) falls back to Gemma, candidates filtered to loaded images, NonRetryableError for deterministic failures; merged e80f719 · merged-main test: worker npm run typecheck clean, npm test 52/52 (scout.test.ts 19/19) — BUILD SUCCESSFUL. Residual: live Workers AI real-clip check deferred (production deploy blocked on R2 permissions); pipeline assemble content is W3-06's |
| W3-05 | Complete | Grounding: box-only path + SAM 3.1, track selection/ranking | S | D | W3-04 | 81617cf6f70a679ab567bdfb25ebe492f8ca2b0b · Review 0002 (BLOCK → fixed): 14d52b0 real @meta-sam/parser mask decoding (raster dims, object-associated), parseSam sanitisation (degenerate records degrade, never crash the job), §10 fallback-a for no-clip/zero-obs with null track fields, no coordinate mixing, box-only rank-sorted; merged fdbfe50 · merged-main test: worker npm run typecheck clean, npm test 74/74 (ground.test.ts 22/22 incl. fixture SAM → ranked AI_GROUNDED with mask) — BUILD SUCCESSFUL. Residual: live SAM 3.1 check attempted 2026-11-01 and blocked — no META_API_KEY in env or repo files; pipeline assemble/public result is W3-06's  · Live SAM check (review 0003 R4/R5): with META_API_KEY from .env, real SAM 3.1 via runGround grounded a synthetic landmark photo AI_GROUNDED in 693 ms, mask raster 120x799, confidence 0.9298, box within 2 px of ground truth; the check found and fixed a live-only bug (9833aef: meta.ts read only delta events, SAM sends done-event text) |
| W3-06 | Complete | Assemble pipeline + vitest + live latency/cost check | F | D | W3-05 | 9f21a46d6200a7ba5da6dfba435213bbe30a258c · test: npm test -- src/pipeline.test.ts  · Review 0004 (BLOCK → fixed): 192cf6b measured usage (vendor video_frames_processed per actual send, real Meta time; samMs absent when SAM never ran), assemble keeps every manifest checkpoint, rank amendment recorded (ground computes §6.6 rank, assemble sorts); merged 1645f06 · merged-main test: worker typecheck clean, npm test 96/96 (pipeline + ground usage), shared suite 254/254 — BUILD SUCCESSFUL. Live: SAM + Spark proven live (docs/measurements.md addendum); deployed 60 m job still blocked (Workers AI token scope + R2 permissions), envelope unclaimed |
| W3-07 | Complete | Merge AI candidates into Review (mask overlay) | F (S) | E | W3-02, W3-06, W2-01 | b73a386829ebdc539a608e97d79431fe68d25ff0 · test: JAVA_HOME=/Users/saidelimam/.sdkman/candidates/java/21.0.12-tem ./gradlew :shared:testAndroidHostTest --tests "app.simt.echo.ui.screens.review.*" --tests "app.simt.echo.ai.*"  · Review 0004 (BLOCK → fixed): 08dca34 spec box normalisation (SAM pixel boxes by declared frame), privacyRisk candidates never offered, truncated masks degrade, float mask scaling, candidate's own keyframe for overlay+crop, candidate geometry persisted on Landmark (optional fields — plan amendment); merged 7fbb9e8 · merged-main test: :shared:testAndroidHostTest suite 254/254 — BUILD SUCCESSFUL |
| W3-08 | Complete | Privacy export preview, expiry, delete-job | L (S) | D | W3-07 | de854693aee84b3cbbec469a5397a8ad861f284a  · Review 0005 (BLOCK → fixed): 29cd4e6 honest ExportSummary preview (all route/landmark/provenance fields disclosed, landmark text always exports), expiry preserved + expired routes refused, delete removes plaintext exports + persisted cloud job id (cleared after cloud delete), creator Sensitive flag in Review, AI_SUGGESTED fail-closed at export, stale QR cleared on re-offer, preview always shown; merged d06f372 · merged-main test: :shared:testAndroidHostTest suite 287/287, iOS compiles, :androidApp:assembleDebug — BUILD SUCCESSFUL |
| W3-09 | Complete | Muse Spark scout provider (optional, P1) | F (S) | D | W3-05 | cb237b376b31c786ed0fa396fa92a77c9025206e · test: npm test -- src/scout.test.ts  · Review 0004 (BLOCK → fixed): 7294cfe + cherry-picked done-event fix 75a6d7d (Spark live-capable), per-checkpoint candidate cleaning, retryable Meta errors reach the Workflow retry; merged a8219e1 · merged-main test: worker npm test 96/96 — BUILD SUCCESSFUL. Live: real Spark call schema-valid in ~1.6–2.9 s (zero candidates for a synthetic photo, recorded in measurements) |
| W4-01 | Complete | Trials: baseline, drift, CSV export | L (F) | F | W2-06 | 3f741e2e91e3b890952b92af73377d9d51f7a7a1 · Review 0002 (BLOCK → fixed): a62ada3 Navigate→Arrived trial writer (stats from Navigator events, Arrived completes §11 lighting/holding/mistaken/notes), flat trials/<id>.json, corrupt-file tolerance, single parseable CSV (exact 5+5 fixture), baseline hides cards + disables confirmations, mode recorded once from EchoConfig; merged b926010 · merged-main test: :shared:testAndroidHostTest suite 208/208 (TrialRepositoryTest, NavigateViewModelTest 6/6), iOS compiles, :androidApp:assembleDebug — BUILD SUCCESSFUL. Residual: Settings Export CSV UI needs a shareFile seam that does not exist yet; non-arrived attempts record no trial |
| W4-02 | Complete | Calibration: step-K + `EchoConfig` | F (S) | F | W2-06 | 77e8eabee615c530b6be37f224ecf76c69436314 · Review 0002 (BLOCK → fixed): cf7e8c6 AppGraph ConfigRepository wired into CaptureSession (Record) and Navigator, BuiltInPdrMotionSource forwards stepK to its engine, K band 0.15–1.5 enforced in calibrateK + on load, Settings VM back-stack-scoped; merged 8ca7871 · merged-main test: :shared:testAndroidHostTest suite 208/208 (engine-fixture drift reduction, CaptureSession K propagation, ConfigRepository sanitisation), iOS compiles, :androidApp:assembleDebug — BUILD SUCCESSFUL. Residual: on-device calibrated-walk verification (two demo phones) not run here |
| W4-03 | Complete | Robustness: permissions, process-death recovery | F | F | W2-06 | 7d1b6ad0f92c4c32e611c0906ff1ed2fab00f636 · test: :shared:testAndroidHostTest --tests app.simt.echo.data.RecoveryAdvisorTest (4/4), :shared:compileKotlinIosArm64, :shared:compileKotlinIosSimulatorArm64, :androidApp:assembleDebug — BUILD SUCCESSFUL  · Review 0003 (BLOCK → fixed): 532b1c5 typed recovery destinations surfaced on Home, CaptureSession.resume continues the same draft, back-stack Record VM with surfaced camera/session errors, LifecycleResumeEffect permission refresh, NOT_DETERMINED first-run, atomic capture autosave; merged 9da95f4 · merged-main test: :shared:testAndroidHostTest suite 229/229, iOS compiles, :androidApp:assembleDebug — BUILD SUCCESSFUL. Residual: device abuse pass (kill/deny/airplane on hardware) not run here |
| W4-04 | Complete | UX polish, accessibility, app icon | L (F) | E | W3-07 | 27d6623c9626e19b4b17221488f844d405027824  · Review 0005 (BLOCK → fixed): 1b52744d light-scheme EchoDangerLight #CC2A30 (5.01:1 on bg, 5.33:1 on surface; dark 6.37:1), DirectionArrow consumes theme accent, arrow decorative in Navigate (no duplicate TalkBack instruction), ErrorState heading + assertive live region, FinishButton TalkBack onClick, gate test asserts the consumed schemes + EchoTouchPrimary == 56.dp; merged to main · merged-main test: :shared:testAndroidHostTest suite 287/287 — BUILD SUCCESSFUL |
| W4-05 | Complete | Relay fallback: zero-knowledge R2 relay (P1) | S | D | W2-04, W0-05 | ba29e1cfb9ae3fe67ff90d6ea38c1975f5132b53, merged c34686f · test: worker npm run typecheck clean, npm test 14/14 (relay.test.ts 7: code alphabet/collision, ciphertext-verbatim store, expiry delete, 400/413/401), wrangler deploy --dry-run binds MEDIA; :shared:testAndroidHostTest --tests app.simt.echo.transfer.TransferServiceTest (13/13) --tests app.simt.echo.ui.screens.share.ShareViewModelTest (4/4), host suite 162/162, :shared:compileKotlinIosArm64, :shared:compileKotlinIosSimulatorArm64, :androidApp:assembleDebug — BUILD SUCCESSFUL, merged-main gate re-run green · RelayClient injected-nullable (chosen design): AppGraph leaves relay unconfigured until W3-02 supplies Worker URL/token, so Share hides the relay action by default; live two-phone relay transfer device-unverified |
| W4-06 | Queued | README, limitations, demo script, release APK | S | F | wave P0 | — |
| W5-01 | Complete | Local-model provider (P2) | S | D | W3-07 | a692bee · test: :shared:testAndroidHostTest suite 287/287 merged (OpenAiCompatVisionTest 3/3, AiRouterTest 3/3, ReviewViewModelTest local path), iOS compiles, :androidApp:assembleDebug with LiteRT-LM bundled |
| W5-02 | Complete | Camera overlay: HUD + mask-over-crop (P2, renamed in review 0004 — no live-frame projection) | F (S) | E | W2-06 | 5534830 + wiring in merge f1d29d2 + tests fa7d1e6, merged to main · Review 0004 (BLOCK → completed as HUD): EchoConfig.cameraOverlayEnabled + Settings switch, preview behind CameraPermissionGate, baseline trials see no landmark content, white-on-scrim text, MaskedCropImage renders the persisted SAM mask over the crop via maskPointInCrop · test: :shared:testAndroidHostTest suite 254/254 (NavigateOverlayTest 5/5, SettingsViewModelTest 1/1), iOS compiles, :androidApp:assembleDebug — BUILD SUCCESSFUL. Residual: on-device overlay + camera gate unverified; live-frame projection does not exist (no pose/tracking) and is not claimed |
| W5-03 | Accepted | iOS actuals (P2) | S | A | wave P0, W4-06 | Postponed — iOS is out of v1 scope; not to implement |
| W5-04 | Complete | Wear OS haptic bridge on `data/wear` (P2) | F | F | W0-01 | 3792fa467c36be9c8379b63d65888c551d462202 · test: :shared:testAndroidHostTest --tests app.simt.echo.platform.WearHapticsTest (5/5), :shared:compileKotlinIosArm64, :shared:compileKotlinIosSimulatorArm64, :androidApp:assembleDebug — BUILD SUCCESSFUL · Review: merged after fresh 48/48 branch gate; merged-main gate 89/89 · watch receiver 8a186731286fe79a456dd6cfda1fb832a9c6b08b · test: :wear:testDebugUnitTest (EchoWearHapticsTest 2/2), :wear:assembleDebug, :wear:lintDebug, :androidApp:assembleDebug — BUILD SUCCESSFUL · applicationId verified app.simt.echo in APK · Residual: on-watch delivery device-unverified (no paired watch here) |

## 7. Slice sections

Each section states the tier rationale and the reuse hook; the build steps,
files, and done-when criteria are Plan §10 under the same id and are not
repeated here.

### W0-01 — Repo recon and reuse decision
**S**, Lane A. The Plan §2.1 adapter table is `_tbd_` because its author
could not read `simt-app`; §4 of this plan is the inventory that replaces it.
Done when W0-01's commit records: Track adapter viability against the Plan
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
**F**, Lane A. Port ECHO's §8 tokens following the design conventions
copied from `simt-app`'s `core/ui` (§4 copy model); keep ECHO's own
Compose nav shell. Acceptance: shell navigates every §8 route with
placeholder screens.

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

### W1-01 — MotionSource (built-in PDR)
**S**, Lane B. Geometry is a hard gate: a wrong yaw or step length silently
corrupts every downstream slice. The engine implements Plan §6.1–6.2 in
`capture/`, following the step/heading approach already solved in
`simt-app`'s track module (copied conventions, ECHO-owned code — §4 copy
model; no dependency, no adapter type). Acceptance: synthetic-walk fixture
produces monotonic distance, stable relative yaw and correct step counts.

### W1-02 — CameraRecorder
**F**, Lane B. CameraX plumbing with `core/ui-camera` reuse where the API
fits. Acceptance: silent video records alongside W1-01 motion with no frame
starvation on the target device.

### W1-03 — Record screen and CaptureSession
**F**, Lane B. Joins motion + camera into one session with a Finish flow.
Acceptance: a 30–80 m walk produces one complete `CaptureLog` on disk.

### W1-04 — Segmenter
**L (S review)**, Lane B. The algorithm is fully specified in Plan §6.3
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
**S**, Lane C. The state machine (Plan §6.4: ALIGN/WALK/APPROACH/TURNING/
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
they do not attach (Plan §0 rule). Acceptance: candidates appear as
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
**F**, Lane F. Permissions and process-death recovery, following the
recovery-checkpoint pattern copied from `simt-app`'s track module (§4).
Acceptance: kill-mid-capture and kill-mid-navigation both recover to a
resumable state.

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

### W5-02 — Camera overlay (P2)
**F (S review)**, Lane E. Built on projection/HUD components copied from
`simt-app`'s `feature/iris` into ECHO's `ui/` (§4 copy model), adding
landmark anchoring. Cut before any P0 work (§8).

### W5-03 — iOS actuals (P2)
**S**, Lane A. Largest single estimate in Plan (6 h) and pure stretch;
starts only when every P0 row above is `Complete`.

**Status: postponed — iOS is out of v1 scope and this slice is not to be
implemented.** Tracker row is `Accepted` (deliberately not done; it satisfies
no dependencies and nothing depends on it).

### W5-04 — Wear OS haptic bridge (P2)
**F**, Lane F. Copies the Data Layer transport code from `simt-app`'s
`data/wear` into ECHO (§4 copy model); ECHO contributes only the
haptic-pattern messages.

## 8. Review of the source documents

Findings from reading the proposal and Plan against both repos. None of
these block Batch 0; each names the slice that resolves it.

1. **AI provider story differs between the two documents.** The proposal
   (v1.0) puts Meta Model API (Muse Spark 1.3 + SAM 3.1) on the creation
   path and names existing Track+/Iris/Connect as foundations. Plan (v1.2)
   makes a Cloudflare Worker with Workers AI the default and the Meta API
   optional (`META_API_KEY`). Plan is the later, more specific document
   and this plan follows it — but the demo script (W4-06) must tell one
   story, and "Push the Frontier / Meta models" judging makes the choice
   visible. Resolved by: W4-06, informed by W3-06's live cost/latency and
   W3-09's status.
2. **Module location and test path are stale in Plan.** It plans an
   `echo/` tree inside `simt-app` and gates on `./gradlew :echo:shared:allTests`;
   this repo is already a standalone scaffold with root `shared/` and
   `androidApp/`, so the gate is `./gradlew :shared:allTests`. Resolved by:
   W0-02 verifying the existing scaffold instead of regenerating it.
3. **Plan §2.1 (adapter mapping) is entirely `_tbd_`.** The inventory now
   exists (§4): Track's `PdrEngine` is a mature, tested sensor stack that
   exceeds the §6.2 spec; Connect's `LinkRepository` has no byte-transfer
   API, so bundle transfer reuse is real at the transport layer and absent
   at the repository layer. Resolved by: W0-01.
4. **Version drift.** Plan pins AGP 9.2.0 / Kotlin 2.4.20; ECHO now pins
   AGP 9.5.0 / Kotlin 2.4.21 / CMP 1.12.0 independently. The pins match
   `simt-app`'s by choice (copied code compiles without translation), not
   by necessity. Resolved by: W0-01 decision, applied in W0-02.
5. **Enclosed copying makes the architecture mismatch irrelevant.**
   `simt-app` is Android-library + Hilt; ECHO's `shared/` is KMP. Nothing
   is imported across that boundary: solved code is copied into ECHO
   packages, translated to the target source set where needed, and owned
   here (§4 copy model). Any earlier *link/adapt* framing was wrong and
   has been corrected throughout this plan.
6. **Proposal acceptance A1–A8 is fully covered by Plan slices** —
   A5/A6 by W2-05/W2-06, A7 by W3-02/W3-08, A1–A4/A8 by W3-05/W2-03/W4-01/
   W4-06. No proposal requirement is unowned; no Plan P0 slice lacks a
   proposal reason. The proposal's illustrative landmark JSON remains
   explicitly non-normative; Plan §5/§7 schemas govern.

## 9. Cut order and escalation

Plan §12 governs and is restated here so agents under time pressure do
not improvise: cut Wave 5 → W3-09 → W4-05 → mask overlays → SAM video
grounding → AI entirely. Never cut: capture (W1-01…W1-03), segmenter
(W1-04), bundle (W2-02), transfer (W2-03/W2-04), navigator (W2-05),
alignment/uncertainty (W2-06), trials (W4-01). A no-AI ECHO that walks a
route from Phone A to Phone B offline is a complete demo; an AI-only ECHO
that cannot is not.

Blocked-slice rule (Plan §0): after 45 minutes blocked, apply the slice's
named fallback and note it in the evidence column — for reuse slices the
fallback is always the §4 right-hand column, which is why every reuse row
has one.
