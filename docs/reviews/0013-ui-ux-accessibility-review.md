# Review 0013 — UI / UX / Accessibility full review (Phone, KMP shared UI & Wear OS)

Status: **complete** · created 2026-11-04 · Verdict: **BLOCK**

Scope: all UI, UX, theme, motion, navigation, and accessibility surfaces at
repo HEAD `da99757` — every file under
`shared/src/commonMain/kotlin/app/simt/echo/ui/` (`theme/`, `shell/`,
`atmosphere/`, `brand/`, `sigil/`, `icons/`, `motion/`, `components/`,
`copy/`, `screens/`), platform UI seams in
`shared/src/commonMain/kotlin/app/simt/echo/platform/` (`Permissions.kt`,
`LiveUpdate.kt`, `NfcPairing.kt`) and `shared/src/androidMain/`
(`DeviceServices.android.kt`, `LiveUpdate.android.kt`), `androidApp/`
(`MainActivity.kt`, `EchoApplication.kt`, `AndroidManifest.xml`, `res/`),
and `wear/` (`MainActivity.kt`, `EchoHapticListenerService.kt`,
`AndroidManifest.xml`, `res/values/strings.xml`).

Method: full manual inspection of all 83 `commonMain/ui` files, platform
actuals, Android/Wear manifests, and existing UI/accessibility host tests
(`A11yMotionGateTest`, `PairingAccessibilityTest`, `ProcessingConstellationFontScaleTest`,
`HostSettingsScreenTest`), checked against WCAG 2.2 AA (1.1.1, 1.3.1, 1.4.3,
1.4.4, 1.4.10, 2.1.1, 2.4.3, 2.4.7, 2.5.1, 2.5.3, 2.5.8, 4.1.2), the Cosmos
& Luminous design freezes (`docs/research/cosmos-echo-freeze.md`,
`docs/research/luminous-freeze.md`), and the workspace rules (`AGENTS.md`).

Severity levels: **Critical** — screen-reader user cannot identify or operate
primary controls, or primary actions clip off-screen without scroll;
**High** — broken WCAG 2.2 AA / `AGENTS.md` hard gate (touch target, fontScale
2.0 layout collision, pointer-only gesture without accessible alternative,
raw decorative symbols read aloud); **Medium** — design-system bypass, missing
input/focus ergonomics, or unlinked legal/settings affordances; **Low** —
visual/copy polish. Critical and High findings BLOCK sign-off.

## Tracker

| id | state | slice | tier | lane | depends on | evidence |
|---|---|---|---|---|---|---|
| R13-01 | Queued | F1 unlabelled `Switch` / `RadioButton` controls & heading pollution: row-level `Modifier.toggleable`/`selectable` (`Role.Switch`/`Role.RadioButton`, 48 dp min) in `SettingsScreen`, `ShareExportSection`, and `ReviewCards`; opt-in `isHeading` on `CosmosRecordRow` + semantics tests | Critical | a11y | — |  |
| R13-02 | Queued | F2 raw ASCII/Unicode symbols announced by TalkBack: `clearAndSetSemantics` on decorative glyphs in `StateViews`, `CosmosChips`, `NavigateProgress`, `FilmStrip`, and `AlignScreen`; spoken kind prefix on `CosmosBanner` + tests | High | a11y | — |  |
| R13-03 | Queued | F3 pointer-only gestures in Review (`DrawOverlay` drag, `KeyframeInspector` pinch-zoom, `HeroImage`, `ReviewInstrumentMap`): add accessible box presets/nudges, zoom `+/−/Reset` buttons, button role/click labels, and `CosmosTextField` in `DrawOverlay` + UI tests | High | review | — |  |
| R13-04 | Queued | F4 `NavigateScreen` & `CameraNavigateOverlay` clip actions at `fontScale 2.0`, use retired Echo Lime, and bind stray preview: make bodies scroll-safe with pinned actions, replace `0xFFC6F432` with Cosmos tokens, fix preview gate & HUD semantics + fontScale 2.0 test | High | navigate | — |  |
| R13-05 | Queued | F5 `RecordScreen` HUD collisions at `fontScale 2.0`, silent tap on `FinishButton`, and `CameraPermissionGate` bypass: flow-layout `TrailRibbon`/`LedgerBrackets`, add tap hint + gold focus ring to `FinishButton`, migrate `CameraPermissionGate` to `CosmosDeniedState` | High | record | — |  |
| R13-06 | Queued | F6 Wear OS TalkBack & round-screen clipping (`wear/.../MainActivity.kt`): set `IMPORTANT_FOR_ACCESSIBILITY_NO` on `arrowView`, use `dp`/chin-safe insets + `ScrollView`, and wire Echo Step launcher icon | High | wear | — |  |
| R13-07 | Queued | F7 modal sheet clipping & text-field ergonomics (`CosmosModals`, `CosmosFields`, `SettingsScreen`, `RecordScreen`, `ProcessingConstellationView`): scroll-safe `CosmosSheet`, `KeyboardOptions`/`ImeAction` on `CosmosTextField`, decimal keypad for `Measured metres`, and star-overlap avoidance | Medium | components | — |  |
| R13-08 | Queued | F8 design-system bypasses & UX gaps (`RouteDetailScreen`, `ArrivedScreen`, `ReceiveScreen`, `ProcessingScreen`): `CosmosEmptyState` + `CosmosDialog(destructive = true)` + landmark cards on `RouteDetail`, `CosmosTextField` in `TrialForm`, spinner semantics on `Receive`, and `Cancel` vs `Continue without AI` clarity on `Processing` | Medium | screens | R13-07 |  |
| R13-09 | Queued | F9 RTL, Arabic typography & string-resource plumbing (`EchoTheme`, `EchoIcons`, `SettingsScreen`, `EchoShell`): `autoMirror = true` on `EchoIcons.Back`, locale-switched `rememberEchoArabicTypography()`, RTL-safe promo arrow, and visible focus ring / wordmark semantics in `EchoShell` | Medium | i18n | — |  |

