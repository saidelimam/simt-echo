package app.simt.echo.sim

import app.simt.echo.capture.FrameSample
import app.simt.echo.model.EchoRoute
import app.simt.echo.model.NodeType
import app.simt.echo.model.PathPoint
import app.simt.echo.model.RouteLeg
import app.simt.echo.model.RouteNode
import app.simt.echo.model.StartAnchor
import app.simt.echo.platform.MotionUpdate
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

data class SimWalkSample(
    val trueDistanceM: Double,
    val trueYawRad: Double,
    val cameraPose: SimPose,
    val motion: MotionUpdate,
    val frame: FrameSample?,
)

/** Seeded gait and phone sensors for a walk over a route's independent geometry. */
class SimWalk(
    val environment: SimEnvironment,
    val seed: Long,
    val phone: SimPhone = SimPhone.forSeed(seed),
) {
    private val world = SimWorld(environment, phone, seed)

    fun samples(
        route: EchoRoute,
        renderFrames: Boolean = false,
        frameIntervalNs: Long = FRAME_INTERVAL_NS,
    ): Sequence<SimWalkSample> = sequence {
        require(route.totalLengthM > 0.0 && route.totalLengthM.isFinite())
        require(frameIntervalNs > 0L)

        val random = Random(seed)
        val cadenceHz = random.nextDouble(MIN_CADENCE_HZ, MAX_CADENCE_HZ)
        val walkingSpeedMps = random.nextDouble(MIN_SPEED_MPS, MAX_SPEED_MPS)
        val strideScale = random.nextDouble(MIN_STRIDE_SCALE, MAX_STRIDE_SCALE)
        val driftPerMinute = random.nextDouble(-MAX_ROTATION_DRIFT_RAD_PER_MIN, MAX_ROTATION_DRIFT_RAD_PER_MIN)
        var trueDistanceM = 0.0
        var odometerM = 0.0
        var stepPhase = 0.0
        var stepCount = 0
        var gyroBiasRad = 0.0
        var previousCameraYaw = routeHeadingAt(route, 0.0) + phone.holdYawOffsetRad
        var previousTimeNs = START_TIME_NS
        var nextFrameNs = START_TIME_NS + frameIntervalNs

        while (trueDistanceM < route.totalLengthM) {
            trueDistanceM = (trueDistanceM + walkingSpeedMps * SAMPLE_INTERVAL_S).coerceAtMost(route.totalLengthM)
            stepPhase += cadenceHz * SAMPLE_INTERVAL_S
            val isStep = stepPhase >= 1.0
            if (isStep) {
                stepPhase -= 1.0
                stepCount += 1
                val idealStepM = walkingSpeedMps / cadenceHz
                odometerM += (idealStepM * strideScale + random.nextDouble(-STEP_NOISE_M, STEP_NOISE_M))
                    .coerceAtLeast(0.0)
            }

            val timeNs = previousTimeNs + SAMPLE_INTERVAL_NS
            val timeSeconds = (timeNs - START_TIME_NS) / NS_PER_SECOND
            val trueYaw = routeHeadingAt(route, trueDistanceM)
            val anomalyRad = magneticYawAnomaly(trueDistanceM)
            val driftRad = driftPerMinute * timeSeconds / SECONDS_PER_MINUTE
            gyroBiasRad += random.nextDouble(-1.0, 1.0) * GYRO_BIAS_RW_RAD * sqrt(SAMPLE_INTERVAL_S)
            val measuredYaw = wrapAngle(
                trueYaw + driftRad + gyroBiasRad + anomalyRad + random.nextDouble(-YAW_NOISE_RAD, YAW_NOISE_RAD),
            )
            val magneticMagnitude = MAG_FIELD_BASE_UT + abs(anomalyRad) * MAG_FIELD_ANOMALY_SCALE +
                random.nextDouble(-MAG_FIELD_NOISE_UT, MAG_FIELD_NOISE_UT)
            val magneticVertical = MAG_VERTICAL_BASE_UT + anomalyRad * MAG_VERTICAL_ANOMALY_SCALE
            val headingQuality = if (abs(anomalyRad) >= MAG_ANOMALY_GATE_RAD) {
                MAG_ANOMALY_QUALITY
            } else {
                NORMAL_HEADING_QUALITY
            }

            val (pathX, pathY) = routePositionAt(route, trueDistanceM)
            val baseHeightM = world.floorHeightM(pathY)
            val yawWobble = sin(timeSeconds * YAW_WOBBLE_HZ * 2.0 * PI) * YAW_WOBBLE_RAD
            val pitchWobble = sin(timeSeconds * cadenceHz * 2.0 * PI) * PITCH_WOBBLE_RAD
            val cameraYaw = trueYaw + phone.holdYawOffsetRad + yawWobble
            val cameraPose = SimPose(
                x = pathX,
                y = pathY,
                z = baseHeightM + CAMERA_HEIGHT_M + abs(sin(stepPhase * PI)) * CAMERA_BOB_M,
                yawRad = cameraYaw,
                pitchRad = phone.holdPitchRad + pitchWobble,
            )
            val angularVelocity = wrapAngle(cameraYaw - previousCameraYaw) / SAMPLE_INTERVAL_S
            val frame = if (renderFrames && timeNs >= nextFrameNs) {
                nextFrameNs += frameIntervalNs
                world.render(cameraPose, timeNs, angularVelocity)
            } else {
                null
            }
            val pressure = pressureAtHeight(baseHeightM)
            val motion = MotionUpdate(
                tNs = timeNs,
                distanceM = odometerM,
                yawRad = measuredYaw,
                stepCount = stepCount,
                isStep = isStep,
                pressureHpa = pressure,
                source = SOURCE,
                headingQuality = headingQuality,
                magMag = magneticMagnitude,
                magVert = magneticVertical,
            )
            yield(
                SimWalkSample(
                    trueDistanceM = trueDistanceM,
                    trueYawRad = trueYaw,
                    cameraPose = cameraPose,
                    motion = motion,
                    frame = frame,
                ),
            )
            previousCameraYaw = cameraYaw
            previousTimeNs = timeNs
        }
    }

    private fun magneticYawAnomaly(distanceM: Double): Double {
        if (environment != SimEnvironment.STAIRWELL_LANDING || distanceM !in STEEL_START_M..STEEL_END_M) {
            return 0.0
        }
        val phase = (distanceM - STEEL_START_M) / (STEEL_END_M - STEEL_START_M)
        return sin(phase * PI) * MAX_MAG_ANOMALY_RAD
    }


    private fun routePositionAt(route: EchoRoute, distanceM: Double): Pair<Double, Double> {
        if (route.path.isEmpty()) return positionFromLegs(route, distanceM)
        var low = 0
        var high = route.path.lastIndex
        while (low < high) {
            val mid = (low + high) ushr 1
            if (route.path[mid].d < distanceM) low = mid + 1 else high = mid
        }
        val end = route.path[low]
        val start = route.path[(low - 1).coerceAtLeast(0)]
        val span = end.d - start.d
        val fraction = if (span <= 0.0) 0.0 else ((distanceM - start.d) / span).coerceIn(0.0, 1.0)
        return (start.x + (end.x - start.x) * fraction) to (start.y + (end.y - start.y) * fraction)
    }

    private fun positionFromLegs(route: EchoRoute, distanceM: Double): Pair<Double, Double> {
        var walked = 0.0
        var x = 0.0
        var y = 0.0
        for (leg in route.legs) {
            val onLeg = (distanceM - walked).coerceIn(0.0, leg.lengthM)
            val heading = leg.headingRelDeg * PI / 180.0
            x += sin(heading) * onLeg
            y += cos(heading) * onLeg
            walked += leg.lengthM
            if (distanceM <= walked) break
        }
        return x to y
    }

    private fun routeHeadingAt(route: EchoRoute, distanceM: Double): Double {
        var walked = 0.0
        for (leg in route.legs) {
            walked += leg.lengthM
            if (distanceM <= walked) return leg.headingRelDeg * PI / 180.0
        }
        return route.legs.lastOrNull()?.headingRelDeg?.times(PI / 180.0) ?: 0.0
    }

    private fun pressureAtHeight(heightM: Double): Double =
        SEA_LEVEL_PRESSURE_HPA * (1.0 - heightM / PRESSURE_SCALE_HEIGHT_M).pow(PRESSURE_EXPONENT)

    private fun wrapAngle(angle: Double): Double {
        var wrapped = (angle + PI) % (2.0 * PI)
        if (wrapped < 0.0) wrapped += 2.0 * PI
        return wrapped - PI
    }

    companion object {
        const val DEFAULT_FAST_WALKS = 100
        const val DEFAULT_FULL_WALKS = 500
        const val BASELINE_SEED = 104_729L

        fun straightRoute(lengthM: Double = 45.0): EchoRoute {
            require(lengthM > 0.0 && lengthM.isFinite())
            val nodes = listOf(
                RouteNode(id = "sim-start", type = NodeType.START, atDistanceM = 0.0, label = "Start"),
                RouteNode(
                    id = "sim-end",
                    type = NodeType.DESTINATION,
                    atDistanceM = lengthM,
                    label = "Destination",
                ),
            )
            val path = buildList {
                var distance = 0.0
                while (distance < lengthM) {
                    add(PathPoint(d = distance, x = 0.0, y = distance))
                    distance += PATH_STEP_M
                }
                add(PathPoint(d = lengthM, x = 0.0, y = lengthM))
            }
            return EchoRoute(
                routeId = "sim-straight-$lengthM",
                name = "Simulated straight route",
                createdAtEpochMs = 0L,
                creatorLabel = "SimWorld",
                motionSource = SOURCE,
                totalLengthM = lengthM,
                start = StartAnchor("Start", null, "Face forward"),
                nodes = nodes,
                legs = listOf(RouteLeg("sim-start", "sim-end", lengthM, 0.0)),
                path = path,
            )
        }

        private const val SOURCE = "sim-world"
        private const val START_TIME_NS = 1_000_000_000L
        private const val SAMPLE_INTERVAL_NS = 50_000_000L
        private const val SAMPLE_INTERVAL_S = 0.05
        private const val FRAME_INTERVAL_NS = 500_000_000L
        private const val NS_PER_SECOND = 1_000_000_000.0
        private const val SECONDS_PER_MINUTE = 60.0
        private const val MIN_CADENCE_HZ = 1.6
        private const val MAX_CADENCE_HZ = 2.2
        private const val MIN_SPEED_MPS = 0.9
        private const val MAX_SPEED_MPS = 1.1
        private const val MIN_STRIDE_SCALE = 0.88
        private const val MAX_STRIDE_SCALE = 1.12
        private const val STEP_NOISE_M = 0.018
        private const val MAX_ROTATION_DRIFT_RAD_PER_MIN = 2.0 * PI / 180.0
        private const val GYRO_BIAS_RW_RAD = 0.004
        private const val YAW_NOISE_RAD = 0.008
        private const val YAW_WOBBLE_HZ = 0.8
        private const val YAW_WOBBLE_RAD = 0.035
        private const val PITCH_WOBBLE_RAD = 0.04
        private const val CAMERA_HEIGHT_M = 1.55
        private const val CAMERA_BOB_M = 0.055
        private const val MAG_FIELD_BASE_UT = 48.0
        private const val MAG_FIELD_NOISE_UT = 0.35
        private const val MAG_FIELD_ANOMALY_SCALE = 0.45
        private const val MAG_VERTICAL_BASE_UT = 42.0
        private const val MAG_VERTICAL_ANOMALY_SCALE = 0.35
        private const val MAG_ANOMALY_GATE_RAD = 4.0 * PI / 180.0
        private const val MAG_ANOMALY_QUALITY = 0.25
        private const val NORMAL_HEADING_QUALITY = 0.92
        private const val MAX_MAG_ANOMALY_RAD = 12.0 * PI / 180.0
        private const val STEEL_START_M = 20.0
        private const val STEEL_END_M = 24.0
        private const val SEA_LEVEL_PRESSURE_HPA = 1013.25
        private const val PRESSURE_SCALE_HEIGHT_M = 44_330.0
        private const val PRESSURE_EXPONENT = 5.255
        private const val PATH_STEP_M = 0.5
    }
}
