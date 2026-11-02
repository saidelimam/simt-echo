package app.simt.echo.capture

import app.simt.echo.model.CaptureLog
import app.simt.echo.model.CaptureSample
import app.simt.echo.model.EchoRoute
import app.simt.echo.platform.MotionSource
import app.simt.echo.platform.MotionUpdate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlin.math.atan2

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
            magMag = interpolateNullable(prev.mag, next.mag, f),
            magVert = interpolateNullable(prev.magV, next.magV, f),
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
        magMag = sample.mag,
        magVert = sample.magV,
    )

    private fun interpolateNullable(a: Double?, b: Double?, f: Double): Double? {
        if (a == null) return b
        if (b == null) return a
        return a + (b - a) * f
    }

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

/**
 * Synthesises the creator capture for a saved route (plan W7-04 Simulate):
 * the draft's `capture.json` is consumed on Review save, so the debug
 * replay walks the saved geometry instead — 1 m/s at 20 Hz along
 * [EchoRoute.path], yaw from each span's direction (0 = +y at start,
 * matching the capture yaw convention the Navigator was tested against).
 * Inputs only: Navigator geometry is untouched.
 */
fun captureLogForRoute(route: EchoRoute): CaptureLog {
    val samples = mutableListOf<CaptureSample>()
    var tNs = CAPTURE_T0_NS
    val path = route.path
    if (path.size < 2) return CaptureLog(
        draftId = "simulate-${route.routeId}",
        startedAtEpochMs = 0L,
        motionSource = ReplayMotionSource.SOURCE,
        videoStartNs = CAPTURE_T0_NS,
        samples = samples,
        marks = emptyList(),
        startPhoto = "",
    )
    var first = true
    for (i in 0 until path.size - 1) {
        val a = path[i]
        val b = path[i + 1]
        val span = b.d - a.d
        if (span <= 0.0) continue
        val yaw = atan2(b.x - a.x, b.y - a.y)
        var covered = 0.0
        if (first) {
            samples += CaptureSample(tNs = tNs, d = a.d, yaw = yaw, p = null)
            first = false
        }
        while (covered < span - 1e-9) {
            val step = minOf(REPLAY_STEP_M, span - covered)
            covered += step
            tNs += REPLAY_TICK_NS
            samples += CaptureSample(tNs = tNs, d = a.d + covered, yaw = yaw, p = null)
        }
    }
    return CaptureLog(
        draftId = "simulate-${route.routeId}",
        startedAtEpochMs = 0L,
        motionSource = ReplayMotionSource.SOURCE,
        videoStartNs = CAPTURE_T0_NS,
        samples = samples,
        marks = emptyList(),
        startPhoto = "",
    )
}

private const val CAPTURE_T0_NS = 1_000_000_000L
private const val REPLAY_TICK_NS = 50_000_000L // 20 Hz
private const val REPLAY_STEP_M = 0.05 // 1 m/s at 20 Hz
