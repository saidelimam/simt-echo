package app.simt.echo.nav.ghost

/**
 * Per-candidate debounce for match hits (W12-05): the matcher fires every
 * frame the camera still sees the same landmark, and each hit would
 * reweight the filter and tick the haptic again — a soft "bends, can't
 * snap" nudge compounding into a snap.
 *
 * A hit on a candidate (identified by [key], e.g. its route position) is
 * [accept]ed only when at least [minGapNs] have passed *and* the walker has
 * moved at least [minAdvanceM] since that candidate's last accepted hit.
 * Distinct candidates are independent. [tNs] is the motion clock and
 * [odoM] the raw odometer, so the rule follows the walker, not wall time.
 */
class HitDebounce(
    private val minGapNs: Long = MIN_GAP_NS,
    private val minAdvanceM: Double = MIN_ADVANCE_M,
) {
    private class Last(val tNs: Long, val odoM: Double)

    private val last = mutableMapOf<Double, Last>()

    fun accept(key: Double, tNs: Long, odoM: Double): Boolean {
        val previous = last[key]
        if (previous != null && (tNs - previous.tNs < minGapNs || odoM - previous.odoM < minAdvanceM)) {
            return false
        }
        last[key] = Last(tNs, odoM)
        return true
    }

    companion object {
        /** Minimum time between accepted hits on one candidate: 3 s. */
        const val MIN_GAP_NS = 3_000_000_000L

        /** Minimum walking between accepted hits on one candidate, metres. */
        const val MIN_ADVANCE_M = 2.0
    }
}
