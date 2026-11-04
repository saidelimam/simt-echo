# Review 0014 — Sharing (NFC / QR / LAN / Relay) full production-readiness review

Status: **complete** · created 2026-11-04 · Verdict: **BLOCK**

Scope: the entire route-sharing pipeline at repo HEAD `36c23b4` — Android
manifest & network security (`androidApp/src/main/AndroidManifest.xml`,
`res/xml/echo_apdu_service.xml`, `res/xml/file_paths.xml`), NFC HCE presenter &
IsoDep reader (`shared/src/androidMain/kotlin/app/simt/echo/platform/nfc/*`,
`NfcPairing.android.kt`, `shared/src/commonMain/kotlin/app/simt/echo/platform/NfcPairing.kt`,
`transfer/NfcApduProtocol.kt`), QR generation & CameraX/ML Kit scanner
(`ui/screens/share/ShareQr.kt`, `androidMain/.../QrScanner.android.kt`), LAN
server & HTTP client (`androidMain/.../KtorLanTransport.kt`,
`transfer/LanAddress.kt`, `transfer/HttpProgress.kt`), session & invitation
orchestration (`transfer/TransferService.kt`, `transfer/OfferSession.kt`,
`transfer/QrPayload.kt`, `transfer/PairingInvitation.kt`,
`transfer/PairingMethod.kt`, `transfer/PairingFailure.kt`), cloud relay
(`transfer/RelayClient.kt`, `worker/src/relay.ts`), file fallback
(`androidMain/.../FileSharing.android.kt`), and UI/ViewModel state machines
(`ui/screens/share/*`, `ui/screens/receive/*`, `ui/components/NfcTapPanel.kt`,
`ui/components/PairingMethodSelector.kt`, `ui/components/PairingLifecycle.kt`).

Method: end-to-end trace of every runtime path on real Android hardware (API
28–36) across NFC tap, QR scan, LAN HTTP transfer, hotspot/offline Wi-Fi
routing, lifecycle transitions (`ON_PAUSE`/`ON_RESUME`/unbind), and cloud relay.

Severity levels: **Critical** — breaks NFC or QR sharing on real Android
devices; **High** — leaves sender/receiver stranded, wipes active offers, or
violates the plan 0005 contract; **Medium** — reliability/UX degradation under
common edge cases. Critical and High findings BLOCK production sign-off.

## Tracker

| id | state | slice | tier | lane | depends on | evidence |
|---|---|---|---|---|---|---|
| R14-01 | Complete | F1 Android cleartext HTTP block & Wi-Fi socket binding: add `network_security_config.xml` permitting cleartext for LAN sharing and bind `KtorLanTransport` client sockets to the active Wi-Fi/hotspot `Network` | Critical | lan | — | 62b176403cbf10e00863aaa490799cdd6e147c07 |
| R14-02 | Complete | F2 `EchoHostApduService.onDestroy()` wipes `ActiveNfcOfferRegistry`: remove `clearAll()` from service `onDestroy()` so OS unbinds after a partial tap or re-offer never erase the live offer | Critical | nfc-hce | — | 7867d22f8996c45cc9a0f15b08a7386213173139 |
| R14-03 | Complete | F3 LAN/hotspot IPv4 selection (`KtorLanTransport` & `LanAddress`): inspect all `Inet4Address`es per interface, exclude `rmnet*`/`tun*`/`clat*`/`dummy*`, and prioritize hotspot (`ap*`/`softap*`/`wlan1`) | Critical | lan | R14-01 | 33c3e1451bdcd8a7290e7c50a125b159a7d3027d |
| R14-04 | Queued | F4 NFC Receive recovery & re-arm deadlocks: keep `nfcListening` honest on retryable failures, add `"Try tap again"` + `"Use QR instead"` actions on `NfcTapPanel`, re-arm on adapter `STATE_ON`, and report non-IsoDep tags | High | nfc-read | — |  |
| R14-05 | Queued | F5 `ShareViewModel` & `NfcPairing.android.kt` lifecycle hazards: preserve user `selection` across `onResume()`, do not kill active LAN transfers on transient pause, and fix observer start order | High | lifecycle | R14-02 |  |
| R14-06 | Queued | F6 QR scanner reliability & lifecycle (`QrScanner.android.kt`, `ShareQr.kt`, `ReceiveViewModel`): 1280×720 analysis resolution, cancel late camera bind on dispose, surface non-ECHO/corrupt QR failures with `"Scan again"` | High | qr | — |  |
| R14-07 | Queued | F7 Post-download & 10-minute expiry desync (`ShareViewModel`, `KtorLanTransport`): unpublish NFC on download/expiry, offer `"Share again"` after download/expiry, and wire `expireOffer()` timer | High | share | R14-05 |  |
| R14-08 | Queued | F8 Missing NFC contact & transfer UX (`ReceiveViewModel`, `ReceiveScreen`, `NfcTapPanel`): wire receiver `Haptics`, render `"Hold still…"` on `NfcEvent.Reading`, and `"Connected — you can separate the phones"` on fetch | Medium | ux | R14-04 |  |
| R14-09 | Queued | F9 Cross-install cloud relay qualification (`worker/src/relay.ts`, `TransferService`, `ShareScreen`): capability-based relay GET, `etagDoesNotMatch: "*"`, device-key auth, and `peerRelayQualified = true` fallback | High | relay | R14-01 |  |
| R14-10 | Queued | F10 Two-phone hardware qualification (`docs/testing/nfc-sharing.md`, W21-05): execute and record the A1–D4 matrix across two physical Android devices | High | qa | R14-01..R14-09 |  |