---

## 1. Critical & High accessibility findings

### F1 — Unlabelled `Switch` and `RadioButton` controls & fake headings across Settings, Share, and Review (Critical → `R13-01`)

1. **Six unlabelled `Switch` controls on `SettingsScreen` (`shared/src/commonMain/kotlin/app/simt/echo/ui/screens/settings/SettingsScreen.kt:129-139, 159-205, 213-223`):**
   - `Baseline mode`, `Visual assist`, `Camera overlay`, `Turn heartbeat`, `Sound cues`, and `Mobile data for landmarks` each pass a bare `Switch(checked = ..., onCheckedChange = ...)` into `CosmosRecordRow(..., trailing = { ... })` with `onClick = null`.
   - Because `CosmosRecordRow` (`ui/components/CosmosRecords.kt:56-99`) is not `toggleable` and does not merge semantics when `onClick == null`, TalkBack focuses the `Text(title)`, `Text(subtitle)`, and `Switch` as **three separate sibling nodes**. When focus lands on any of the six switches, TalkBack announces only **"On, Switch"** or **"Off, Switch"** with no label (violating WCAG 1.3.1, 2.5.3, and 4.1.2). Sighted users tapping the row title or subtitle also get no response.
2. **Same defect on `ShareExportSection` (`ui/screens/share/ShareExportSection.kt:192-207, 220-232`) and `ReviewCards` (`ui/screens/review/ReviewCards.kt:313-326`):**
   - In `ShareExportSection.kt:192-207`, every asset toggle row places `Column(Text(item.label), ...)` beside a standalone `Switch(checked = included, onCheckedChange = { onToggle(item.id, it) })` without row-level `Modifier.toggleable(role = Role.Switch)` or `heightIn(min = 48.dp)`.
   - In `ShareExportSection.kt:220-232`, the `Link expires` options render standalone `RadioButton` + `Text` siblings inside an unmerged `Row` without `Modifier.selectable(role = Role.RadioButton)` and without `Modifier.selectableGroup()` on the parent column.
   - In `ReviewCards.kt:313-326` (`LandmarkRow`), the `"Sensitive (people, plates, screens)"` `Text` and `Switch` are unmerged siblings.
   - Notably, `FilePerceptionOptIn` in `ShareScreen.kt:262-277` already implements the *correct* pattern (`Modifier.heightIn(min = 48.dp).toggleable(value = include, role = Role.Switch, onValueChange = onChange)` with `Switch(checked = include, onCheckedChange = null)`).
