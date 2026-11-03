# Ego-motion fit — W16-02

Evidence: **sim and host unit/allocation only**, not device accuracy or timing.

The fixed-storage fit estimates radial expansion and the focus of expansion
in focal-normalised coordinates. Before least-squares runs,
`TranslationConsensus` intersects pairwise translation-flow lines to locate a
depth-free focus of expansion and identify the supporting block set. When the
supporting per-block expansion scales agree (`sharedDepth`), consensus masks
outlier blocks before the initial weighted solve and three Huber IRLS passes.
When depth varies across an oblique or mixed-depth scene, the intersection
focus and median expansion are published directly without allowing the
single-depth yaw nuisance term to shift `foeX` or `travelOffsetRad`. Tied rival
direction clusters supported by distinct block sets mark the fit ambiguous,
keep `hasFoe = false` and cap `confidence01` at `0.49`. Texture scoring is
shared with `VisualUsability`, not copied.

`VisualEgo` is reused. Callers must copy primitive fields before retaining
them; absent focus coordinates and travel angle are primitive NaNs, with
`hasFoe = false`. Near-zero expansion does not fabricate a travel direction.
Radial confidence does **not** validate yaw. `yawSigmaRadPerSec` is computed
from the final weighted yaw profile, separately from radial confidence;
`yawResolved` means statistical distinction from zero only. Unresolved yaw
is retained with uncertainty, never substituted by a trusted zero.

## Executed gate

```
./gradlew --no-configuration-cache :shared:testAndroidHostTest --tests app.simt.echo.capture.visual.EgoMotionFitTest --tests app.simt.echo.capture.visual.EgoMotionFitAllocationTest --tests app.simt.echo.capture.visual.VisualUsabilityTest
```

The three classes pass 24 tests (`EgoMotionFitTest` 16,
`EgoMotionFitAllocationTest` 4, `VisualUsabilityTest` 4) with no failures or
skips. Rendered forward expansion meets the 8% bound at both 8 Hz Record and
2 Hz Navigate spacings; ±10° travel offsets meet the 2° bound both for
uniform-depth diagonal travel and for actual rotated `SimPose` camera holds on
mixed-depth corridor walls (plus pitched hold recovery). All 495 four-of-twelve
coherent-outlier placements across three velocities never produce >15%
expansion error with `confidence01 >= 0.5`, and blocks `2,3,10,11`, all six
contiguous 2×2 billboard placements, and rendered moving-billboard vectors
retain the 15% expansion bound with `confidence01 > 0.5`. Contradictory split
directions stay absent and below 0.5 confidence. Blank/flat, insufficient
support, standing, sideways, singular and malformed inputs have explicit
coverage.

Four warmed allocation probes cover joint, robust, standing and sideways
fits, including result consumption. All measure zero bytes. The travel-angle
getter is exercised before measurement, avoiding a cold `atan` initialization
inside an otherwise warmed fit probe; no allocation threshold was relaxed.
iOS target compiles were out of scope for the Review 0009 repair.

Limits: billboard and 495-placement sweeps are deterministic rendered/vector
fixtures, not a field crowd result. When an oblique wall produces mixed depths,
`TranslationConsensus` recovers the travel direction while leaving
`yawResolved = false`; W16-03 still owns heading-fusion policy and ten-minute
drift validation.
