package app.simt.echo.capture

import app.simt.echo.model.CaptureLog
import app.simt.echo.model.CaptureSample
import app.simt.echo.platform.MotionSource
import app.simt.echo.platform.MotionUpdate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/**
 * Replays a recorded [CaptureLog] as a [MotionSource] (plan W2-05).
 *
 * Emits interpolated updates at 20 Hz of replay time; [speed] scales how
 * fast capture time advances per real second (2.0 = twice as fast). Yaw is
 * interpolated around the circle so a replayed turn never sweeps the long
 * way through ±π. This is what powers navigation scenario tests and the
 * Simulate action: capture and navigation never diverge (§5 seam).
 */
class ReplayMotionSource(
    private val log: CaptureLog,
    private val scope: CoroutineScope,
    private val speed: Double = 1.0,
) : MotionSource {

    private val _updates = MutableSharedFlow<MotionUpdate>(extraBufferCapacity = 256)
    override val updates: Flow<MotionUpdate> = _updates.asSharedFlow()

    private var job: Job? = null

    override fun start() {
        if (job?.isActive == true || log.samples.size < 2) return
        val samples = log.samples.sortedBy { it.tNs }
        val tickCaptureNs = (TICK_MS * NS_PER_MS * speed).toLong()
        job = scope.launch {
            var captureNs = samples.first().tNs
            val endNs = samples.last().tNs
            while (captureNs <= endNs) {
                _updates.emit(interpolate(samples, captureNs))
                captureNs += tickCaptureNs
                if (captureNs <= endNs) delay(TICK_MS)
            }
            // Land exactly on the final sample: arrival checks key off it.
            if ((captureNs - tickCaptureNs) < endNs) {
                _updates.emit(interpolate(samples, endNs))
            }
        }
    }

    override fun stop() {
        job?.cancel()
        job = null
    }

    /** Linear interpolation between the samples bracketing [tNs]. */
    private fun interpolate(samples: List<CaptureSample>, tNs: Long): MotionUpdate {
        val nextIdx = samples.indexOfFirst { it.tNs >= tNs }
            .takeIf { it >= 0 } ?: samples.lastIndex
        if (nextIdx == 0) return update(samples.first(), stepCount = 1, isStep = false)
        val next = samples[nextIdx]
        if (next.tNs == tNs) return update(next, stepCount = nextIdx + 1, isStep = true)
        val prev = samples[nextIdx - 1]
        val span = (next.tNs - prev.tNs).toDouble()
        val f = if (span > 0.0) (tNs - prev.tNs).toDouble() / span else 0.0
        return MotionUpdate(
            tNs = tNs,
            distanceM = prev.d + (next.d - prev.d) * f,
            yawRad = wrapPi(prev.yaw + wrapPi(next.yaw - prev.yaw) * f),
            stepCount = nextIdx,
            isStep = false,
            pressureHpa = interpolatePressure(prev, next, f),
            source = SOURCE,
        )
    }

    private fun update(sample: CaptureSample, stepCount: Int, isStep: Boolean) = MotionUpdate(
        tNs = sample.tNs,
        distanceM = sample.d,
        yawRad = sample.yaw,
        stepCount = stepCount,
        isStep = isStep,
        pressureHpa = sample.p,
        source = SOURCE,
    )

    private fun interpolatePressure(prev: CaptureSample, next: CaptureSample, f: Double): Double? {
        val a = prev.p ?: return next.p
        val b = next.p ?: return a
        return a + (b - a) * f
    }

    companion object {
        const val SOURCE = "replay"

        /** Replay cadence: 20 Hz of capture time per tick. */
        const val TICK_MS = 50L
        private const val NS_PER_MS = 1_000_000L
    }
}
