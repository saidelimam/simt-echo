# Luminous design freeze (W10-07)

Status: **frozen** · slice W10-07 · plan `docs/plans/0003-echo-luminous.md` §0, §8.

Luminous is ECHO 2.0's design language. It sits **on top of** the Cosmos
freeze (`docs/research/cosmos-echo-freeze.md`, cited *Cosmos §n*): every
token, contrast pair, focus rule and "status never by hue alone" rule there
is inherited unchanged. Luminous adds a brand mark, motion physics, named
motifs, a shader atmosphere, and a haptic + sonic grammar. Where it departs
from Cosmos, §9 says so and why.

Legend: **[inherited]** = Cosmos value, unchanged · **[Luminous]** = new
here. Anything unmarked is a defect in this doc.

## 1. Principles (binding on every W13 slice)

1. **Honest light.** Every glow, ripple, aura, particle and counter is
   driven by a named signal (step event, posterior σ, byte progress, job
   event, touch). If a motif has no signal it does not move. [Luminous]
2. **Echo the body.** Rhythm comes from steps and heading, not timers. The
   only timer-driven loops are the idle atmosphere and the idle brand echo,
   and both stop below FULL tier. [Luminous]
3. **Glance, don't read.** Walking surfaces read in 300 ms: one edge, one
   arrow, one number. [Luminous]
4. **Calm by default, bright at decisions.** Ambient motion is quiet;
   amplitude peaks only at turns, confirmations and arrival. [Luminous]
5. **Copy is sacred ground.** Nothing animates behind body copy; shaders
   and stars live in the horizon band and the edges. [Luminous, extends
   Cosmos §3 "no stars behind long copy"]

## 2. Brand mark — "Echo Step"

A single footprint (forefoot + heel, tilted 18° clockwise, like a step
taken toward the upper right) whose outline **ripples outward in three
contour echoes**, like sonar or a topographic line around the place a
person once stood. It is the product in one picture: *walk it once — the
step echoes for the next person.* [Luminous]

| Part | Geometry (108-unit grid, centre 54,54) | Colour |
|---|---|---|
| Forefoot | ellipse rx 8.6, ry 12.5, centred (0, −9) in mark space | Sole gradient ink `#FFF8F2` → gold `#FFC766` [inherited hues] |
| Heel | ellipse rx 6.2, ry 7.2, centred (−1.2, 13.5) | same gradient |
| Echo 1 (inner) | contour band between offsets 2.5 and 4.5 | Ember `#FF8C42` |
| Echo 2 | contour band between offsets 7.25 and 9.5 | Rose `#FF7E8A` @ ~75 % |
| Echo 3 (outer) | contour band between offsets 12.5 and 15 | Violet `#C9A0FF` @ ~25 % |
| Field | radial gradient `#3B1A47` (upper-left) → hull `#1D1220` → void `#120A14` | [inherited void/hull + Luminous glow] |

Rules: the mark is scaled 0.88 into the 66 dp adaptive-icon safe zone; the
outer echo may fade into the mask edge, never the foot. Monochrome (themed
icon) keeps foot + echoes as one alpha channel. Below 24 dp only Echo 1 is
drawn; the top-chrome wordmark is a 28 dp miniature of the launcher icon
(void disc + three echoes). The mark never sits on Daylight without its
own void disc. The source of record is `docs/design/echo-step.svg`; launcher PNGs
are rendered from it (`docs/design/README.md`).

**Animated forms.** *Splash*: the foot is present from the first frame; three outline echoes
expand from it and fade (one breath, ≤ 1000 ms). *Idle echo* (Home hero,
FULL tier only): an echo leaves the foot every 2.4 s and fades by 1.8×
scale. REDUCED: one static set of echoes. STATIC: foot + Echo 1.

## 3. Motion physics (springs replace tweens for spatial motion)

| Token | Use | dampingRatio | stiffness |
|---|---|---|---|
| `spatialFast` | Chips, toggles, small controls | 0.90 | 1400 |
| `spatialDefault` | Cards, sheets, record rows, deck | 0.85 | 700 |
| `spatialSlow` | Full-screen transitions, shared elements | 0.82 | 320 |
| `spatialExpressive` | Blooms, arrival, sigil reveal | 0.55 | 420 |
| `effectsFast` | Opacity/colour of small elements | 1.00 | 1600 |
| `effectsDefault` | Opacity/colour of panels | 1.00 | 800 |

Durations are inherited for non-spatial fades (Cosmos §7: 150/300/500 ms).
Springs retarget from current velocity, so interrupted motion never jumps.
Screens never hard-code a spring; they name a token. [Luminous]

## 4. Signature motifs

Each motif names its **signal**, its FULL form, and what REDUCED and STATIC
do. REDUCED removes travel and loops (opacity ≤ 150 ms); STATIC settles
immediately with the same information (Cosmos C17, inherited).

