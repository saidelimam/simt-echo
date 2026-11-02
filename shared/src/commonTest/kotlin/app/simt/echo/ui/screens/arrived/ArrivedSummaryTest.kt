package app.simt.echo.ui.screens.arrived

import app.simt.echo.model.Trial
import app.simt.echo.model.TrialMode
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * W8-10 gate: the Arrived success summary transcribes the saved run —
 * time, distance, confirmations — and never invents observations.
 */
class ArrivedSummaryTest {

    private fun trial(durationS: Double, distanceM: Double, confirmations: Int) = Trial(
        trialId = "trial-r-1",
        routeId = "r",
        mode = TrialMode.ECHO,
        device = "pixel-8",
        startedAtEpochMs = 1_000L,
        durationS = durationS,
        distanceM = distanceM,
        arrived = true,
        confirmations = confirmations,
    )

    @Test
    fun shortRunShowsSeconds() {
        assertEquals("45 s · 42.0 m · 2 confirmations", arrivedSummary(trial(45.0, 42.0, 2)))
    }

    @Test
    fun longRunShowsMinutes() {
        assertEquals("2 min 5 s · 80.0 m · 0 confirmations", arrivedSummary(trial(125.0, 80.0, 0)))
    }
}
