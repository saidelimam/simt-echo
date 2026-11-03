# ECHO Tap — device qualification runbook

Plan: [0005](../plans/0005-echo-nfc-sharing.md) (slice W21-05)
Status: **not yet run.** No result rows below have been filled in. Every row is
`—` until someone runs it on hardware: a host test cannot produce radio
evidence, and this document must not be read as one.

## Prerequisites

- Two Android phones (A = sender, B = receiver). At least one must support Host
  Card Emulation for the NFC cases; check with `adb shell pm list features | grep nfc`
  and the phone's own NFC settings.
- The same build installed on both
  (`./gradlew :androidApp:assembleDebug`, then `adb install -r`).
- NFC enabled on both, both screens unlocked, both apps in the foreground.
- A route on A with at least one landmark, a start photo, and one crop left on —
  so the "what others receive" assertions have something to check.
- Both phones on the same Wi-Fi **or** one hotspot with the other joined. NFC
  never creates that network; tapping without it must fail honestly.
- For evidence: `adb logcat -s EchoTap:I` and a screen recording of the pair.

Record for each case: result (`pass`/`fail`), date, devices, build SHA, and a
one-line observation. A failure is a regression against plan 0005 §1, not a new
feature request.

## A. NFC handshake

| # | Steps | Expected | Result |
|---|---|---|---|
| A1 | A: Share → NFC selected. B: Receive → NFC selected. | A shows "Ready to tap" only after the offer is published; B shows "Ready to receive a tap". Neither claims a connection yet. | — |
| A2 | Hold the backs of A and B together (move slowly; antennas are not always at the top). | One restrained pulse per real connection. B: "Hold still…" then "Connected — you can separate the phones". A: "Tap details shared". A never says the route was received. | — |
| A3 | Separate the phones after the connection. | The route still transfers over the LAN: measured particles move, no synthetic percentage. A ends at "Route downloaded", not "Saved". | — |
| A4 | B: review the import sheet, then **Save**. | Preview shows sigil, creator, distance, landmark count, expiry. Save persists; Open route navigates. Nothing was saved before Save. | — |
| A5 | Repeat A2, then decline on B (Cancel). | B returns to the selected method with no preview; A may show "Route downloaded" only if bytes actually moved, otherwise stays waiting. | — |
| A6 | Tap A to B twice in a row without moving the phones. | Exactly one completion haptic on B; no duplicate previews; no second fetch. | — |

## B. Recovery and refusals

| # | Steps | Expected | Result |
|---|---|---|---|
| B1 | Turn NFC **off** on A (quick settings), open Share. | NFC stays selected and says "Turn on NFC to tap"; **Open NFC settings** opens the system NFC screen (never toggled by the app); **Use QR instead** switches in one tap and the QR is scannable. | — |
| B2 | Use a phone without HCE as A, open Share. | "This phone can share by QR" with the QR visible beneath; no "Ready to tap" ever. | — |
| B3 | Lock A's screen mid-offer, then tap. | Nothing is served: no invitation, no fetch. Unlock and tap again succeeds. | — |
| B4 | Background A (Home), then reopen. | Offer re-arms; tapping works; no stale invitation survives. | — |
| B5 | Switch A NFC → QR → NFC while B is waiting on NFC. | B stays armed but nothing arrives until A republishes; switching never rotates the token/key (same QR after the round trip is resealed on resume — record what actually happens). | — |
| B6 | Tap B against a random NFC tag or a payment terminal. | B says "This isn't an ECHO share." No network activity, nothing launches, no crash. | — |
| B7 | Expire the route on A (short expiry), then tap. | A refuses to publish; B never receives a usable invitation. | — |

## C. QR path and mismatch

| # | Steps | Expected | Result |
|---|---|---|---|
| C1 | B: Receive (QR default) scans A's QR. | Same import sheet as the tap path, camera gate only around the scanner. | — |
| C2 | B: Receive → NFC while A stays on QR. | B leaves the scanner armed, A's QR does nothing; both screens explain the matching methods. | — |
| C3 | Deny camera permission on B, choose NFC. | Selector still usable, NFC receive works without any camera prompt. | — |
| C4 | Scan a long Unicode route name (accents/emoji). | The rendered QR decodes to the same URI; the name arrives whole. | — |

## D. Data, privacy and relay

| # | Steps | Expected | Result |
|---|---|---|---|
| D1 | Exclude the start photo and a landmark crop on A, then pair. | B's saved route has no excluded asset; the included ones are byte-identical to A's. | — |
| D2 | Share as file on A with perception data present. | Plain-file warning appears first; the perception opt-in is **off** by default and only then includes perception. | — |
| D3 | Inspect Share on a build with the relay configured. | **Use internet relay** is absent; no upload is attempted while the backend cannot carry another install's code. | — |
| D4 | `adb logcat` during A2–A4 (filtered to ECHO). | No token, key, fingerprint or full invitation URI appears in any log line. | — |

## E. Sign-off

| Field | Value |
|---|---|
| Build SHA | — |
| Devices (model / Android version / A or B) | — |
| Date | — |
| Cases passed / run | — / — |
| Reviewer verdict (ADR-008) | — |

Only when this table is filled and the verdict recorded may the tracker call
ECHO Tap shipped. Until then the feature's supported paths are LAN NFC and QR on
Android, unverified on hardware, and the relay action stays hidden.