---

## 1. Root causes why NFC & QR sharing fail on real devices

### F1 — Android blocks cleartext `http://` LAN fetches, and offline Wi-Fi/hotspots route over cellular/VPN (Critical → `R14-01`)

1. **Android 9+ `NetworkSecurityPolicy` blocks `http://<lan-ip>:<port>/echo/<token>`:**
   - `KtorLanTransport.fetch` (`shared/src/androidMain/kotlin/app/simt/echo/platform/KtorLanTransport.kt:138-140`) fetches the bundle over plain HTTP (`http://${endpoint.host}:${endpoint.port}/echo/$token`).
   - `androidApp/src/main/AndroidManifest.xml:32-41` declares neither `android:usesCleartextTraffic="true"` nor `android:networkSecurityConfig` (even though `docs/research/SIMT_ECHO_IMPLEMENTATION_PLAN_V2.md:668` required `<base-config cleartextTrafficPermitted="true"/>`).
   - On Android 9+ (`minSdk = 26`, `targetSdk = 36`), `cleartextTrafficPermitted` defaults to **`false`**. Every QR scan and every NFC tap succeeds up to invitation parsing, and then `OkHttp` immediately throws `java.net.UnknownServiceException: CLEARTEXT communication to <ip> not permitted by network security policy`.
   - `ReceiveViewModel.fetch` (`ReceiveViewModel.kt:226-236`) catches that exception and displays `"Can't reach the other phone. Join its hotspot and try again."` (`UNREACHABLE_COPY`) or `"Tap connected, but the route couldn't transfer..."` (`TAP_NETWORK_FAILED`). `KtorLanSessionTest.kt:28` missed this because it ran under Robolectric with `manifest = Config.NONE`.
2. **Offline Wi-Fi / hotspot routing hijacked by cellular data or VPN:**
   - When Phone B connects to Phone A's hotspot or an offline indoor Wi-Fi network (no internet access) while mobile data or a VPN is active on Phone B, Android's `ConnectivityManager` keeps cellular/VPN as the process default network because the Wi-Fi network lacks `NET_CAPABILITY_VALIDATED`.
   - `KtorLanTransport.defaultClient()` (`KtorLanTransport.kt:182-188`) uses the default `SocketFactory`, so `OkHttp` routes `192.168.x.x` over cellular/VPN instead of the Wi-Fi interface and times out after 4 s.

**Fix (`R14-01`):**
- Add `androidApp/src/main/res/xml/network_security_config.xml` with `<base-config cleartextTrafficPermitted="true" />` (and wire `android:networkSecurityConfig="@xml/network_security_config"` in `AndroidManifest.xml`; `EchoApiClient.kt:504-509` already enforces `https://` in code for all cloud API calls).
- In `KtorLanTransport`, resolve the active `TRANSPORT_WIFI` (or local interface) `android.net.Network` from `ConnectivityManager` when dialing a site-local IPv4 and configure `OkHttp` `socketFactory(network.socketFactory)` so offline Wi-Fi and hotspot transfers never route over cellular/VPN.
- **Suggested acceptance tests (`R14-01`):**
  1. Extend `NfcManifestTest.kt` with `manifestDeclaresNetworkSecurityConfigPermittingCleartextForLanTransfer()`, parsing `AndroidManifest.xml` and `res/xml/network_security_config.xml` and asserting `cleartextTrafficPermitted="true"` is active on API 28+.
  2. Add `KtorLanSessionTest.clientUsesBoundWifiSocketFactoryWhenProvided()`, verifying that `KtorLanTransport` binds its `OkHttp` client socket factory to the supplied Wi-Fi `SocketFactory` when fetching a LAN endpoint.