3. **Heading pollution in `CosmosRecordRow` (`ui/components/CosmosRecords.kt:76`):**
   - `CosmosRecordRow` unconditionally attaches `Modifier.semantics { heading() }` to every row's `title`. Every saved walk row on Home, every setting toggle row in Settings, and every node header in Review is exposed as a heading, drowning out real section headings (`HomeSection`, `SettingsSection`, `EchoShellHeadingTag`).

**Fix (`R13-01`):** Introduce `CosmosToggleRow` (or row-level `toggleable(value, role = Role.Switch)` with `Switch(checked, onCheckedChange = null)` and `heightIn(min = 48.dp)`) and apply it to `SettingsScreen`, `ShareExportSection`, and `ReviewCards.LandmarkRow`; wrap `ShareExportSection` expiry rows in `Modifier.selectableGroup()` with row-level `Modifier.selectable(selected, role = Role.RadioButton)` and `RadioButton(selected, onClick = null)`; make `isHeading: Boolean = false` opt-in on `CosmosRecordRow`.

### F2 — Decorative ASCII/Unicode marks announced verbatim by screen readers (High → `R13-02`)

1. **`StateViews.kt:36-43, 77-86`:** `CosmosStateScaffold` renders `Text(text = kind.mark)` inside a 48 dp circle without `clearAndSetSemantics {}`. TalkBack announces `"White circle"` (`○` on `CosmosEmptyState`), `"Exclamation mark"` (`!` on `CosmosDeniedState`), `"i"` (on `CosmosOfflineState`), `"Multiplication sign"` (`×` on `CosmosErrorState`), and `"Check mark"` (`✓` on `CosmosSuccessState`) before the state heading.
2. **`CosmosFeedback.kt:32-37, 65`:** `CosmosBanner` renders `Text(text = kind.mark)` (`"i"`, `"!"`, `"×"`, `"✓"`) with no semantic label or `clearAndSetSemantics {}`, so an error banner reads `"Multiplication sign, <message>"`.
3. **`CosmosChips.kt:77-84, 142-144`:** `CosmosChip` and `CosmosSegmentedControl` set `role = Role.Checkbox` / `Role.RadioButton` and `this.selected = selected` on the `Surface`, *and* render `Text(text = "✓")` without `clearAndSetSemantics {}`, causing TalkBack to double-announce `"Checked, Check mark, <label>"`.
4. **`NavigateScreen.kt:326-338`:** `NavigateProgress` renders a row of `Text(text = if (leg <= legIndex) "●" else "○")` above `Text("Leg ${legIndex + 1} of $legCount")` without `clearAndSetSemantics {}` on the dot row; TalkBack reads `"Black circle, Black circle, White circle, White circle..."` on every swipe.
5. **`ReviewStoryUi.kt:235-276` & `AlignScreen.kt:158-171`:** `FilmStrip` cells set a `contentDescription` on the `Surface` (line 201) without `mergeDescendants = true` or `clearAndSetSemantics` on the child index/star/distance `Text`s; `AlignScreen.LivePreview` sets `contentDescription = "Live preview"` on the `Box` (line 158) and also renders child `Text("Live preview")` (line 161), announcing `"Live preview"` twice.

**Fix (`R13-02`):** Add `Modifier.clearAndSetSemantics {}` to the decorative glyph nodes in `StateViews.kt`, `CosmosChips.kt`, `NavigateScreen.NavigateProgress`, `ReviewStoryUi.FilmStrip` child texts (or use `clearAndSetSemantics` on the cell with its complete `contentDescription`), and `AlignScreen.LivePreview`'s inner `Text`; give `CosmosBanner`'s mark `semantics { contentDescription = kind.spokenLabel }` (`"Information"`, `"Warning"`, `"Error"`, `"Success"`).

### F3 — Pointer-only gestures in Review (`DrawOverlay`, `KeyframeInspector`, `HeroImage`, `ReviewInstrumentMap`) block non-pointer users (High → `R13-03`)

