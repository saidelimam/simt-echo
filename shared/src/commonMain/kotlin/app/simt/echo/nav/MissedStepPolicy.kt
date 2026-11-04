package app.simt.echo.nav

import app.simt.echo.capture.VisualStepGate
import kotlin.math.sqrt

/**
 * LINEAR uncertainty inflation for `MISSED_STEP_SUSPECT` events. Each
 * suspicion adds [INFLATION_M] in quadrature, matching GHOST's
 * [app.simt.echo.nav.ghost.GhostFilter.inflateUncertainty], so a long still
 * walk cannot linearly exhaust the uncertainty ceiling.
 */
internal class MissedStepPolicy {
    var sigmaBonusM = 0.0
        private set

    fun onSuspect() {
        sigmaBonusM = sqrt(sigmaBonusM * sigmaBonusM + INFLATION_M * INFLATION_M)
    }

    fun reset() {
        sigmaBonusM = 0.0
    }

    companion object {
        val INFLATION_M: Double = VisualStepGate.MISSED_STEP_SIGMA_INFLATION_M
    }
}
