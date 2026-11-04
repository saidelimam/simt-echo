package app.simt.echo.sim

import app.simt.echo.capture.FrameSample
import kotlinx.serialization.Serializable
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

@Serializable
enum class SimEnvironment {
    CORRIDOR,
    JUNCTION,
    OPEN_HALL,
    STAIRWELL_LANDING,
}

data class SimPoint3(val x: Double, val y: Double, val z: Double)

data class SimPose(
    val x: Double,
    val y: Double,
    val z: Double,
    val yawRad: Double,
    val pitchRad: Double = 0.0,
)

data class SimPixel(val x: Double, val y: Double)

data class SimFlow(val x: Double, val y: Double)

data class SimPhone(
    val horizontalFovDeg: Double = 65.0,
    val exposure: Double = 1.0,
    val gamma: Double = 1.0,
    val exposureSeconds: Double = 0.04,
    val holdYawOffsetRad: Double = 0.0,
    val holdPitchRad: Double = 0.0,
) {
    init {
        require(horizontalFovDeg in 30.0..120.0)
        require(exposure > 0.0 && exposure.isFinite())
        require(gamma > 0.0 && gamma.isFinite())
        require(exposureSeconds >= 0.0 && exposureSeconds.isFinite())
    }

    val focalLengthPx: Double
        get() = SimWorld.LUMA_WIDTH / (2.0 * kotlin.math.tan(horizontalFovDeg * PI / 360.0))

    companion object {
        fun forSeed(seed: Long): SimPhone {
            val random = Random(seed xor PHONE_SEED_SALT)
            return SimPhone(
                horizontalFovDeg = random.nextDouble(52.0, 78.0),
                exposure = random.nextDouble(0.72, 1.28),
                gamma = random.nextDouble(0.82, 1.18),
                exposureSeconds = random.nextDouble(0.018, 0.075),
                holdYawOffsetRad = random.nextDouble(-12.0, 12.0) * PI / 180.0,
                holdPitchRad = random.nextDouble(-8.0, 8.0) * PI / 180.0,
            )
        }

        private const val PHONE_SEED_SALT = 0x4f1b_7c21_33a5L
    }
}