---

### F2 — `EchoHostApduService.onDestroy()` wipes `ActiveNfcOfferRegistry`, breaking NFC taps after any unbind, failed tap, or re-offer (Critical → `R14-02`)

In `shared/src/androidMain/kotlin/app/simt/echo/platform/nfc/EchoHostApduService.kt:52-59`:
```kotlin
override fun onDestroy() {
    presenter?.deactivate()
    presenter = null
    ActiveNfcOfferRegistry.clearAll()
    super.onDestroy()
}
```
On Android, the OS NFC stack (`com.android.nfc`) binds `HostApduService` on-demand when an RF field/SELECT arrives and **unbinds (`onDestroy()`) as soon as the RF field drops** or when `unsetPreferredService` is processed asynchronously:
1. **Second tap after an interrupted tap always fails with `OfferExpired` (`6985`):** If the user moves the phones apart slightly too early on the first tap (`PairingFailure.Interrupted`), Android unbinds `EchoHostApduService` when the RF link drops. `onDestroy()` calls `ActiveNfcOfferRegistry.clearAll()`, erasing `snapshot` and setting `armed = false` — while `AndroidNfcPairingController._publication` stays `ready = true` and `ShareScreen` still displays **"Ready to tap"**. When the user taps a second time 1 second later, the newly bound service sees `ActiveNfcOfferRegistry.current() == null` and answers `6985` (`"That invitation expired. Ask for a fresh one."`).
2. **Async unbind race on `reoffer()`:** When the sender toggles a crop or expiry in `ShareScreen`, `detachNfc()` calls `unsetPreferredService`, and `reoffer()` publishes and arms generation `N+1`. When Android's binder thread subsequently delivers `onDestroy()` for the previous service binding, `clearAll()` wipes generation `N+1` from `ActiveNfcOfferRegistry`.

**Fix (`R14-02`):**
- Remove `ActiveNfcOfferRegistry.clearAll()` from `EchoHostApduService.onDestroy()` (keep `presenter?.deactivate()` and `presenter = null`). `AndroidNfcPairingController` (`publish`, `clearPublishedOffer`, `onPaused`, `close`) is the sole owner of `ActiveNfcOfferRegistry`, and `NfcOfferSnapshot.expiresAtMonotonicMs` + `deviceIsInteractive()` already guard stale/locked access.
- **Suggested acceptance tests (`R14-02`):**
  1. Update `NfcPresenterTest.kt`: replace the faulty `onDestroy` assertion with `serviceDestroyAfterFieldLossKeepsArmedOfferForNextTap()`, proving that `service1.onDestroy()` followed by `service2 = buildService(...).create().get()` still answers `SW_OK` (`90 00`) while `ActiveNfcOfferRegistry` remains armed, and answers `SW_NO_OFFER` (`6985`) once `AndroidNfcPairingController.onPaused()` / `clearPublishedOffer()` disarms it.
  2. Add `NfcPresenterTest.delayedDestroyAfterReofferDoesNotWipeNewGeneration()`, proving an `onDestroy()` after publishing generation 2 leaves generation 2 readable.

---

### F3 — `localIpv4()` and `selectLanIpv4()` pick cellular/VPN IPs or miss hotspot interfaces (Critical → `R14-03`)

In `KtorLanTransport.kt:191-205` and `shared/src/commonMain/kotlin/app/simt/echo/transfer/LanAddress.kt:18-27`:
1. **Only `.firstOrNull()` IPv4 per interface is inspected (`KtorLanTransport.kt:195`):** If an interface holds multiple IPv4 addresses, all but the first are discarded instead of `flatMap`ping all `Inet4Address`es. Also, `NetworkInterface.getNetworkInterfaces()` can return `null` on Android, risking an NPE on `.toList()`.
2. **Cellular (`rmnet*`) and VPN (`tun*` / `ppp*`) private IPs are accepted as fallback LAN addresses:** Carrier CGNAT/private addresses (`10.x.x.x`) and VPN tunnels (`10.x.x.x`, `172.16.x.x`) have `isSiteLocalAddress == true`. When a user turns on a Wi-Fi Hotspot whose interface is named `softap0`, `wlan1`, `rndis0`, or `p2p-wlan0-0` (none of which start with `"ap"` or `"swlan"`), `selectLanIpv4` either picks client `wlan0` (when concurrent STA+AP is active) or falls through to `candidates.firstOrNull()` and embeds the unreachable **`rmnet_data0` / `tun0` IP** into the QR code and NFC invitation!

