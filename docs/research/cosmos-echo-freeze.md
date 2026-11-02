# Cosmos → ECHO adoption freeze (W8-01)

Status: **frozen** · slice W8-01 · plan `docs/plans/0002-production-ready-cosmos.md` §5.

Authority order (plan §5, snapshot §0.1): product/privacy/a11y → published spec
at `simt.space/cosmos/design` → simt-app snapshot → ECHO code. Where the snapshot
and the published spec disagree, the published value wins and the gap is recorded
in §8. `simt-app` / `simt-space` are read-only references: everything below is
copied or re-implemented into `simt-echo` as ECHO-owned code.

Sources verified 2026-11-02 (fetch dates; spec version 2.3.0, updated 2026-10-03):

- `[P-T]` `https://simt.space/cosmos/design/tokens.json` — base tokens + 4 theme adapters.
- `[P-R]` `https://simt.space/cosmos/design/rules.json` — 80 normative rules (C01–C31, P, M, W).
- `[P-C]` `https://simt.space/cosmos/design/contrast-pairs.json` — measured pairs (deepSpace, daylight, astronomy, watchAmbient).
- `[S]` `/Users/saidelimam/Documents/GitHub/simt/simt-app/docs/design/COSMOS_REDESIGN.md` — dated snapshot, header says last verified 2026-07-31 and names the published spec the single source of truth.
- `[A]` simt-app sources (read-only): `core/ui/.../theme/{Color,Shape}.kt`, `components/backgrounds/CosmosBackground.kt`, `core/ui/src/main/res/font/{space_grotesk,el_messiri_*}.ttf`, `docs/design/SPACE_GROTESK_OFL.txt`.
- `[E]` current ECHO: `shared/.../ui/theme/{EchoColors,EchoType}.kt` (lime theme, replaced by W8-02).

Legend: every frozen value ends with its provenance — `[P-T]`, `[P-R Cnn]`,
`[P-C pair]`, `[S §n]`, or **[ECHO-local]** (an ECHO application of a published
rule, not a Cosmos value). Anything unmarked is a defect in this doc.

## 1. Plan §5 decisions settled

1. **Echo Lime retired as primary accent.** `[E]` `EchoLimeDark #C6F432` /
   `EchoBgDark #0B0D10` have no Cosmos provenance. Accent grammar `[P-R C04]`:
   Ember = primary agency, Rose = social, Violet = identity, Gold =
   focus/time/precision/value, Cyan = live/location, Moss = nature, Danger +
   Success named; at most one dominant + two supporting channels per viewport.
   No lime channel exists in `[P-T]`; ECHO ships none.
2. **Deep Space dark-first** (+ Daylight Nebula light): tables §2–§3.
3. **Shapes:** plan §5 shorthand "Control 10 / Record 14 / Panel 20" uses `[S]`
   role names; `[P-T]`/`[P-R C09]` restructured the Android roles. Frozen
   Android mapping §4 uses published role names; the dp values coincide
   (10 = secondary, 14 = emphasis, 20 = record). Gap recorded in §8.1.
4. **Font:** bundle Space Grotesk (display/instrument) + El Messiri (Arabic);
   Android body is the native system font per `[P-R C07]`. OFL allows bundling
   (§6). W8-02 implements.

## 2. Deep Space token table (dark-first default)

Base anchors `[P-T color.*]`; semantic roles `[P-T adapters.deepSpace.*]`.

| Role | Hex | Job |
|---|---|---|
| background / void | `#120A14` | Page and immersive field |
| void-edge | `#1F1126` | Depth at the edge of the field |
| surface / hull | `#1D1220` | Standard opaque surface |
| raised / hull-raised | `#2A1830` | Raised or selected surface |
| highest / hull-highest | `#352040` | Highest temporary surface |
| text / ink | `#FFF8F2` | Primary text on Deep Space |
| muted | `#BBAEBB` | Supporting text |
| faint | `#9A8B9A` | Secondary metadata; check actual surface |
| dim | `#90839A` | Inactive ornament; never essential text `[P-R C05]` |
| line | `rgb(255 236 226 / 0.12)` | Divider/border; decorative only, never the essential outline |
| boundary | `#9A8B9A` | Essential control outlines on dark surfaces |
| action / ember | `#FF8C42` | Primary agency: create, continue, commit |
| accentText / ember-soft | `#FFB27C` | Supporting warm emphasis; accent-as-text role `[P-R C06]` |
| onAction | `#120A14` | Text on the primary-action fill |
| social / rose | `#FF7E8A` | Social participation and attention |
| identity / violet | `#C9A0FF` | Identity, roles, transformation |
| precision / gold | `#FFC766` | Focus, time, precision, value |
| live / cyan | `#00E5FF` | Live, connected, located |
| outdoors / moss | `#83B692` | Outdoor and natural categories |
| danger | `#FF6978` | Destructive action and failure |
| success | `#71D5A4` | Confirmed completion |
| focus / gold | `#FFC766` | Focus ring (2px + dark separation, 3px offset `[P-R C12/C19]`) |
| petrol | `#102C2D` | Map ground |
| water | `#123D42` | Map water |

