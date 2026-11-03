package app.simt.echo.capture.visual

import app.simt.echo.capture.ThermalState
import app.simt.echo.model.EchoRoute
import app.simt.echo.model.NodeType
import kotlin.math.abs

/**
 * Pure information-gain scheduler for the visual analyzer tap (plan W18-03).
 *
 * Chooses the `FrameTap` base interval (`ns`, `0L` = paused / 0 Hz) from:
 * - Standing state (`isStanding` → `0 Hz` / `0L`)
 * - Thermal state (`SEVERE` → `0 Hz` / `0L`, `MODERATE` halves rate)
 * - Battery Saver (`batterySaver` halves rate, capped at ≤ 2 Hz)
 * - Filter uncertainty `sigmaM` (`< 2 m` straight → `1 Hz`; `2..3 m` → `2 Hz`; `>= 3 m` → `4 Hz`)
 * - Turn proximity (turn node within [TURN_PROXIMITY_M] = `8 m` raises rate to ≥ `2 Hz`)
 * - Ambiguity (same-direction turn within [AMBIGUOUS_TURN_WINDOW_M] = `6 m` raises rate to `4 Hz`)
 * - `usability01` (when `< 0.25`, backs off high rates to `1 Hz` probe duty)
 */
object VisualScheduler {

    const val OFF_INTERVAL_NS = 0L
    const val INTERVAL_4_HZ_NS = 250_000_000L
    const val INTERVAL_2_HZ_NS = 500_000_000L
    const val INTERVAL_1_HZ_NS = 1_000_000_000L
    const val INTERVAL_HALF_HZ_NS = 2_000_000_000L

    const val STRAIGHT_SIGMA_M = 2.0
    const val HIGH_SIGMA_M = 3.0
    const val TURN_PROXIMITY_M = 8.0
    const val AMBIGUOUS_TURN_WINDOW_M = 6.0
    const val LOW_USABILITY_THRESHOLD = 0.25

    /**
     * Returns the target `FrameTap` interval in nanoseconds (`0L` = 0 Hz paused).
     */
    fun intervalNs(
        isStanding: Boolean,
        sigmaM: Double,
        usability01: Double = 1.0,
        distanceToTurnM: Double = Double.POSITIVE_INFINITY,
        hasAmbiguousTurn: Boolean = false,
        thermal: ThermalState = ThermalState.NORMAL,
        batterySaver: Boolean = false,
    ): Long {
        if (isStanding || thermal == ThermalState.SEVERE) return OFF_INTERVAL_NS
        val safeSigma = if (sigmaM.isFinite() && sigmaM >= 0.0) sigmaM else HIGH_SIGMA_M
        val nearTurn = distanceToTurnM.isFinite() && distanceToTurnM in 0.0..TURN_PROXIMITY_M

        // Low usability (flat hold / dark / blank wall): probe at 1 Hz instead of burning 2-4 Hz.
        if (usability01.isFinite() && usability01 < LOW_USABILITY_THRESHOLD) {
            return if (thermal == ThermalState.MODERATE || batterySaver) {
                INTERVAL_HALF_HZ_NS
            } else {
                INTERVAL_1_HZ_NS
            }
        }

        val baseNs = when {
            safeSigma >= HIGH_SIGMA_M || (nearTurn && hasAmbiguousTurn) || hasAmbiguousTurn -> INTERVAL_4_HZ_NS
            nearTurn || safeSigma >= STRAIGHT_SIGMA_M -> INTERVAL_2_HZ_NS
            else -> INTERVAL_1_HZ_NS
        }

        return if (thermal == ThermalState.MODERATE || batterySaver) {
            (baseNs * 2L).coerceAtMost(INTERVAL_HALF_HZ_NS)
        } else {
            baseNs
        }
    }

    /** Frequency in Hz corresponding to [intervalNs] (`0.0` when `intervalNs <= 0L`). */
    fun rateHz(intervalNs: Long): Double =
        if (intervalNs <= 0L) 0.0 else 1_000_000_000.0 / intervalNs.toDouble()

    /**
     * Convenience overload evaluating turn proximity and same-direction
     * ambiguity directly from [route] at along-route position [sM].
     */
    fun intervalForRouteNs(
        route: EchoRoute,
        sM: Double,
        isStanding: Boolean,
        sigmaM: Double,
        usability01: Double = 1.0,
        thermal: ThermalState = ThermalState.NORMAL,
        batterySaver: Boolean = false,
    ): Long {
        var nearestTurnDist = Double.POSITIVE_INFINITY
        var ambiguous = false
        val turns = route.nodes.filter { it.type == NodeType.TURN && it.turn != null }
        for (i in turns.indices) {
            val t = turns[i]
            val dist = abs(t.atDistanceM - sM)
            if (dist < nearestTurnDist) nearestTurnDist = dist
            if (dist <= TURN_PROXIMITY_M) {
                for (j in turns.indices) {
                    if (i != j && turns[j].turn == t.turn &&
                        abs(turns[j].atDistanceM - t.atDistanceM) <= AMBIGUOUS_TURN_WINDOW_M
                    ) {
                        ambiguous = true
                        break
                    }
                }
            }
        }
        return intervalNs(
            isStanding = isStanding,
            sigmaM = sigmaM,
            usability01 = usability01,
            distanceToTurnM = nearestTurnDist,
            hasAmbiguousTurn = ambiguous,
            thermal = thermal,
            batterySaver = batterySaver,
        )
    }
}
