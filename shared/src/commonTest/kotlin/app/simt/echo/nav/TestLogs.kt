package app.simt.echo.nav

import app.simt.echo.model.CaptureLog
import app.simt.echo.model.CaptureSample
import kotlin.math.PI
import kotlin.math.pow

/**
 * Shared synthetic-capture builder for navigator-driven tests (W2-05
 * scenario table, W2-06 replay-to-ARRIVED gate). Extracted from
 * NavigatorTest when the Navigate ViewModel tests became the second user;
 * both suites replay `L_ROUTE` walks at 1 m/s through [ReplayMotionSource].
 */
internal const val TEST_T0_NS = 1_000_000_000L
internal const val TEST_TICK_NS = 50_000_000L // 20 Hz
internal const val TEST_STEP_M = 0.05 // 1 m/s at 20 Hz

/** Builds a capture from (lengthM, headingDeg) segments walked at 1 m/s. */
internal fun walkLog(
    segments: List<Pair<Double, Double>>,
    startYawDeg: Double = segments.first().second,
): CaptureLog {
    val samples = mutableListOf<CaptureSample>()
    var t = TEST_T0_NS
    var d = 0.0
    samples += CaptureSample(tNs = t, d = d, yaw = deg(startYawDeg))
    for ((length, headingDeg) in segments) {
        var walked = 0.0
        while (walked < length - 1e-9) {
            val step = minOf(TEST_STEP_M, length - walked)
            walked += step
            d += step
            t += TEST_TICK_NS
            samples += CaptureSample(tNs = t, d = d, yaw = deg(headingDeg))
        }
    }
    return CaptureLog(
        draftId = "nav-test",
        startedAtEpochMs = 0L,
        motionSource = "echo_builtin_pdr",
        videoStartNs = TEST_T0_NS,
        samples = samples,
        marks = emptyList(),
        startPhoto = "start.jpg",
    )
}

internal fun deg(v: Double) = v * PI / 180.0

/**
 * Inverse barometric formula shared by level fixtures (W12-08):
 * pressure [dh] metres above datum [p0], so a fixture named "+3 m"
 * really climbs 3 m from the leg-start baseline.
 */
internal fun pressureForDh(p0: Double, dh: Double): Double =
    p0 * (1.0 - dh / 44330.0).pow(5.255)
