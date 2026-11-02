# W9-11 build record — first-run, icon, splash, screenshots

Slice: W9-11 (plan `docs/plans/0002-production-ready-cosmos.md` §6).
Base commit: `815af09c60f15e6b768fde1a4bdfb44053ed7967`
Slice commit: recorded by `simt slice finish W9-11 --yes` (see tracker row).

## Build under test

- `./gradlew :androidApp:assembleDebug` green on the slice tree.
- APK: `androidApp/build/outputs/apk/debug/androidApp-debug.apk`
- APK SHA-256: `fe8914999e9509aaf3d4509f23e1b1cfc43d807f1ecb39c9d586f15d059e2b6f`
- Installed with `adb install -r` (streamed install Success).

## Device

- `emulator-5554` device, `product:sdk_gphone64_arm64 model:sdk_gphone64_arm64`.
- Camera permission granted on-device (prior run); seeded routes `test` and
  `Gate-WalkLab-Door` pre-existed from earlier device slices and were kept.
- Night mode toggled `yes` for the Deep Space shot, then restored to `no`.

## Shots (all `adb exec-out screencap -p` from the build above)

| File | Screen | How reached |
|---|---|---|
| `home-daylight.png` | Home, populated, Daylight Nebula | cold start |
| `home-deep-space.png` | Home, Deep Space + starfield | `cmd uimode night yes` + restart |
| `routedetail-daylight.png` | RouteDetail (`Gate-WalkLab-Door`) | tap saved-walk row |
| `align-daylight.png` | Align with live camera PiP | RouteDetail → Navigate |
| `navigate-daylight.png` | Navigate instrument (σ chip, arrow, `0.0 m`) | Align → I'm aligned |
| `record-daylight.png` | Record pre-start (camera live, 88 dp Start) | Home deck → Record |
| `finish-sheet-daylight.png` | Finish canopy sheet (name + destination, Continue) | 18 s live recording → hold Finish |
| `review-daylight.png` | Review of the just-recorded walk (destination `Door` carried through) | Finish sheet → Continue |

## Deliberately not captured on device

- **First-run Ignition empty state.** The device holds shared fixtures
  (seeded routes also used by sibling slices); `pm clear` was deliberately
  not run. The priming copy ("Recording and scanning use the camera —
  nothing leaves this phone until you share.") is asserted in
  `HomeScreenTest.emptyRepository` under `:shared:testAndroidHostTest`.
- **Cold-start splash frame.** Timing-sensitive under `exec-out`; the splash
  is pinned structurally instead: `IconSplashTest` asserts the void
  `windowBackground` + edge-to-edge status/navigation splash theme.

## Asset provenance (freeze `docs/research/cosmos-echo-freeze.md` §1–§2)

- Icon foreground: hull disc `#1D1220`, Ember ring `#FF8C42`, Rose core
  `#FF7E8A`, all inside the 66 dp safe circle. No lime anywhere.
- Launcher/splash field: void `#120A14` (`echo_launcher_bg`, splash theme
  window + status + navigation bars).

## Residual observation (NOT this slice — reported, not fixed)

`home-deep-space.png` (confirmed after force-stop + 5 s settle): title/
heading text (`Echo`, `Walk it once. Share the way.`, section headers,
record titles) renders near-black on void, while subtitles, badges, and
deck labels render correct ink. Subtitles-vs-titles split suggests a title
text-style color issue in the W8 theme/screens lane. Untouched per ADR-002;
needs a W8 follow-up before release screenshots in W9-12.