Measured pairs `[P-C]` (W8-02 asserts these, it does not re-pick them):
ink-on-void 18.50, muted-on-void 9.16, faint-on-void 6.05, void-on-ember 8.42
(primary-action text passes on every action-gradient stop `[P-T gradient.action]`
`[P-R C27]`), boundary-on-void 6.05. Faint is supporting text only on a passing
surface; dim never completes a task `[P-R C05]`.

## 3. Daylight Nebula token table (light)

Semantic roles `[P-T adapters.daylight.*]`; all pairs below clear AA normal text
per `[P-C daylight]` (quoted ratios).

| Role | Hex | Measured pair |
|---|---|---|
| background | `#FFF6EC` | text-on-background 15.93 |
| surface | `#FFF9F2` | text-on-surface 16.29 |
| raised | `#F6E7DC` | text-on-raised 14.10 |
| highest | `#F3E0F7` | text-on-highest 13.64 |
| text | `#241A14` | — |
| muted | `#52443C` | muted-on-background 8.73 |
| faint | `#6B5F58` | faint-on-background 5.77 |
| line | `rgb(36 26 20 / 0.14)` | decorative only |
| boundary | `#52443C` | boundary-on-background 8.73 |
| action | `#D84E00` | onAction-on-action 4.64 (dark text, never assumed white `[P-R C06]`) |
| accentText | `#B04300` | accentText-on-background 5.38 |
| onAction | `#120A14` | — |
| social | `#B83266` | 5.33 |
| identity | `#7C4DBD` | 5.38 |
| precision / focus | `#8B5E00` | 5.31 |
| live | `#006974` | 6.00 |
| outdoors | `#356447` | 6.41 |
| danger | `#9B2432` | 7.30 |
| success | `#276442` | 6.58 |

Daylight keeps the horizon + spectrum light only; no nebula/corona/rim/starfield
`[P-R C27]`, flat background, no stars behind long copy `[S §1.2]`.

## 4. Shape / spacing tables (Android)

Published Android geometry `[P-T platformAdapters.android.radius]` `[P-R C09]`;
1 spacing unit = 1 dp, no native spacing table `[P-R C09]`.

| Role | Radius | ECHO use **[ECHO-local]** |
|---|---|---|
| control | 8 dp | Chips, fields, compact icon controls, tags |
| secondary | 10 dp | Buttons, inputs, inline affordances |
| emphasis | 14 dp | Start/record emphasis controls, inline emphasis |
| record | 20 dp | Route records, landmark cards, list rows that earn a card |
| overlay | 28 dp | Sheets, dialogs, large instrument panels |
| circle | round | Instruments, compact icon controls; never paragraph containers |

Spacing scale `[P-T space]`: 4, 8, 12, 16, 20, 24, 32, 40, 48, 64, 80, 96, 128.
Canopy: void stays continuous, 1 dp `line` borders, horizon top-light only on
dialogs/sheets/floating surfaces per variant table, top light never touches a
corner at full intensity, flat fallback without blur / in forced-colors
`[S §3.1]` `[P-R C10/C27]`. Touch targets ≥ 48×48 dp `[P-R C19]`; record
actions keep 88 dp Start and ≥260 dp QR surface **[ECHO-local]** (carried from
`[E]` `EchoRecordButton`/`EchoQrMin`, consistent with C19/C25).

## 5. Type table (Android)

Roles + mobile sizes `[P-R C08]`; families `[P-T font]` `[P-R C07]`.

| Role | Android size / leading | ECHO use **[ECHO-local]** |
|---|---|---|
| Narrative display | 40–48 sp / 1.1, entry only | First-run Ignition title only |
| Primary instrument | 48–64 sp / 1.1 | Record distance, Navigate arrow readout, bearing |
| Page title | 28–32 sp / 1.2 | Screen titles |
| Section title | 20–24 sp / 1.3 | Sheet titles, settings sections |
| Body | 16 sp / 1.5 | Default reading |
| Supporting | 14 sp / 1.45 | Help, metadata |
| Control label | 14–16 sp / 1.3 | Buttons, chips, compact status |

