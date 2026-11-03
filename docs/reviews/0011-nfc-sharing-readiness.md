# Review 0011 — ECHO Tap (NFC + QR sharing) readiness

Date: 2026-11-03 · Plan: [0005](../plans/0005-echo-nfc-sharing.md) · Slices: W20-01 … W21-04

**Verdict: APPROVE WITH RESIDUAL RISK.**

What was reviewed: the pairing code path from invitation to saved route, and the
copy that path can show. What was **not** reviewed is listed under residual risk —
above all, no Android device was in the loop. A host test proves the software
agreeing with itself, not a radio delivering bytes.

## What is proven, and by what

| Claim | Evidence |
|---|---|
| One invitation format for both transports; strict bounded parse | `PairingInvitationTest` (10), `QrPayloadTest` (9) |
| APDU wire contract, status handling, transaction invalidation | `NfcApduProtocolTest` (16) |
| Reader reassembly, cancellation, timeouts, one connection at a time | `NfcReaderTest` (10) |
| Presenter publication gating (foreground, expiry, AID) | `NfcPresenterTest` (9), `NfcManifestTest` (4) |
| Offer session ownership and cancellation | `OfferSessionTest` (7), `TransferCancellationTest` (8) |
| Share state machine: default NFC, method switch, generation evidence | `SharePairingViewModelTest` (11), `ShareViewModelTest` (21) |
| Receive state machine: default QR, single-flight attempt, one Save | `ReceivePairingViewModelTest` (14), `ReceiveViewModelTest` (16) |
| Selector semantics, 48 dp targets, 200 % text, RTL, recovery | `PairingAccessibilityTest` (8) |
| Method surfaces, one-action switch, no camera in NFC | `PairingModesUiTest` (5) |
| **End to end**: presenter → reader → `TransferService` → `BundleCodec` → saved route, including a golden transcript decoded independently, a cut at every chunk, offer replacement mid-read, expiry, background refusal, callback storm, stale stop, wrong key, malformed corpus | `NfcTransferIntegrationTest` (10) |
| No capability in any representation; mutation corpus cannot widen a field | `PairingSecurityTest` (5) |
| Export semantics and codec untouched by this feature | `ExportSelectionTest` (15), `BundleCodecTest` (18) |

The integration test's only fakes are the radio (a scripted transceiver that
drives the real presenter) and the network (an in-memory loopback). The bundle
that arrives is decoded by the production `BundleCodec` and saved by the
production repository.

## Residual risk — what this review did not establish

1. **No radio evidence (release-gating).** Nothing here shows that two Android
   phones exchange the APDU sequence over RF, that HCE delivery works with the
   screen unlocked, or that `IsoDep` timing holds on real silicon. Plan W21-05
   owns that, and it cannot be closed by any host test. Until then the feature
   must not be described as verified on hardware.
2. **No iOS review.** NFC is unsupported on iOS by design (all capabilities
   false, `Unsupported` failures, copy says so). The iOS half of the W21-03 gate
   could not even be compiled: `capture/visual/ParallaxProfiler.kt` (from
   `2d008dc`, untouched here) uses JVM-only `synchronized`/`sortedSetOf` in
   `commonMain`. That is a pre-existing break on main, not a finding about this
   feature, but it means "iOS still compiles" is currently unproven for every
   slice, not just this one.
3. **Lifecycle on hardware.** Pause/resume wiring is covered by state-machine
   tests and code read (`PairingLifecycle`, controller lifecycle in
   `NfcPairing.android.kt`), not by a device moving between apps, a locked
   screen, or a killed process mid-tap.
4. **Adapter toggling.** The disabled-adapter path is driven by a fake
   capabilities flow. The real `NfcAdapter` broadcast path and the system
   settings intent were not exercised on a phone.
5. **Two-phone interference.** Reader mode disables local card emulation on one
   phone; two phones both in Receive, or both in Share, were not tested on the
   radio.
6. **Relay stays unqualified.** `TransferService.peerRelayQualified` defaults to
   false because `worker/src/relay.ts` rejects a code requested by another
   install (R10-01). The action is hidden and no upload is attempted; the tests
   assert the invitation never gains `c=` through the UI. A future backend
   contract plus a two-install test is required before that action may appear.
7. **Copy location deviation.** The plan asked for `composeResources` strings.
   They are unreadable in this build (`DefaultAndroidResourceReader` has no
   Android context under Robolectric; the Gradle task that mirrors generated
   resources into `androidApp/.../assets` is unwired, as `EchoFonts.kt`
   documents). Pairing copy therefore lives in `ui/copy/PairingCopy.kt`, like
   every other screen's. No behaviour depends on this, but localisation of these
   strings now requires touching that file rather than a resource bundle.

## Hard gates

- Accessibility: selector is one `selectableGroup` with radio-role items,
  selected semantics, 48 dp minimum targets, stacked labels at 200 % text, and
  the selector renders above the camera gate. Status changes are polite live
  regions; decorative phone outlines carry no semantics.
- Security: invitation URIs are bearer capabilities. No representation
  (`QrPayload`, `PairingInvitation`, `NfcOfferSnapshot`, `NfcEvent.Invitation`)
  renders the token, key or URI; typed failures carry no detail; a malformed
  invitation never starts a fetch; an excluded asset never arrives.
- Privacy: the plain-file warning and perception opt-in are unchanged and stay
  off by default; relay cannot upload while unqualified.

## Before release

W21-05 must run `docs/testing/nfc-sharing.md` on at least two Android phones
(one with HCE, one as receiver), record pass/fail per step, and only then may the
tracker call this feature shipped. Any failure found there is a regression
against the acceptance table in plan 0005 §1, not a new feature request.
