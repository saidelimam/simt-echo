# Review 0006 — Residual risks from 0001-agentic-flow

Status: **complete** · created 2026-11-01 · archived 2026-11-04 · converted from `docs/plans/0002-residual-risks.md` 2026-11-01

## Scope

Close out the ten residual-risk markers carried on the `Complete` rows of
`docs/plans/0001-agentic-flow.md`, and record the new risks that surfaced
while doing so. The original markers fall into three shapes:

1. **One code gap** — W4-01: Settings had no CSV export action. The `shareFile`
   seam it was waiting on landed with W2-07; what was missing is the wiring.
2. **Verification debt** — W2-01 (draw gesture), W2-06 (KeepScreenOn, online
   chip, haptic feel), W4-03 (device abuse pass), W2-04 (two-phone transfer),
   W4-02 (calibrated walk), W5-04 (on-watch delivery). Marked
   "device-unverified". Headless coverage is added where it is honest; the
   rest is recorded open-with-owner for the W4-06 field pass.
3. **Infra-blocked + resolved** — W3-02/W3-04 (production deploy blocked on
   R2 bucket permission), W3-05 (marked blocked, then cleared by its own live
   check). Closed with hard evidence via the Cloudflare MCP connection.

## Method

Every original marker gets a final disposition in W6-04:
closed-with-evidence (the test or live check that covers it, named) or
open-with-owner (the W4-06 field pass). New risks found during the W6 work
are recorded in "New residual risks" below with the same disposition
discipline — a risk is not closed by claiming it; it is closed by the check
that proves it.

## Severity levels

- **S1 — blocks release**: a production capability cannot be demonstrated
  (W3-02/W3-04 deploy block). Must close with hard evidence before the
  release is claimed.
- **S2 — verification debt with headless coverage**: behaviour is covered by
  automated tests, device feel remains (W2-01 gesture, W2-06 flag/online,
  W4-03 offline/permission paths).
- **S3 — open-with-owner / accepted-with-mitigation**: needs a field pass on
  hardware, or a known harness limitation with a documented failure mode
  (W5-04, W2-04, W4-02, W2-06 haptics, NR-1, NR-2).

## Settled decisions

- **Single worktree** for all four slices (owner instruction); slices commit
  sequentially in it, each with its own commit and recorded SHA.
- **W6-03 depth**: Robolectric + Compose UI tests are added to the Android
  host test source set (owner decision). Logic-level extraction (W6-02)
  precedes it so the UI tests assert behaviour, not implementation.
- **Remote mutation approved**: creating the `echo-media` R2 bucket and
  deploying the worker to production via the Cloudflare MCP connection is
  in scope for W6-04 (owner decision). Every remote mutation records its
  resource id and the command that made it.

## Tracker

| id | state | slice | tier | lane | depends on | evidence |
|----|-------|-------|------|------|------------|----------|
| W6-01 | Complete | Settings CSV export over the W2-07 `shareFile` seam | F | E | — | 6c523a8 · test: ./gradlew :shared:testAndroidHostTest |
| W6-02 | Complete | Draw-box normalisation extracted + unit-tested | F | E | — | 1c51afa · test: ./gradlew :shared:testAndroidHostTest |
| W6-03 | Complete | Robolectric + Compose UI verification (gesture, KeepScreenOn, offline/airplane, permission gate) | L | E | W6-02 | test: `./gradlew :shared:testAndroidHostTest --tests app.simt.echo.ui.screens.review.DrawOverlayUiTest` (3/3) `--tests app.simt.echo.platform.DeviceServicesUiTest` (2/2) `--tests app.simt.echo.platform.CameraPermissionGateUiTest` (2/2) — BUILD SUCCESSFUL 2026-11-04 |
| W6-04 | Complete | Production close-out: R2 bucket, worker deploy, live job, residual dispositions on 0001 | L | D | W6-01, W6-02, W6-03 | Remote evidence recorded in `docs/release.md` §Production deploy / §Relay live round-trip (W9-01, W9-02: bucket `echo-media`, deploy version ids, live job lifecycle) · ten markers disposed in §W6-04 dispositions below (2026-11-04) |

