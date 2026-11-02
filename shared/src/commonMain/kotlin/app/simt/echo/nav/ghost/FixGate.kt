package app.simt.echo.nav.ghost

import kotlin.math.abs

/**
 * Suppresses redundant position fixes (W12-04): the matcher re-runs every
 * 0.5 m bucket over windows that overlap ~95 %, so each bucket re-finds the
 * same evidence. Feeding every one to the filter counts it ~20 times and
 * turns a soft nudge into a hard anchor.
 *
 * A fix is [accept]ed only when it is new information: the walker has
 * advanced at least [minAdvanceM] since the last accepted fix (the windows
 * no longer share most of their samples), or the fix moved at least
 * [movedM] away from where the last accepted one, carried forward by the
 * distance walked since, would put it (the evidence disagrees with what
 * was already folded in).
 *
 * [odoM] is the raw odometer and [fixS] the along-route position the fix
 * claims for the walker *now*. Pure and allocation-free.
 */
class FixGate(
    private val minAdvanceM: Double = MIN_ADVANCE_M,
    private val movedM: Double = MOVED_M,
) {
    private var lastOdoM = Double.NaN
    private var lastS = Double.NaN

    fun accept(odoM: Double, fixS: Double): Boolean {
        if (!odoM.isFinite() || !fixS.isFinite()) return false
        if (lastOdoM.isFinite()) {
            val advanced = odoM - lastOdoM
            val moved = abs(fixS - (lastS + advanced))
            if (advanced < minAdvanceM && moved < movedM) return false
        }
        lastOdoM = odoM
        lastS = fixS
        return true
    }

    companion object {
        /** Odometer metres between accepted fixes. */
        const val MIN_ADVANCE_M = 5.0

        /** Disagreement with the carried-forward last fix that re-opens the gate, metres. */
        const val MOVED_M = 2.0
    }
}
