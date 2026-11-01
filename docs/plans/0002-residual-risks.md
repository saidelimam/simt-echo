# Plan 0002 — Residual risks from 0001-agentic-flow

Status: **queued** · created 2026-11-01

## Scope

Close out the ten residual-risk markers carried on the `Complete` rows of
`docs/plans/0001-agentic-flow.md`. The markers fall into three shapes:

1. **One code gap** — W4-01: Settings has no CSV export action. The `shareFile`
   seam it was waiting on landed with W2-07; what is missing is the wiring.
2. **Verification debt** — W2-01 (draw gesture), W2-06 (KeepScreenOn, online
   chip, haptic feel), W4-03 (device abuse pass), W2-04 (two-phone transfer),
   W4-02 (calibrated walk), W5-04 (on-watch delivery). Six items marked
   "device-unverified". Headless coverage is added where it is honest; the
   rest is recorded open-with-owner for the W4-06 field pass.
3. **Infra-blocked + resolved** — W3-02/W3-04 (production deploy blocked on
   R2 bucket permission), W3-05 (marked blocked, then cleared by its own live
   check). Closed with hard evidence via the Cloudflare MCP connection.

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
| W6-02 | Queued | Draw-box normalisation extracted + unit-tested | F | E | — | — |
| W6-03 | Queued | Robolectric + Compose UI verification (gesture, KeepScreenOn, offline/airplane, permission gate) | L | E | W6-02 | — |
| W6-04 | Queued | Production close-out: R2 bucket, worker deploy, live job, residual dispositions on 0001 | L | D | W6-01, W6-02, W6-03 | — |

## Slices

### W6-01 — Settings CSV export

**F**, Lane E. The W4-01 residual said the export "needs a shareFile seam
that does not exist yet"; the seam exists (W2-07 `FileSharing.shareFile`,
`TrialRepository.exportCsv()` tested). The gap is the UI: `SettingsScreen`
has no export action.

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
is inline in `DrawOverlay`'s confirm lambda. Extract it to a pure
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
   walk, W2-06 haptic feel).

Acceptance: live-job evidence recorded on the W3-02/W3-04 rows; every one
of the ten markers carries a disposition; no residual row left bare.
Docs-only diff in the repo plus the recorded remote mutations.
