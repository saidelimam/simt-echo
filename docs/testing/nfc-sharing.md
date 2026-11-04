# ECHO Tap — device qualification runbook

Plan: [0005](../plans/0005-echo-nfc-sharing.md) (slice W21-05)
Status: **not yet run.** No result rows below have been filled in. Every row is
`—` until someone runs it on hardware: a host test cannot produce radio
evidence, and this document must not be read as one.

## Prerequisites

- Two Android phones (A = sender, B = receiver), from two different OEM
  families. At least one must support Host Card Emulation for the NFC cases;
  check with `adb shell pm list features | grep nfc` and the phone's own NFC
  settings.
- Also needed, and recorded in the device matrix below: one device **without**
  NFC (QR/file recovery) and one with a reader but **no HCE** (role policy).
- The same build installed on both
  (`./gradlew :androidApp:assembleDebug`, then `adb install -r`). Record the
  build SHA in the matrix; a mixed pair is not a valid run.
- NFC enabled on both, both screens unlocked, both apps in the foreground,
  unless a step says otherwise.
- A route on A with at least one landmark, a start photo, and one crop left on —
  so the "what others receive" assertions have something to check.
- Both phones on the same Wi-Fi **or** one hotspot with the other joined. NFC
  never creates that network; tapping without it must fail honestly.
- Leave wallet/default-tap-app settings alone unless a step says to inspect one;
  the run must not depend on changing a user's payment defaults.

## Evidence capture

Record for each case: result (`pass`/`fail`), date, devices, build SHA, and a
one-line observation. A failure is a regression against plan 0005 §1, not a new
feature request.

```bash
# Per case: clear, capture, stop. Keep the file with the row it belongs to.
adb -s <serial-A> logcat -c && adb -s <serial-B> logcat -c
adb -s <serial-A> logcat -v time -s EchoTap:I > case-A.log &
adb -s <serial-B> logcat -v time -s EchoTap:I > case-B.log &
adb -s <serial-A> exec-out screenrecord --time-limit 60 /sdcard/<case>.mp4
adb -s <serial-A> pull /sdcard/<case>.mp4
```

Timestamps from these logs are what the latency table measures, so keep the
clock of both phones in sync (both on automatic time) before starting.

## Device matrix

Fill every slot you claim support for. A slot left `—` is an explicit blocker
for that claim, not a silent omission.

| Slot | Role | Model | Android / API | NFC read | HCE | Build SHA | Notes |
|---|---|---|---|---|---|---|---|
| A | sender | — | — | — | — | — | — |
| B | receiver | — | — | — | — | — | — |
| A2 | second-OEM sender | — | — | — | — | — | — |
| B2 | second-OEM receiver | — | — | — | — | — | — |
| N | no-NFC device | — | — | — | — | — | QR/file recovery only |
| R | reader without HCE | — | — | — | — | — | sender role policy only |

## A. NFC handshake

| # | Steps | Expected | Result |
|---|---|---|---|
| A1 | A: Share → NFC selected. B: Receive → NFC selected. | A shows "Ready to tap" only after the offer is published; B shows "Ready to receive a tap". Neither claims a connection yet. | — |
| A2 | Hold the backs of A and B together (move slowly; antennas are not always at the top). | One restrained pulse per real connection. B: "Hold still…" then "Connected — you can separate the phones". A: "Tap details shared". A never says the route was received. | — |
| A3 | Separate the phones after the connection. | The route still transfers over the LAN: measured particles move, no synthetic percentage. A ends at "Route downloaded", not "Saved". | — |
| A4 | B: review the import sheet, then **Save**. | Preview shows sigil, creator, distance, landmark count, expiry. Save persists; Open route navigates. Nothing was saved before Save. | — |
| A5 | Repeat A2, then decline on B (Cancel). | B returns to the selected method with no preview; A may show "Route downloaded" only if bytes actually moved, otherwise stays waiting. | — |
| A6 | Tap A to B twice in a row without moving the phones. | Exactly one completion haptic on B; no duplicate previews; no second fetch. | — |
| A7 | Repeat A2 with each phone in its everyday case, and once with the antenna end offset by ~2 cm. | The tap still completes, or fails as "Tap interrupted" with a working retry. Never a "Ready" that does not serve. | — |
| A8 | Force-stop A's app while Share shows "Ready to tap", then reopen Share. | The dead process serves nothing; reopening arms a fresh offer and a tap succeeds. | — |

## B. Recovery and refusals

