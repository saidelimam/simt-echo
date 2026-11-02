# Plan 0002 — Production-Ready Cosmos ECHO (Plan 2)

Status: **queued** · created 2026-11-01

Owning repository: `simt-echo` (this repo) — fully enclosed. `simt-app` and
`simt-space` are read-only references: Cosmos code/tokens are copied or
re-implemented into this repo and become ECHO code (see §5). They are never
dependencies, never linked, never modified.
Predecessors: `docs/plans/0001-agentic-flow.md` (36 slices, 34 Complete,
1 Accepted, W4-06 Queued), `docs/plans/0002-residual-risks.md` (W6-01
Complete, W6-02…W6-04 Queued), spec of record
`docs/research/SIMT_ECHO_IMPLEMENTATION_PLAN_V2.md` (cited as *V2 §n*).
This is the second product plan: 0001 proved slices could be marked
Complete; this plan makes the shipped app usable, beautiful, and
production-true. Slice ids continue at W7–W9 so they match the registered
pattern in `simt.json` (`^W[0-9]-[0-9]{2}$`); no pattern change is needed.

## 1. Review verdict and evidence (why this plan exists)

Review verdict on the app as it stands today: **BLOCK** (ADR-008 scale).

Plan 0001 is 97% Complete on unit evidence, and the app a person opens is
still not usable. Both facts are true because 0001's gates were almost
entirely headless: screens were verified as ViewModel state, never as a
journey a non-developer can finish. The findings below were verified by
reading the current tree and running the current gate in worktree `w6-01`
on 2026-11-01; every finding names the slice that closes it.

| # | Severity | Finding (evidence) | Closed by |
|---|---|---|---|
| B1 | BLOCK | **Home is a developer debug menu, not a product.** `ui/screens/Screens.kt:87-95` ships five demo buttons (`Review demo draft`, `Route detail demo`, `Share demo route`, `Align demo route`, `Navigate demo route`) bound to ids `demo-draft`/`demo-route` that never exist on a real install. Every one lands on an error/empty surface ("No saved route demo-route", "No draft route found.", a Share offer that throws `offer: no route`). This is exactly the reported "buttons that navigate to empty screens". Real drafts are listed by raw draft id as buttons; there are no route cards, no empty state, no bottom-fixed Record/Receive, no gear icon (V2 §8.3 Home). | W7-03, W8-06 |
| B2 | BLOCK | **The core loop dead-ends: a saved/received route cannot be navigated.** `RouteDetailScreen.kt` offers Share / Delete uploaded media / Delete route / Back home — and no **Navigate**, no **Align**, no **Simulate**, although V2 §8.3 RouteDetail mandates Navigate + Share + debug Simulate. The only Navigate entry in the app is the B1 demo button with a fake id. Align → Navigate exists in `EchoNavHost.kt:150-171` but is unreachable from any real route. MVP loop step 6 (V2 §1) is therefore impossible from the UI. | W7-04 |
| B3 | BLOCK | **The current test gate is red.** `./gradlew :shared:testAndroidHostTest` on the current tree: **304 tests, 6 failed** — 5× `NoClassDefFoundError: androidx/savedstate/R$id` (`CameraPermissionGateUiTest` ×2, `DeviceServicesUiTest` ×2, `DrawOverlayUiTest` ×1; the in-flight W6-03 Robolectric infra) and `ExportSelectionTest.deleteRemovesPlainExportsAndJobIdRecord` (assertion failure against the dirty `TransferService` change). Worker gate cannot even start here: `npm --prefix worker test` → `vitest: command not found` (dependencies not installed in this worktree). A partial pass is a blocker, not evidence (ADR-006). | W7-02 |
| B4 | BLOCK | **The cloud path is unreachable in a normal build.** `EchoApplication` reads `WORKERS_API_BASE`/`ECHO_APP_TOKEN` from gitignored `local.properties`; absent them `AppGraph.apiBaseUrl/appToken` are empty and `EchoApiClient` fails closed. There is no Settings field, status, or production default for the API, and the worker has never been deployed (R2 `echo-media` blocked; `docs/measurements.md`: deployed 60 m job "still unmeasured and unclaimed"). Processing therefore errors for every fresh install, with no honest "AI unavailable — continue manually" path presented first-class. | W7-07, W9-01, W9-02 |
| B5 | BLOCK | **The relay fallback is dead code in production.** `AppGraph.kt:88-99` constructs `TransferService` without a `RelayClient` ("stays unconfigured here on purpose… Until then transfers are LAN-only"), so `relayAvailable` is permanently false and Share never offers the W4-05 relay that 0001 marked Complete. | W9-03 |
| M1 | MAJOR | **No Cosmos anywhere.** Current theme (`ui/theme/EchoColors.kt`, `EchoTheme.kt`) is V2 §8.1 lime-on-near-black (`#0B0D10`/`#C6F432`), `FontFamily.Default`, only 4 type styles defined, no `Shapes` passed to `MaterialTheme`, card radius 28 dp everywhere, zero icons in the whole UI (`grep Icon` in commonMain: none). None of the six Cosmos signatures (warm depth, semantic light, spatial structure, instrument clarity, responsive presence, human control) is present. Cosmos of record: published spec at simt.space/cosmos/design, Android reading in `simt-app/docs/design/COSMOS_REDESIGN.md`. | W8-01…W8-12 |
| M2 | MAJOR | **Machines, not people, are addressed.** Home shows raw draft ids; `RouteDetail`/Align/Navigate show `No saved route <uuid>`; Receive shows `Expires 1762…` (raw epoch ms, `ReceiveScreen.kt` ImportSheet); Record/Processing surface exception messages. No error taxonomy, no toast/snackbar, no recovery-first copy. Cosmos §9.3 / V2 §8.4 copy discipline (verb-first, ≤6-word instructions, no implementation terms) is unmet. | W7-06 |
| M3 | MAJOR | **Record → Review handoff is a maze.** After Finish, `EchoNavHost.kt:64` pops back to Home, where the user must find their raw-id draft button to open Review. V2's loop (Record → Finish sheet → Review) is never presented as one flow. The Finish "sheet" is an inline form, not a sheet; Record has no heading tick (V2 §8.3). | W7-05, W8-07 |
| M4 | MAJOR | **Research instruments leak into the product UI.** Navigate shows an **At marker** trial button to every user (`NavigateScreen.kt` NavigateActions); Arrived is half destination summary, half §11 field-trial form (lighting / holding style / mistaken confirmations as free-text fields). Trials belong behind Settings/research, not on the success screen. | W8-10, W8-11 |
| M5 | MAJOR | **Settings is a flat developer panel and is missing V2 functions.** No sections, no **Delete all**, no API/AI status (V2 §8.3 Settings lists API base + delete all), no storage/privacy management, no permissions review. Calibration, baseline mode, CSV, camera overlay and LAN-model fields are one long scroll of equal-weight rows. | W8-11 (Delete all, AI status), W7-07 |
| M6 | MAJOR | **Whole surfaces remain device-unverified** (0001 evidence residuals): two-phone LAN/hotspot transfer + ML Kit scan (W2-04), on-device replay-to-ARRIVED + haptic feel + Align PiP (W2-06), calibrated walk (W4-02), device abuse pass (W4-03), live relay (W4-05), on-phone LiteRT (W5-01), camera overlay (W5-02), on-watch delivery (W5-04). Unit-green was repeatedly recorded as if it were product-green. This plan never repeats that claim: device slices below require named hardware evidence. | W9-04…W9-08 |
| M7 | MAJOR | **The Wear module is absent from this build.** `settings.gradle.kts` includes only `:shared` and `:androidApp`, yet W5-04 is Complete on 0001 claiming `:wear:testDebugUnitTest`. Whatever exists on the slice branch is not in the integrated app a user installs. | W9-08 |
| M8 | MAJOR | **Truth drift in the repo itself.** `README.md` still says "Status: scaffold complete… no feature code yet" while 0001 says 97% Complete; 0002's tracker still lists W6-02 Queued although its commit (`1c51afa`) is in history; there is no CI (`.github/workflows/` absent), no signing/release configuration (`isMinifyEnabled = false`, versionCode 1), so "release APK" (W4-06) has no pipeline to come from. | W7-11, W9-10, W9-12 |
| m1 | MINOR | Review is a 462-line screen + 553-line ViewModel; Share buries the QR below a full export-preview form; Receive's scanner is a 280 dp box rather than a full-bleed reticle surface; Align's PiP is 120 dp. Polish, not correctness — fixed inside the Cosmos screen slices, not by refactoring sprees (ADR-002). | W8-08, W8-09, W8-10 |
| m2 | MINOR | V2 §8.1 SIMT font never landed, splash/icon are still lime-rings-on-black placeholders, and `Placeholder.kt`/`PlaceholderScreen` scaffolding still ships in commonMain. | W8-02, W9-11, W7-03 |

