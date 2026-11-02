package app.simt.echo.benchmark

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * W10-04: Baseline Profile generator for `app.simt.echo`.
 *
 * Run on a connected device via `:androidApp:generateBaselineProfile`
 * (exact command in `docs/perf.md`). The task copies the result to
 * `androidApp/src/release/generated/baselineProfiles`, and the release
 * build ships it through the `baselineProfile(project(":benchmark"))`
 * dependency — inspect the assembled release artefact to confirm.
 *
 * Journeys mirror [EchoBenchmarks] but stay short: profile collection
 * already iterates the block, so the full 30 s / 60 s dwells would only
 * slow generation without covering new code.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {

    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun generate() {
        rule.collect(
            packageName = TARGET_PACKAGE,
            includeInStartupProfile = true,
        ) {
            pressHome()
            startActivityAndWait()

            // Home scroll journey: exercise list + sigil rendering paths.
            val scrollable = device.findObject(By.scrollable(true))
            if (scrollable != null) {
                repeat(2) {
                    runCatching { scrollable.fling(Direction.DOWN) }
                    device.waitForIdle()
                }
            }

            // TODO(nav-tags): extend with Record and Navigate-Simulate
            // passes once their test tags land (W13 slices), so the
            // profile also covers Preview, Frame Tap and the instrument.
        }
    }
}

private const val TARGET_PACKAGE = "app.simt.echo"