| Motif | Signal | FULL | REDUCED | STATIC |
|---|---|---|---|---|
| **ripple** | Step event, confirmation, primary-command press (touch point) | Ring from origin to 0.6 × short edge in 900 ms, alpha 0.45 → 0, ≤ 4 alive, edge band only on Record | None — the command's own pressed state carries it | None (text/haptic carry it) |
| **echo** | Brand idle (Home hero) | An outline echo leaves the mark every 2.4 s | Static three echoes | Foot + Echo 1 |
| **bloom** | Node passed, arrival, sigil reveal | Scale 0 → 1 on `spatialExpressive` + 6 short rays fading | Fade in | Final state |
| **trace** | Path shown for the first time (sigil, unfold, mask) | Draw along length, 600–1200 ms by length, Transform easing | Whole path fades in, 150 ms | Whole path |
| **unfold** | Route received | trace of the route + node blooms staggered 60 ms | Fade | Final |
| **constellation** | Job events per checkpoint | Star per checkpoint: queued dim, scouting twinkle, grounded bright, skipped hollow (shape + label) | No twinkle | No twinkle |
| **count** | A number changes (distance, stats) | Rolling tabular digits, `spatialFast` per digit | Cross-fade | Snap |
| **morph** | Arrow phase/turn change | Shape morph straight → bend → U → check | Cross-fade shapes | Snap |
| **aura** | Posterior σ | Ring radius and softness ∝ σ; dashed + label when UNCERTAIN | Same, no easing | Same |
| **periphery** | ≤ 8 m to a turn | Turning-side edge glow, intensity ∝ 1 − d/8 | Same, no pulse | Same (static intensity) |

## 5. Echo field (atmosphere)

The Cosmos background becomes a living field [Luminous]:

- **Shader** (API 33+, AGSL `RuntimeShader`): two-octave domain-warped
  aurora in the scheme's ember/rose/violet, confined to a **horizon band**
  (top 30 % of the screen) and a faint **floor glow** (bottom deck area);
  the body band stays the flat void. The horizon band is 30 % of the
  screen height, so curtains end above the first line of screen copy. Uniforms: `time` (FULL only),
  `tilt` (device tilt, ±12 dp parallax, low-passed), `ripples[4]`
  (centre, age) so the ripple motif bends the aurora where it passes.
- **Stars** stay the Cosmos edge band (inherited algorithm), now offset by
  tilt parallax.
- **Fallback**: API 29–32 and STATIC tier draw the inherited Canvas
  background with the same horizon gradient, no animation.
- **Daylight**: a "dawn" horizon — the same band blended ≤ 55 % toward pastel stops
  `#FFD9B8 → #F3E0F7`, no stars, no floor glow, body flat (§9 D1).
- **Cost**: shader created once, uniforms only per frame, ≤ 2 ms GPU on
  the mid device (W14-01 measures).

## 6. Route sigils

Every route gets a glyph generated from its own path [Luminous]:
resample `path` to 64 points, rotate so start → end points up, normalise
into a unit circle, draw as a tapered ribbon (thin at start, full at the
destination) with a dot per TURN/DESTINATION node. Accent comes from a
stable hash of `routeId` over {Ember, Rose, Violet, Gold} — **never Cyan**
(reserved for live). Same route → same sigil on both phones. Sigils are
decorative to TalkBack and never the only identifier of a route.

## 7. Haptic grammar

`VibrationEffect.Composition` primitives on API 30+ when supported, the V2
§8.4 waveforms otherwise (inherited fallback).

| Cue | Primitives | Feel |
|---|---|---|
| TICK | `TICK` 0.6 | A nudge |
| CONFIRM | `CLICK` 0.8 → 60 ms → `CLICK` 0.5 | "Got it" |
| LEFT | `QUICK_RISE` 0.6 → `CLICK` 1.0 | Rising, then a knock |
| RIGHT | `CLICK` 0.8 → 60 ms → `CLICK` 0.8 → `QUICK_RISE` 0.6 | Knock-knock, rising |
| U_TURN | `SLOW_RISE` 0.8 → `THUD` 1.0 | Heavy turn-around |
| WARN | `THUD` 0.7 → 120 ms → `THUD` 0.7 | Something is off |
| ARRIVE | `QUICK_RISE` 0.5 → `CLICK` 0.7 → 80 ms → `SPIN` 0.6 | A small bloom |
| Heartbeat | `TICK` 0.4, interval 1.2 s → 0.4 s over the last 8 m to a turn | Quickening |

## 8. Sonic grammar (opt-in, headphones or explicit opt-in only)

Synthesised 80 ms sine earcons, no assets. Pitch: confirm C6, turn E6,
warn A4, arrive C6–E6–G6 arpeggio (60 ms each). Stereo pan = sin(arrow
angle). Never plays over TalkBack speech; volume follows the media
stream; silent while walking straight. [Luminous]

## 9. Deviations from Cosmos (deliberate)

| # | Cosmos rule | Luminous | Why |
|---|---|---|---|
| D1 | Daylight is flat: no nebula/corona [P-R C27] | A low-alpha dawn horizon band, top only, never behind copy | Daylight was the one flat, lifeless surface; the band keeps hierarchy and contrast (copy area unchanged) |
| D2 | Nebula fields only on Home/Gate | Echo field on every shell surface, horizon band only | The atmosphere is the product's identity; confined to non-copy bands |
| D3 | Ambient motion limited to the field | Ripples on primary presses and steps | Each is driven by a real event (§1.1), and stops below FULL |
| D4 | Wordmark is plain text | Echo Step mark + "ECHO" label | Brand recognition; label kept for semantics |
| D5 | Cosmos §7 easings for all motion | Springs (§3) for spatial motion; easings kept for fades | Interruptible, velocity-preserving motion |

Not changed: every contrast pair, focus ring, 48 dp target, tabular
numerals, accent-channel assignment (Cyan stays live-only), status never by
hue alone, Static tier completeness.

## 10. V-review checklist (every W13 slice)

1. Hierarchy reads in one glance in both schemes, at fontScale 1.0 and 2.0.
2. Every moving element names its §4 signal; nothing loops below FULL.
3. No motion or shader behind body copy.
4. One dominant Ember action per region (inherited).
5. Frames captured mid-motion and settled, with timestamps and build SHA,
   under `docs/screenshots/<slice>/`.