**Residual risk of this review itself:** no physical device was driven
while writing it; all runtime-behaviour findings are code- and
gate-evidence, and the plan's W9 device slices exist precisely to convert
them. What was run: the host-test gate quoted in B3 and the worker command
quoted in B3. What was not run: `assembleDebug` standalone (the test task
failed first in the same invocation), any on-device flow, any live worker
call.

Acceptance for this document itself:

- [x] This file exists at `docs/plans/0002-production-ready-cosmos.md` and
      `simt plan status` reads its tracker (36 rows W7-01…W9-12;
      verified 2026-11-01: `slices=36 queued=36, tiers F=15 L=2 S=5 T=14`).
- [x] Every 0001/0002 residual and every V2 §5–§13 requirement appears in
      §3's coverage table with a named closing slice in §6, or is
      explicitly declared out of MVP v1 in §4.
- [x] Tier totals include the new **T** tier, and no S-only surface
      (contract freeze, crypto/security, geometry, release) is assigned
      below S as sole author (§2 rules).

## 2. Model tiers (L · F · T · S)

Tiers are capability classes, not vendor names. Assign the cheapest tier
that can finish the slice; escalate on evidence, not habit. This plan adds
**T** between F and S, as instructed: a thinking model reasons better than
a fast flash model and costs the slice more deliberation than speed, but
it is not the frontier smart model and does not own frontier decisions.

| Tier | Name | Use when | Never alone on |
|---|---|---|---|
| **L** | Small local model | Behaviour fully specified verbatim; single-module, mostly mechanical (copy swaps, asset wiring, doc reconciliation); a test oracle already exists | Anything user-facing that is not already frozen, crypto, privacy, geometry |
| **F** | Cloud flash model | Well-bounded cross-file plumbing against frozen contracts; compile-and-iterate work; straightforward screen rebuilds onto a frozen Cosmos component library | Contract changes, security design, diagnosis of novel failures, UX invention |
| **T** | Thinking model (new) | The slice must *reason*: reconcile spec vs code, diagnose a failure whose cause is not yet known, design screen/state behaviour within frozen tokens, write journey tests, integrate across 3+ subsystems, or review F/L work. Better judgement than F, deliberately not frontier authority | Contract/architecture freezes, crypto & security design, sensor-geometry validation, provider-routing policy, release verdicts — T may draft and recommend; **S** decides |
| **S** | Frontier smart model | Contract or architecture freeze; security/privacy design; geometry validation; Cosmos adoption freeze; production deploy & release gates; final review of anything that can silently corrupt routes, bundles, or user trust | — (ceiling) |

Binding rules:

- **Escalation.** One tier up after two failed test runs on the same cause,
  or immediately on touching a contract, security, privacy, or geometry —
  whoever started the slice.
- **Review pairing.** `Primary (Reviewer)` in §6. L/F primaries on
  sensitive surfaces carry a T or S reviewer who reads the staged diff
  before commit; T primaries on contract-adjacent work carry an S
  reviewer. T is the default reviewer for F screen work (it catches UX
  and state errors F self-certifies past); S reviews T where the surface
  is security, privacy, geometry, or release.
- **Where frontier tokens go.** W8-01 (Cosmos freeze), W9-01 (production
  deploy), W9-09 (security/privacy audit), W7-12/W9-12 (exit/release
  gates). Frontier models do not write boilerplate an L slice can finish.
- **One agent, one slice, one worktree** (`simt slice start/finish`,
  `simt plan done <id> <sha> --test "<cmd>"`). Never two agents on the
  same files concurrently; §7 names sole owners for shared files.
- **Evidence honesty.** A slice is Complete only with its named command
  green *on the integrated tree*, or its named device evidence recorded
  (device model, build SHA, route, result). "Device-unverified" may be
  recorded as residual risk only by W9 slices explicitly chartered to
  close it — and then the slice is not Complete, it is escalated.

## 3. Spec coverage: V2 → this plan (nothing silently dropped)

Status key: **Works** = implemented and unit-proven; **Unverified** =
implemented, never proven where it runs; **Partial** = present but
missing mandated parts; **Broken** = present and wrong for users.

