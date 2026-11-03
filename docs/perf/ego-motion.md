# Ego-motion fit — W16-02

Evidence: **sim and host unit/allocation only**, not device accuracy or timing.

The fixed-storage fit estimates radial expansion and the focus of expansion
in focal-normalised coordinates. A resolved residual-yaw component can join
the fit; an unresolved nuisance must not amplify travel-angle noise. Three
Huber reweighting passes follow the initial weighted least-squares solve.
Texture scoring is shared with `VisualUsability`, not copied.

`VisualEgo` is reused. Callers must copy primitive fields before retaining
them; absent focus coordinates and travel angle are primitive NaNs, with
`hasFoe = false`. Near-zero expansion does not fabricate a travel direction.
Radial confidence does **not** validate yaw. `yawSigmaRadPerSec` is computed
from the final weighted yaw profile, separately from radial confidence;
`yawResolved` means statistical distinction from zero only. Unresolved yaw
is retained with uncertainty, never substituted by a trusted zero.

## Executed gate

```
./gradlew :shared:testAndroidHostTest --tests app.simt.echo.capture.visual.EgoMotionFitTest --tests app.simt.echo.capture.visual.EgoMotionFitAllocationTest --tests app.simt.echo.capture.visual.VisualUsabilityTest :shared:compileKotlinIosArm64 :shared:compileKotlinIosSimulatorArm64
```

The three classes pass 20 tests with no failures or skips. Rendered forward
expansion meets the 8% bound; ±10° travel offsets meet the 2° bound. Replacing
four of twelve measured vectors by coherent camera-relative moving-billboard
vectors preserves the 15% expansion bound, at least six static inliers and
confidence above 0.5. Blank/flat, insufficient support, standing, sideways,
singular and malformed inputs have explicit coverage.

Four warmed allocation probes cover joint, robust, standing and sideways
fits, including result consumption. All measure zero bytes. The travel-angle
getter is exercised before measurement, avoiding a cold `atan` initialization
inside an otherwise warmed fit probe; no allocation threshold was relaxed.
Both iOS target compiles pass.

Limits: rendered hold-offset cases use diagonal travel with a wall-normal
camera, so their depth is uniform. They do not prove the radial model for
arbitrary slanted walls or mixed-depth scenes. Billboard replacements are
explicit vector-level adversarial fixtures, not a rendered crowd result.
Heading fusion and Android registration belong to subsequent slices.

An oblique plane can exactly mimic yaw: translation rates
`rx = d*x + g*x*x`, `ry = d*y + g*x*y` fit the joint model with
`yaw = -g` and `foeX = g/d`, even though true yaw and FOE are zero.
Small residuals and statistical significance cannot distinguish those
explanations. W16-03 must impose an independent physical-validity policy
before using this witness for heading; neither `confidence01` nor
`yawResolved` alone is that policy.