**Fix (`R14-03`):**
- In `KtorLanTransport.localIpv4()`, guard `NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()` and `flatMap` all `Inet4Address`es per interface.
- In `selectLanIpv4`, explicitly exclude cellular, tunnel, loopback, and virtual interfaces (`rmnet*`, `ccmni*`, `pdp*`, `tun*`, `tap*`, `ppp*`, `clat*`, `dummy*`, `lo*`, `v4-*`) and order priority as: dedicated hotspot/tether interfaces (`ap*`, `softap*`, `swlan*`, `wlan1`, `wlan2`, `p2p*`, `rndis*`) -> primary Wi-Fi (`wlan0`, `wlan*`) -> remaining physical LAN (`eth*`, `en*`).
- **Suggested acceptance tests (`R14-03`):**
  1. Add `LanAddressTest.kt` (`commonTest`) covering: (a) `rmnet_data0` (`10.24.1.5`) + `tun0` (`10.8.0.2`) + `softap0` (`192.168.43.1`) selects `192.168.43.1`; (b) concurrent `wlan0` (`192.168.1.10`) + hotspot `wlan1` (`192.168.43.1`) prefers the hotspot interface `192.168.43.1`; (c) only `rmnet_data0` + `tun0` present returns `null` (triggers honest `"Connect to Wi-Fi or turn on hotspot"` error rather than generating an unreachable QR/NFC URI); (d) multi-address interface keeps the site-local IPv4.

---

### F4 — NFC Receive deadlocks after any tap failure, 20s timeout, adapter toggle, or non-IsoDep tag (High → `R14-04`)

Tracing `ReceiveViewModel.kt`, `AndroidNfcPairingController.kt`, `NfcInvitationReader.kt`, `ReceiveScreen.kt`, and `NfcTapPanel.kt` exposes four compounding bugs on the receiving phone:

1. **20-second timeout and `OfferExpired` leave the user stranded with no retry or QR button:**
   - After 20 s without a tap, `AndroidNfcPairingController.kt:148-154` calls `stopReading(attemptId)` (disabling Android reader mode) and emits `NfcEvent.ReadFailed(attemptId, PairingFailure.Timeout)`.
   - `ReceiveViewModel.onPairingEvent` (`ReceiveViewModel.kt:318-322`) sets `nfcListening = false` and `pairingFailure = PairingFailure.Timeout` (leaving `ui.error = null`).
   - Because `nfcListening` is now `false`, `refreshReadiness()` sets `nfcReadiness = NfcReadiness.PREPARING` (`PairingMethod.kt:106`).
   - `NfcTapPanel` (`NfcTapPanel.kt:130, 154-178, 198`) then renders the heading **`"Preparing to receive…"`** above `"No tap yet. Keep both phones unlocked, or use QR."` — with **zero action buttons** (`canSwitch` at line 155 is only true for `UNAVAILABLE` or `DISABLED`, and `CosmosErrorState` in `ReceiveScreen.kt:201` only renders when `ui.error != null`). Reader mode is off, tapping does nothing, and `"Try tap again"` / `"Use QR instead"` (required by plan 0005 §4.4 line 131) do not exist!
2. **Transient `Interrupted` / `InvalidInvitation` tap failures falsely flip the UI to `"Preparing to receive…"`:**
   - When a tap fails mid-exchange (`PairingFailure.Interrupted`), `AndroidNfcPairingController` keeps reader mode enabled for the rest of the 20 s window, and `NfcInvitationReader` is still armed (`active == attemptId`). Yet `ReceiveViewModel.kt:320` sets `nfcListening = false`, killing the armed pulse ring and switching the heading from `"Ready to receive a tap"` to `"Preparing to receive…"`.
3. **Turning NFC on while on `ReceiveScreen` never arms the reader:**
   - When the user enables NFC via the Quick Settings tile or returns from system NFC Settings (where `ACTION_ADAPTER_STATE_CHANGED` with `STATE_ON` arrives after `ON_RESUME`), `AndroidNfcPairingController.kt:206` calls `onResumed()`, which only re-publishes `requested` for Share and does nothing for Receive. And `ReceiveViewModel.kt:136-138` (`pairing.capabilities.collect { refreshReadiness() }`) never calls `armIfIdle()` when `capabilities.enabled` becomes `true`! Receive stays stuck on `"Preparing to receive…"` with the reader disarmed.
4. **Non-`IsoDep` NFC tags (NTAG, Mifare, badges) are silently ignored:**
   - In `AndroidNfcPairingController.kt:275`, `IsoDep.get(tag)` returns `null` for standard NFC-A NDEF/Ultralight tags, and `NfcInvitationReader.kt:143` (`val opened = open() ?: return`) exits silently without emitting `ReadFailed(attemptId, PairingFailure.InvalidInvitation)`. Plan 0005 §2 & runbook B6 require showing `"This isn't an ECHO share."`

