package app.simt.echo.benchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * W10-04 perf harness: the four macrobenchmark scenarios behind the §4
 * manual gates in `docs/perf.md`.
 *
 * Hardware-needed: every test here runs via a connected-device task (see
 * `docs/perf.md` for the exact command) and writes JSON to the device's
 * `additionalTestOutputDir`. They never run on the host and are not
 * pre-commit gates (ADR-017).
 *
 * Screen targeting is deliberately shallow: the journeys assert the app
 * launches and stays alive under the workload. Once navigation test tags
 * land (W13 slices), extend [dwellOnJourney] call sites with selectors
 * that push into Record / Navigate-Simulate.
 */
@RunWith(AndroidJUnit4::class)
class EchoBenchmarks {

    @get:Rule
    val rule = MacrobenchmarkRule()

    /** Cold start: worst-case launch, uncompiled, from Home. */
    @Test
    fun coldStart() {
        rule.measureRepeated(
            packageName = TARGET_PACKAGE,
            metrics = listOf(StartupTimingMetric()),
            compilationMode = CompilationMode.None(),
            startupMode = StartupMode.COLD,
            iterations = 5,
            setupBlock = { pressHome() },
            measureBlock = { startActivityAndWait() },
        )
    }

    /** Home scroll: warm launch, then fling the first scrollable. */
    @Test
    fun homeScroll() {
        rule.measureRepeated(
            packageName = TARGET_PACKAGE,
            metrics = listOf(FrameTimingMetric()),
            compilationMode = CompilationMode.Partial(),
            startupMode = StartupMode.WARM,
            iterations = 5,
            setupBlock = { pressHome() },
            measureBlock = {
                startActivityAndWait()
                val scrollable = device.findObject(By.scrollable(true))
                if (scrollable != null) {
                    // Flings, not a single swipe: sustained frames, not one gesture.
                    repeat(4) {
                        runCatching { scrollable.fling(Direction.DOWN) }
                        device.waitForIdle()
                    }
                }
            },
        )
    }

    /**
     * Record 30 s: warm launch, then dwell 30 s collecting frame timing.
     * Feeds gate G-PERF-05 (Record jank vs the W10-01 baseline).
     */
    @Test
    fun record30s() {
        rule.measureRepeated(
            packageName = TARGET_PACKAGE,
            metrics = listOf(FrameTimingMetric()),
            compilationMode = CompilationMode.Partial(),
            startupMode = StartupMode.WARM,
            iterations = 2,
            setupBlock = { pressHome() },
            measureBlock = {
                startActivityAndWait()
                // TODO(nav-tags): push into the Record screen here once its
                // test tag lands, so the 30 s covers Preview + Frame Tap.
                dwell(seconds = 30)
            },
        )
    }

    /**
     * Navigate Simulate 60 s: warm launch, then dwell 60 s collecting frame
     * timing. Feeds gates G-PERF-05 (jank) and G-PERF-08 (battery, paired
     * with a 10 min `dumpsys batterystats` walk — see docs/perf.md).
     */
    @Test
    fun navigateSimulate60s() {
        rule.measureRepeated(
            packageName = TARGET_PACKAGE,
            metrics = listOf(FrameTimingMetric()),
            compilationMode = CompilationMode.Partial(),
            startupMode = StartupMode.WARM,
            iterations = 2,
            setupBlock = { pressHome() },
            measureBlock = {
                startActivityAndWait()
                // TODO(nav-tags): push into Navigate-Simulate here once its
                // test tag lands, so the 60 s covers the instrument + aura.
                dwell(seconds = 60)
            },
        )
    }

    private fun MacrobenchmarkScope.dwell(seconds: Int) {
        // Chunked sleeps keep the device watchdog-friendly; frame metrics
        // accumulate across the whole measure block.
        val deadline = System.currentTimeMillis() + seconds * 1000L
        while (System.currentTimeMillis() < deadline) {
            device.wait(Until.gone(By.pkg(device.launcherPackageName)), 5_000)
            device.waitForIdle()
        }
    }
}

private const val TARGET_PACKAGE = "app.simt.echo"
