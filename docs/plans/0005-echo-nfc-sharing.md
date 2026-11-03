# Plan 0005 — ECHO Tap: NFC + QR sharing

Status: **queued** · created 2026-11-03 · owning repository: **simt-echo**.
This is an implementation specification, not evidence that the feature exists. All performance numbers below are proposed acceptance targets, unmeasured on hardware. Every implementation slice starts Queued.

Authority: [implementation V2](../research/SIMT_ECHO_IMPLEMENTATION_PLAN_V2.md), [Cosmos freeze](../research/cosmos-echo-freeze.md), [Luminous freeze](../research/luminous-freeze.md), and [Plan 0004](0004-echo-parallax.md). This plan owns W20–W21, independently of the outstanding navigation slices. It changes sharing, not route capture/navigation or the bundle schema. No backend deployment, database migration, new account, or cloud service is required.

## 1. Product contract and scope

**Share opens in NFC mode; Receive opens in QR mode. Both expose an always-visible NFC / QR selector.** These are per-visit defaults, not remembered preferences. Choosing another method is explicit. Switching methods preserves the route, privacy selection, link expiry, and optional relay choice.

The complete shipping feature in this plan is **Android ↔ Android**. This follows the current repository's Android-only v1 decision: `DeviceServices.ios.kt` throws for LAN transfer and `QrScanner.ios.kt` never scans. Keep iOS compilation working and report NFC as unsupported there; do not describe QR or NFC transfer as functional on iOS. A future iOS delivery needs its own scanner/transport integration and entitlement/device evidence. Ordinary Core NFC reading does not establish general iPhone card-emulation availability; Apple's HCE and NFC/SE offerings have eligibility restrictions ([Apple HCE](https://developer.apple.com/support/hce-transactions-in-apps/), [Apple NFC/SE](https://developer.apple.com/support/nfc-se-platform/)).

NFC exchanges a small invitation containing the existing endpoint, token, AES key, fingerprint and name. The encrypted route then moves through the existing LAN transport. Phones need the same reachable Wi-Fi/hotspot for LAN; tapping does not create that network. They can separate after the invitation is read. Offline means no internet is needed on a working LAN, not that NFC carries the route. Preserve relay data compatibility, but gate its action as described below rather than promising a working cross-install fallback.

**Existing relay limitation:** `worker/src/relay.ts:getRelay` rejects a newly minted code when the requesting install differs from its owner (R10-01). Therefore today's relay is not a verified cross-phone fallback. This plan does not remove that authorization check. Distinguish configured relay from usable peer relay in the client; default peer-relay capability to false for this backend, hide the upload action, and explain “For now, use the same Wi-Fi, a hotspot, or a file.” A separately reviewed producer contract plus a two-install integration test must enable it later. LAN NFC/QR and file fallback constitute this feature's supported delivery paths; backend relay redesign is outside its acceptance scope.

Use foreground Android Host Card Emulation (HCE) on the sender and `IsoDep` reader mode on the receiver. Android documents this phone-to-phone arrangement ([HCE overview](https://developer.android.com/develop/connectivity/nfc/hce)). Android Beam is deprecated ([Android 10 behavior changes](https://developer.android.com/about/versions/10/behavior-changes-all#nfc-beam)). Avoid Beam, wallet roles, privileged APIs, Bluetooth discovery, Wi-Fi Direct negotiation, UWB, tag writing and an additional transfer protocol. The modern experience comes from a fast tap, lifecycle-safe state, and the existing measured transfer visualization, without another networking stack.

### Required behavior

| Context | Initial mode / behavior | User recovery |
|---|---|---|
| Share, NFC + HCE available and enabled | NFC selected; prepare offer, then show Ready to tap | QR switches immediately using the same offer |
| Share, NFC disabled | NFC selected with “Turn on NFC to tap” | Open NFC settings or choose QR; no silent switch |
| Share, NFC/HCE absent | NFC selected with “This phone can share by QR” | Prominent “Use QR instead”; QR/file remain usable |
| Receive, any Android hardware | QR selected; existing camera permission gate | NFC selector and Import file remain outside the camera gate |
| Receive, user selects NFC | Stop scanner, then arm reader if NFC available | No camera permission required; NFC settings / QR / file recovery |
| Receive, NFC present but HCE absent | NFC receive is still supported | Only sending requires HCE |
| Re-enter either destination | Restore its specified default | Never carry a previous visit's choice into the new visit |
| QR sender + NFC receiver, or reverse | Both screens explain the matching modes | “Ask the other person to choose the same method” |

## 2. Experience: a route handed from one phone to another

1. **Sender:** open Share. Above the main panel, a full-width segmented selector reads `NFC · Tap phones` / `QR · Show code`. NFC is selected. The existing route sigil sits between two understated phone outlines, with “Ready to tap” only after the invitation is actually published. Copy: “Ask the other person to open Receive → NFC. Hold the backs of your unlocked phones close together.” Keep “What others receive” and its export controls directly below.
2. **Receiver:** open Receive. QR is selected, scanner and reticle work as today. The selector is visible before any camera permission control. Choosing `NFC · Tap phones` disposes the camera and replaces its surface with two phone outlines and “Ready to receive a tap”. Copy: “Ask the other person to open Share → NFC. Move the backs of the phones slowly until they connect.” Do not claim every antenna is at the top.
3. **Contact:** a short, restrained pulse accompanies a real reader connection. “Hold still…” while the invitation chunks arrive. The sender can say “Tap details shared”; it must not say the route was received. The receiver's completion haptic fires only after the full invitation validates, once per attempt. “Connected — you can separate the phones” leads into actual byte progress.
4. **Transfer:** reuse `TransferParticles` and `ProgressRateTracker`; no synthetic percentage or fake peer signal. An NFC read does not trigger transfer-complete animation. Sender completion means “Route downloaded” when `served` fires, not “Saved on the other phone”. The current protocol has no receiver-save acknowledgement, especially through the relay; add none in this plan.
5. **Preview:** the existing verified import canopy unfolds the route sigil, creator, distance, landmarks and expiry. Explicit **Save route** is still required. No tap auto-saves, overwrites a stored route, starts navigation or launches an arbitrary URI. Existing replacement behavior occurs only after Save.
6. **Fallback:** QR is a first-class sibling with the current high-contrast QR and logo. “Use QR instead” selects it in one action. `Share as file` / `Import file` stay secondary actions below the panel, preserving plaintext warnings and perception opt-in. Relay is a network option, not a third pairing tab; it stays unavailable for the current owner-only backend. If a future qualified backend enables it, explicit opt-in regenerates the invitation in whichever mode is selected.

Layout intent (selected labels marked with `*`; outlines/sigil are drawn with existing Compose primitives):

```text
SHARE                               RECEIVE
Share route                  Close  Receive route                Close
[ NFC · Tap phones* | QR ]          [ QR · Scan code* | NFC ]
┌──────────────────────────────┐    ┌──────────────────────────────┐
│  phone    route sigil  phone │    │       live QR reticle        │
│         Ready to tap         │    │                              │
│ Ask them: Receive → NFC      │    │ Scan the other phone's code  │
└──────────────────────────────┘    └──────────────────────────────┘
Same Wi-Fi or hotspot needed.       Same Wi-Fi or hotspot needed.
What others receive       Details  Choose NFC to receive by tap.
Use QR instead                     Import file
Share as file
```

In Receive/NFC, the tap panel occupies the scanner's place; the selector and file action stay in place. Reuse the same panel footprint on method changes to avoid a large layout jump, but allow height to grow with text. On a valid invitation, replace acquisition with progress, then the existing import canopy; retain the selected method indicator above it. This is one destination per role, without an extra setup screen.

### Visual and accessibility specification

- Reuse `CanopyPanel`, Cosmos buttons, spacing, typography, `RouteSigil`, `ShareQrCode`, and motion tiers. Share uses the frozen rose channel; Receive can use cyan for real active sensing; success colors appear only on measured success. Avoid a new visual language or library.
- The NFC panel has a soft, slow outline echo only while armed in FULL motion. REDUCED renders a static outline; OFF removes motion. Connection and completion are finite events. Do not imply distance, direction, remote presence or signal strength before a reader event. No radar or continuous vibration.
- Make the selector a single accessible selectable group with two labeled items, selected semantics, stable focus, and minimum **48 × 48 dp** targets. At large font scale, stack the two options rather than truncating their names. No swipe-only switching. Unsupported selection remains explainable and keyboard/TalkBack reachable.
- All new and touched sharing copy belongs in Compose resources (`shared/src/commonMain/composeResources/values/strings.xml`); platform service descriptions belong in Android resources. Supply descriptions for meaningful illustrations; decorative outlines explicitly use `contentDescription = null`. Announce Ready, reading, error, preview once, never byte ticks. Focus moves to the preview heading when it opens and to the relevant recovery action after failure.
- Support 320 dp width, 200% font, RTL, TalkBack, high contrast and system motion settings. Scroll without fixed-height text containers. QR remains at the frozen minimum when space permits and scrolls rather than clipping. Keep the selector available when camera permission is denied.
- A partial tap says “Tap interrupted. Hold the phones together and try again.” Wrong tag says “This isn't an ECHO share.” Timeout says “No tap yet. Keep both phones unlocked, or use QR.” Network failure after a valid tap says “Tap connected, but the route couldn't transfer. Join the same Wi-Fi or hotspot and try again.” Authentication/integrity failure says “Couldn't verify this route”; never turn it into network advice.

## 3. Verified starting points and ownership

Code-read on 2026-11-03; no device run was performed for this plan. Paths below are relative to the repository root. Abbreviations: **C** = `shared/src/commonMain/kotlin/app/simt/echo`, **A** = `shared/src/androidMain/kotlin/app/simt/echo`, **I** = corresponding `iosMain`; **CT** = corresponding `commonTest`, **HT** = corresponding `androidHostTest`. Listed new files are proposed deliverables, not existing commands/tests.

| Existing seam | Current behavior | Planned change owner |
|---|---|---|
| `C/transfer/QrPayload.kt` | v1 `simt-echo://r` invitation; token 16 B, key 32 B; permissive query parsing | W20-02 adds shared strict validation without changing emitted v1 fields |
| `C/transfer/TransferService.kt` | seal → offer → `OfferInfo.qrUri`; fetch → verify → preview → explicit save | W20-03 adds owned offer session lifecycle and cancellation preservation |
| `A/platform/KtorLanTransport.kt` | HTTP sealed bytes; stop 3 s after download / 10 min after offer; unscoped delayed stops | W20-03 binds jobs and stops to their own offer generation |
| `C/ui/screens/share/ShareViewModel.kt` | init creates offer; re-offers on export/relay changes; screen remembers VM | W21-01 owns method state, sequential re-offer and screen exit cleanup |
| `C/ui/screens/receive/ReceiveViewModel.kt` | `onScanned` → `fetchPreview`; URI dedupe; file import; explicit Save | W21-02 unifies QR/NFC ingestion and cancels stale receive attempts |
| `C/ui/screens/{share,receive}/*Screen.kt` | QR-only main panel; Receive camera gate; existing canopy preview | W21-03 owns visual integration and touched string migration |
| `C/ui/EchoNavHost.kt`, `C/AppGraph.kt` | graph supplies shared transfer service; screens use manual `remember` VMs | W20-04 adds platform controller factory; W21-03 wires route-scoped ownership |
| `androidApp/src/main/AndroidManifest.xml` | no NFC permission/features/service | W20-04 adds optional NFC declarations and protected HCE component |
| `I/platform/{DeviceServices,QrScanner}.ios.kt` | explicit Android-only transport/scanner stubs | W20-01 adds truthful unsupported NFC implementation; compile gate remains |

Current hazards inside this feature's blast radius: re-offer coroutines can race; delayed transport shutdown can stop a newer offer; cancelling a fetch is caught as an ordinary `Exception`, including by relay fallback; remembered VMs need explicit disposal. Close these before exposing a second invitation source. Do not refactor navigation, capture, worker APIs, or unrelated dirty files.

## 4. Contracts to implement before screen integration

### 4.1 Shared domain and platform boundary

- Add `C/transfer/PairingMethod.kt`: enum `NFC, QR`, and `PairingSource` for event attribution only. File actions remain separate. Add sealed `PairingFailure` with unsupported/disabled, interrupted, timeout, invalidInvitation, offerExpired, network, verification and internal cases; UI maps these to resource copy, never exception text or URI contents.
- Add `C/platform/NfcPairing.kt`: `NfcCapabilities(canRead, canPresent, enabled)` as `StateFlow`; `NfcEvent` carries attempt/offer generation and typed events. `NfcPairingController` exposes publish, startReading, stopReading, clearPublishedOffer and close. Commands are idempotent; only one reader job and one publication can exist. No Android Activity type crosses into common code.
- Proposed common calls: `publish(invitation: String, offerGeneration: Long)`, `startReading(attemptId: Long)`, `stopReading(attemptId: Long)`, `clearPublishedOffer(offerGeneration: Long)`, `close()`. Events: `Reading(attemptId)`, `Invitation(attemptId, uri)`, `ReadFailed(attemptId, failure)`, `InvitationRead(offerGeneration)`. Add an explicit publication-ready state/event so Ready never means merely requested. Stopping an old generation cannot clear a newer one.
- Use an `expect @Composable rememberNfcPairingController()` with Android/iOS actuals, matching existing platform factories. Android implementation owns the resumed Activity via composition/lifecycle; underlying service reads only a process-scoped registry. iOS actual has all capabilities false and typed Unsupported behavior. Inject fakes into VMs and UI tests. Start native acquisition only after its composable is mounted/resumed; stop the old method before starting the new one.
- Resource ownership is route-scoped: effects own controller activation and call VM `stop()` plus controller `close()` on destination disposal; retained navigation entries must also deactivate on pause. Do not rely solely on `onCleared()` for manually remembered VMs. Do not put Activities or composables in `AppGraph`.

### 4.2 One invitation, two representations

Keep `QrPayload` and `OfferInfo.qrUri` source compatible; NFC carries that exact UTF-8 v1 URI. Do not invent a second token/key or change `EchoSchemaVersion`. Add `C/transfer/PairingInvitation.kt` as the shared bounded validation entry used before either QR or NFC starts networking; it delegates field decoding to a hardened `QrPayload.parse`. Call it inside `TransferService.fetchPreview` too so callers cannot bypass validation.

Limit invitation UTF-8 length to **1,024 bytes** and recognize the existing v1 fields. Reject duplicate known keys, fragments, malformed escapes/UTF-8, unsupported versions, invalid ports, invalid key/token lengths and oversized names/relay codes. Decode names once; bound the decoded name to the existing 40-character contract, retaining valid Unicode. Keep valid legacy v1 samples, including legitimate non-private IPv4 LAN/hotspot addresses. Do not rely on the old comment that every QR is ≤200 characters: percent-encoded Unicode can exceed it. Enforce the byte bound on generation too; failure must offer file recovery, not silently truncate a URI.

Do not fetch loopback, unspecified, broadcast or multicast addresses. The LAN client uses only the fixed `/echo/<token>` path and supplied validated IPv4/port, with redirects disabled. Preserve `c` parsing as exactly eight characters from `ABCDEFGHJKMNPQRSTUVWXYZ23456789`, matching `worker/src/relay.ts`; peer-relay fetch/upload also requires the explicit qualified capability. The relay stays on its configured origin. Do not broaden relay fallback on parse, cancellation, decrypt, fingerprint or expiry failure. V1 unknown query fields may be ignored for legacy compatibility only within the total byte bound; duplicate recognized fields always fail. URI, key and token must be redacted from object formatting, exceptions, analytics and logs.

### 4.3 Minimal Android NFC protocol: ECHO Tap v1

Use a private application APDU exchange, not a Type 4 NDEF filesystem. It is intended for ECHO's foreground reader, not arbitrary tag-reader apps or OS URI dispatch. Both components implement this fixed contract; no payment AIDs or NFC intent/deep-link filters are registered. Protocol values below are ECHO design choices, not Android-specified values.

| Item | Exact wire contract |
|---|---|
| Application ID | `F04543484F01`, locally chosen proprietary AID; six bytes, `category="other"`; do not claim globally registered uniqueness |
| SELECT request | `00 A4 04 00 06 F0 45 43 48 4F 01 00` |
| SELECT response | ASCII `ECHO` (4 B), version `01` (1 B), random offer ID (8 B), unsigned big-endian invitation length (2 B), status `90 00` |
| READ request | `00 B0 offsetHi offsetLo Le`; unsigned big-endian offset; `Le` 1…224; no extended APDUs or `Le=0` |
| READ response | Exactly `Le` invitation bytes plus `90 00`; receiver requests `min(224, remaining)` |
| Bounds | Total invitation 1…1,024 B; all responses checked for status and exact length before allocation/copy; no partial invitation emitted |
| Failures | Wrong AID `6A82`; no active/expired/changed offer `6985`; malformed length `6700`; bad offset/count `6B00`; unsupported INS `6D00`; unsupported CLA `6E00` |

Create `C/transfer/NfcApduProtocol.kt` for pure parser/encoder and transcript tests. SELECT pins the current immutable snapshot's generation for the RF transaction. Each READ checks that the registry still holds that generation and is armed/unexpired. A changed offer invalidates the transaction; it cannot concatenate bytes from two invitations. Deactivation clears selected state and contiguous-read tracking, but does **not** cancel the LAN transfer. A fresh SELECT can restart the same offer after field loss.

Require sequential READ offsets, allowing an exact repeat of the immediately preceding request for retry. Emit `InvitationRead` once when the entire contiguous invitation has been returned, not on tag discovery or SELECT. That is only an observation that details were supplied; it does not authenticate a person or prove successful receive. The reader validates the complete UTF-8 invitation before emitting `Invitation` and starting any network work.

### 4.4 Android wiring and lifecycle

- Add normal `android.permission.NFC`; declare both `android.hardware.nfc` and `android.hardware.nfc.hce` with `required="false"`. Keep installation and QR/file use available without NFC. No runtime NFC permission dialog. `canPresent` requires NFC + HCE; `canRead` requires NFC only. Recheck on resume and adapter state changes; unregister listeners on close.
- Add `A/platform/nfc/EchoHostApduService.kt` and Android app XML metadata. Service must be `exported="true"` for the OS, protected by `android.permission.BIND_NFC_SERVICE`, with the HCE service action/metadata and `requireDeviceUnlock="true"`. Store only a bounded immutable snapshot in `ActiveNfcOfferRegistry`, using atomic publication/invalidation and a monotonic expiry. Cold service/process restart has no snapshot and returns `6985`. No disk persistence, bundle assembly, network calls or blocking work in the APDU callback ([HostApduService reference](https://developer.android.com/reference/android/nfc/cardemulation/HostApduService)).
- Present only while Share is resumed, NFC selected, offer valid, unlocked and screen on. Clear the registry before leaving that state; independently check interactive/unlocked state before every response. Select/unset the foreground service on resume/pause; check the returned registration result and expose a recoverable unavailable state if selection fails. Never request the default wallet role ([CardEmulation reference](https://developer.android.com/reference/android/nfc/cardemulation/CardEmulation)).
- Reader activation uses `FLAG_READER_NFC_A | FLAG_READER_SKIP_NDEF_CHECK`, optionally `FLAG_READER_NO_PLATFORM_SOUNDS` when ECHO supplies accessible haptic feedback. Reader mode disables local card emulation, so never arm both roles on one phone. Only arm after Receive's QR scanner is disposed ([NfcAdapter reference](https://developer.android.com/reference/android/nfc/NfcAdapter)).
- `IsoDep.connect/transceive` run on IO, never the UI thread. Set per-command timeout to 1,500 ms and bound one exchange to 5 s; close the active `IsoDep` on cancel/timeout from another thread to interrupt blocking I/O, plus finally-close. Coroutine timeout alone is insufficient. Suppress parallel callbacks with an attempt-scoped mutex/atomic gate. Test cleanup on every exit ([IsoDep reference](https://developer.android.com/reference/android/nfc/tech/IsoDep)).
- At 20 s without a completed invitation, stop polling and show “Try tap again” plus QR recovery. No automatic repeated timeout loop. Field loss is retryable in the same selected method. Pausing, settings, lock, close or switching away stops polling immediately. Returning to Receive/NFC from Settings may re-arm once if enabled; leaving the destination always resets the next visit to QR.

### 4.5 Session, cancellation and privacy invariants

An active offer has a generation, exact invitation, immutable selected export, and a **10-minute maximum lifetime** matching today's transport window. Pairing-method switches reuse this offer and do not reseal, rotate keys, upload again or restart byte progress. Exiting Share, expiry, download completion, or changing export/relay settings unpublishes NFC before invalidating/replacing its offer. A re-offer uses a new generation and fresh existing seal/token behavior; no stale QR or NFC publication is shown while preparing it.

Serialize all offer mutations under one owner. Invalidate UI/registry synchronously, cancel/join prior preparation, then stop the old offer and create the replacement. Guard every completion/progress/served event by its session generation; a late result cannot republish or report success. Bind transport 3-second and 10-minute stop jobs to the server instance they created and cancel them on replacement. A failed `offer` must close any partially started server. Ignore stale stop calls. Keep heavy bundle preparation off Main; retain existing cryptography and export selection semantics.

Receive owns one attempt job spanning NFC read/QR ingestion → parse → fetch → preview. An accepted invitation stops both scanners. During fetching and preview, lock method switching with an explanation; offer a distinct Cancel/Discard action to return to the selected mode. Cancel increments the attempt generation before cancelling/closing work and discards preview/progress/dedupe; late callbacks are ignored. `rescan()` becomes method-aware retry. Save is single-flight and cannot be duplicated by repeated taps. Always rethrow `CancellationException`, including `TransferService.fetchSealed`, before mapping errors or considering relay fallback.

Treat the invitation as a bearer capability. NFC is not an authenticated/encrypted channel or a proof of proximity/identity; a nearby reader may copy it, as a camera can copy QR. Preserve explicit export disclosure and Save. Clear in-memory references promptly, avoid promising perfect JVM memory erasure, and never persist invitations in saved state/clipboard. The sigil is a recognizable route graphic, not a security code. Relay ciphertext may remain until existing server TTL after Share closes; this plan does not promise remote revocation or single-use across relay and LAN. Document that residual risk, and keep relay opt-in.

## 5. Slice tracker

Use `simt slice start <ID>` from this tracker before implementation, `simt why <file>` before edits, and the repository's slice finish / tracker completion procedure. Add the actual commit SHA, exact passing commands/test counts and device evidence to Evidence when a row completes. Queued commands below are requirements, never claimed results. One slice/worktree at a time is sufficient; dependencies do not authorize parallel edits to shared files.

| ID | State | Independently reviewable slice | Depends on | Owner / completion evidence |
|---|---|---|---|---|
| W20-01 | Queued | Pairing modes, capabilities, failures and platform seam | — | simt-echo · — |
| W20-02 | Queued | Bounded shared invitation validation and NFC wire protocol | W20-01 | simt-echo · — |
| W20-03 | Queued | Generation-safe transfer sessions and cancellation | W20-02 | simt-echo · — |
| W20-04 | Complete | Android foreground NFC presenter, registry and manifest | W20-01, W20-02, W20-03 | simt-echo · — |
| W20-05 | Complete | Android NFC reader with bounded cancellable exchange | W20-04 | simt-echo · — |
| W21-01 | Complete | Share state machine, NFC default and mode switching | W20-03, W20-04 | simt-echo · — |
| W21-02 | Complete | Receive state machine, QR default and unified import | W20-03, W20-05 | simt-echo · — |
| W21-03 | Complete | Complete Cosmos UI, resources, accessibility and navigation wiring | W21-01, W21-02 | simt-echo · — |
| W21-04 | Complete | End-to-end compatibility, security and adversarial regressions | W21-03 | simt-echo · — |
| W21-05 | Queued | Real-device qualification and feature release evidence | W21-04 | simt-echo · — |

### W20-01 — freeze a testable platform seam

**Files:** new `C/transfer/PairingMethod.kt`, `PairingFailure.kt`, `C/platform/NfcPairing.kt`, Android/iOS actual factory files, new `CT/transfer/PairingPolicyTest.kt`. Keep any initial Android adapter explicitly unavailable until W20-04/05; no fake Ready behavior. No feature UI/default changes ship from this slice.

**Implement:** define §4.1 types and factory; centralize default policy (`Share=NFC`, `Receive=QR`) and role-specific capability mapping. Readiness must distinguish unavailable, disabled, preparing and armed. Provide a deterministic fake controller in tests with explicit generations and externally controlled callbacks. Keep payloads out of generated diagnostic representations.

**Accept/tests:** verify defaults on every new session, NFC read without HCE, disabled adapter, no adapter, unsupported iOS policy, idempotent teardown and old-generation clears. Run `./gradlew :shared:testAndroidHostTest --tests app.simt.echo.transfer.PairingPolicyTest :shared:compileKotlinIosArm64 :shared:compileKotlinIosSimulatorArm64`.

### W20-02 — validate the invitation and freeze the APDU transcript

**Files:** `C/transfer/QrPayload.kt`, new `PairingInvitation.kt`, `NfcApduProtocol.kt`; extend `CT/transfer/QrPayloadTest.kt`; add `PairingInvitationTest.kt` and `NfcApduProtocolTest.kt`. Existing wire-format output remains v1; no model or worker changes.

**Implement:** §4.2 validation and §4.3 protocol as pure common code with immutable snapshots supplied by the caller. Golden byte fixtures cover SELECT and a full multi-chunk exchange. Protocol state pins generation, tracks contiguous bytes and permits last-chunk retransmission. Add safe formatting for capability-bearing structures. Reuse the exact relay-code bounds in §4.2; no new code format.

**Accept/tests:** round-trip Unicode/Arabic/emoji names and valid legacy QR; 1,024 B boundary and 1,025 B rejection; duplicate fields; malformed percent/UTF-8; bad endpoint/key/token; trailing/short status bytes; unknown command/AID/version; READ before SELECT; offset overflow; zero/oversize Le; truncated invitation; generation change between chunks; field-loss restart; duplicate final READ emits one observation. Run `./gradlew :shared:testAndroidHostTest --tests app.simt.echo.transfer.QrPayloadTest --tests app.simt.echo.transfer.PairingInvitationTest --tests app.simt.echo.transfer.NfcApduProtocolTest`.

### W20-03 — make existing transfer ownership safe

**Files:** `C/transfer/TransferService.kt`, new `OfferSession.kt` (or equivalent small session owner), `C/platform/PeerTransport.kt` only if generation attribution needs additive events, `A/platform/KtorLanTransport.kt`; new `CT/transfer/OfferSessionTest.kt`, `TransferCancellationTest.kt`, new `HT/platform/KtorLanSessionTest.kt`; extend existing `TransferServiceTest.kt` only for changed contracts.

**Implement:** §4.5 session ownership, bounded lifetime and generation-tagged progress/completion; retain the old service API until VM consumers migrate. Do not tag global late events with the current generation at collection time: carry their originating offer identity from the transport. Offer failure must stop a server started before address resolution/relay failure. Close/cancel the previous lifetime jobs before replacement. Preserve cancellation across LAN/relay and disable LAN redirects. Apply shared invitation validation before networking. Add explicit peer-relay capability, false for the current backend, gating both upload and fallback at service level; test qualified behavior with injected capability only. Ensure file and privacy behavior stays compatible.

**Accept/tests:** deterministic barriers force two re-offers to complete backwards; the latest selection alone remains served. An old timeout/served callback/stop cannot stop or complete a new server. Test no-Wi-Fi preparation cleanup, cancellation during relay upload, cancel fetch causes zero relay downloads, invalid URI causes zero HTTP calls, integrity failure causes no fallback, lifetime expiry and repeated stop. Run `./gradlew :shared:testAndroidHostTest --tests app.simt.echo.transfer.OfferSessionTest --tests app.simt.echo.transfer.TransferCancellationTest --tests app.simt.echo.transfer.TransferServiceTest --tests app.simt.echo.platform.KtorLanSessionTest`. Prefer injected clocks/jobs over real 10-minute waits.

### W20-04 — present a foreground NFC offer

**Files:** new `A/platform/nfc/{EchoHostApduService,ActiveNfcOfferRegistry,AndroidNfcPairingController}.kt`, Android actual factory, app manifest, `androidApp/src/main/res/xml/echo_apdu_service.xml`, Android service-description resources; new `HT/platform/nfc/NfcPresenterTest.kt` and `NfcManifestTest.kt`.

**Implement:** §4.4 presenter and hardware detection. Service reads the same process registry written by the screen's controller; do not add a separate service process. Every APDU verifies publication, generation, monotonic expiry, screen/lock state. Registry updates are atomic and copied byte arrays cannot be mutated by callers. Lifecycle clear occurs before UI hides or replacement prepares. A standalone service invocation never creates an offer. Retain QR/file support on devices without either NFC feature.

**Accept/tests:** read only while foreground NFC Share is armed; reject background, lock, expiry, QR mode, changed selection, process cold-start and service recreation with no publication. Verify manifest optional features, permission protection, exact AID and non-payment category. Registration failure never renders Ready. Run `./gradlew :shared:testAndroidHostTest --tests app.simt.echo.platform.nfc.NfcPresenterTest --tests app.simt.echo.platform.nfc.NfcManifestTest :androidApp:assembleDebug`. Host tests prove policy/configuration; RF routing remains W21-05 evidence.

### W20-05 — read a complete invitation without blocking the UI

**Files:** new `A/platform/nfc/NfcInvitationReader.kt`, finish Android controller read side; new `HT/platform/nfc/NfcReaderTest.kt`. Use an injected `IsoDep` connection wrapper for host tests; production wrapper invokes the actual Android API.

**Implement:** reader flags, IO dispatch, chunk loop, timeouts, dedupe and cancellation in §4.4. Validate SELECT header/length/status before buffer allocation, then validate assembled URI. Always close on success/error/pause/cancel and reset selected protocol state on reconnect. Wrong tags have no network or OS launch side effects. Feed typed events only, with originating attempt ID; reader code never invokes `TransferService` itself.

**Accept/tests:** one invitation after complete read; chunked Unicode across byte boundaries; unknown AID/tag; field loss at each command; short/error/oversized responses; callback storm produces one connection; cancel closes blocked transceive; 5 s timeout cannot leak IO; stale invitation event after switch is ignored; 20 s idle stops polling. Run `./gradlew :shared:testAndroidHostTest --tests app.simt.echo.platform.nfc.NfcReaderTest`. No simulator/host test counts as a successful phone tap.

### W21-01 — Share behavior independent of the selected method

**Files:** `C/ui/screens/share/ShareViewModel.kt`; new `CT/ui/screens/share/SharePairingViewModelTest.kt`. Extend `ShareUiState` with selected method, role availability, publication phase and generation while preserving existing export/file controls. Defer visual changes to W21-03.

**Implement:** initialize NFC policy, create one offer, publish only while resumed/selected. Method changes only attach/detach presentation; they never call `service.offer` again. Export/expiry/relay changes use the serialized session path and invalidate both renderings first. Ready requires confirmed publication. NFC read only changes tap-detail status; completion/haptic requires own-session transfer evidence. Stop cancels producer/collector jobs as well as the transport; stop must remain reusable for pause/resume if the destination survives. Closing ends the session permanently.

**Accept/tests:** default NFC, unsupported/disabled NFC leaves explicit recovery, switch NFC→QR→NFC preserves token/key/offer count and export selection; privacy change during a multi-chunk tap rejects old transaction; relay update publishes one replacement; late events after disposal cannot republish; duplicate served emits one completion haptic; relay without delivery evidence stays honest. Run `./gradlew :shared:testAndroidHostTest --tests app.simt.echo.ui.screens.share.SharePairingViewModelTest --tests app.simt.echo.ui.screens.share.ShareViewModelTest`.

### W21-02 — Receive through either source, with one explicit Save

**Files:** `C/ui/screens/receive/ReceiveViewModel.kt`; new `CT/ui/screens/receive/ReceivePairingViewModelTest.kt`. Keep `onScanned` as a compatibility adapter to `onInvitation(uri, source, attemptId)` rather than copying the fetch/save path.

**Implement:** new visit starts QR; method switch cancels and disposes old acquisition before activating the next one. Route QR/NFC through bounded parser and a single-flight attempt; no fetch from an obsolete generation. Preserve invalid/newer-schema/expired-route preview handling and typed failures. Cancel/Discard and method-aware retry reset dedupe and progress. NFC mode never depends on camera permission. Import file suspends acquisition and returns to the selected method on picker cancellation; confirmed Save remains the only persistence point.

**Accept/tests:** QR default on repeated visits; NFC selection with camera denied; simultaneous NFC/QR/stale callbacks yield exactly one fetch; cancel after partial read and during fetch cannot resurrect preview; retry in the same method succeeds; invalid/integrity/network cases use distinct copy keys; new schema/expiry fail before save; duplicate Save calls persist once; cancelled picker re-arms selected mode. Run `./gradlew :shared:testAndroidHostTest --tests app.simt.echo.ui.screens.receive.ReceivePairingViewModelTest --tests app.simt.echo.ui.screens.receive.ReceiveViewModelTest`.

### W21-03 — ship the complete interaction and accessible visuals

**Files:** share/receive screens, new `C/ui/components/PairingMethodSelector.kt`, `NfcTapPanel.kt`, Compose string resources, `C/ui/EchoNavHost.kt`; platform lifecycle wiring from W20-04/05 only as needed. Extract export-preview UI from the existing 443-line Share screen into `ShareExportSection.kt` while touching it; avoid adding more unrelated responsibilities. New `HT/ui/screens/PairingModesUiTest.kt`, `PairingAccessibilityTest.kt`; extend QR and disclosure regressions.

**Implement:** all of §2 and the behavior matrix. Inject route-scoped controllers into both screens; dispose owned jobs on navigation and deactivate on pause. The QR camera permission gate wraps only QR capture. During fetch/preview, the disabled selector explains why; Cancel/Discard restores interaction. NFC Settings is an explicit user action using the system NFC settings screen, with general wireless settings fallback where needed; never toggle the adapter programmatically. Show network guidance in both modes, including after a valid tap; hide unqualified relay actions and assert that no upload occurs. Move touched hardcoded sharing strings into resources; do not migrate unrelated screens.

**Accept/tests:** stable tags `PairingMethodNfc`, `PairingMethodQr`, `NfcTapPanel`, `PairingRecovery`, existing QR/preview tags retained. Assert semantic selection, minimum targets, 200% text/RTL layout, focus/announcement behavior, no camera mount/request in NFC, scanner disposal before reader start, disabled/unsupported recovery, file warning/perception defaults, and motion tiers FULL/REDUCED/OFF. QR decode test still reads the rendered code, including a long Unicode name. Run `./gradlew :shared:testAndroidHostTest --tests app.simt.echo.ui.screens.PairingModesUiTest --tests app.simt.echo.ui.screens.PairingAccessibilityTest --tests app.simt.echo.ui.screens.share.ShareQrScanTest --tests app.simt.echo.ui.screens.share.SharePerceptionDisclosureUiTest --tests app.simt.echo.ui.screens.ProcessingShareReceiveCosmosTest :shared:compileKotlinIosArm64 :shared:compileKotlinIosSimulatorArm64 :androidApp:assembleDebug`.

### W21-04 — prove compatibility and adversarial boundaries

**Files:** new `HT/transfer/NfcTransferIntegrationTest.kt`, `CT/transfer/PairingSecurityTest.kt`, extend source-specific regression files rather than building a giant test fixture. New `docs/reviews/0011-nfc-sharing-readiness.md` only if that number is still free; otherwise take the next available review number.

**Implement/test:** drive presenter protocol → reader → real `TransferService`/`BundleCodec` with fake network and in-memory storage, asserting imported route/assets match the selected export exactly. Independently decode a golden APDU transcript to avoid merely testing encoder against itself. Keep real radio evidence separate. Audit every new log/error/state representation for secret-bearing URIs. New NFC→NFC, new QR→new QR, old valid v1 QR→new receiver and new QR→legacy parser must work. Legacy clients do not gain NFC support.

**Accept:** deterministic interruption at every chunk, mode switch while preparing, selection change during SELECT/READ/fetch, adapter off/on, expiry, foreground loss, malformed byte corpus, duplicate events, stale stop timer, wrong AES key/fingerprint, consent-less relay attempt and URI redirect attack. Check privacy-risk assets remain excluded and `.echo` perception opt-in stays off. Run `./gradlew :shared:testAndroidHostTest --tests app.simt.echo.transfer.NfcTransferIntegrationTest --tests app.simt.echo.transfer.PairingSecurityTest --tests app.simt.echo.transfer.ExportSelectionTest --tests app.simt.echo.bundle.BundleCodecTest`. Inspect XML reports: every named class must exist with a nonzero count and no unexplained skips. Return BLOCK for any unrun required case, not approval based on a passing subset.

### W21-05 — qualify actual phones and close the tracker

**Files:** new `docs/testing/nfc-sharing.md` containing reproducible steps and actual results; readiness review, this tracker, and any narrowly scoped regression fixes. Do not change release version or publish an app as part of this plan.

**Device matrix:** two Android OEM families with NFC/HCE, testing both send/receive directions; include Android 10/API 29 and a current supported Android build across the matrix. A no-NFC device/emulator proves QR/file recovery. Include a device with NFC reader capability but mocked/absent HCE for role policy; do not claim hardware compatibility from mocking. Test a current wallet installed/default, screen lock and Secure NFC settings without asking the user to change wallet defaults. Record exact model, OS build, app SHA, setting, direction and outcome; device/OS rows not exercised remain explicit blockers for the declared support claim.

**Manual script:** fresh launch defaults → QR success → NFC success → preview/save → compare route/assets; switch methods both ways before tap; camera denied; NFC disabled/settings return; foreground/background/lock; weak antenna placement/case; field loss at first/middle/final chunk; duplicate tap; app process killed and service recreated; same Wi-Fi and hotspot; AP isolation failure; unqualified relay action absent and no upload attempted; no-internet LAN; old v1 QR; `.echo` privacy path. Confirm no unsolicited browser/Wallet launch, no background key disclosure, no accidental save and no camera indicator during NFC acquisition. Two simultaneous senders/receivers get guidance rather than a fictional connection. Cross-install relay success is not claimed; later enabling it requires separate producer/consumer evidence.

**Targets:** for each supported OEM direction collect 20 deliberate aligned taps: at least 19 complete invitations within 2 s of first reader contact, and every failure recovers through retry or QR. Measure invitation latency separately from network time. After invitation completion the phones separate and the route transfer continues. No main-thread NFC I/O; polling/publication ends on pause/exit, with instrumentation showing no callbacks accepted after deactivation. These are proposed product gates, not repository-wide coverage percentages.

**Release checks:** run `./gradlew :shared:testAndroidHostTest :shared:compileKotlinIosArm64 :shared:compileKotlinIosSimulatorArm64 :androidApp:assembleDebug` once as this feature's named cross-system/phase-exit gate. Record counts, failures, skips and artifacts, plus the manual matrix and UI captures for light/dark, large text and reduced motion. Review accessibility, correctness, privacy and security as hard gates. Mark this row Complete only with passing required evidence, actual SHAs in all predecessor rows, and no unresolved critical cases. A host-only run cannot close this row. No push/deployment is authorized.

## 6. Agent execution and validation notes

- Apply the tracker dependencies serially. Before starting a slice, inspect live status/worktrees and preserve unrelated in-progress changes. `simt slice finish <ID>` runs declared gates/commit; record the resulting SHA with `simt plan done <ID> <sha>`. If tooling cannot perform the step, report the concrete blocker and preserve the work; never manufacture completion evidence or bypass hooks/signing.
- The actual shared host task is `:shared:testAndroidHostTest`, declared by `withHostTest {}` in `shared/build.gradle.kts`; do not substitute generic `:shared:testDebugUnitTest`. New selectors above become runnable only after their test files exist. Confirm each selector in `shared/build/test-results/testAndroidHostTest/TEST-*.xml`. Do not repeatedly run full unrelated suites per slice.
- Planning-time runner discovery: `./gradlew --no-configuration-cache :shared:tasks --all --console=plain` succeeded and listed shared host tests and both iOS compilation tasks. Combined `:shared:tasks --all :androidApp:tasks --all` failed creating `generateDebugComposePreviewRunfiles` because Compose Preview requires Android unit-test resources. This is not a feature test failure or a verified blocker for `assembleDebug`; verify the exact required build tasks when implementing rather than weakening resource checks to list tasks.
- Planning-time `simt check --json` failed on existing generated-rule/version drift, missing CI configuration and pre-existing file-length violations outside this new document. Those findings were left untouched. No feature tests, Android build or real-device NFC run were executed while writing this plan. Implementation agents must distinguish these baseline/tooling findings from failures introduced by their slices.
- No behavioral delta is needed for this planning-only document. Validate Markdown whitespace, relative links, unique tracker IDs, matching slice sections/dependencies and the runner references. Do not infer implemented functionality from a plan or mark unrun device gates passed.

## 7. Scope boundaries and retained risks

No general iPhone sending/receiving claim, background sharing, NFC-only bulk transfer, automatic hotspot setup, arbitrary NFC tag import, third-party URI launching, identity authentication, receiver-save acknowledgement, or new relay authorization/revocation contract. None is necessary for the specified Android tap/QR choice. Wi-Fi isolation still requires hotspot/file recovery while peer relay is unqualified. OEM RF routing and wallet interaction remain hardware qualification risks; do not paper over them with a fake success state. If a required device gate fails, keep the feature release blocked and document the exact supported boundary before any change to the defaults.