**Fix (`R14-04`):**
- In `NfcInvitationReader.exchange`, when `open()` returns `null`, emit `report(attemptId, PairingFailure.InvalidInvitation)`.
- In `AndroidNfcPairingController`, stop reader mode only on `Invitation` or `Timeout`; on retryable tag failures (`Interrupted`, `InvalidInvitation`, `OfferExpired`), keep reader mode armed and reset the 20 s idle timer so the user can immediately re-tap.
- In `ReceiveViewModel`:
  - Only set `nfcListening = false` on `ReadFailed` when `failure` is `Timeout`, `Disabled`, or `Unsupported`; for `Interrupted`, `InvalidInvitation`, and `OfferExpired`, keep `nfcListening = true` so the status stays `"Ready to receive a tap"` above the error message.
  - In `pairing.capabilities.collect`, when `method == PairingMethod.NFC && !nfcListening && PairingPolicy.availableNow(RECEIVE, capabilities)` (and not fetching/previewing/saved), call `arm()` and clear `pairingFailure = null`.
- In `NfcTapPanel`, when `failure != null` and `!listening` (e.g., `Timeout`), render `"Tap timed out"` as the status, a primary `"Try tap again"` button (`onRetryTap = viewModel::rescan`), and a secondary `"Use QR instead"` button (`onUseQrInstead`).
- **Suggested acceptance tests (`R14-04`):**
  1. `NfcReaderTest.nonIsoDepTagReportsInvalidInvitation()` — `open = { null }` emits `ReadFailed(attemptId, PairingFailure.InvalidInvitation)`.
  2. `ReceivePairingViewModelTest.interruptedTapKeepsReaderListeningAndReadyStatus()` — after `ReadFailed(Interrupted)`, `ui.nfcListening` remains `true`, `ui.nfcReadiness` remains `ARMED`, and a subsequent `Invitation` on the same attempt succeeds.
  3. `ReceivePairingViewModelTest.enablingNfcAdapterWhileOnReceiveNfcArmsReaderAutomatically()` — starting with `enabled = false` (`DISABLED`), flipping `capabilities` to `enabled = true` calls `startReading`, clears `PairingFailure.Disabled`, and transitions `nfcReadiness` to `ARMED`.
  4. `PairingModesUiTest.receiveTimeoutShowsTryTapAgainAndUseQrInstead()` — after `ReadFailed(Timeout)`, `"Try tap again"` and `"Use QR instead"` are displayed and clickable on `NfcTapPanel`.

---

### F5 — `ShareViewModel` & `NfcPairing.android.kt` lifecycle bugs: `onResume()` wipes user export selections, `onPause()` kills active LAN downloads, and observer ordering double-fires (High → `R14-05`)

1. **`ShareViewModel.onResumed()` overwrites `selection` with `defaultSelectionFor(routeId)` (`ShareViewModel.kt:208-211, 221-252`):**
   - Whenever the Activity pauses and resumes (e.g., returning from `Open NFC settings`, dismissing a system dialog, or unlocking the phone), `PairingLifecycle` calls `viewModel.onResumed()` -> `start()`, which resets `selection = service.defaultSelectionFor(routeId)` — silently discarding any landmark crops the user toggled off or link expiry (`24h`/`7d`) they selected!
2. **Initial composition double-triggers `onResumed()` and registers observers before `controller.start()` (`NfcPairing.android.kt:28-39`, `PairingLifecycle.kt:24-32`):**
   - `LifecycleRegistry.addObserver` synchronously dispatches `ON_RESUME` when added to an already-resumed `LifecycleOwner`.
   - In `NfcPairing.android.kt:35-39`, `addObserver(observer)` dispatches `controller.onResumed()` *before* `controller.start()` registers the adapter `BroadcastReceiver` and `NfcEventBus` collector, and then lines 37–39 call `controller.onResumed()` a second time.
   - In `ReceiveScreen`, `ReceiveViewModel.init` calls `arm()` (generation 1) and `PairingLifecycle`'s `addObserver` immediately fires `onResumed()` -> `arm()` (generation 2), cancelling generation 1 on mount.
3. **`ShareViewModel.onPaused()` kills the Ktor LAN server even while a peer is downloading after an NFC tap (`ShareViewModel.kt:217-219, 451-457`):**
   - Plan 0005 §1 & §4.3 state: *"After invitation completion the phones separate and the route transfer continues. Deactivation... does not cancel the LAN transfer."*
   - When the user separates the phones after an NFC tap and lowers Phone A (or a notification shade/lock occurs during the LAN transfer), `onPaused()` calls `stop()` -> `service.stopOffer()`, killing the HTTP server mid-transfer! Pausing Share should disarm NFC HCE (`detachNfc()`), not tear down the LAN server while the offer is still within its lifetime (only `close()` on leaving the screen should stop the LAN server).