## W6-04 dispositions

Every marker carried on a `Complete` row of 0001 gets one final state. A
marker closes only on the check named beside it; the rest name the slice
that owns the hardware run, so nothing is left bare and nothing is claimed
without evidence.

| Marker (0001 row) | Disposition | Evidence / owner |
|---|---|---|
| W4-01 Settings CSV export gap | closed-with-evidence | W6-01 `6c523a8`; `SettingsViewModelTest` asserts shared bytes, name and MIME |
| W2-01 Compose draw gesture | closed-with-evidence (headless data path) | W6-02 `1c51afa` + `DrawOverlayUiTest.dragConfirm_reportsNormalizedCropBox` (3/3); device feel stays W9-05 |
| W2-06 KeepScreenOn / isOnline | closed-with-evidence | `DeviceServicesUiTest.keepScreenOn_holdsFlagOnlyWhileComposed`, `rememberOnline_followsConnectivity` (2/2) |
| W2-06 haptic feel | open-with-owner | W9-04 judges the TICK/CONFIRM/LEFT/RIGHT/WARN/ARRIVE sequence on hardware |
| W2-04 two-phone transfer | open-with-owner | W9-04; runbook `docs/testing/nfc-sharing.md` |
| W3-02 jobs API live job | closed-with-evidence | `docs/release.md` §Production deploy: POST → complete → fetch → DELETE → 404 |
| W3-04 worker deploy / R2 bucket | closed-with-evidence | `docs/release.md`: bucket `echo-media`, deploy version ids, health true-to-config |
| W3-05 live SAM 3.1 check | closed-with-evidence | `docs/measurements.md` addendum: `AI_GROUNDED` in 693 ms, confidence 0.9298 |
| W4-02 calibrated walk | open-with-owner | W9-06 (5+5 §11 protocol, CSV, `docs/measurements.md`) |
| W4-03 device abuse pass | permission path closed-with-evidence; kill/airplane open-with-owner | `CameraPermissionGateUiTest` (2/2) covers granted and NOT_DETERMINED; W9-05 runs kill, deny and airplane on hardware |
| W5-02 camera overlay | open-with-owner | W9-04/W9-05 on-device overlay and camera gate; no live-frame projection is claimed |
| W5-04 on-watch delivery | superseded by owner | W9-08 (`a3e32c1`) owns the paired-watch proof |
| NR-1 stub-`R` harness | accepted-with-mitigation | open-with-owner; revisit if AGP adds an `androidHostTest` resource pipeline |
| NR-2 Robolectric SDK pin | accepted-with-mitigation | open-with-owner; raise when Robolectric supports SDK 37 |

## New residual risks (surfaced during W6-01…W6-03)

- **NR-1 — Stub-`R` harness (S3, accepted-with-mitigation).** AGP 9 KMP's
  `androidHostTest` source set runs no Android resource pipeline: `R` classes
  are not generated for transitive dependencies, and no manifest is
  processed. The headless UI harness therefore stands on hand-written stub
  `R` objects (`shared/src/androidHostTest/kotlin/androidx/**/R.kt`) and a
  custom `RobolectricActivityRule` that bypasses
  `ActivityScenarioRule`'s manifest-based resolution. Every new AndroidX
  dependency that references `R$id` in a static initializer breaks the suite
  until a stub is added. Failure mode is loud — `NoClassDefFoundError`
  naming the exact class — not silent. Disposition: open-with-owner; revisit
  if AGP ships a resource pipeline for `androidHostTest` or the team moves
  to instrumentation tests.
- **NR-2 — Robolectric SDK pin lags compileSdk (S3, accepted-with-mitigation).**
  UI tests run `@Config(sdk = [36])` while `compileSdk` is 37; Robolectric
  4.16 does not ship SDK-37 shadows yet. Code that starts relying on
  API-37 behaviour will be tested one level below. Disposition: open-with-
  owner; raise the pin when Robolectric supports 37.

## Slices

