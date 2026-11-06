# Demo — one story, end to end

One continuous run of the **release APK** (`simt-echo-v0.2.0.apk`) on an
Android emulator, telling a single story from an empty home screen to a
friend navigating the recorded route:

> **Record → consent + upload → AI grounding → EchoRoute bundle →
> share (QR / relay) → navigate.**

Every step below was driven on the release APK with `adb`; screenshots are in
the run's `shots/` directory. The run is honest about what an emulator can and
cannot do — where the scene is static, the app fails closed rather than
pretending, and that failure is part of the demo.

## Environment

- **APK:** `simt-echo-v0.2.0.apk` (the GitHub release asset; SHA-256
  verified before install, `versionName=0.2.0`).
- **Device:** Android emulator `emulator-5554`, AVD
  `Medium_Phone_API_36.1`, 1080×2400, persistent data.
- **Server:** `https://api.echo.simt.app` (production worker).
- **Limits:** the emulator's camera is a **static virtual scene** (a room with
  a bookshelf, a cat, and a checkerboard TV). It produces no real PDR motion,
  so a recorded walk has no walked path and, consequently, no keyframe photos.
  That is the honest envelope this demo runs inside; it is not a defect.

## The story

### 1. Record

Home shows the empty-state copy and the two entry points. Tap **Record**: the
capture screen comes up with a live preview, a Start control, a **Mark spot**
control and a **Hold to finish** control.

- Start the capture, mark two spots (Start, Destination), then hold to finish.
- The naming sheet asks for a route name. This run named it
  `Demo-Walk-LabvThe-Cat` (both text inputs landed in the name field —
  cosmetic, not functional).

The app segments the capture into a route with a Start and a Destination node.
Because the scene never moves, the walked path is **0 m**.

### 2. Consent + upload

Review shows the route health (100/100, "Ready to share"), the two spot cards
with fresh scene thumbnails, and "No walked path recorded". Tap **Find
landmarks** to open the consent sheet.

The sheet carries the frozen privacy copy, byte-for-byte:

> "Send 0 photos (and 0 short silent clips if SAM is enabled) to cloud AI on
> Cloudflare (Meta's Llama 4 Scout on Workers AI, with Google Gemma 4 as its
> automatic fallback; the Meta Model API if enabled) via SIMT ECHO's server to
> suggest landmarks. Files are deleted within 24 h (or now, from this screen).
> If the Meta Model API is enabled, its standard tier is used, not the
> contributor tier whose inputs may be used for training. Your route shape
> stays on this phone. Frames showing faces are left out before upload."

With **0 photos** on a static-scene walk, the **Send** button is **disabled**
(`ProcessingScreen.kt`: `enabled = info.photos > 0`). Nothing uploads before a
real Send, and there is nothing to upload here — the app says so in the count
instead of sending an empty job. Defence in depth: if Send were reached with no
files, `prepareUpload()` throws `EchoApiError.Server(0, "No keyframes to
send.")`. This is the **honest fail-closed** state. Tap **Not now** to return
to Review.

### 3. AI grounding (the cloud path, proven separately)

Grounding is the one leg that needs a network and real keyframes, so on the
static-scene emulator it is proven with production evidence rather than
pretended on-device:

- **W9-01 production job `fe7f506f`** — `POST 202` with a real
  emulator-camera door photo → `complete` in <60 s with a genuine Llama 4
  Scout candidate ("Door / Turn left at the door", box-only,
  `scoutMs: 4320`) → result fetched (readable) → `DELETE 204` → `GET 404
  job_not_found`. Full lifecycle on production data and code
  ([docs/release.md](release.md)).
- **W9-02 live envelope** — job `56a0b9d4` over a 7-checkpoint route:
  `scoutMs: 53175` (7 live Llama 4 Scout image calls, no Gemma fallback
  needed), box-only grounding fallthrough, end-to-end POST→complete 66 s
  ([docs/measurements.md](measurements.md)).

These are the numbers behind "AI grounds landmarks onto the captured
geometry"; they are measured, not claimed.

### 4. EchoRoute bundle

The route is an **EchoRoute bundle** — geometry, nodes, landmarks and
provenance in a self-contained file. Saving it (below) writes the bundle to
the local store; sharing it sends that bundle, not a server record. The
bundle codec is W0-04 / W2-02.

### 5. Share (QR / relay)

From the saved route's action list, **Share** opens the share screen:

- Two transports: **NFC · Tap phones** and **QR · Show code**.
- The phone presents an ECHO-branded **QR code**; the other phone decodes it.
- "What others receive: `Demo-Walk-LabvThe-Cat` · 2 nodes · 0 landmarks",
  with a **Show details** disclosure.
- The relay path is zero-knowledge over R2 (W4-05): the server stores and
  returns opaque bytes only — it never parses the bundle and the key never
  leaves the QR path.

### 6. Navigate

From the saved route's action list, **Navigate** opens the **Align** screen —
"Stand here. Match this view." with the start photo, an anchor marker and a
live-preview inset. Confirm **I'm aligned** and the **Navigate** HUD appears:

- an accuracy chip (`σ ±0.5 m`),
- a direction arrow,
- "You're here — Destination",
- the distance to the next node (0.0 m on this 0 m route),
- the leg progress ("Leg 1 of 1"),
- and **I'm here** / **Lost** actions.

This is the turn-cue / navigation leg (W2-05 / W2-06), driven by the recorded
route, not by GPS.

## Saving the route (the on-device close)

Between consent and the share/navigate legs, the run saves the route so it
persists as a navigable, shareable bundle:

- Review → **Save ECHO** → the **Route** screen shows
  `Demo-Walk-LabvThe-Cat` · 0 m · 2 nodes · 0 landmarks with **Navigate**,
  **Share**, **Simulate walk** and **Delete route**.

## Worker health (live, at run time)

`curl https://api.echo.simt.app/v1/health` at the time of this run returned:

```json
{"ok":true,"version":"0.1.0","metaKeyConfigured":true,
 "providers":{"workersAi":true,"metaKey":true}}
```

Note the drift: [docs/release.md](release.md) (2026-11-02) recorded
`metaKeyConfigured: false` at first production deploy. `META_API_KEY` has
since been configured, so SAM grounding is now live server-side; the
2026-11-02 numbers above predate that and are cited as measured then.

## Honest scope

- The emulator's static scene means **no walked path → 0 keyframes → 0
  photos → Send disabled**. The demo exercises the full *UI* story and proves
  the *cloud* story with production job evidence; it does not fabricate a
  walked 60 m route on a standing-still camera.
- Guidance quality, haptics and a real walked path belong to physical-device
  runs (W9-04 / W9-06), which this emulator session does not cover.
- Nothing in the run was forced past a fail-closed state; where the app
  refuses (empty upload, 0 m route), that refusal is the evidence.

## Evidence references

- Run screenshots: `shots/01-launch … 22-share-qr` (this session).
- Production grounding jobs: W9-01 `fe7f506f` ([release.md](release.md)),
  W9-02 `56a0b9d4` ([measurements.md](measurements.md)).
- Release + signing + deploy evidence: [docs/release.md](release.md).
- Plan + slice trace: [`docs/plans/0001-agentic-flow.md`](plans/0001-agentic-flow.md).