Families: Space Grotesk (Latin titles + instrumentation, `tnum`), native system
body on Android (Source Sans 3 is web-only), El Messiri for every Arabic role
`[P-R C07]`. Zero tracking default (no negative tracking — `[S §15.2.3]` calls
the current simt-app negative display tracking migration debt); sentence case;
tabular numerals + visible adjacent units for every distance/bearing/time readout
`[P-R C08]` `[S §2.3]`; verify at fontScale 2.0 `[P-R C08]`. Current `[E]`
`EchoType.kt` (4 styles, `FontFamily.Default`, 64 sp display) is replaced whole
by W8-02 — none of it is frozen here.

## 6. Font decision (bundled, OFL verified)

`[A]` `SPACE_GROTESK_OFL.txt` is SIL OFL 1.1, which permits bundling/embedding
with software; `[A]` `res/font/space_grotesk.ttf` + `el_messiri_{regular,medium,
semibold,bold}.ttf` already ship in simt-app, and `[S §15.1]` confirms Android
implements the Space Grotesk + El Messiri families. **Decision:** W8-02 copies
the OFL-licensed `space_grotesk.ttf` and the El Messiri weights into simt-echo
as ECHO-owned assets (with the OFL text), wired per `[P-R C07]` (bundle fonts,
declare fallbacks, reserve fallback geometry). Source Sans 3 is **not** bundled:
Android body is the system font. No third-party font requests `[P-R C07]`.

## 7. Motion table + accent-channel assignments per ECHO surface

Durations shared across parts `[P-T motion]`: feedback 150 ms, state 300 ms,
context 500 ms. Android easings `[P-T platformAdapters.android.easing]`:
standard (0.2, 0, 0.2, 1), enter (0.05, 0.7, 0.1, 1), exit (0.3, 0, 0.8, 0.15),
transform (0.2, 0, 0, 1). Tiers Full / Reduced / Static `[P-R C17]`: Reduced
removes travel + ambient loops (opacity ≤150 ms); Static settles immediately
with identical outcome; sync, power, thermal, visibility can only lower the tier.
State changes name a motif `[P-R C31]`; ECHO-needed motifs: sheetRise (Finish/
consent/import sheets), listReveal (route records), targetAcquired (Align lock),
horizonSweep (processing progress), dataStale (σ/age labels), routeChange
(screen transitions with W8-05), spineAdvance (Record→Review step). Gold focus
is never glow-only; status never by hue alone `[P-R C04/C19]`.

Accent assignments **[ECHO-local]** (applications of `[P-R C04/C12]`, one
dominant Ember action per task region, ≤1 dominant + 2 supporting per viewport):

| Channel | ECHO surfaces only |
|---|---|
| Ember (primary agency) | The one primary action per region: Record/Start, Finish-sheet Continue, Review Use/Save, Share offer start, Receive import, Align→Navigate begin, Arrived Done. Action-gradient fill + action shadow + void label `[P-T gradient.action, shadow.action]` `[P-R C12]`. |
| Cyan (live/location-lock ONLY) | GPS/location lock, Align reticle live state, Navigate live σ chip, Receive scanner live frame. Never a default secondary action. |
| Gold (focus/precision) | Keyboard/D-pad focus ring everywhere; bearing beam + tabular instrument numerals; Review minimap route path (`[S §5.3]` route/guidance gold); time/value readouts. |
| Rose (secondary/social) | Received-route provenance, Share social attention, secondary active state. Never generic errors. |
| Violet (identity) | Reserved: no ECHO roles feature in MVP v1; only AI-provenance footnote accents (W8-08) where a second supporting channel is needed. |
| Moss (natural/stable) | Minimap parks/natural land only. Never a generic success replacement. |
| Danger / Success | Named channels only: destructive confirms (Delete, Delete-all) and confirmed completions (Saved, Arrived), always with icon + text, never hue-only. |

Icon family **[ECHO-local]**: per `[P-R C25]` (one established native library;
20–24 dp marks, 48 dp targets) ECHO uses the Compose Material icon set; custom
marks only for SIMT concepts without a familiar equivalent. Current `[E]` tree
has zero icons (plan M1) — W8-04+ adds them.

## 8. Spec-vs-snapshot gaps (published spec won each)