### W6-01 — Settings CSV export

**F**, Lane E. The W4-01 residual said the export "needs a shareFile seam
that does not exist yet"; the seam exists (W2-07 `FileSharing.shareFile`,
`TrialRepository.exportCsv()` tested). The gap was the UI: `SettingsScreen`
had no export action.

Changes: `EchoNavHost` passes `rememberFileSharing()` into the Settings
route; `SettingsViewModel.exportTrials()` writes `trials.csv` to app
storage, shares it as `text/csv`, and reports the result in
`SettingsUi` (few words, §8.4). Empty trial set is an honest no-op note,
not a share-sheet with nothing in it.

Acceptance: new `SettingsViewModelTest` cases assert the shared file's
bytes equal `exportCsv()` output, the name and MIME, and the empty-set
note; `:shared:testAndroidHostTest` full host suite green; iOS compiles.

### W6-02 — Draw-box normalisation

**F**, Lane E. W2-01's residual: "Compose draw gesture device-unverified".
The gesture's data path — canvas-px `Rect` → normalised 0..1 `CropBox` —
was inline in `DrawOverlay`'s confirm lambda. Extract it to a pure
commonMain function and test the exact mapping the drag produces.

Acceptance: `cropBoxFromRect` (or equivalent) is pure, used by
`DrawOverlay`, and unit-tested for: left-to-right/top-to-bottom, inverted
drags, drags extending past the canvas edges (clamp), zero-area drag,
full-frame drag. Host suite green; overlay behaviour unchanged.

### W6-03 — Robolectric + Compose UI verification

**L**, Lane E. Adds the Android-only test infrastructure (Robolectric,
Compose `ui-test-junit4`) to the `shared` host test source set, then
headless tests that stand in for the named device checks:

- **Draw gesture (W2-01)**: render `DrawOverlay` with a real bitmap,
  dispatch a drag, confirm — the resulting `CropBox` equals W6-02's
  mapping. The framework gesture wiring itself is now exercised.
- **KeepScreenOn (W2-06)**: composing `KeepScreenOn()` sets
  `FLAG_KEEP_SCREEN_ON` on the hosting window; leaving composition clears
  it.
- **Offline/airplane (W2-06, W4-03)**: `rememberOnline` reports offline
  when the shadow `ConnectivityManager` has no active network, recovers
  when one appears; the Navigate screen shows/hides the "Offline ✓" chip
  accordingly.
- **Permission gate (W4-03)**: the camera-denial path renders the
  denial surface with its message; first-run `NOT_DETERMINED` shows the
  rationale. Existing VM coverage is extended, not duplicated.

Acceptance: all new UI tests pass under `:shared:testAndroidHostTest`;
full host suite green; iOS compiles. Anything that still needs real
hardware (haptic feel, two-phone LAN, watch delivery, calibrated walk)
is listed in W6-04's dispositions, not claimed here.

### W6-04 — Production close-out and residual dispositions

**L**, Lane D. Two parts:

1. **Remote evidence (W3-02, W3-04)**: via the Cloudflare MCP connection —
   inspect the account, create the `echo-media` R2 bucket if absent,
   verify the worker's wrangler config binds it, deploy the worker to
   production, then run a live end-to-end job (POST → complete → media
   readable → DELETE) and record the ids, timings, and SHA of the
   deployed code.
2. **Dispositions on 0001**: every residual marker gets a final state —
   closed-with-evidence (W3-02, W3-04, W3-05, plus whatever W6-03's tests
   cover: W2-01 gesture, W2-06 KeepScreenOn/online, W4-03 offline/
   permission paths) or **open-with-owner** naming the W4-06 field pass
   (W5-04 watch delivery, W2-04 two-phone transfer, W4-02 calibrated
   walk, W2-06 haptic feel). NR-1 and NR-2 close with their accepted-
   mitigation dispositions.

Acceptance: live-job evidence recorded on the W3-02/W3-04 rows; every one
of the ten markers carries a disposition; no residual row left bare.
Docs-only diff in the repo plus the recorded remote mutations.