| # | Steps | Expected | Result |
|---|---|---|---|
| B1 | Turn NFC **off** on A (quick settings), open Share. | NFC stays selected and says "Turn on NFC to tap"; **Open NFC settings** opens the system NFC screen (never toggled by the app); **Use QR instead** switches in one tap and the QR is scannable. | — |
| B2 | Use a phone without HCE as A (slot R), open Share. | "This phone can share by QR" with the QR visible beneath; no "Ready to tap" ever. | — |
| B3 | Lock A's screen mid-offer, then tap. | Nothing is served: no invitation, no fetch. Unlock and tap again succeeds. | — |
| B4 | Background A (Home), then reopen. | Offer re-arms; tapping works; no stale invitation survives. | — |
| B5 | Switch A NFC → QR → NFC while B is waiting on NFC. | B stays armed but nothing arrives until A republishes; switching never rotates the token/key (record whether the QR is identical after the round trip). | — |
| B6 | Tap B against a random NFC tag or a payment terminal. | B says "This isn't an ECHO share." No network activity, nothing launches, no crash. | — |
| B7 | Expire the route on A (short expiry), then tap. | A refuses to publish; B never receives a usable invitation. | — |
| B8 | With a wallet installed and set as the default tap app, tap A to B. | ECHO's own AID still serves the invitation, or the failure is honest. No payment sheet appears and no wallet prompt interrupts the tap. | — |
| B9 | With the phone locked and "require unlock for NFC" at its shipped default, tap B. | Locked: nothing served. Unlocked: the tap succeeds. Do not change the setting to make a case pass — record the setting you tested. | — |
| B10 | Interrupt the field early: once right after contact, once mid-read, once on the final chunk. | Each attempt reports "Tap interrupted. Hold the phones together and try again.", nothing is fetched, and an immediate retry with the same phones succeeds. | — |
| B11 | Two senders (A and A2) tap B within a second of each other. | B shows guidance rather than a fictional connection, and at most one attempt proceeds. | — |
| B12 | Put the pair on isolated guest/campus Wi-Fi (AP isolation). | Tap and scan still succeed up to the fetch; the failure copy asks to join the same Wi-Fi or hotspot; "Use internet relay" is absent and no upload is attempted. | — |

## C. QR path and mismatch

| # | Steps | Expected | Result |
|---|---|---|---|
| C1 | B: Receive (QR default) scans A's QR. | Same import sheet as the tap path, camera gate only around the scanner. | — |
| C2 | B: Receive → NFC while A stays on QR. | B leaves the scanner armed, A's QR does nothing; both screens explain the matching methods. | — |
| C3 | Deny camera permission on B, choose NFC. | Selector still usable, NFC receive works without any camera prompt. | — |
| C4 | Scan a long Unicode route name (accents/emoji). | The rendered QR decodes to the same URI; the name arrives whole. | — |
| C5 | Scan an old valid v1 QR produced by a previous build. | Same import sheet; the wire format is unchanged and the legacy code still opens. | — |
| C6 | Scan at arm's length (~30–50 cm) in a bright room, then in dim light. | The code decodes in both; a miss offers "Scan again" rather than silence. | — |

## D. Data, privacy and offer lifetime

| # | Steps | Expected | Result |
|---|---|---|---|
| D1 | Exclude the start photo and a landmark crop on A, then pair. | B's saved route has no excluded asset; the included ones are byte-identical to A's. | — |
| D2 | Share as file on A with perception data present. | Plain-file warning appears first; the perception opt-in is **off** by default and only then includes perception. | — |
| D3 | Inspect Share on a build with the relay configured. | **Use internet relay** is absent; no upload is attempted while the backend cannot carry another install's code. | — |
| D4 | `adb logcat` during A2–A4 (filtered to ECHO). | No token, key, fingerprint or full invitation URI appears in any log line. | — |
| D5 | Import the `.echo` file on B through **Import file**, with no NFC or QR involved. | The privacy path is intact: excluded assets stay excluded, and perception data is absent unless it was opted in. | — |
| D6 | Leave A's offer untouched for 10 minutes, then tap or scan it. | A stops showing the code and the tap surface, says the invitation expired, and **Try again** mints a fresh, working offer. | — |
| D7 | Complete a download, wait ~5 s, then tap or scan the same offer again. | A no longer advertises the consumed offer; **Share again** produces a new one that completes. | — |

## E. Latency

Twenty deliberate aligned taps per direction, on each OEM pair. Measure first
reader contact → complete invitation from the logcat timestamps, and keep network
transfer time out of it. Product gate: at least 19 of 20 within 2 s, and every
failure recovers through retry or QR.

| Direction | Taps run | ≤ 2 s | Median (s) | Failures recovered | Result |
|---|---|---|---|---|---|
| A → B | — / 20 | — / 20 | — | — | — |
| B → A | — / 20 | — / 20 | — | — | — |
| A2 → B2 | — / 20 | — / 20 | — | — | — |
| B2 → A2 | — / 20 | — / 20 | — | — | — |

## F. Sign-off

| Field | Value |
|---|---|
| Build SHA (both phones) | — |
| Devices (model / Android version / A or B) | — |
| Date | — |
| Cases passed / run | — / — |
| Latency gate met (≥19/20 ≤ 2 s, each direction) | — |
| Artifacts attached (logcat per case, screen recordings, UI captures: light/dark, 200% text, reduced motion) | — |
| Reviewer verdict (ADR-008) | — |

Only when this table is filled and the verdict recorded may the tracker call
ECHO Tap shipped. Until then the feature's supported paths are LAN NFC and QR on
Android, unverified on hardware, and the relay action stays hidden.