**Fix (`R14-05`):**
- In `NfcPairing.android.kt`, call `controller.start()` *before* `lifecycleOwner.lifecycle.addObserver(observer)` and remove the redundant manual `if (isAtLeast(RESUMED)) controller.onResumed()` call.
- In `PairingLifecycle.kt`, ignore the initial synchronous `ON_RESUME` dispatched during `addObserver` (only forward `ON_RESUME` after a preceding `ON_PAUSE`), so `init` and `PairingLifecycle` never double-start on mount.
- In `ShareViewModel`:
  - `onPaused()` should call `detachNfc()` (disarming HCE while not foreground) without killing the active LAN offer (`service.stopOffer()` runs in `close()` when leaving Share).
  - `onResumed()` should preserve `_ui.value.selection` and re-publish the existing unexpired offer (`publishIfSelected()`) if `service.currentOffer` is still valid, only calling `reoffer(_ui.value.selection)` if no valid offer is active.
- **Suggested acceptance tests (`R14-05`):**
  1. `SharePairingViewModelTest.pauseAndResumePreservesExportSelectionAndActiveLanOffer()` — toggling a crop off, pausing (`onPaused()`), and resuming (`onResumed()`) keeps the excluded crop off, disarms NFC while paused, and re-publishes the same offer generation on resume without re-sealing.
  2. `ReceivePairingViewModelTest.initialMountArmsGenerationExactlyOnce()` — mounting `ReceiveViewModel` with `PairingLifecycle` leaves `generation == 1`.

---

### F6 — QR scanner fails on dense codes at 640×480, races camera bind on dispose, and traps users on invalid QR codes (High → `R14-06`)

1. **CameraX default `640×480` `ImageAnalysis` resolution is too low for ECHO's high-density QR codes (`QrScanner.android.kt:65-69`, `ShareQr.kt:61,136-149`):**
   - An ECHO v1 URI (~180–260 bytes) encoded at `QrErrorCorrectionLevel.High` with a 22% center sigil logo and `roundCorners(0.45f)` produces a dense Version 14–18 QR code (73×73 to 89×89 modules). At CameraX's default `640×480` analysis resolution, modules are ~2 px wide at normal scanning distance, causing slow or failed scans in indoor lighting.
2. **Async `ProcessCameraProvider` listener binds the camera *after* `QrScannerView` is disposed (`QrScanner.android.kt:98-119`):**
   - If the user switches from QR to NFC on `ReceiveScreen` before `providerFuture` completes on `mainExecutor`, `onDispose` sees `providerFuture.isDone == false`, skips `unbindAll()`, and the listener subsequently runs and binds the camera while in NFC mode!
3. **Invalid or non-ECHO QR codes either do nothing or hide `"Scan again"` (`QrScanner.android.kt:88-92`, `ReceiveViewModel.kt:166, 189-201`, `ReceiveScreen.kt:200-217`):**
   - `QrScanner.android.kt:89` silently ignores any QR code that does not start with `simt-echo://`.
   - If a `simt-echo://` QR code fails `PairingInvitation.parse(uri)` (e.g., `simt-echo://x` or corrupt parameters), `ReceiveViewModel.fetch` sets `fetching = false, pairingFailure = InvalidInvitation` with `ui.error = null` and **without resetting `handledUri` or incrementing `generation`**. `ReceiveScreen.kt:210-216` renders the error text `"This isn't an ECHO share."` with **no `"Scan again"` button**, and re-scanning the same or another code is blocked if `delivered` was already set!

**Fix (`R14-06`):**
- In `QrScanner.android.kt`:
  - Configure `ImageAnalysis.Builder().setResolutionSelector(ResolutionSelector.Builder().setResolutionStrategy(ResolutionStrategy(Size(1280, 720), FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)).build())`.
  - Track a `disposed = AtomicBoolean(false)` in `DisposableEffect`; inside the `providerFuture` listener, if `disposed.get()` is true, do not call `bindToLifecycle`.
  - Deliver any scanned barcode to `onScanned` (not only `startsWith("simt-echo://")`) so non-ECHO QR codes produce the honest `"This isn't an ECHO share."` state with recovery.