| V2 area | Today | Closing slices |
|---|---|---|
| §1 MVP loop steps 1–3 (record, segment, consent AI) | Works headless; Broken as a journey (B3, B4, M3) | W7-02, W7-05, W7-07, W9-02 |
| §1 steps 4–5 (review, QR/LAN transfer) | Works headless; Unverified on two phones | W8-08, W9-04 |
| §1 step 6 (airplane navigate) | **Broken from UI** (B2); engine Works | W7-04, W9-04 |
| §1 steps 7–8 (re-anchor, trials) | Works; trials leak into product UI (M4) | W8-10, W9-06 |
| §5.1 platform seams | Works; Wear seam orphaned (M7) | W9-08 |
| §5.2 domain model, §5.3 storage | Works (frozen in W0-04; do not re-open except via S) | W7-10 (corrupt-state hardening only) |
| §5.4 wire formats (ECHO/ECX1/QR ≤200 chars) | Works; relay `c=` never exercised live (B5) | W9-03, W9-09 |
| §5.5 worker API + manifest/result schemas | Works in vitest; never deployed (B4) | W9-01, W9-02 |
| §6.1–6.2 heading/PDR, §6.3 segmenter | Works on fixtures; Unverified on walks | W9-05, W9-06 |
| §6.4 navigator, §6.5 turn detector | Works (7 scenarios); Unverified to ARRIVED on device | W7-04, W9-04 |
| §6.6 track selection/ranking | Works on fixtures; live SAM proven once on synthetic image only | W9-02 |
| §7.0–7.2 provider routing, Workers AI scout | Works in vitest; live Workers AI blocked on token scope | W9-01, W9-02 |
| §7.3 SAM 3.1 ground | Works; one live synthetic check | W9-02 |
| §7.4 envelope (~$0.16/route, <2 min) | **Unmeasured, unclaimed** (`measurements.md`) | W9-02 |
| §7.5 local models (LAN + LiteRT) | Implemented; Unverified on any device/server | W9-07 |
| §8.1 tokens/type/motion | Partial (lime theme, 4 type styles, no shapes) | W8-01, W8-02 |
| §8.2 screen map | All routes exist; Home/RouteDetail violate it (B1, B2) | W7-03, W7-04, W8-05 |
| §8.3 screens (each row) | Home Broken; Record Partial (no heading tick, no sheet); Review Works/oversized; Processing Broken unconfigured; RouteDetail Partial (no Navigate/Simulate/expiry line); Share Partial (QR buried); Receive Partial (epoch expiry, small scanner); Align Partial; Navigate Works + trial leak; Arrived Partial (trial form); Settings Partial (no delete-all/API base) | W7-03…W7-08, W8-06…W8-11 |
| §8.4 haptics & copy | Waveforms Works; copy Broken (M2); haptic feel Unverified | W7-06, W9-04 |
| §9/§10 slice definitions | Superseded as sequencing by 0001 + this plan; contracts remain of record | W7-01 (coverage matrix keeps them traceable) |
| §11 testing & field protocol (5+5 runs) | Never executed | W9-06 |
| §12 risks & cut line | Fallbacks mostly implemented; "never cut" core was cut by B2 in practice | §4 cut order restates it for this plan |
| §13 iOS port | Out of scope (W5-03 Accepted/postponed) | — (§4) |
| 0001 residuals (all "device-unverified"/blocked markers) | Open | Mapped per-slice in §6 (W9-01…W9-08) |
| 0002 residuals W6-02/W6-03/W6-04 | W6-02 code landed (`1c51afa`) but tracker stale; W6-03 red (B3); W6-04 not started | W7-11, W7-02, W9-01/W9-02 |

## 4. Scope: MVP v1, explicitly

**MVP v1 is done when** a non-developer, on two production-signed Android
phones, with no developer help and no demo data: records a 30–80 m indoor
walk, reviews/optionally AI-enriches it, saves it, shares it by QR over
LAN/hotspot (relay when LAN is blocked), and the second phone — in
airplane mode — aligns and navigates it to ARRIVED with honest
uncertainty; the worker path is deployed and measured; and every claim in
the README traces to a passing slice or a recorded device run.

In scope: everything in §6 (W7 stabilise → W8 Cosmos → W9 production),
including all residuals listed in §3. Out of scope for MVP v1 (unchanged
from V2 §1/§12, do not re-litigate inside slices): iOS (W5-03 stays
Accepted/postponed; iOS *compilation* gates still run so shared code does
not rot), 3D reconstruction, arbitrary routing, public route library,
accounts, automatic re-localisation, VIO/camera-motion fusion, safety or
evacuation use. On-phone AI remains an optional configured provider
(W9-07 proves it or the UI stops implying it).

Cut order if time squeezes (never improvise): W9-11 → W9-07 (remove the
local-model UI claims instead) → W9-08 (ship phone-only, say so) →
W8-03 ambient Full-tier motion (ship Reduced-tier Cosmos) → W9-03 relay
(ship LAN+file only, say so). **Never cut:** W7-02…W7-04, W7-08, W8-02,
W8-06, W8-10, W9-01, W9-04, W9-12. A Cosmos-skinned app whose core loop
dead-ends is the failure this plan exists to replace.

## 5. Cosmos adoption and copy map (`simt-app`/`simt-space` → ECHO)

Authority order (Cosmos guide §0.1): product/privacy/a11y requirements →
published spec at simt.space/cosmos/design (verify tokens against
`tokens.json` before freezing; the simt-app markdown is a dated snapshot)
→ `simt-app/docs/design/COSMOS_REDESIGN.md` for Android composition →
ECHO code. Where the snapshot and the published spec disagree, the
published value wins and the gap is recorded in W8-01.

Decisions this plan settles (W8-01 freezes the details, S-owned):

- **Echo Lime is retired as the primary accent.** Cosmos accent grammar
  governs: Ember primary agency, Rose secondary/social, Violet identity,
  Gold focus/precision, Cyan live/location, Moss natural/stable, Danger
  and Success as named. ECHO keeps its name, wordmark and copy voice;
  it does not keep a competing lime brand channel (Cosmos §1.4: at most
  one dominant + two supporting channels per viewport).
- **Deep Space dark-first** (`void #120A14`, `hull #1D1220`, ink
  `#FFF8F2`) with Daylight Nebula light; astronomy red mode is *not* in
  MVP v1 (no ECHO surface is a dark-adaptation tool) — recorded as a
  deliberate non-adoption in W8-01, not an oversight.
- **Surfaces:** canopy principle (§3.1 Cosmos): void stays continuous,
  1 dp `line` borders, top-light only on dialogs/sheets/floating
  surfaces per variant table; shapes Control 10 / Record 14 / Panel
  20 dp (replacing the uniform 28 dp); spacing on the 4-unit baseline;
  type in Space Grotesk-style semantic roles with tabular numerals for
  every distance/bearing/time readout, sentence case, no negative
  tracking; motion tiers Full/Reduced/Static with gold focus that is
  never glow-only; status never by hue alone.