1. **Shape role names** (§1/§4): `[S §3.2]` Android Control 10 / Record 14 /
   Panel 20 (+Micro 8, Immersive 28, Pill) vs `[P-T]`+`[P-R C09]` control 8 /
   secondary 10 / emphasis 14 / record 20 / overlay 28 / circle round. Values
   coincide under new names; `[A]` `Shape.kt` (8/10/14/20/28) already matches
   the published dp values with snapshot-era names. Freeze uses published names.
2. **Type roles**: `[S §2.2]` Hero/Display/H1/H2/Title/Body/Supporting/Label/
   Micro vs `[P-R C08]` Narrative display / Primary instrument / Page title /
   Section title / Body / Supporting / Control label. Freeze uses published
   roles; Android sp mappings in §5 are ECHO-local readings of C08.
3. **Layers**: `[S §3.5]` web z-numbers (1/3/20/50/100/200) vs `[P-T layer]`
   field 0 / chrome 10 / context 20 / scrim 30 / modal 40 / feedback 50 /
   skip 100. Freeze uses the token registry; ECHO maps them to
   drawing/elevation order, never copies web numbers `[S §3.5]`.
4. **Snapshot-only extras, NOT frozen**: `line-strong`, Ember/Rose/Violet
   `*Core`/`On*Core` containers (`[S §1.1]`, in `[A]` `Color.kt` as
   `EmberCore #7A3409` etc.), Daylight `*Container` fills (`[S §1.2]`,
   `[A]` `Dawn*Container`). No published provenance; `[S §15.2.1/15.2.6]`
   already flags the aliases as debt. W8-02 must not copy them; a container
   need is a new-slice proposal, not a silent alias.
5. **Snapshot-only timings, NOT frozen**: `[S §4.5]` dialog/panel ms values
   (260/180/220 …) are not in `[P-T]`/`[P-R]`; durations + C31 motifs (§7)
   govern. W8-05 owns transitions.
6. **Contrast pairs live outside tokens**: per `[P-R C05/C22]` measurements are
   published once in `[P-C]` and never embedded in a token payload — W8-02
   fetches `[P-C]` (as this slice did) instead of inventing pairs.

## 9. Explicit non-adoptions (deliberate, not oversights)

1. **Astronomy red mode.** Exists in spec (`[P-T adapters.astronomy]`,
   `[P-R C06]`, measured in `[P-C]`). Not in MVP v1: no ECHO surface is a
   dark-adaptation instrument (plan §5). Revisit only with a night-field-work
   surface and its own slice.
2. **Orbital dock.** `[S §7.5]` allows it only for exactly four peer
   destinations + Create ("more or fewer than four destinations invalidates
   this pattern"). ECHO has 2 persistent primary actions (Record, Receive) +
   Settings (plan W8-05) — wrong count. ECHO uses the tool-deck/top-chrome
   grammar; W8-05 freezes it.
3. **Disk-distributed starfield.** `[A]` `CosmosBackground.kt:228-233`
   (`sqrt(random) * 0.78` disk placement) contradicts the published algorithm;
   `[S §5.1]` admits this is convergence debt. W8-03 implements the spec:
   outer-20% edge band (centre 60%×60% clear), `round(area/7500)` clamped
   24–160 screens / `round(area/10000)` clamped 12–72 dialogs, bucket 160,
   ≥70% ink, ≤10% any one accent, ≤3 spikes, 90–160 s drift ≤1.5% of short
   edge, one cached layer, absent from the accessibility tree
   `[P-T starfield]` `[P-R C11]`. Low power draws 40%.
4. **Legacy simt-app aliases.** `CosmicDust #9A8294` (≠ boundary `#9A8B9A`),
   `WarmStarWhite #FFFFF4E8` (≠ ink `#FFF8F2`), `Vibrant*`/`TrailGreen`/
   `GoldenAmber` role accents (`[A]` `Color.kt:31-60`), non-canonical gold
   focus/favorite mappings (`[S §15.2.6]`), negative display tracking
   (`[S §15.2.3]`). None enters ECHO.

## 10. Handoff to W8-02 (implements this freeze in `ui/theme/`)

W8-02 scope: semantic color schemes (Deep Space + Daylight Nebula) from §2–§3,
`Shapes` from §4, full type scale from §5, bundled fonts from §6, contrast unit
tests asserting the `[P-C]` pairs quoted in §2–§3 (4.5:1 text / 3:1
large+boundary) for both schemes, extending the W4-04 scheme tests. No screen
work in this slice; no screen work was done in W8-01 either (docs-only —
`docs/research/cosmos-echo-freeze.md` is the sole artifact).