1. **`DrawOverlay` (`ui/screens/review/DrawOverlay.kt:129-186`):** Drawing a landmark crop requires a two-point drag gesture (`detectDragGestures`) on the `Canvas`. The canvas has no accessibility semantics or actions, and `"Use box"` stays disabled while `box == null` (line 219). A TalkBack, Switch Access, or keyboard user cannot create a manual landmark at all (violating WCAG 2.1.1 Keyboard and 2.5.1 Pointer Gestures). In addition, line 189 uses a raw M3 `OutlinedTextField` instead of `CosmosTextField`, losing the 48 dp Clear button and gold focus ring.
2. **`KeyframeInspector` (`ui/screens/review/ReviewStoryUi.kt:399-459`):** Zooming (`1×–5×`) relies exclusively on multi-touch pinch (`.transformable(zoom)` at line 432; content description at line 423 literally says `"Pinch to zoom"`), with no single-pointer `+` / `−` / `Reset` buttons (violating WCAG 2.5.1).
3. **`HeroImage` (`ui/screens/review/ReviewCards.kt:333-388`):** Uses `Modifier.clickable(onClick = onInspect)` without `role = Role.Button` or `onClickLabel = "Inspect full frame"`, and renders `"Tap to zoom"` as a separate sibling `Text` node rather than merging it into the image button's action label.
4. **`ReviewInstrumentMap` (`ui/screens/review/ReviewInstrument.kt:165-167, 217-235`):** Exposes a static `"Route map"` contentDescription regardless of `selectedPositionLabel` and uses `detectTapGestures` on `Canvas` with no semantic state description.

**Fix (`R13-03`):** In `DrawOverlay`, add preset crop buttons (e.g., `"Frame centre"`, `"Wide box"`) and替换 `OutlinedTextField` with `CosmosTextField`; in `KeyframeInspector`, add `"Zoom in"`, `"Zoom out"`, and `"Reset zoom"` buttons alongside `.transformable(zoom)`; in `HeroImage`, set `role = Role.Button, onClickLabel = "Zoom photo"` on `.clickable` and `clearAndSetSemantics {}` on the `"Tap to zoom"` pill; include `selectedPositionLabel` in `ReviewInstrumentMap`'s `contentDescription`.

---

## 2. Layout, fontScale 2.0 & UX findings

### F4 — `NavigateScreen` and `CameraNavigateOverlay` clip primary actions at `fontScale 2.0`, use retired Echo Lime, and bind a stray camera preview (High → `R13-04`)

1. **No scroll container on `NavigateBody` (`ui/screens/navigate/NavigateScreen.kt:220-296`) or `CameraNavigateOverlay` (`ui/components/NavigateOverlay.kt:141-167`):**
   - Both containers are fixed non-scrollable `Column(Modifier.fillMaxSize().padding(24.dp))` stacks holding: status chips + `CosmosBanner` + 200 dp `NavigateMorphArrow` + `titleLarge` instruction + 56 sp `RollingDistance` + 160 dp `InstrumentLandmarkCard` (`LookForThisCard` adds another `titleMedium` heading + 120 dp `LandmarkCard`!) + `FingerprintMatchChip` + `NavigateProgress` + 2–3 `NavigateActions` buttons.
   - Note that `LookForThisCard` (`NavigateInstrumentUi.kt:256-290`) renders **both** `MaskedCropImage` (160 dp) **and** `LandmarkCard` (which in `Readouts.kt:59-65` renders a second empty 120 dp placeholder `Surface` above the title/cue!), making `LookForThisCard` >360 dp tall by itself!
   - On a standard phone viewport (and severely at `fontScale = 2.0`), `NavigateProgress` and `NavigateActions` (`"I see it"`, `"Lost"`, `"I'm here"`) are pushed off the bottom of the screen with no scroll container — making the walker unable to confirm a landmark or declare `"Lost"` / `"I'm here"`! (`A11yMotionGateTest.kt:98` missed this because it passed a dummy `Text("body")` into `EchoShell` instead of rendering the real screen bodies at `fontScale = 2.0`.)
2. **Retired "Echo Lime" hardcoded in camera HUD (`NavigateOverlayBody.kt:46`, `NavigateOverlay.kt:110`):**
   - `EchoColors.kt:13` explicitly states: *"Echo Lime (§1: retired, no Cosmos provenance) ... deliberately absent"*. Yet `NavigateOverlayBody.kt:46` and `NavigateOverlay.kt:110` hardcode `Color(0xFFC6F432)`.
3. **Ungated `camera.Preview` when `visualAssistRequested == false` (`NavigateScreen.kt:172-179`):**
   - Lines 177–179 call `if ( viewModel.visualAssistRequested ) { ... } else { camera.Preview(modifier = Modifier) }` outside `CameraPermissionGate`! When visual assist is OFF and camera overlay is OFF, it still invokes `camera.Preview` without checking camera permission!