- In `ReceiveViewModel` + `ReceiveScreen`:
  - Remove the silent `if (source == PairingSource.QR && !uri.startsWith(ECHO_QR_SCHEME)) return` guard in `ReceiveViewModel.kt:166` so `PairingInvitation.parse(uri)` rejects it uniformly with `PairingFailure.InvalidInvitation`.
  - In `ReceiveScreen.kt:208-217`, when `ui.pairingFailure != null` in QR mode, render `CosmosErrorState(message = pairingFailureCopy(RECEIVE, failure), actionLabel = PairingCopy.RECEIVE_SCAN_AGAIN, onAction = viewModel::rescan)` so the user always gets `"Scan again"` (which increments `generation` and resets `delivered` via `key(ui.generation)`).
- **Suggested acceptance tests (`R14-06`):**
  1. `ReceivePairingViewModelTest.scanningNonEchoOrMalformedQrSetsInvalidInvitationAndRescanRecovers()` — calling `onScanned("https://example.com")` sets `pairingFailure = InvalidInvitation`, and `rescan()` clears it, increments `generation`, and accepts the next valid QR scan.
  2. `PairingModesUiTest.qrInvalidInvitationShowsScanAgainButton()` — when `onScanned("https://example.com")` fires on `ReceiveScreen`, `"This isn’t an ECHO share."` and `"Scan again"` are displayed and clicking `"Scan again"` remounts the scanner.

---

### F7 — Post-download 3s server shutdown & 10-minute expiry leave dead QR/NFC offers on `ShareScreen` (High → `R14-07`)

1. **After one download, `KtorLanTransport` stops the server after 3 s (`KtorLanTransport.kt:106-109`), but `ShareViewModel.onServed` (`ShareViewModel.kt:306-313`) keeps NFC armed and shows no `"Share again"` action:**
   - Plan 0005 §4.5 requires: *"Exiting Share, expiry, download completion, or changing export/relay settings unpublishes NFC before invalidating/replacing its offer."*
   - Currently, `onServed` sets `sent = true` but never calls `detachNfc()`. After 3 seconds, the HTTP server is stopped, yet Phone A still advertises the dead invitation over NFC HCE (and displays the dead QR code). If the receiver clicked Cancel on the preview or a second person taps/scans, the tap/scan succeeds and the HTTP fetch fails!
2. **`TransferService.expireOffer()` (`TransferService.kt:353`) is dead code — never called anywhere:**
   - After `OFFER_LIFETIME_MS` (10 minutes), `KtorLanTransport.kt:118-120` stops the HTTP server, and `NfcOfferSnapshot.expiresAtMonotonicMs` expires in `EchoHostApduService`, but `ShareViewModel` never runs an expiry timer or calls `service.expireOffer()`. The screen continues showing `"Ready to tap"` / `"Waiting for the other phone…"` over a stopped server.

**Fix (`R14-07`):**
- In `ShareViewModel.onServed`, call `detachNfc()` so a completed download unpublishes the single-use offer, and expose a `"Share again"` action on `ShareScreen` when `ui.sent == true` that calls `reoffer(_ui.value.selection)` to mint a fresh offer for another receiver.
- In `ShareViewModel`, schedule a coroutine timer for `OFFER_LIFETIME_MS` on each published offer that calls `service.expireOffer()`, `detachNfc()`, and sets `tapFailure = PairingFailure.OfferExpired` / `qrUri = null` with `"Try again"`.
- **Suggested acceptance tests (`R14-07`):**
  1. `SharePairingViewModelTest.downloadCompletionUnpublishesNfcAndAllowsShareAgain()` — when `servedGenerations` emits the current generation, `ui.sent` is `true`, `ui.readyToTap` becomes `false`, `ActiveNfcOfferRegistry` is cleared, and calling `shareAgain()` starts a new offer generation.
  2. `SharePairingViewModelTest.offerLifetimeExpiryUnpublishesNfcAndShowsRetry()` — advancing virtual time by `OFFER_LIFETIME_MS` expires the offer, clears `qrUri` and `readyToTap`, and surfaces `PairingFailure.OfferExpired`.

---

### F8 — Missing NFC contact haptics and step-by-step status copy on `ReceiveScreen` (Medium → `R14-08`)

Plan 0005 §2 item 3 and the device runbook (`docs/testing/nfc-sharing.md` A2) specify:
- A short pulse haptic when a real reader connection starts (`NfcEvent.Reading`), with status copy **`"Hold still…"`** while invitation chunks arrive.
- A completion haptic on the receiver once the full invitation validates (`NfcEvent.Invitation`), once per attempt.
- Status copy **`"Connected — you can separate the phones"`** while the route transfers over LAN after an NFC tap.

