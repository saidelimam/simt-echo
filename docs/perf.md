# Performance harness (W10-04)

Macrobenchmarks, Baseline Profile generation, and JankStats summaries for
ECHO 2.0. Plan 0003 §4; baselines in `docs/measurements.md` (W10-01).

> Hardware-needed: every command that touches a device is a **named manual
> gate**, never a pre-commit gate (ADR-017). No device was available in
> this slice, so everything below is scaffolded and the device steps are
> recorded as TBD with exact commands. Nothing here runs on the host.

## Module

`:benchmark` (`benchmark/`) is a `com.android.test` module targeting
`:androidApp`, with the `androidx.baselineprofile` plugin (1.5.0 — 1.4.1
rejects AGP 9.5 at configuration time, so do not downgrade).

- `benchmark/src/main/java/app/simt/echo/benchmark/EchoBenchmarks.kt` —
  four scenarios: `coldStart`, `homeScroll`, `record30s`,
  `navigateSimulate60s`. Cold start measures uncompiled worst-case;
  journeys use `CompilationMode.Partial()` (the shipped profile) with
  `FrameTimingMetric`.
- `benchmark/src/main/java/app/simt/echo/benchmark/BaselineProfileGenerator.kt`
  — `BaselineProfileRule.collect` over launch + Home scroll. Short by
  design: collection already iterates the block, so the 30 s / 60 s dwells
  would add time without covering new code.
- Screen targeting is shallow on purpose: journeys launch the app and dwell.
  Once navigation test tags land (W13 slices), extend the `TODO(nav-tags)`
  sites to push into Record and Navigate-Simulate.

Release shipping: `androidApp` applies `androidx.baselineprofile` and
declares `baselineProfile(project(":benchmark"))`, plus
`androidx.profileinstaller` so the profile installs on first launch.
Verified in this slice: `:androidApp:assembleRelease` emits
`assets/dexopt/baseline.prof` (13,248 bytes). It currently contains only
library-consumed rules (3,942 merged rules, e.g. appcompat) — zero
app-specific rules until the device run below lands.

## JankStats (debug only)

`androidApp/src/debug/.../DebugJankStats.kt` tracks the window and logs one
`EchoJank` summary line per 300 frames (cumulative frame count, janky
count, share). `MainActivity` installs it via reflection when
`BuildConfig.DEBUG`; the dependency is `debugImplementation`, so release
never loads it. Filter:

```sh
adb logcat -s EchoJank
```

## Devices

| Role | Device | Status |
|---|---|---|
| Low-end | TBD (named phone, cf. W10-01) | TBD |
| Mid/flagship | TBD (named phone, cf. W10-01) | TBD |

Record the device + build SHA with every run; W14-01 fills the results log.

## Commands

Host-verified in this slice (W10-04 evidence):

```sh
./gradlew :benchmark:assemble
./gradlew :androidApp:assembleDebug :androidApp:assembleRelease
unzip -l androidApp/build/outputs/apk/release/androidApp-release.apk | grep -E "baseline|dexopt"
```

Device runs (TBD — one connected device, airplane mode per W10-01 §"2.0
baselines" notes where applicable):

```sh
# 1. Benchmarks: writes macrobenchmark JSON to the device output dir.
./gradlew :benchmark:connectedBenchmarkReleaseAndroidTest
# 2. Profile: run the generator, then collect into the app module.
./gradlew :benchmark:connectedNonMinifiedReleaseAndroidTest
./gradlew :benchmark:collectNonMinifiedReleaseBaselineProfile
# 3. Rebuild release and confirm app rules shipped.
./gradlew :androidApp:assembleRelease
unzip -l androidApp/build/outputs/apk/release/androidApp-release.apk | grep -E "baseline|dexopt"
# 4. Debug JankStats alongside any manual run.
adb logcat -s EchoJank
# 5. Navigate battery leg (gate G-PERF-08): 10 min Navigate, screen on.
adb shell dumpsys batterystats --reset
# ... 10 min Navigate on the mid phone ...
adb shell dumpsys batterystats | grep -i "echo\|Uid u0a"
```

Note: the plan names the benchmark task `connectedBenchmarkAndroidTest`;
under AGP 9.5 + baselineprofile 1.5.0 the variant-qualified task is
`connectedBenchmarkReleaseAndroidTest` (profile generation uses the
`nonMinifiedRelease` variant). The commands above are the exact ones.

Pull benchmark JSON off the device with `additionalTestOutputDir`
(`adb pull /sdcard/Download/...`) and record the medians in the results
log; every README/claim citation needs device + SHA + numbers.

## Manual gates (§4 budgets)

| Gate | Budget (2.0 target) | Measured by | Owner |
|---|---|---|---|
| G-PERF-01 | SAM billed frames / 60 m route ≤ 120 (hard cap 240) | Live SAM envelope | W11-10 |
| G-PERF-02 | SAM calls ≤ N video + ≤ 2N image; 0 re-billed | Live SAM envelope | W11-06, W11-10 |
| G-PERF-03 | Upload bytes / route ≤ 2 MB | Weigh `jobs/<id>/in/` after walked-record upload | W11-03 |
| G-PERF-04 | Result latency after Finish ≤ 45 s (≤ 15 s ready-on-arrival) | Live envelope | W11-10 |
| G-PERF-05 | Record jank ≤ +1 pp vs W10-01 baseline; 0 preview stalls | `record30s` benchmark + `EchoJank` summaries | W14-01 (this harness) |
| G-PERF-06 | Frame Tap ≤ 6 ms median, ≤ 8 Hz, thermal-adaptive | Device evidence in-slice | W10-03 |
| G-PERF-07 | Ghost filter update ≤ 1 ms / step event (256 particles) | Host timing + device confirm | W12-02 |
| G-PERF-08 | Navigate battery ≤ 6 % / 10 min, screen on | `navigateSimulate60s` + `dumpsys batterystats` | W14-01 (this harness) |
| G-PERF-09 | Job status 1 stream (+ ≤ 5 fallback polls) | Client instrumentation | W11-08 |
| G-PERF-10 | Visual match precision ≥ 0.95 at chosen τ | Recorded pair set | W12-05 |

## Results log

| Date | Gate | Device | Build SHA | Result | Notes |
|---|---|---|---|---|---|
| — | — | — | — | TBD | No device in W10-04; W14-01 runs these gates. |
