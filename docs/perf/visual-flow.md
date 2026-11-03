# Visual flow — W16-01

Evidence: **sim and host unit/allocation only**, not device accuracy or timing.

## Kernel and coordinates

- An owned 240×180 plane and ping-ponged 120×90 / 60×45 levels never retain
  Frame Tap buffers. Twelve textured 16×16 blocks use plain integer SAD.
- The search is centred on the pinhole rotational prediction; coarse search
  is used only when that prediction exceeds three base-level pixels.
- Global gain/offset normalisation precedes matching. Squared photometric
  costs provide the parabolic sub-pixel fit, with a final local refinement
  in the original 240×180 plane. Removing that refinement failed six oracle
  cases at the unchanged bounds, so it is necessary, not a dead alternative.
- Compact output positions and translation-only displacements are divided
  by focal length. Multiply by `BlockFlow.fPx` for 120×90-level pixels.
  Displacements are per frame; `deltaSeconds` converts them to rates.
- Untrusted clocks reset pairing. Nonpositive/overlong time intervals,
  changed intrinsics and invalid rotation deltas do not produce a pair.
  Boundary minima and blocks untextured in either frame are excluded. A blank
  target requires two subsequent textured frames before correspondence recovers.

## Image-formation correction

Nearest-cell point sampling lost fractional edge motion before estimation.
The independent `SimPixelIntegrationTest` reproduced that failure against
the frozen renderer. The scoped W16-01 exception integrates the same seeded
material over an 8×8 pixel footprint before sensor noise, exposure and gamma.
Analytic edge-coverage and 8-versus-16-sample convergence checks are independent
of the flow estimator. Geometry, oracle projection and baseline JSON are unchanged.

## Executed gate

```
./gradlew :shared:testAndroidHostTest --tests app.simt.echo.capture.visual.VisualFlowTest --tests app.simt.echo.capture.visual.VisualFlowAllocationTest --tests app.simt.echo.sim.SimPixelIntegrationTest --tests app.simt.echo.sim.SimWorldTest --tests app.simt.echo.capture.visual.VisualUsabilityTest :shared:simWorldFastBaseline :shared:compileKotlinIosArm64 :shared:compileKotlinIosSimulatorArm64
```

The five focused classes pass 28 tests with no failures or skips. Flow checks
retain the 0.2 base-pixel per-block bound and 0.15 base-pixel rotational RMS
bound, including small rotations with ±15% focal approximations. Warm host
allocation probes pass at zero bytes for normal and large-prior dispatch.
The fast seeded navigation baseline and both iOS compiles pass.

Limits: these are controlled static-wall oracle fixtures, not a field result;
the small-angle model is not a calibrated lens/rolling-shutter model. No SWAR
alternative was introduced without its required mid-device evidence. W16-05
owns combined host cost measurement; W19-03 owns absolute device timing.