Currently:
- `ReceiveViewModel` does not accept `Haptics` at all; no haptics fire on `Reading` or `Invitation`.
- `NfcEvent.Reading` (`ReceiveViewModel.kt:312-314`) only sets `nfcListening = true` (which was already `true`), and neither `"Hold still…"` nor `"Connected — you can separate the phones"` exists in `PairingCopy.kt`, `NfcTapPanel.kt`, or `ReceiveScreen.kt`.

**Fix (`R14-08`):**
- Add `haptics: Haptics` to `ReceiveViewModel` (and pass `rememberHaptics()` from `EchoNavHost.kt:241`).
- Add `readingTap: Boolean = false` and `lastSource: PairingSource? = null` to `ReceiveUiState`.
- On `NfcEvent.Reading(attempt)`, set `readingTap = true` and play `HapticPattern.TICK`; render `PairingCopy.RECEIVE_HOLD_STILL` (`"Hold still…"`) in `NfcTapPanel`.
- On valid `NfcEvent.Invitation`, play `HapticPattern.CONFIRM` once per attempt and show `PairingCopy.RECEIVE_CONNECTED_SEPARATE` (`"Connected — you can separate the phones"`) above `TransferParticles` during `ui.fetching`.
- **Suggested acceptance tests (`R14-08`):**
  1. `ReceivePairingViewModelTest.nfcReadingAndValidInvitationTriggerDistinctHapticsAndStatusCopy()` — asserts `NfcEvent.Reading` sets `ui.readingTap == true` and plays `TICK`, and a valid `NfcEvent.Invitation` plays `CONFIRM` once and sets `ui.lastSource == PairingSource.NFC`.

---

### F9 — Cross-install cloud relay fallback is disabled (`peerRelayQualified = false`) when campus/corporate Wi-Fi isolates peers (High → `R14-09`)

When two phones are on a university, office, or hotel Wi-Fi with client isolation (AP isolation) and neither user knows how to configure a mobile hotspot, LAN fetch (`http://<ip>:<port>`) cannot connect. ECHO built the zero-knowledge R2 relay (`worker/src/relay.ts`, `KtorRelayClient`) specifically for this case, and both `docs/legal/privacy-policy.md` §4.3 and `docs/legal/terms-of-use.md` §5.2 advertise it as a live feature.
However, `TransferService.kt:64` hardcodes `peerRelayQualified: Boolean = false` because:
1. `worker/src/relay.ts:127-130` rejects `GET /v1/relay/:code` when `customMetadata.owner !== c.get("deviceId")` (blocking the receiving phone).
2. `worker/src/relay.ts:95` uses `onlyIf: { etagDoesNotMatch: "" }` instead of `etagDoesNotMatch: "*"`.
3. `EchoApplication.kt:20` still sends the retired `ECHO_APP_TOKEN` instead of minting a `dk_` device key via `POST /v1/device`.

**Fix (`R14-09`, coordinated with `R12-02`/`R12-03`):**
- In `worker/src/relay.ts`: allow any authenticated install to `GET /v1/relay/:code` (the 10-character high-entropy code in the QR/NFC URI is the capability, and the payload is AES-256-GCM ciphertext whose key `k` never leaves the QR/NFC invitation); fix `onlyIf: { etagDoesNotMatch: "*" }` and `include: ["customMetadata"]` in `sweepExpiredRelayObjects`.
- Once the client device-key store (`R12-02`) is wired, set `peerRelayQualified = true` in `AppGraph.transferService` so `"Use internet relay"` is available when peers are on isolated networks.
- **Suggested acceptance tests (`R14-09`):**
  1. `worker/src/relay.test.ts`: `allowsReceiverInstallToDownloadCiphertextUploadedBySenderInstall()`.
  2. `shared/.../NfcTransferIntegrationTest.kt`: `whenLanIsIsolatedTapWithRelayCodeFallsBackToRelayAndVerifiesBundle()` — drives sender (`useRelay = true`) → NFC presenter → NFC reader → receiver (`transport.fetch` throws `IOException`, `relay.download` returns sealed bundle) → `BundleCodec.open` + `sha8Hex` verification.

---

### F10 — Hardware qualification (`W21-05`) on two physical Android phones (High → `R14-10`)

`docs/testing/nfc-sharing.md` has all result rows blank (`—`), and `docs/plans/0005-echo-nfc-sharing.md:157` (`W21-05`) remains `Queued`. After `R14-01`…`R14-09` land, run the full A1–D4 runbook on two physical Android phones and record build SHA, models, Android versions, latency (≥19/20 aligned taps < 2 s), and `adb logcat` verification.

Verdict: **BLOCK** — `R14-01`…`R14-07` and `R14-09`…`R14-10` must land before the NFC/QR sharing feature can be signed off as production-ready.