| ECHO need | Source to copy/adapt (read-only) | Verdict | Fallback |
|---|---|---|---|
| Tokens (color/type/shape/motion) | `simt-app/core/ui/theme/{Color,Type,Shape,Motion}.kt` + published `tokens.json` | **Adapt** into `ui/theme/` as ECHO-owned Cosmos tokens; verify every hex against the published spec in W8-01 | V2 §8.1 values mapped through the Cosmos grammar (never ship lime-by-default) |
| Atmosphere | `components/backgrounds/CosmosBackground.kt`, `components/CanopyGlass.kt`, `components/CosmosFx.kt` | **Adapt, with one correction:** simt-app's star placement is disk-distributed; the published spec mandates the outer-20% edge band, cached as one layer, centre 60%×60% clear. Implement the spec, not the drift (Cosmos §5.1 calls this out itself) | Static void gradient + edge band, no animation (Reduced tier as the whole feature) |
| Components (buttons, chips, fields, dialogs, sheets, banners, empty states) | `core/ui/components/{buttons,chips,forms,dialogs,sheets,banners}`, `theme/Theme.kt` mappings | **Copy the smallest pieces** into `ui/components/`, renamed, M3-mapped; dialogs/sheets go through the shared canopy variants — no raw `AlertDialog`/bottom-sheet bypass (repo modal policy) | Build to Cosmos §8 tables directly |
| Nav shell / screen grammar | `core/ui/layout/AppScreen.kt`, `transitions/AppScreenTransition.kt`; Cosmos §6–§7 (Ignition, Personal orrery, Settings rail, tool deck vs orbital dock) | **Adapt grammar, keep ECHO routes.** ECHO has 2 primary actions + Settings, not 4 dock destinations, so the orbital-dock pattern does *not* apply (Cosmos §7.5 forbids squeezing); use the tool-deck/top-chrome grammar | Plain Cosmos top bar + bottom action bar built in W8-05 |
| Haptics, DI, domain patterns | (as 0001 §4) | Unchanged: no helper to copy, manual `AppGraph`, no framework vendored | — |

## 6. Tracker

Evidence naming: `./gradlew :shared:testAndroidHostTest --tests "<class>"`
(KMP slices), `./gradlew :androidApp:assembleDebug`,
`./gradlew :shared:compileKotlinIosArm64 :shared:compileKotlinIosSimulatorArm64`,
worker `npm ci && npm test` in `worker/`, or a named device check recorded
with device model + build SHA. No slice is Complete on a partial pass.

| ID | State | Slice | Tier | Lane | Depends on | Evidence |
|---|---|---|---|---|---|---|
| W7-01 | Complete | Runtime truth audit + V2 coverage matrix (docs) | T (S) | F | — | 264fb5c7281ee0cab96ba996a1358cedffdccf59 |
| W7-02 | Complete | Fix the red host gate: Robolectric savedstate + ExportSelection | T | E | — | b48637784d1b50fe45dd1162c7b6a58894136890 · test: ./gradlew :shared:testAndroidHostTest |
| W7-03 | Complete | Purge demo Home; real routes/drafts only, honest empty state | F (T) | E | W7-02 | 9f4756890e003642a347290bde7f758546588837 · test: ./gradlew :shared:testAndroidHostTest |
| W7-04 | Complete | Close the loop: RouteDetail → Align → Navigate + Simulate | T (S) | E | W7-03 | 741d42ffdcaf6a2e8ce378d25ab762f7f4dbc714 |
| W7-05 | Complete | Record → Review handoff (Finish lands in Review, named drafts) | F (T) | B | W7-03 | 841c74d20c5a1ae5e0add906137b7192a1361013 |
| W7-06 | Complete | Human copy & error taxonomy (no ids, epochs, or exceptions) | F (T) | E | W7-01 | 74c0d428e3349edef4aec065ee1366608e93c9ac |
| W7-07 | Complete | AI-unavailable honesty when the worker is unconfigured | F (S) | D | W7-06 | 4884127a145a59750b8465d547572b034e709de7 |
| W7-08 | Complete | Share/Receive failure recovery & offer lifecycle | F (T) | E | W7-06 | 318f122eb4e864a9772fcbadbd92d3be117398ba |
| W7-09 | Complete | Headless user-journey tests for the full core loop | T | F | W7-04, W7-05, W7-08 | 23d9dd636219df03a474b1e88d3ba25e344dcda6 |
| W7-10 | Complete | Corrupt/missing storage never crashes startup or screens | F (T) | A | W7-02 | f73c967d4b4a7fbaa816a8c3697ec266ecb18683 |
| W7-11 | Complete | Repo truth: README, tracker states, stale scaffolding removed | L (F) | F | W7-01 | 72c8c1cbde400ddbf19614a5e03e9d7b7a495fdf |
| W7-12 | Queued | Wave 7 exit gate: offline core loop proven on one device | S | F | W7-09, W7-10, W7-11 | — |
| W8-01 | Queued | Cosmos adoption freeze (tokens, accents, non-adoptions) | S | A | W7-12 | — |
| W8-02 | Queued | Cosmos tokens & theme in code + contrast gate | F (T) | E | W8-01 | — |
| W8-03 | Queued | Cosmos atmosphere: background, canopy, motion tiers | T | E | W8-02 | — |
| W8-04 | Queued | Cosmos component library (commands, fields, dialogs, states) | F (T) | E | W8-02, W8-03 | — |
| W8-05 | Queued | App shell & navigation grammar (chrome, back, transitions) | T (S) | A | W8-04 | — |
| W8-06 | Queued | Home in Cosmos (route records, first-run, recovery) | T | E | W8-05 | — |
| W8-07 | Queued | Record in Cosmos (camera instrument, Finish sheet) | T | B | W8-05 | — |
| W8-08 | Queued | Review in Cosmos (minimap instrument, landmark records) | T (S) | E | W8-05 | — |
| W8-09 | Queued | Processing/Share/Receive in Cosmos (consent, QR, reticle) | F (T) | E | W8-05 | — |
| W8-10 | Queued | Align/Navigate/Arrived in Cosmos (instrument clarity) | T (S) | E | W8-05 | — |
| W8-11 | Queued | Settings in Cosmos (sections, delete-all, AI status) | F (T) | E | W8-05, W7-07 | — |
| W8-12 | Queued | Accessibility & motion gate (contrast, fontScale, TalkBack) | T | F | W8-06, W8-07, W8-08, W8-09, W8-10, W8-11 | — |
| W9-01 | Queued | Worker production deploy (R2, secrets, live health) | S | D | W7-12 | — |
| W9-02 | Queued | Live AI envelope: deployed 60 m job, cost/latency measured | T (S) | D | W9-01 | — |
| W9-03 | Queued | Relay wired end-to-end (AppGraph → Share → Receive) | F (S) | D | W9-01, W7-08 | — |
| W9-04 | Queued | Two-phone field E2E (transfer, airplane navigate, haptics) | T | F | W7-12, W8-12 | — |
| W9-05 | Queued | Device robustness pass (kill, deny, airplane, no-sensor) | F (T) | F | W9-04 | — |
| W9-06 | Queued | Calibration + §11 field trials (5+5, CSV, honest report) | F (T) | F | W9-04, W9-05 | — |
| W9-07 | Queued | Local model proven on device/LAN — or claims removed | T (S) | D | W9-02 | — |
| W9-08 | Queued | Wear module restored; paired-watch haptic proof | F (T) | F | W7-12 | — |
| W9-09 | Queued | Security & privacy production audit | S | D | W9-01, W9-03 | — |
| W9-10 | Queued | Release engineering: CI, signing, versioned release build | F (S) | A | W7-02 | — |
| W9-11 | Queued | First-run, icon, splash, screenshots in Cosmos | L (T) | E | W8-12 | — |
| W9-12 | Queued | MVP v1 release gate (README, demo, limitations, APK) | S | F | W9-02, W9-04, W9-05, W9-06, W9-09, W9-10, W9-11 | — |

