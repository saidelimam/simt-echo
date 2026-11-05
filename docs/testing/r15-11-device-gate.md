# Parallax visual assist — device gate runbook

Review: [0015](../reviews/0015-parallax-visual-assist-review.md) (slice R15-11, §4)
Plan: [0004](../plans/0004-echo-parallax.md) (§4 visual budget, W16-04 gate)
Status: **not yet run.** Every result cell below is `—` until someone runs it on
hardware. A host suite cannot close this gate: the sim lacks rolling shutter,
auto-exposure, lens distortion and real IMU bias, so no simulator result is
device evidence.

## What this gate decides

Whether W16-04's visual step gate may ship flag-on. Failing case B or D on
either phone keeps W16-04 flag-off (plan 0004 §4). Passing it does **not** close
W19-03 (performance) or W19-04 (field A/B); those have their own runs.

## Prerequisites

- **Two named phones**: A = Pixel-class, B = mid-range Samsung. Record exact
  model and Android version in the matrix. Both run the same build SHA; a mixed
  pair is not a valid run.
- Build and install:
  `./gradlew :androidApp:assembleDebug` then `adb -s <serial> install -r …`,
  once per phone. Record `git rev-parse HEAD` and confirm it on both.
- **Visual assist on** in Settings, and the debug build's research mode on so the
  Navigate HUD shows the R15-08 counters
  (`Visual gate: N accepted · N rejected · N unwitnessed · N suspects`).
- **Overlay on and off**: the camera overlay is a setting. Run every case in
  both states, and record `overlay=on|off` next to each result. Overlay off still
  binds the analysis tap; it just hides the viewfinder.
- **Textured scene ≥ 2 m away** for every indoor case: posters, shelving,
  furniture edges — not a blank wall. Note the scene and lighting in the row.
- **Taped 50 m straight course**, measured with a tape, on a corridor with
  visible texture. The same course is walked visual-on and visual-off.
- Phone A and B both on automatic time; put both in the same thermal starting
  state (room temperature, not charging) before the thermal case.

## Evidence capture

Per case, keep the screen recording and the counter line from the research HUD
(`Visual gate: … accepted · … rejected · … unwitnessed · … suspects`). A trial
CSV row exists only for a run that reaches ARRIVED, so completed walks (case D)
carry the counters in the trials CSV export as well; stationary, bob and thermal
runs never arrive and their only counter surface is the HUD. Capture the HUD in
the recording and transcribe it into the row — a missing counter line is an
unrun case, not a pass.

```bash
# Per case: clear, record, stop, pull.
adb -s <serial> logcat -c
adb -s <serial> logcat -v time > case-<id>-<phone>.log &
adb -s <serial> exec-out screenrecord --time-limit 90 /sdcard/<case>-<phone>.mp4
adb -s <serial> pull /sdcard/<case>-<phone>.mp4
```

Record in every row: phone (A/B), build SHA, overlay state, scene/lighting,
accepted / rejected / unwitnessed / suspects, and a one-line observation. A
missing counter row is an unrun case, not a pass.

## Device matrix

| Slot | Model | Android / API | Build SHA | Visual assist | Overlay runs |
|---|---|---|---|---|---|
| A (Pixel-class) | — | — | — | on | on + off |
| B (mid-range Samsung) | — | — | — | on | on + off |

## A. Still in hand

Hold the phone naturally, as if reading it, for 60 s. Do not deliberately steady
it against a surface.

| # | Steps | Expected | Result |
|---|---|---|---|
| A1 | Navigate, aligned, stand still 60 s, overlay on. | ≤ 1 accepted step; phase never UNCERTAIN; distance does not advance a leg. | — |
| A2 | Same run, overlay off. | Identical verdicts: the overlay must not change the gate. | — |
| A3 | Record screen on the same stand, 60 s. | Record HUD distance grows ≤ 0.7 m; no phantom step is credited. | — |

## B. Bob in place

Deliberate 1–2 Hz vertical bob for 60 s, phone held as in case A. This is the
reported symptom.

| # | Steps | Expected | Result |
|---|---|---|---|
| B1 | Bob 60 s in Navigate, overlay on. | ≥ 95 % of candidates rejected; never advances a leg; no ARRIVE. | — |
| B2 | Same run, overlay off. | Same rejection rate; the overlay is presentation only. | — |
| B3 | Bob 60 s on the Record screen with analysis running. | Record HUD distance grows ≤ 0.7 m; the counters show rejected candidates, not accepted ones. | — |
| B4 | Bob 60 s while walking on the spot with a ±2° hand roll. | Still ≥ 95 % rejected; any accepted candidate is counted `unwitnessed`, never silently credited. | — |

## C. Thermal MODERATE

Reach MODERATE by warming the device with sustained camera analysis (record with
analysis running, or repeat case B until the platform reports MODERATE). Record
how MODERATE was reached; do not fake the state in code.

| # | Steps | Expected | Result |
|---|---|---|---|
| C1 | Repeat B1 at MODERATE. | Still ≥ 95 % rejected; the witness rate never drops below the 4 Hz floor (R15-06). | — |
| C2 | Continue until SEVERE, then repeat B1. | Analysis stops; the gate reports UNKNOWN, never STILL-by-default; no step is credited as visually witnessed. | — |
| C3 | Cool down, repeat B1. | Behaviour returns to C1 without a restart. | — |

## D. Walk 50 m taped

Walk the taped course at normal pace, then at slow pace. Compare against the same
course with visual assist off on the same phone, same day.

| # | Steps | Expected | Result |
|---|---|---|---|
| D1 | Normal pace, visual off: record step count and distance. | Baseline row; no gate involvement. | — |
| D2 | Normal pace, visual on, overlay on. | Step count within ±2 % of D1; `unwitnessed` ≤ 10 % of steps in the textured corridor. | — |
| D3 | Slow pace (≈0.5 m/s), visual on. | Within ±2 % of the slow visual-off walk; false rejects ≤ 1 %. | — |
| D4 | Normal pace, visual on, overlay off. | Same counts as D2; no behavioural difference from the overlay. | — |
| D5 | Walk with the phone in a pocket, visual on. | Verdicts UNKNOWN; step count equals the visual-off pocket walk. | — |

## E. Flat on a table

| # | Steps | Expected | Result |
|---|---|---|---|
| E1 | Phone flat and still on a table 60 s, visual on. | No accepted steps; gate reports UNKNOWN, not STILL-by-default. | — |
| E2 | Pick up and walk immediately after E1. | Steps resume within two cadence cycles; no stuck UNKNOWN. | — |
| E3 | Phone face down / covered 60 s, then walk. | Same as E1–E2; a covered camera must fail open to ACCEPT-with-UNKNOWN, never credit phantom steps. | — |

## Sign-off

| Field | Value |
|---|---|
| Build SHA (both phones) | — |
| Devices (model / Android / serial) | — |
| Date and operator | — |
| Cases passed / run | — / — |
| Counters recorded for every case (HUD; CSV too for arriving walks) | — |
| Thermal MODERATE reached by (method) | — |
| Artifacts attached (CSV per run, HUD captures, screen recordings) | — |
| Reviewer verdict (ADR-008) | — |
| Decision | — (ship W16-04 flag-on / keep flag-off) |

Only when this table is filled and the decision recorded may plan 0004 call
W16-04's device gate closed. Until then the visual gate is host-tested only and
its device behaviour is unknown.