/** A deterministic, bounded indoor scene rendered directly to Frame Tap luma. */
class SimWorld(
    val environment: SimEnvironment,
    val phone: SimPhone = SimPhone(),
    private val seed: Long = 0L,
    private val pixelSamplesPerAxis: Int = 8,
    /** Seeded per-pixel temporal read noise, DN (same distribution for a still scene). */
    private val temporalNoiseDn: Double = 0.0,
    /** Row-to-row exposure delay; 0 disables rolling shutter. */
    private val readoutSeconds: Double = 0.0,
) {
    init {
        require(pixelSamplesPerAxis in 1..16)
        require(temporalNoiseDn >= 0.0 && temporalNoiseDn.isFinite())
        require(readoutSeconds >= 0.0 && readoutSeconds.isFinite())
    }

    /** Integrate the unchanged tiled material over the pixel, then apply imaging. */
    internal fun pixelAt(
        x: Int,
        y: Int,
        pose: SimPose,
        tNs: Long,
        angularVelocityRadPerSec: Double = 0.0,
        pitchVelocityRadPerSec: Double = 0.0,
    ): Int {
        require(angularVelocityRadPerSec.isFinite())
        require(pitchVelocityRadPerSec.isFinite())
        val angularRate = kotlin.math.hypot(angularVelocityRadPerSec, pitchVelocityRadPerSec)
        val blurSamples = if (angularRate * phone.exposureSeconds >= BLUR_THRESHOLD_RAD) {
            BLUR_SAMPLES
        } else {
            1
        }
        val rowOffsetSeconds = if (readoutSeconds == 0.0) 0.0 else ((y + 0.5) / LUMA_HEIGHT - 0.5) * readoutSeconds
        var brightness = 0
        for (sample in 0 until blurSamples) {
            val shutterOffset = (if (blurSamples == 1) 0.0 else (sample.toDouble() / (blurSamples - 1) - 0.5) * phone.exposureSeconds) +
                rowOffsetSeconds
            val sampledPose = if (shutterOffset == 0.0) pose else pose.copy(
                yawRad = pose.yawRad + angularVelocityRadPerSec * shutterOffset,
                pitchRad = pose.pitchRad + pitchVelocityRadPerSec * shutterOffset,
            )
            for (sy in 0 until pixelSamplesPerAxis) {
                for (sx in 0 until pixelSamplesPerAxis) {
                    brightness += samplePixel(
                        x + (sx + 0.5) / pixelSamplesPerAxis,
                        y + (sy + 0.5) / pixelSamplesPerAxis,
                        sampledPose,
                        tNs = tNs,
                    )
                }
            }
        }
        return applyPhone(brightness / (blurSamples * pixelSamplesPerAxis * pixelSamplesPerAxis), x, y, tNs)
    }

    fun render(
        pose: SimPose,
        tNs: Long,
        angularVelocityRadPerSec: Double = 0.0,
        pitchVelocityRadPerSec: Double = 0.0,
    ): FrameSample {
        require(angularVelocityRadPerSec.isFinite())
        require(pitchVelocityRadPerSec.isFinite())
        val luma = ByteArray(LUMA_WIDTH * LUMA_HEIGHT)
        for (y in 0 until LUMA_HEIGHT) {
            for (x in 0 until LUMA_WIDTH) {
                luma[y * LUMA_WIDTH + x] = pixelAt(x, y, pose, tNs, angularVelocityRadPerSec, pitchVelocityRadPerSec).toByte()
            }
        }
        return FrameSample(
            tNs = tNs,
            width = FULL_WIDTH,
            height = FULL_HEIGHT,
            decimation = DECIMATION,
            luma = luma,
        )
    }

    fun projectWorldPoint(point: SimPoint3, pose: SimPose): SimPixel? {
        val deltaX = point.x - pose.x
        val deltaY = point.y - pose.y
        val deltaZ = point.z - pose.z
        val sinYaw = sin(pose.yawRad)
        val cosYaw = cos(pose.yawRad)
        val sinPitch = sin(pose.pitchRad)
        val cosPitch = cos(pose.pitchRad)
        val right = deltaX * cosYaw - deltaY * sinYaw
        val forward = deltaX * sinYaw * cosPitch + deltaY * cosYaw * cosPitch + deltaZ * sinPitch
        val up = -deltaX * sinYaw * sinPitch - deltaY * cosYaw * sinPitch + deltaZ * cosPitch
        if (forward <= NEAR_PLANE_M) return null
        return SimPixel(
            x = LUMA_WIDTH / 2.0 + phone.focalLengthPx * right / forward,
            y = LUMA_HEIGHT / 2.0 - phone.focalLengthPx * up / forward,
        )
    }

    fun oracleFlow(point: SimPoint3, from: SimPose, to: SimPose): SimFlow? {
        val first = projectWorldPoint(point, from) ?: return null
        val second = projectWorldPoint(point, to) ?: return null
        return SimFlow(second.x - first.x, second.y - first.y)
    }

    fun visibleWorldPoint(pixel: SimPixel, pose: SimPose, tNs: Long): SimPoint3? {
        val nx = (pixel.x - LUMA_WIDTH / 2.0) / phone.focalLengthPx
        val ny = (LUMA_HEIGHT / 2.0 - pixel.y) / phone.focalLengthPx
        return nearestHit(pose, rayDirection(pose, nx, ny), tNs)?.point
    }

    internal fun floorHeightM(y: Double): Double = if (environment == SimEnvironment.STAIRWELL_LANDING) {
        ((y - STAIR_START_M) / (STAIR_END_M - STAIR_START_M)).coerceIn(0.0, 1.0) * STAIR_RISE_M
    } else {
        0.0
    }

    private fun samplePixel(x: Double, y: Double, pose: SimPose, tNs: Long): Int {
        val nx = (x - LUMA_WIDTH / 2.0) / phone.focalLengthPx
        val ny = (LUMA_HEIGHT / 2.0 - y) / phone.focalLengthPx
        val ray = rayDirection(pose, nx, ny)
        val hit = nearestHit(pose, ray, tNs) ?: return 24
        val textureX: Double
        val textureY: Double
        when (hit.material) {
            MATERIAL_LEFT_WALL, MATERIAL_RIGHT_WALL, MATERIAL_BRANCH_WALL -> {
                textureX = hit.point.y
                textureY = hit.point.z
            }
            MATERIAL_FLOOR, MATERIAL_CEILING -> {
                textureX = hit.point.x
                textureY = hit.point.y
            }
            MATERIAL_END_WALL -> {
                textureX = hit.point.x
                textureY = hit.point.z
            }
            MATERIAL_BILLBOARD -> {
                textureX = hit.point.x * 2.0
                textureY = hit.point.z * 2.0
            }
            else -> {
                textureX = hit.point.x
                textureY = hit.point.y
            }
        }
        val cellX = floor(textureX * TEXTURE_SCALE).toInt()
        val cellY = floor(textureY * TEXTURE_SCALE).toInt()
        return texture(hit.material, cellX, cellY)
    }

    private fun rayDirection(pose: SimPose, nx: Double, ny: Double): SimPoint3 {
        val sinYaw = sin(pose.yawRad)
        val cosYaw = cos(pose.yawRad)
        val sinPitch = sin(pose.pitchRad)
        val cosPitch = cos(pose.pitchRad)
        val rightX = cosYaw
        val rightY = -sinYaw
        val forwardX = sinYaw * cosPitch
        val forwardY = cosYaw * cosPitch
        val forwardZ = sinPitch
        val upX = -sinYaw * sinPitch
        val upY = -cosYaw * sinPitch
        val upZ = cosPitch
        return SimPoint3(
            x = forwardX + nx * rightX + ny * upX,
            y = forwardY + nx * rightY + ny * upY,
            z = forwardZ + ny * upZ,
        )
    }

    private fun nearestHit(origin: SimPose, ray: SimPoint3, tNs: Long): RayHit? {
        var bestDistance = Double.POSITIVE_INFINITY
        var bestMaterial = 0
        fun consider(distance: Double, material: Int, accepts: Boolean) {
            if (accepts && distance > NEAR_PLANE_M && distance < bestDistance) {
                bestDistance = distance
                bestMaterial = material
            }
        }

        val halfWidth = environment.halfWidthM
        val ceiling = environment.ceilingHeightM
        if (abs(ray.x) > RAY_EPSILON) {
            for (side in listOf(-1.0, 1.0)) {
                val wallX = side * halfWidth
                val distance = (wallX - origin.x) / ray.x
                val wallY = origin.y + distance * ray.y
                val wallZ = origin.z + distance * ray.z
                val junctionOpening = environment == SimEnvironment.JUNCTION && side > 0.0 &&
                    wallY in JUNCTION_OPEN_START_M..JUNCTION_OPEN_END_M
                consider(
                    distance,
                    if (side < 0.0) MATERIAL_LEFT_WALL else MATERIAL_RIGHT_WALL,
                    !junctionOpening && wallY in SCENE_START_M..SCENE_END_M && wallZ in 0.0..ceiling,
                )
                if (junctionOpening) {
                    val branchDistance = (side * (halfWidth + BRANCH_WIDTH_M) - origin.x) / ray.x
                    val branchY = origin.y + branchDistance * ray.y
                    val branchZ = origin.z + branchDistance * ray.z
                    consider(
                        branchDistance,
                        MATERIAL_BRANCH_WALL,
                        branchY in JUNCTION_OPEN_START_M..JUNCTION_OPEN_END_M && branchZ in 0.0..ceiling,
                    )
                }
            }
        }

        fun considerFloor(slope: Double, intercept: Double, startY: Double, endY: Double) {
            val denominator = ray.z - slope * ray.y
            if (abs(denominator) <= RAY_EPSILON) return
            val distance = (intercept + slope * origin.y - origin.z) / denominator
            val pointX = origin.x + distance * ray.x
            val pointY = origin.y + distance * ray.y
            consider(distance, MATERIAL_FLOOR, abs(pointX) <= halfWidth + 0.5 && pointY in startY..endY)
        }
        if (environment == SimEnvironment.STAIRWELL_LANDING) {
            val slope = STAIR_RISE_M / (STAIR_END_M - STAIR_START_M)
            considerFloor(0.0, 0.0, SCENE_START_M, STAIR_START_M)
            considerFloor(slope, -STAIR_START_M * slope, STAIR_START_M, STAIR_END_M)
            considerFloor(0.0, STAIR_RISE_M, STAIR_END_M, SCENE_END_M)
        } else {
            considerFloor(0.0, 0.0, SCENE_START_M, SCENE_END_M)
        }
        if (abs(ray.z) > RAY_EPSILON) {
            val distance = (ceiling - origin.z) / ray.z
            val pointX = origin.x + distance * ray.x
            val pointY = origin.y + distance * ray.y
            consider(distance, MATERIAL_CEILING, abs(pointX) <= halfWidth + 0.5 && pointY in SCENE_START_M..SCENE_END_M)
        }

        if (abs(ray.y) > RAY_EPSILON) {
            for ((wallY, material) in listOf(SCENE_START_M to MATERIAL_END_WALL, SCENE_END_M to MATERIAL_END_WALL)) {
                val distance = (wallY - origin.y) / ray.y
                val pointX = origin.x + distance * ray.x
                val pointZ = origin.z + distance * ray.z
                consider(distance, material, abs(pointX) <= halfWidth && pointZ in 0.0..ceiling)
            }

            val timeSeconds = tNs / NS_PER_SECOND
            val billboardY = origin.y + BILLBOARD_DISTANCE_M + sin(timeSeconds * BILLBOARD_SPEED) * BILLBOARD_SWAY_M
            val billboardDistance = (billboardY - origin.y) / ray.y
            val billboardX = origin.x + billboardDistance * ray.x
            val billboardZ = origin.z + billboardDistance * ray.z
            val billboardCenterX = origin.x + sin(timeSeconds * BILLBOARD_LATERAL_SPEED) * BILLBOARD_LATERAL_SWAY_M
            consider(
                billboardDistance,
                MATERIAL_BILLBOARD,
                abs(billboardX - billboardCenterX) <= BILLBOARD_HALF_WIDTH_M &&
                    billboardZ in BILLBOARD_BOTTOM_M..BILLBOARD_TOP_M,
            )
        }

        if (!bestDistance.isFinite()) return null
        return RayHit(
            distance = bestDistance,
            point = SimPoint3(
                origin.x + bestDistance * ray.x,
                origin.y + bestDistance * ray.y,
                origin.z + bestDistance * ray.z,
            ),
            material = bestMaterial,
        )
    }

    private fun texture(material: Int, x: Int, y: Int): Int {
        val noise = hash(seed.toInt() xor material * HASH_MATERIAL xor x * HASH_X xor y * HASH_Y)
        val coarse = noise ushr 1 and 0x7f
        val seam = if ((x + y * 3 + material) % TEXTURE_SEAM_PERIOD == 0) TEXTURE_SEAM_DARKENING else 0
        val stairStripe = if (environment == SimEnvironment.STAIRWELL_LANDING && material == MATERIAL_FLOOR) {
            if (y % STAIR_TREAD_PERIOD == 0) STAIR_TREAD_DARKENING else 0
        } else {
            0
        }
        return (TEXTURE_MIN + coarse + seam - stairStripe).coerceIn(0, 255)
    }

    private fun applyPhone(value: Int, x: Int, y: Int, tNs: Long): Int {
        val pixelBias = ((hash(seed.toInt() xor x * HASH_Y xor y * HASH_X) ushr 1) % SENSOR_NOISE_RANGE) -
            SENSOR_NOISE_RADIUS
        val temporal = if (temporalNoiseDn == 0.0) 0.0 else {
            val timeBits = (tNs xor (tNs ushr 32)).toInt()
            val noise = hash(seed.toInt() xor timeBits xor x * HASH_X xor y * HASH_Y xor TEMPORAL_SEED)
            ((noise ushr 8 and 0xffff) / 65535.0 * 2.0 - 1.0) * temporalNoiseDn
        }
        val exposed = ((value + pixelBias + temporal).coerceIn(0.0, 255.0) / 255.0 * phone.exposure).coerceIn(0.0, 1.0)
        return (exposed.pow(1.0 / phone.gamma) * 255.0).toInt().coerceIn(0, 255)
    }

    private data class RayHit(val distance: Double, val point: SimPoint3, val material: Int)

    companion object {
        const val FULL_WIDTH = 960
        const val FULL_HEIGHT = 720
        const val DECIMATION = 4
        const val LUMA_WIDTH = FULL_WIDTH / DECIMATION
        const val LUMA_HEIGHT = FULL_HEIGHT / DECIMATION

        private const val MATERIAL_LEFT_WALL = 1
        private const val MATERIAL_RIGHT_WALL = 2
        private const val MATERIAL_FLOOR = 3
        private const val MATERIAL_CEILING = 4
        private const val MATERIAL_END_WALL = 5
        private const val MATERIAL_BILLBOARD = 6
        private const val MATERIAL_BRANCH_WALL = 7
        private const val NEAR_PLANE_M = 0.05
        private const val RAY_EPSILON = 1e-8
        private const val BLUR_THRESHOLD_RAD = 0.012
        private const val BLUR_SAMPLES = 5
        private const val TEXTURE_SCALE = 7.0
        private const val TEXTURE_MIN = 40
        private const val TEXTURE_SEAM_PERIOD = 11
        private const val TEXTURE_SEAM_DARKENING = 35
        private const val STAIR_TREAD_PERIOD = 9
        private const val STAIR_TREAD_DARKENING = 48
        private const val STAIR_START_M = 20.0
        private const val STAIR_END_M = 24.0
        private const val STAIR_RISE_M = 1.2
        private const val SENSOR_NOISE_RANGE = 7
        private const val SENSOR_NOISE_RADIUS = 3
        private const val HASH_X = 0x45d9f3b
        private const val HASH_Y = 0x119de1f3
        private const val HASH_MATERIAL = 0x3449
        private const val TEMPORAL_SEED = 0x51ed270b
        private const val SCENE_START_M = -4.0
        private const val SCENE_END_M = 60.0
        private const val JUNCTION_OPEN_START_M = 16.0
        private const val JUNCTION_OPEN_END_M = 25.0
        private const val BRANCH_WIDTH_M = 8.0
        private const val BILLBOARD_DISTANCE_M = 7.0
        private const val BILLBOARD_SPEED = 0.35
        private const val BILLBOARD_SWAY_M = 1.25
        private const val BILLBOARD_LATERAL_SPEED = 0.23
        private const val BILLBOARD_LATERAL_SWAY_M = 1.2
        private const val BILLBOARD_HALF_WIDTH_M = 0.65
        private const val BILLBOARD_BOTTOM_M = 0.2
        private const val BILLBOARD_TOP_M = 2.3
        private const val NS_PER_SECOND = 1_000_000_000.0

        private val SimEnvironment.halfWidthM: Double
            get() = when (this) {
                SimEnvironment.CORRIDOR, SimEnvironment.JUNCTION, SimEnvironment.STAIRWELL_LANDING -> 2.4
                SimEnvironment.OPEN_HALL -> 6.5
            }

        private val SimEnvironment.ceilingHeightM: Double
            get() = when (this) {
                SimEnvironment.OPEN_HALL -> 5.5
                SimEnvironment.STAIRWELL_LANDING -> 3.6
                else -> 3.0
            }

        private fun hash(value: Int): Int {
            var result = value
            result = (result xor (result ushr 16)) * -0x7a143595
            result = (result xor (result ushr 13)) * -0x3d4d51cb
            return result xor (result ushr 16)
        }
    }
}