Tier totals: **L 2 · F 15 · T 14 · S 5** (primary tiers; reviewers in
parentheses). Lanes: A — Foundation & contracts (W7-10, W8-01, W8-05,
W9-10); B — Capture & sensors (W7-05, W8-07); C — Core loop (consumed via
W7-04/W9-09; no primary rows, its contracts are frozen); D — Worker & AI
(W7-07, W9-01…W9-03, W9-07, W9-09); E — UI & UX (all remaining screen
slices); F — QA, field & release (W7-01, W7-09, W7-11, W7-12, W8-12,
W9-04…W9-06, W9-08, W9-12). Parallel batches: Batch 7a = W7-01 + W7-02
(disjoint: docs vs test infra); 7b = W7-03…W7-08, W7-10, W7-11 as
dependencies allow (Review screen and `EchoNavHost` serialise per §7);
7c = W7-09 → W7-12. Batch 8a = W8-01 → W8-02 → W8-03/W8-04; 8b = W8-05,
then W8-06…W8-11 in parallel (disjoint screen files); 8c = W8-12.
Batch 9a = W9-01/W9-10 in parallel; 9b = W9-02, W9-03, W9-04, W9-08;
9c = W9-05…W9-07, W9-09, W9-11; 9d = W9-12 alone. Critical path:
`W7-02 → W7-04 → W7-09 → W7-12 → W8-01 → W8-02 → W8-05 → W8-10 → W8-12 → W9-04 → W9-12`.

## 7. Shared-file ownership

| File / contract | Sole owner | Consumers wait for |
|---|---|---|
| `ui/screens/Screens.kt` (Home), `ui/EchoNavHost.kt`, `ui/EchoRoutes.kt` | Lane E via W7-03/W7-04, then W8-05/W8-06 by handoff | The owning slice's commit; screens register, never edit the graph |
| `ui/screens/review/*` | Lane E (W8-08 after W2-01/W3-07 lineage) | One Review change at a time, as in 0001 §5 |
| `ui/theme/*` | Lane E via W8-01/W8-02 freeze | No screen hard-codes a hex/sp/dp token after W8-02 |
| `AppGraph.kt`, BuildConfig plumbing | Lane A (W7-07 status surface, W9-03 relay wiring by D handoff) | Named handoffs only |
| Worker `types.ts` / API contract | Lane D (frozen in W3-03; W9-01 deploys, never re-shapes) | Any contract change is a new S slice, not an edit inside W9-01 |
| Domain model, `BundleCodec`, `Navigator` geometry | Frozen (0001) | Changes only via new S slices with geometry/security review |

## 8. Slice sections

Each section: tier rationale, reuse hook, acceptance. Build steps and
file lists are bounded by the blast radius named here; widening them is
an escalation, not initiative (ADR-002).

### Wave 7 — Truth and stabilisation (make it work)

#### W7-01 — Runtime truth audit + V2 coverage matrix
**T (S review)**, Lane F. A thinking slice: reconcile V2 §5–§13, both
trackers, all five reviews, and the current tree into
`docs/research/echo-v2-coverage.md` — one row per requirement: state
(Works/Unverified/Partial/Broken), code evidence (file:line), closing
slice in this plan. The S reviewer checks that no residual was quietly
dropped. Acceptance: matrix exists; every §3 row traces to it; no
requirement marked Works without a named green test.