4. **Semantics gaps in Navigate HUD:** `CameraNavigateOverlay` (`NavigateOverlay.kt:156`) omits `Modifier.semantics { heading() }` on `instruction` (unlike `NavigateScreen.kt:258`), and `RelockRing` (`NavigateInstrumentUi.kt:232-252`) has no `contentDescription`.

**Fix (`R13-04`):** Make `NavigateBody` and `CameraNavigateOverlay` scroll-safe (scrollable upper content with pinned bottom `NavigateActions`, and remove the duplicate 120 dp placeholder inside `LandmarkCard` when `LookForThisCard` / `DefaultOverlayLandmarkCard` already renders `MaskedCropImage`); replace `Color(0xFFC6F432)` with `EchoExtras.ember` / `EchoExtras.cyan`; remove the `else { camera.Preview(...) }` branch in `NavigateScreen.kt:177-179`; add `heading()` to `CameraNavigateOverlay` instruction, `contentDescription` to `RelockRing`, and a real-screen `fontScale = 2.0` test for `NavigateScreen`.

### F5 — `RecordScreen` HUD overlaps at `fontScale 2.0`, silent tap on `"Hold to finish"`, and `CameraPermissionGate` state bypass (High → `R13-05`)

1. **Floating `TrailRibbon` and `LedgerBrackets` collide with HUD content (`ui/screens/record/RecordScreen.kt:85, 257-297`):**
   - `TrailRibbon` (`150.dp × 112.dp`) is pinned in the outer `Box` at `padding(top = 112.dp, end = 20.dp)` (`RecordScreen.kt:85, 263`), and `LedgerBrackets` places its `"Great view captured"` pill at `padding(top = 72.dp)` (`LedgerBrackets.kt:104`).
   - At `fontScale = 2.0`, the top `StatusChip` row (`elapsed`, `steps`, `spots`) and the center column (`CosmosBanner`, 112 sp `RollingDistance`, `HeadingTick`, `PrestartOverlay`) overlap `TrailRibbon` and `LedgerBrackets`.
2. **`FinishButton` gives zero feedback on a normal tap and lacks the Cosmos focus ring (`RecordScreen.kt:512-542`):**
   - `FinishButton` uses `detectTapGestures(onLongPress = { onFinish() })` with `onTap` unimplemented. When a user taps `"Hold to finish"` instead of holding for 600 ms, nothing happens — no haptic tick, no hint, no press indicator. It also omits the 2 dp `extras.gold` focus border (`onFocusChanged`) required on all Cosmos buttons (`CosmosButtons.kt:52-53`).
3. **`CameraPermissionGate` bypasses `CosmosDeniedState` and styles `NOT_DETERMINED` as an error (`platform/Permissions.kt:63-120`):**
   - `StateViews.kt:27` mandates that `CosmosDeniedState` is the only permission-denied component screens may use. `CameraPermissionGate` builds a custom `Column` without `liveRegion` or `heading()` semantics, and line 97 styles `cameraNotDeterminedMessage()` (`"ECHO has not asked for the camera yet."`) in `MaterialTheme.colorScheme.error`, contradicting line 25's own comment (*"not a denial, and must not be labelled as one"*).

**Fix (`R13-05`):** Place `TrailRibbon` inside the top HUD layout column below the status chips (and show it only during `RECORDING`) so it never collides with `PrestartOverlay`, `CosmosBanner`, or scaled chips; add a tap hint + press-hold visual feedback + gold focus ring to `FinishButton`; refactor `CameraPermissionGate` to use `CosmosDeniedState` (and neutral `onSurfaceVariant` copy when `NOT_DETERMINED`).

### F6 — Wear OS TalkBack announces `"Upwards arrow"` for every direction & round-watch layout clips (`wear/src/main/java/app/simt/echo/wear/MainActivity.kt:47-108`) (High → `R13-06`)

1. **`contentDescription = null` on an Android `TextView` does not hide its text from TalkBack (`wear/.../MainActivity.kt:47-53`):**
   - `arrowView` is a `TextView` whose text is `"↑"` (`ARROW_GLYPH`) rotated via `arrowView.rotation = state.arrowDeg.toFloat()`. Setting `contentDescription = null` on a `TextView` causes TalkBack to fall back to `getText()`, announcing **"Upwards arrow"** even when the view is rotated 90° right or 180° backward! To make `arrowView` decorative in Android Views, `importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO` is required.
