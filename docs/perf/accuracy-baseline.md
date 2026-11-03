# Simulation accuracy baseline

`accuracy-baseline.json` records seeded simulation evidence for the existing
`LINEAR` and `GHOST` navigation engines. It is a regression baseline, not a
device-accuracy claim and not evidence for the visual estimator planned in a
later slice.

## Reproduction

Run the fast gate on every relevant change:

```text
./gradlew :shared:simWorldFastBaseline
```

The existing CI `Shared host tests` step runs
`./gradlew :shared:testAndroidHostTest`, which includes the same baseline test
with its default 100-walk count.

The manual 500-walk gate is:

```text
./gradlew :shared:simWorldFullBaseline
```

Regenerate both sample-count baselines only when intentionally accepting new
measurements:

```text
./gradlew :shared:simWorldRecordBaseline
```

The recorder executes 500 seeded walks per scenario and engine, and records the
first 100 as the fast subset. It writes the resulting evidence to
`docs/perf/accuracy-baseline.json`; review the JSON and this methodology together.

## Simulation inputs

- Four bounded scenes are included: `CORRIDOR`, `JUNCTION`, `OPEN_HALL`, and
  `STAIRWELL_LANDING`. Their procedural luma frames use the production
  `FrameSample` dimensions and decimation, deterministic textured planes, a
  moving occluder, phone-specific exposure/FOV, and optional rotational blur.
- `SimWalk` uses a 20 Hz clock, seeded cadence (1.6–2.2 Hz), walking speed
  (0.9–1.1 m/s), stride scale (0.88–1.12), yaw noise/drift, and magnetic and
  pressure readings. Each sample also synthesizes a `HandsetWitness` derived
  from the same camera pose (hold offset, yaw/pitch wobble, gyro step deltas,
  and gravity projection) after the legacy `MotionUpdate` RNG draws so the
  committed sensor baseline stays unchanged. Each trial runs through the
  `WalkEstimator` seam (defaulting to the real `Navigator` engine) using only
  generated `MotionUpdate`s; truth is retained separately for scoring, and
  negative-control tests run seeded walks through a faulty estimator.
- Arrival and uncertainty trials score the first true-position sample at or
  beyond 45 m on a 60 m straight navigation route. The remaining 15 m is padding
  so route-end clamping cannot hide baseline error. Every trial uses the same
  checkpoint and quantity, regardless of whether an arrival prompt appeared.
  Relock trials use the existing
  multi-turn `Fixtures.L_ROUTE` and inject `NavInput.Lost` at 15 m true distance.
- Seeds are reproducible from `seedStart`, scenario ordinal, and trial index.
  Both engines receive the same sensor walk for each `(scenario, trial)`; only
  GHOST consumes its separate filter RNG. The same first 100 seeds are used by
  the 100- and 500-walk runs.

## Metric definitions and gate

- `arrivalErrorP95M`: nearest-rank p95 of `|estimated position − true position|`
  at the 45 m pre-arrival checkpoint. This is a symmetric, uncensored position
  error, not the distance remaining when the UI prompts. A perfect estimator
  scores zero; equal positive and negative position biases score equally.
- `sigmaCoverage01`: fraction of motion samples where the absolute difference
  between estimated and true route position is no greater than `2 × σ`.
  Coverage below 95% fails even when it is within the regression tolerance.
- `headingDriftP95Deg`: nearest-rank p95 of the absolute wrapped difference
  between the scored heading and true route heading at the 45 m checkpoint.
  When the estimator does not emit a fused heading, `ScenarioMetrics.headingMetric`
  is `"raw-sensor-yaw"` and scores raw `sample.motion.yawRad` (a sensor-only,
  roughly 45-second measurement shared by both engines, not the later slice's
  ten-minute heading-fusion target). Estimators that emit a heading are tagged
  `"fused-heading"`.
- `relockDistanceP95M`: nearest-rank p95 of true metres walked from `Lost` to
  return to `WALK`. Failed attempts remain in `relockAttempts` and reduce
  `relockSuccessRate01`; `LINEAR` reports relock as not applicable (`null`, zero
  attempts) because its documented behavior is to enter `OFF_ROUTE`. Any failed
  GHOST relock attempt fails the gate.
- Percentiles use the nearest-rank rule `ceil(p × n)`. The gate rejects more
  than 5% regression in arrival error, heading drift, relock distance, or relock
  success rate. Missing/non-finite metrics and under-calibrated uncertainty fail.

The simulation does not feed rendered frames into either navigation engine in
this slice; the image generator and projection oracle are tested independently
so later visual-estimator work can use the same deterministic world without
pretending that W15-01 measures visual odometry.

## Metrics review limits

Metrics-design review verdict: **APPROVE WITH RESIDUAL RISK**. The review covered
the scoring/gate definitions, paired-seed design, and regression tests; it did
not independently remeasure the JSON or inspect device behavior. The executed
fast/full gates validate the recorded values and metadata.

- Arrival is a pre-arrival position-error proxy, not prompt-time accuracy.
- Heading is sensor-only and short-duration; no ten-minute fusion claim closes.
- Pooled 20 Hz uncertainty samples are autocorrelated. The coverage floor detects
  overconfidence but does not penalize unnecessarily wide uncertainty.