#### W7-02 — Fix the red host gate
**T**, Lane E. Diagnosis before code (B3): the Robolectric
`androidx/savedstate/R$id` `NoClassDefFoundError` (missing/incorrect
test-classpath wiring introduced with W6-03's infra in
`gradle/libs.versions.toml`/`shared/build.gradle.kts`) and the
`ExportSelectionTest` regression against the current `TransferService`.
Thinking-tier because both causes are non-obvious and cross Gradle,
Robolectric and transfer layers; do not "fix" by deleting tests.
Acceptance: `./gradlew :shared:testAndroidHostTest` exits 0 with every
test passing (≥304, none skipped without pre-existing written reason);
worker `npm ci && npm test` green in `worker/`; `DrawOverlayUiTest`,
`CameraPermissionGateUiTest`, `DeviceServicesUiTest` genuinely run
(this finishes 0002's W6-03 in substance).

#### W7-03 — Purge demo Home
**F (T review)**, Lane E. Delete the five demo buttons and the
`demo-draft`/`demo-route` ids entirely; delete `Placeholder.kt`
scaffolding if unreferenced after. Home lists only real saved routes,
real drafts (by route/draft *name*, never raw id where a name exists),
and recoverable drafts, with the V2 empty state ("Walk it once. Share
the way.") when all three are empty. Acceptance: `Screens.kt` contains
no `demo-` string; a Compose journey test with an empty repository
shows the empty state and Record/Receive only; host suite green.

#### W7-04 — Close the loop: RouteDetail → Align → Navigate + Simulate
**T (S review)**, Lane E. The single most valuable slice in this plan
(B2). RouteDetail gains **Navigate** (→ Align → Navigate), **Share**,
and debug **Simulate** (replays the creator capture via
`ReplayMotionSource`, V2 W2-05's promised RouteDetail entry that was
never wired); Arrived becomes reachable from a real route. S reviews
that navigation inputs/geometry are untouched — this is wiring, not
navigator changes. Acceptance: journey test (W7-09 may extend it)
drives fixture route Home → RouteDetail → Align → Navigate →
ARRIVED on `ReplayMotionSource`; `simulate` flag is honoured end-to-end
in `EchoNavHost`; host suite + `assembleDebug` green.

#### W7-05 — Record → Review handoff
**F (T review)**, Lane B. On Finish save, navigate directly to
`Review(draftId)`; Home draft rows show the route name/destination the
user just typed, not the draft id. Resume-recording keeps working via
the recoverable-drafts section. Acceptance: `RecordScreen`/
`EchoNavHost` no longer pop-to-Home on save; a host test asserts the
navigation result carries the new draftId; CaptureSession behaviour
unchanged (existing `CaptureSessionTest` green).

#### W7-06 — Human copy & error taxonomy
**F (T review)**, Lane E. Introduce one typed user-error surface (every
screen error is: what happened, what is safe, next action — Cosmos
§8.8) and purge raw identifiers from UI copy: no route/draft UUIDs in
titles or errors, Receive expiry formatted as a human date/time,
exception messages never interpolated into user text (log/detail stays
in code, copy stays human). Headings/instructions follow V2 §8.4
(verb-first, ≤6 words where it is an instruction). Acceptance: grep
gate — no `routeId`/`draftId`/`expiresAtEpochMs` string interpolation
remains in screen copy; copy snapshot tests updated; host suite green.

#### W7-07 — AI-unavailable honesty
**F (S review of the privacy wording)**, Lane D. When
`apiBaseUrl`/`appToken` are empty (every fresh install until W9-01
config exists), Processing presents "Landmark AI isn't set up on this
build" with **Continue without AI** as the primary path — never a
network error — and Settings shows the same status (consumed fully in
W8-11). No fake "cloud" progress. Acceptance:
`ProcessingViewModelTest` covers unconfigured → honest state, no HTTP
attempt made (mock-client assertion); host suite green.

#### W7-08 — Share/Receive failure recovery & offer lifecycle
**F (T review)**, Lane E. Share: offer failure (no route, no Wi-Fi IP,
transport error) is a recoverable state with Retry, not a stuck
"Starting offer…"; leaving Share always stops the offer (already
`DisposableEffect` — prove it). Receive: LAN failure explains the
hotspot next step (V2 W2-04 copy), expiry and counts come from the
preview model, rescan resets cleanly. Acceptance: `ShareViewModelTest`/
`ReceiveViewModelTest` gain failure/retry/stop cases; host suite green.

#### W7-09 — Headless user-journey tests
**T**, Lane F. The gate 0001 never had: Robolectric/Compose journeys
over fixture data for (a) empty Home → Record entry, (b) Home →
RouteDetail → Align → Navigate → Arrived (replay), (c) draft →
Review → Save → RouteDetail, (d) Share offer → Receive preview model
(no sockets: service fakes at the seams). Thinking-tier: designing
journeys that assert behaviour, not implementation, across five
screens. Acceptance: new journey tests pass under
`:shared:testAndroidHostTest`; they fail if the W7-04 Navigate wiring
is removed (mutation-checked once, recorded).

#### W7-10 — Corrupt/missing storage hardening
**F (T review)**, Lane A. Corrupt `route.json`/`capture.json`/config,
missing assets, and a purged-while-open route degrade to the honest
empty/error states of W7-06 — never a startup crash (`EchoApplication`
purge, `RouteRepository` reads, `ConfigRepository` sanitisation are
the audit surface). Acceptance: repository tests with corrupt fixtures
per file type; startup path test; host suite green.

#### W7-11 — Repo truth
**L (F review)**, Lane F. Mechanical, fully specified: README status
replaced with the true state and a pointer to this plan; 0001/0002
tracker states reconciled with git history (incl. W6-02's landed
commit); measurements/README claims each trace to evidence or are
deleted. Docs-only. Acceptance: named doc checks — README contains no
"scaffold complete / no feature code" claim; `simt plan status` totals
match the trackers' text.

#### W7-12 — Wave 7 exit gate
**S**, Lane F. Frontier-owned verdict on one physical device (named in
evidence): install the debug build, walk the offline core loop with a
fixture-free real recording (Record → Review → Save → Share screen →
Align → Navigate → Arrived or an honest UNCERTAIN/OFF_ROUTE), airplane
mode for navigation. No AI, no second phone (those are W9). Acceptance:
recorded run (device, SHA, route length, outcome) appended to
`docs/measurements.md`; verdict APPROVE / APPROVE WITH RESIDUAL RISK
(listed) / BLOCK with the blocking slice re-opened. Wave 8 does not
start on a BLOCK.

### Wave 8 — Cosmos (make it designed)

#### W8-01 — Cosmos adoption freeze
**S**, Lane A. Verify published tokens (`tokens.json`/`rules.json` at
simt.space/cosmos/design) against the simt-app snapshot; freeze
`docs/research/cosmos-echo-freeze.md`: token table (Deep Space +
Daylight Nebula), accent-channel assignments per ECHO surface (e.g.
Cyan only for live/location-lock, Gold for focus/precision, Ember for
the one primary action), shape/spacing/type/motion tables, and the
explicit non-adoptions (astronomy red mode; orbital dock — wrong
destination count; disk-distributed starfield). Acceptance: freeze doc
exists; every value cites the published spec or is marked ECHO-local;
no screen work in this slice.

#### W8-02 — Cosmos tokens & theme in code
**F (T review)**, Lane E. Implement the freeze in `ui/theme/`: full
semantic type scale (not 4 styles), `Shapes` in `MaterialTheme`,
spacing/touch tokens, tabular-numeral readouts, SIMT-appropriate font
decision from the freeze (bundled Space Grotesk-class font if licensing
allows, else platform with the freeze's fallback — decided in W8-01,
not here). Contrast gate: unit tests assert the frozen WCAG pairs
(4.5:1 text / 3:1 boundaries) for both schemes, extending the existing
W4-04 scheme tests. Acceptance: theme tests + host suite green;
`assembleDebug` renders Home in both schemes (screenshot test or
recorded manual capture named in evidence).

#### W8-03 — Cosmos atmosphere
**T**, Lane E. `CosmosBackground` (void gradient, edge-band starfield
cached as one layer per Cosmos §5.1, ≤2 nebula fields Full-tier only),
canopy-glass surface with variant top-lights, and the motion-tier
resolver (Full/Reduced/Static from accessibility + battery +
visibility). Thinking-tier: performance and reduced-motion correctness
are design decisions, not decoration. Acceptance: background draws
with zero per-frame allocation in the draw phase (code-audited +
recorded profile or Compose test), Reduced/Static render the same
hierarchy statically, stars absent from the accessibility tree.

#### W8-04 — Cosmos component library
**F (T review)**, Lane E. Rebuild `ui/components/` on the freeze:
Primary (ember→rose, one per region) / Secondary / Tertiary /
Destructive / Icon commands with Cosmos §8.1 loading semantics, chips
& segmented controls, record/row grammar (dividers, not nested cards),
form fields with visible labels + clear buttons, canopy dialogs &
sheets (shared modal policy — no raw dialog bypass), banners, toast,
and the §8.9 state family (loading/empty/denied/offline/error/success)
as the only state components screens may use. Acceptance: component
tests (states, 48 dp targets, gold focus visible) green; old
`EchoButton/PillButton` either migrated or deleted — no third variant
survives.

#### W8-05 — App shell & navigation grammar
**T (S review of the grammar)**, Lane A. One Cosmos shell: top chrome
(wordmark, Settings gear, back/close semantics that are never
interchanged), screen transitions per Cosmos §4.5, safe-area/dock
clearance, Record + Receive as the persistent primary actions, heading
focus on destination change. S reviews the grammar once so eleven
screen slices do not invent eleven shells. Acceptance: shell tests
(back vs close, focus, safe areas at fontScale 2.0) green; every
route renders inside the shell with no screen-local top bar.

#### W8-06 — Home in Cosmos
**T**, Lane E. Collections grammar (Cosmos §6.5): route *records* —
name, length, turns, landmark count, Own/Received/Draft badges (shape
+ text, never hue-only), offline-ready mark — in stable tracks;
first-run Ignition (one centred task, one primary command);
interrupted-drafts recovery section first when present. Acceptance:
journey tests from W7-09 re-green against the new Home; empty,
populated, and recovery states each screenshot- or test-evidenced.

#### W8-07 — Record in Cosmos
**T**, Lane B. Personal-orrery grammar (Cosmos §6.3): camera is the
instrument and owns the first read; pre-start overlay, top chips
(time/steps/marks), giant tabular distance, heading tick (the missing
V2 §8.3 element), 88 dp Start, Mark spot, long-press Finish — then a
real Cosmos Finish *sheet* (name + destination, one Continue). Sensor
unavailable/denied/stale states keep the same geometry. Acceptance:
Record journey + permission-gate UI tests green; no behaviour change
to `CaptureSession` (its tests untouched and green).

#### W8-08 — Review in Cosmos
**T (S review of creator-verification semantics, as in 0001)**, Lane E.
Minimap as a Cosmos instrument surface (petrol ground grammar, gold
route), node timeline as records, landmark cards with keyframe/mask,
stat chips, Use / Next / Draw / Skip, provenance footnote naming the
models that ran. The serial-handoff rule still applies: this slice
owns `review/*` alone in its batch window. Acceptance:
`ReviewViewModelTest` + draw-gesture UI test green; AI candidates
still cannot mutate geometry (existing immutability test untouched);
screen fits 400-line warning discipline by extracting card composables
into the component library where W8-04 already owns them.

#### W8-09 — Processing/Share/Receive in Cosmos
**F (T review)**, Lane E. Consent as a canopy sheet with the frozen
privacy copy (W7-07 states preserved); Processing as a staged
instrument with determinate-where-honest progress; Share with the QR
as the first read (export preview becomes a collapsed canopy section,
QrCard on its light surface at ≥260 dp); Receive full-bleed scanner
with reticle motif and import sheet. Acceptance: Processing/Share/
Receive ViewModel tests green; QR legibility asserted at the frozen
size; consent appears before any upload (existing test untouched).

#### W8-10 — Align/Navigate/Arrived in Cosmos
**T (S review of UNCERTAIN/OFF_ROUTE honesty, as in 0001)**, Lane E.
Instrument clarity under motion/outdoors: reticle-framed 200 dp arrow,
instruction + tabular distance, σ/offline chips (icon + text, never
hue-only), landmark card, progress with node dots, contextual actions
with trial instrumentation (At marker) removed to the research surface
(W8-11); Arrived as a Cosmos success state (destination, time,
distance, confirmations) with the §11 trial form moved behind
"Field trial details" disclosure — still saved, no longer the screen.
Camera-overlay variant keeps W5-02's honest HUD semantics on the new
shell. Acceptance: `NavigateViewModelTest`/`NavigatorTest` green;
phase→UI mapping test (all 8 phases) green; overlay mask-over-crop
tests green.

#### W8-11 — Settings in Cosmos
**F (T review)**, Lane E. Settings grammar (Cosmos §6.7): sectioned
rows (Step length & calibrate / Trials & baseline & CSV / Navigation /
Privacy & data incl. **Delete all** with canopy confirm / AI status
from W7-07 + Advanced local-model, collapsed by default). Delete-all
removes routes, drafts, trials and exports and says what it did.
Acceptance: `SettingsViewModelTest` gains delete-all + status cases;
every row ≤ the frozen row grammar; host suite green.

#### W8-12 — Accessibility & motion gate
**T**, Lane F. Verify, don't assert: WCAG pairs measured for the
shipped schemes, fontScale 2.0 + display-size pass on every screen,
TalkBack order/names audited (icons gain content descriptions or are
declared decorative), touch targets ≥48 dp asserted, Reduced/Static
motion parity (task order and completion unchanged). Acceptance:
named UI tests + a recorded manual TalkBack pass per primary screen;
any failure reopens its screen slice — this gate does not fix forward.

### Wave 9 — Production (make it true)

#### W9-01 — Worker production deploy
**S**, Lane D. 0002's W6-04, production half: create `echo-media` R2
bucket (record resource id + command), verify wrangler bindings,
set secrets (`ECHO_APP_TOKEN`, `META_API_KEY` if used), deploy, prove
`GET /v1/health` live with provider flags true-to-config, bake the
production base URL into release BuildConfig plumbing (no developer
`local.properties` required for a release build). Remote mutations are
recorded, never implied. Acceptance: live health response recorded;
a throwaway job POST → complete → DELETE → 404 recorded with ids.

#### W9-02 — Live AI envelope
**T (S review of the claims)**, Lane D. One real 60 m route (6–8
checkpoints) through the deployed worker: Workers AI scout (Llama 4
Scout → Gemma fallback genuinely exercised or its absence recorded),
SAM 3.1 grounding when the key is configured, Spark only if enabled.
Record scoutMs/samMs/samFrames, billed cost, end-to-end latency in
`docs/measurements.md` against the §7.4 envelope; update Review's
model-naming copy to what actually ran. Acceptance: `result.json`
from the live job archived as evidence; envelope met or the miss is
recorded with cause — unmeasured is not Complete.

#### W9-03 — Relay wired end-to-end
**F (S review of the zero-knowledge property)**, Lane D. `AppGraph`
constructs `RelayClient` from the same production config as
`EchoApiClient`; Share offers **Use internet relay**, QR gains `c=`,
Receive tries LAN then relay (W4-05 semantics, finally reachable).
Acceptance: `TransferServiceTest` relay cases run against the wired
graph (not a null relay); one LAN-blocked transfer on devices or a
recorded network-emulated equivalent; server-side inspection shows
ciphertext only (S-reviewed).

#### W9-04 — Two-phone field E2E
**T**, Lane F. The demo that should have gated 0001: two named Android
phones, Phone A records a real 40–80 m course, shares by QR on venue
Wi-Fi *and* on A's hotspot, Phone B imports, goes airplane mode,
aligns (PiP judged usable or fixed via re-open), navigates to ARRIVED;
haptic sequence judged on device (TICK/CONFIRM/LEFT/RIGHT/WARN/ARRIVE
distinguishable or re-open W8-10). Acceptance: recorded runs (devices,
SHA, times, transfer durations, outcome per run) in `measurements.md`;
failures become new slices, not footnotes.

#### W9-05 — Device robustness pass
**F (T review)**, Lane F. The W4-03 abuse script on hardware: kill
mid-record, kill mid-navigation, deny camera, airplane mid-upload, no
barometer, no Wi-Fi IP, camera-bind failure — each ends in the W7-06
honest state with resumable work where 0001 promised it. Acceptance:
checklist with per-case outcome + build SHA recorded; any crash
reopens W7-10.

#### W9-06 — Calibration + §11 field trials
**F (T review)**, Lane F. Calibrated 10 m → K per phone; the full V2
§11 protocol on one course: 5 SENSOR_ONLY + 5 ECHO runs alternating,
tape markers at 3 nodes, arrivals/attempts, time, confirmations,
drift, mistaken confirmations, lighting/holding/notes — exported as
one CSV via Settings, published in `measurements.md` exactly as
measured, including failures. Acceptance: CSV + report committed;
baseline mode provably hid landmark cards during SENSOR_ONLY runs.

#### W9-07 — Local model: prove it or remove the claim
**T (S review)**, Lane D. On a ≥8 GB device with a pushed `.litertlm`
(or a real LAN server): Review shows local candidates with correct
`models = "<id> (local)"` naming, offline. If it cannot be proven,
Settings' local-model section is removed/hidden for v1 and the README
says local AI is not in v1 — an honest absence beats an unverified
feature. Acceptance: recorded inference run (device, model, latency)
or the removal commit; either way no unverified claim survives.

#### W9-08 — Wear module restored
**F (T review)**, Lane F. Reintegrate the W5-04 wear code into
`settings.gradle.kts` as a real module of this build (applicationId
parity with the phone app, per the Wear rules), phone↔watch Data
Layer haptics proven on a paired watch. Acceptance:
`:wear:assembleDebug` (or the module's real task) green in the
integrated build; on-watch delivery recorded (watch model, SHA) —
or the module and its claims are removed from v1 explicitly.

#### W9-09 — Security & privacy production audit
**S**, Lane D. Frontier audit of what ships: bundle crypto (ECX1,
token/key handling in QR), relay zero-knowledge, consent-before-upload
ordering, `privacyRisk` fail-closed export, expiry purge on both
phones, contributor-model exclusion, secrets scan of the repo and the
release APK (no `local.properties` values baked beyond the intended
public base URL + app token design, which is itself re-examined).
Acceptance: audit recorded in `docs/reviews/` with the three-state
verdict; BLOCK findings become slices before W9-12.

#### W9-10 — Release engineering
**F (S review of signing)**, Lane A. CI that runs the exact gates of
this plan (host tests, iOS compiles, worker typecheck+test,
assembleDebug) on every push; signed release build (keystore outside
git, documented), versionCode/versionName discipline, minify decision
recorded (enable with keep rules proven by a release-build smoke run,
or record why not). Acceptance: CI green on the release commit;
`assembleRelease` artefact recorded with SHA-256.

#### W9-11 — First-run, icon, splash, screenshots
**L (T review)**, Lane E. Mechanical against the freeze: Cosmos app
icon (no lime rings), splash on void, first-run permission priming in
the Ignition grammar, and store/demo screenshots taken from the real
Cosmos build. Acceptance: assets committed; screenshots are of the
current SHA's build (recorded); icon/splash render in both schemes.

#### W9-12 — MVP v1 release gate
**S**, Lane F. W4-06's successor, frontier-owned because claims are
the product: README rewritten to what the release APK actually does
(setup from a fresh clone, connectivity table, limitations and
non-claims — no offline AI generation, no 3D, no arbitrary routing,
no safety use), demo script run end-to-end on the signed APK on two
phones, every README claim traced to a slice SHA or a W9 device
record. Acceptance: fresh-clone build following the README only;
demo recorded; verdict recorded on this row. Push/release is a
separate approved decision after this gate, never part of it.

## 9. Risks and escalation

| Risk | Mitigation / fallback |
|---|---|
| Cosmos published spec and simt-app snapshot disagree | Published spec wins (W8-01 verifies per-token); snapshot drift is recorded, never copied (starfield is the known case) |
| Cosmos rebuild destabilises working ViewModels | Wave 8 changes presentation only; ViewModel tests are the untouched contract; any ViewModel change escalates to T with S review on Review/Navigator |
| Device access unavailable for W9 | Slices stay Queued and v1 does not claim them; a release without W9-04/W9-06 evidence is labelled a developer build, not MVP v1 |
| Worker deploy blocked again (R2/token scope) | W9-01 escalates to S immediately with the exact permission error recorded; app-side v1 proceeds LAN/file-only and W7-07's honest AI copy becomes the shipped truth |
| Thinking-tier slices drift into frontier decisions | §2 pairing: T drafts, S decides on freeze/security/geometry/release; reviewers named per row in §6 |
| Scope creep into iOS/VIO/social | §4 out-of-scope list; new ideas get a future plan number, not a W7–W9 row |

## 10. What this plan deliberately does not do

It does not re-open frozen contracts (domain model, bundle format,
Navigator geometry, worker schemas) except through new S slices; it
does not refactor Review/ViewModel internals beyond what W8-08's
presentation extraction needs; and it does not mark anything Complete
on the strength of tests that never touched the surface the slice
claims. That last rule is the whole lesson of Plan 0001.
