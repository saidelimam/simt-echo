package app.simt.echo.nav

import app.simt.echo.capture.circularMean
import app.simt.echo.capture.wrapPi
import app.simt.echo.model.EchoConfig
import app.simt.echo.model.TurnDir
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sqrt

/** A settled heading change, classified as in §6.3 step 3. */
data class DetectedTurn(
    val dir: TurnDir,
    val angleRad: Double,
)

/**
 * Online turn detector (implementation plan §6.5).
 *
 * Keeps [EchoConfig.turnHistoryMs] of yaw samples. A turn fires when the
 * stable recent heading differs from the reference heading — the circular
 * mean over the window ending [EchoConfig.turnRefEndMs] ago — by at least
 * [EchoConfig.turnMinAngleDeg], and the recent window is steady enough to
 * trust (spread below [EchoConfig.turnRecentMaxStdDeg]). After firing, the
 * history clears and a cooldown suppresses double-fires.
 *
 * Pure and clock-driven by sample timestamps, so replayed captures behave
 * exactly like live sensor streams.
 */
class TurnDetector(
    private val config: EchoConfig = EchoConfig(),
) {
    private data class Sample(val tNs: Long, val yawRad: Double)

    private val samples = mutableListOf<Sample>()
    private var cooldownUntilNs: Long = Long.MIN_VALUE

    fun reset() {
        samples.clear()
        cooldownUntilNs = Long.MIN_VALUE
    }

    /** Feeds one yaw sample; returns a turn the moment it settles, else null. */
    fun onSample(tNs: Long, yawRad: Double): DetectedTurn? {
        samples += Sample(tNs, yawRad)
        val historyNs = config.turnHistoryMs * NS_PER_MS
        samples.removeAll { it.tNs < tNs - historyNs }
        if (tNs < cooldownUntilNs) return null

        val ref = samples.filter {
            it.tNs in (tNs - config.turnRefStartMs * NS_PER_MS)..(tNs - config.turnRefEndMs * NS_PER_MS)
        }
        val recent = samples.filter { it.tNs >= tNs - config.turnRecentMs * NS_PER_MS }
        if (ref.isEmpty() || recent.isEmpty()) return null

        val recentMean = circularMean(recent.map { it.yawRad })
        if (angularStd(recent.map { it.yawRad }, recentMean) >= degToRad(config.turnRecentMaxStdDeg)) {
            return null
        }
        val angle = wrapPi(recentMean - circularMean(ref.map { it.yawRad }))
        if (abs(angle) < degToRad(config.turnMinAngleDeg)) return null

        samples.clear()
        cooldownUntilNs = tNs + config.turnCooldownMs * NS_PER_MS
        return DetectedTurn(dir = classify(angle), angleRad = angle)
    }

    private fun classify(angleRad: Double): TurnDir = when {
        abs(angleRad) >= degToRad(config.uTurnAngleDeg) -> TurnDir.U_TURN
        angleRad > 0.0 -> TurnDir.RIGHT
        else -> TurnDir.LEFT
    }

    private fun angularStd(angles: List<Double>, mean: Double): Double {
        if (angles.isEmpty()) return 0.0
        val variance = angles.sumOf {
            val d = wrapPi(it - mean)
            d * d
        } / angles.size
        return sqrt(variance)
    }

    private companion object {
        const val NS_PER_MS = 1_000_000L
    }
}

internal fun degToRad(deg: Double): Double = deg * PI / 180.0
