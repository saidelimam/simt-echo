# Visual flow — W16-01

Evidence: **sim and host unit/allocation only**, not device accuracy or timing.

## Kernel and coordinates

- An owned 240×180 plane and ping-ponged 120×90 / 60×45 levels never retain
  Frame Tap buffers. Twelve textured 16×16 blocks use plain integer SAD.
- Each block is inverse-warped by the finite pinhole rotation (`PinholeRotation`,
  Rodrigues rotation on normalised camera rays) centred on its rotated position.
  The initial integer SAD search uses a ±3 px fine window. When that window
  lacks an interior minimum or reaches its inner margin, bounded radial expansion
  steps (up to seven 4% steps) search along the block's radial ray to cover
  0.9–1.1 m/s walking at 8 Hz Record and 2 Hz Navigate spacings. It does not
  claim the later 1 Hz straight-walk duty.
- Global gain/offset normalisation precedes matching. Squared photometric
  costs provide the parabolic sub-pixel fit, with a final local refinement
  in the original 240×180 plane that scores both the 32×32 patch and its
  central 16×16 window (`inset = 8`) from a single warp. Corner blocks with
  oblique depth variation select the central 16×16 window when the 32×32 mean
  is biased, and blocks that clip the frame border or straddle a surface
  crease (`|e32 - e16| > 0.35` base px under expansion) are dropped.
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
./gradlew --no-configuration-cache :shared:testAndroidHostTest --tests app.simt.echo.capture.visual.VisualFlowTest --tests app.simt.echo.capture.visual.VisualFlowAllocationTest --tests app.simt.echo.sim.SimPixelIntegrationTest --tests app.simt.echo.sim.SimWorldTest --tests app.simt.echo.capture.visual.VisualUsabilityTest :shared:simWorldFastBaseline
```

The five focused classes pass 31 tests (`VisualFlowTest` 14,
`VisualFlowAllocationTest` 2, `SimPixelIntegrationTest` 2, `SimWorldTest` 9,
`VisualUsabilityTest` 4) with no failures or skips. Flow checks retain the
0.2 base-pixel per-block bound across 8 Hz Record and 2 Hz Navigate walking
speeds on near and far corridor surfaces, and the 0.15 base-pixel rotational
RMS bound across the full ±0.15 rad yaw/pitch and combined-rotation domain
(with explicit bounded residual checks under ±15% focal approximations). Warm
host allocation probes pass at zero bytes for normal and large-prior dispatch.
The fast seeded navigation baseline passes with `accuracy-baseline.json`
unchanged; iOS compilation tasks were out of scope for the Review 0009 repair.

Limits: these are controlled static-wall oracle fixtures, not a field result;
the pinhole model is not a calibrated radial-distortion or rolling-shutter
model, and corridor seam blocks at 2 Hz are dropped rather than modeled with a
two-plane warp. No SWAR alternative was introduced without its required
mid-device evidence. W19-03 owns absolute device timing.

## Combined pipeline host cost & allocation — W16-05

`VisualAllocationHostTest`, `VisualImuHistoryAllocationTest` and
`VisualCostBenchmarkTest` (`./gradlew :shared:visualCostBenchmark`) measure the
full `FrameTapMux` → `VisualMotionConsumer` → `VisualImuHistory` /
`VisualUsability` → `VisualFlow` → `EgoMotionFit` → `VisualEgoRing.publish` /
`drain` path after warm-up:

- **Allocation:** `0` bytes per frame after warm-up across both the IMU
  bracketed interpolation/pose-mode history and the full textured consumer
  dispatch + ring drain path (`ThreadMXBean.getThreadAllocatedBytes`).
- **Relative host cost (9 paired 512-frame batches after 5,000 warm frames):**
  flow-only median `439.7 µs/frame` (`439,733.6 ns`), combined pipeline median
  `491.8 µs/frame` (`491,791.5 ns`), median paired ratio **`1.118×`** (+11.8%
  for mux dispatch, IMU pose read, usability scoring, ego-motion fit and SPSC
  ring publish/drain). Host-relative evidence only; the ≤ 0.8 ms median /
  ≤ 2.0 ms p99 device gate remains owned by W19-03.