2. **Raw pixel padding & no scroll/inset handling on round watch screens (`wear/.../MainActivity.kt:62-70`):**
   - `setPadding(24, 24, 24, 24)` uses raw pixels (only `12 dp` on a 2× density watch, inside the circular bezel cutoff) instead of `WindowInsets` / chin-safe dp padding, and the `LinearLayout` is not wrapped in a `ScrollView` (clipping multi-line cues at `56sp` + `16sp` + `18sp`).
3. **Generic system launcher icon (`wear/src/main/AndroidManifest.xml:14`):** Uses `@android:mipmap/sym_def_app_icon` instead of the Echo Step icon.

**Fix (`R13-06`):** Set `importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO` on `arrowView`, apply density-scaled/inset-aware padding inside a `ScrollView` in `wear/.../MainActivity.kt`, and share the Echo Step adaptive launcher icon in `wear/src/main/res/`.

---

## 3. Component, screen consistency & internationalization findings

### F7 — Non-scrollable `CosmosSheet`, missing `KeyboardOptions` on `CosmosTextField`, and `ConstellationLayout` star overlap (Medium → `R13-07`)

1. **`CosmosSheet` (`ui/components/CosmosModals.kt:105-127`) has no `verticalScroll`:** At `fontScale = 2.0` or when the IME keyboard opens over `FinishSheet` (`RecordScreen.kt:479-504`) or `ReadyConsentSheet` (`RecordScreen.kt:402-425`), sheet content overflows without scrolling.
2. **`CosmosTextField` (`ui/components/CosmosFields.kt:38-89`) does not expose `KeyboardOptions` or `KeyboardActions`:** As a result, `"Measured metres"` in `SettingsScreen.kt:105-109` opens a full text keyboard instead of `KeyboardType.Decimal`, and multi-field forms (`FinishSheet`, `NodeCard`, `LandmarkRow`) cannot set `ImeAction.Next` / `ImeAction.Done`.
3. **`ConstellationLayout` (`ui/screens/processing/ProcessingConstellationView.kt:158-191`) has no overlap avoidance:** Checkpoints with nearby normalized `(x01, y01)` coordinates place their `StarItem` labels directly on top of each other, especially at `fontScale = 2.0`.

**Fix (`R13-07`):** Add `Modifier.verticalScroll(rememberScrollState())` to `CosmosSheet`'s inner column; add `keyboardOptions: KeyboardOptions = KeyboardOptions.Default` and `keyboardActions: KeyboardActions = KeyboardActions.Default` to `CosmosTextField` and wire `KeyboardType.Decimal` in `SettingsScreen.kt:105`; add minimum vertical/horizontal separation pass in `ConstellationLayout`.

### F8 — Design-system bypasses and UX rough edges on `RouteDetailScreen`, `ArrivedScreen`, `ReceiveScreen`, and `ProcessingScreen` (Medium → `R13-08`)

1. **`RouteDetailScreen` (`ui/screens/detail/RouteDetailScreen.kt`):**
   - Lines 85–92 render the missing-route state with raw `Text` + `CosmosPrimaryButton` instead of `CosmosEmptyState` (used on `AlignScreen`, `NavigateScreen`, and `ReviewScreen`).
   - Lines 178–185 render `"Confirm delete"` as a `CosmosPrimaryButton` (ember gradient) inline instead of `CosmosDialog(destructive = true)` or `CosmosDestructiveButton` (violating `CosmosButtons.kt:245` / `SettingsScreen.kt:66-78`).
   - Lines 121–128 list landmarks as plain `Text` pairs with no `CosmosRecordRows`, no crop thumbnails, and no node distances.
2. **`ArrivedScreen` (`ui/screens/arrived/ArrivedScreen.kt:128-151`):** `TrialForm` uses four raw M3 `OutlinedTextField`s instead of `CosmosTextField` (missing Clear buttons, 48 dp targets, gold focus rings, and `KeyboardType.Number` on `Mistaken confirmations`).
3. **`ReceiveScreen` (`ui/screens/receive/ReceiveScreen.kt:183, 240`):** Line 183 renders an unlabelled `CircularProgressIndicator()` above `"Fetching…"`, and line 240 marks `MethodIndicator` (`"NFC · Tap phones"`) as a `heading()` immediately above `ImportSheet`'s route-name heading.
4. **`ProcessingScreen` (`ui/screens/processing/ProcessingScreen.kt:88-106`):** In `Uploading` (line 92), `"Cancel"` is styled as `CosmosPrimaryButton` and only calls `onDone` (leaving walk-staged uploads on the server instead of offering `"Continue without AI"` / `viewModel.decline(onDone)`).

**Fix (`R13-08`):** Use `CosmosEmptyState`, `CosmosDialog(destructive = true)`, and `CosmosRecordRows` (with crops/distances) on `RouteDetailScreen`; replace raw `OutlinedTextField`s in `ArrivedScreen.TrialForm` with `CosmosTextField`; hide the redundant spinner from semantics and remove `heading()` from `MethodIndicator` on `ReceiveScreen`; offer both `"Leave in background"` and `"Cancel & continue without AI"` clearly on `ProcessingScreen`.

### F9 — RTL mirroring, unwired Arabic typography (`rememberEchoArabicTypography`), and `EchoShell` focus/wordmark polish (Medium → `R13-09`)

1. **`EchoIcons.Back` does not mirror in RTL (`ui/icons/EchoIcons.kt:18-41`):** `ImageVector.Builder` omits `autoMirror = true`, so the Back arrow points left even in RTL locales (`android:supportsRtl="true"` is enabled in `AndroidManifest.xml:40`). Similarly, `SimtPromoBanner` (`SettingsScreen.kt:346`) hardcodes `"simt.app →"`.
2. **`rememberEchoArabicTypography()` is dead code (`ui/theme/EchoType.kt:155-158`, `EchoTheme.kt:118`):** `EchoTheme` always calls `rememberEchoTypography()` (Space Grotesk display font, which has no Arabic glyphs) instead of switching to `rememberEchoArabicTypography()` (El Messiri) when the active locale is Arabic (`ar`).
3. **`EchoShell` (`ui/shell/EchoShell.kt:225-275`):** `LaunchedEffect(config.title) { focusRequester.requestFocus() }` moves input focus to `EchoShellHeadingTag` (`Modifier.focusable()`), which has no visual focus ring when focused via keyboard/D-pad, and the `"ECHO"` wordmark text above the heading is re-read on every screen transition.

**Fix (`R13-09`):** Pass `autoMirror = true` for `EchoIcons.Back`, select `rememberEchoArabicTypography()` in `EchoTheme` when `Locale.current.language == "ar"`, make the promo arrow RTL-aware, and merge or hide the `"ECHO"` eyebrow in `EchoShell` while showing a focus indicator only on keyboard focus.

---

## 4. What is already good (verified, keep)

- **Pairing accessibility (`PairingMethodSelector.kt`, `NfcTapPanel.kt`, `PairingAccessibilityTest.kt`):** Radio-group semantics, 48 dp targets, automatic vertical stacking at `fontScale >= 1.5f`, polite live-region status updates, decorative `PhoneOutline` with `clearAndSetSemantics {}`, and camera permission isolation in NFC mode.
- **Luminous motion tier discipline (`CosmosMotion.kt`, `EchoMark.kt`, `ProcessingConstellationView.kt`, `RollingDistance.kt`):** Infinite transitions (`echoIdle`, `starTwinkle`, `nfcEcho`, `beaconPhase`) are structurally absent off `CosmosMotionTier.FULL`, and `RollingDistance` merges per-digit texts into a single `"Distance 23.4 m"` node.
- **Shared modal policy (`CosmosModals.kt`):** No feature module bypasses `CosmosDialog` / `CosmosSheet` with a raw `Dialog` or `ModalBottomSheet`.

## 5. Residual risk & evidence

- **Residual risk:** No physical Android handset or paired Wear OS watch was attached in this environment; TalkBack swipe order and round-watch rendering are verified from semantics trees and layout code, not on hardware.
- **Inspected suites:** `A11yMotionGateTest`, `PairingAccessibilityTest`, `ProcessingConstellationFontScaleTest`, `HostSettingsScreenTest`, `EchoTypographyTest`, `CosmosComponentsTest`.

Verdict: **BLOCK** — `R13-01`…`R13-06` (Critical/High) must land before UI/UX/Accessibility sign-off.
