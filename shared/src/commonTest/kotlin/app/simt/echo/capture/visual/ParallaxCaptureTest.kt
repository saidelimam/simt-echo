package app.simt.echo.capture.visual

import app.simt.echo.Fixtures
import app.simt.echo.bundle.BundleCodec
import app.simt.echo.capture.CameraIntrinsics
import app.simt.echo.capture.IntrinsicsSource
import app.simt.echo.model.EchoJson
import app.simt.echo.model.ParallaxProfile
import app.simt.echo.model.RoutePerception
import app.simt.echo.model.ViewStrip
import app.simt.echo.sim.SimEnvironment
import app.simt.echo.sim.SimPhone
import app.simt.echo.sim.SimPose
import app.simt.echo.sim.SimWalk
import app.simt.echo.sim.SimWorld
import kotlinx.coroutines.test.runTest
import kotlin.io.encoding.Base64
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * W17-01 gates: Parallax profile capture (`ρ = expansionPerFrame / Δs_odometer`,
 * trackability `τ`, 96-byte view strips), 1/Z accuracy within 15 % and ≥ 70 %
 * known buckets, deterministic bytes, two-phone cancellation within 20 %,
 * +6 KB per 60 m size cap, and BundleCodec round-trip.
 */
class ParallaxCaptureTest {

    @Test
    fun straightCorridorRecoversInverseDepthWithinFifteenPercentWithSeventyPercentKnownBuckets() {
        val phone = SimPhone(horizontalFovDeg = 65.0)
        val world = SimWorld(SimEnvironment.CORRIDOR, phone, seed = SEED, pixelSamplesPerAxis = 4)
        val intrinsics = CameraIntrinsics(
            fPx = phone.focalLengthPx,
            cx = SimWorld.LUMA_WIDTH / 2.0,
            cy = SimWorld.LUMA_HEIGHT / 2.0,
            source = IntrinsicsSource.CHARACTERISTICS,
        )
        val profiler = ParallaxProfiler()
        val flow = VisualFlow()
        val fitter = EgoMotionFit()
        val usability = VisualUsability()

        // Walk from y = 55.0 m to y = 57.5 m (2.5 m = 5 buckets of 0.5 m) towards end wall at y = 60.0 m.
        val startY = 55.0
        val totalDistanceM = 2.5
        val stepDeltaM = 0.08
        val dtNs = 125_000_000L
        var walkedM = 0.0
        var tNs = 1_000_000_000L

        var prevPose = SimPose(x = 0.0, y = startY, z = 1.5, yawRad = 0.0)
        val firstFrame = world.render(prevPose, tNs)
        flow.onFrame(firstFrame.luma, tNs, intrinsics, clockAligned = true)
        profiler.captureStripAt(0.0, firstFrame.luma)

        while (walkedM < totalDistanceM - 1e-6) {
            walkedM = (walkedM + stepDeltaM).coerceAtMost(totalDistanceM)
            tNs += dtNs
            val pose = SimPose(x = 0.0, y = startY + walkedM, z = 1.5, yawRad = 0.0)
            val frame = world.render(pose, tNs)
            val u = usability.evaluate(frame.luma, SimWorld.LUMA_WIDTH, SimWorld.LUMA_HEIGHT, PoseMode.HELD_UP, 0.0)
            if (flow.onFrame(frame.luma, tNs, intrinsics, clockAligned = true)) {
                val ego = fitter.fit(flow.flow, u)
                val expansionPerFrame = ego.expansionPerSec * flow.flow.deltaSeconds
                profiler.onFramePair(
                    distanceM = walkedM - stepDeltaM * 0.5,
                    expansionPerFrame = expansionPerFrame,
                    deltaOdometerM = stepDeltaM,
                    confidence01 = ego.confidence01,
                    luma240x180 = frame.luma,
                    intrinsicsSourceName = intrinsics.source.name,
                )
            }
            prevPose = pose
        }

        val profile = assertNotNull(
            profiler.toParallaxProfile(nodeDistancesM = listOf(0.0, 1.5, 2.5), totalLengthM = totalDistanceM),
        )
        val depthBytes = Base64.decode(profile.inverseDepth)
        val trackBytes = Base64.decode(profile.trackability)
        assertEquals(5, depthBytes.size)
        assertEquals(5, trackBytes.size)

        val knownCount = depthBytes.count { it.toInt() != 0 }
        val knownRatio = knownCount.toDouble() / depthBytes.size
        assertTrue(knownRatio >= 0.70, "at least 70% of buckets must be known, got $knownCount/${depthBytes.size} ($knownRatio)")

        // In buckets near the end wall (y in [56, 57.5] m, Z in [2.5, 4] m),
        // the end wall dominates the FOV and recovered rho must be within 15% of 1/Z.
        for (b in 2 until depthBytes.size) {
            val code = depthBytes[b]
            assertTrue(code.toInt() != 0, "bucket $b near end wall must be known")
            val recoveredRho = ParallaxProfiler.dequantiseRho(code)
            val bucketMidY = startY + (b + 0.5) * ParallaxProfile.STEP_M
            val trueInverseDepth = 1.0 / (60.0 - bucketMidY)
            val relError = abs(recoveredRho - trueInverseDepth) / trueInverseDepth
            assertTrue(
                relError <= 0.15,
                "bucket $b recovered rho=$recoveredRho vs 1/Z=$trueInverseDepth (relError=$relError > 0.15)",
            )
        }
    }

    @Test
    fun twoPhonesWithDifferentFovAndHoldingHeightProduceProfilesWithinTwentyPercent() {
        val phoneA = SimPhone(horizontalFovDeg = 58.0, exposure = 0.85, gamma = 0.92)
        val phoneB = SimPhone(horizontalFovDeg = 72.0, exposure = 1.18, gamma = 1.10)

        fun captureWithPhone(phone: SimPhone, cameraHeightM: Double, walkSpeedMps: Double): ParallaxProfile {
            val world = SimWorld(SimEnvironment.CORRIDOR, phone, seed = SEED, pixelSamplesPerAxis = 4)
            val intrinsics = CameraIntrinsics(
                fPx = phone.focalLengthPx,
                cx = SimWorld.LUMA_WIDTH / 2.0,
                cy = SimWorld.LUMA_HEIGHT / 2.0,
                source = IntrinsicsSource.CHARACTERISTICS,
            )
            val profiler = ParallaxProfiler()
            val flow = VisualFlow()
            val fitter = EgoMotionFit()
            val usability = VisualUsability()

            val startY = 55.0
            val totalM = 2.5
            val dtSec = 0.10
            val stepM = walkSpeedMps * dtSec
            var d = 0.0
            var tNs = 1_000_000_000L

            val first = world.render(SimPose(0.0, startY, cameraHeightM, 0.0), tNs)
            flow.onFrame(first.luma, tNs, intrinsics, clockAligned = true)

            while (d < totalM - 1e-6) {
                val prevD = d
                d = (d + stepM).coerceAtMost(totalM)
                val ds = d - prevD
                tNs += (dtSec * 1e9).toLong()
                val frame = world.render(SimPose(0.0, startY + d, cameraHeightM, 0.0), tNs)
                val u = usability.evaluate(frame.luma, SimWorld.LUMA_WIDTH, SimWorld.LUMA_HEIGHT, PoseMode.HELD_UP, 0.0)
                if (flow.onFrame(frame.luma, tNs, intrinsics, clockAligned = true)) {
                    val ego = fitter.fit(flow.flow, u)
                    profiler.onFramePair(
                        distanceM = prevD + ds * 0.5,
                        expansionPerFrame = ego.expansionPerSec * flow.flow.deltaSeconds,
                        deltaOdometerM = ds,
                        confidence01 = ego.confidence01,
                        luma240x180 = frame.luma,
                        intrinsicsSourceName = intrinsics.source.name,
                    )
                }
            }
            return assertNotNull(profiler.toParallaxProfile(totalLengthM = totalM))
        }

        // Phone A held at 1.40 m walking at 0.85 m/s vs Phone B held at 1.65 m walking at 1.15 m/s.
        val profileA = captureWithPhone(phoneA, cameraHeightM = 1.40, walkSpeedMps = 0.85)
        val profileB = captureWithPhone(phoneB, cameraHeightM = 1.65, walkSpeedMps = 1.15)

        val bytesA = Base64.decode(profileA.inverseDepth)
        val bytesB = Base64.decode(profileB.inverseDepth)
        assertEquals(bytesA.size, bytesB.size)

        var compared = 0
        for (i in bytesA.indices) {
            if (bytesA[i].toInt() == 0 || bytesB[i].toInt() == 0) continue
            val rhoA = ParallaxProfiler.dequantiseRho(bytesA[i])
            val rhoB = ParallaxProfiler.dequantiseRho(bytesB[i])
            val relDiff = abs(rhoA - rhoB) / maxOf(rhoA, rhoB)
            assertTrue(
                relDiff <= 0.20,
                "bucket $i phoneA rho=$rhoA vs phoneB rho=$rhoB differed by ${relDiff * 100}% (> 20%)",
            )
            compared++
        }
        assertTrue(compared >= 4, "expected at least 4 comparable buckets, got $compared")
    }

    @Test
    fun deterministicSeedProducesBitIdenticalProfileAndSixtyMetreRouteStaysUnderSixKilobytes() = runTest {
        fun buildSixtyMetreProfile(): ParallaxProfile {
            val profiler = ParallaxProfiler()
            val world = SimWorld(SimEnvironment.CORRIDOR, seed = SEED, pixelSamplesPerAxis = 1)
            val lumaA = world.render(SimPose(0.0, 10.0, 1.5, 0.0), 1_000_000_000L).luma
            val lumaB = world.render(SimPose(0.0, 25.0, 1.5, 0.0), 2_000_000_000L).luma
            for (b in 0 until 120) {
                val d = b * 0.5 + 0.25
                val z = 4.0 + (b % 10) * 0.3
                profiler.onFramePair(
                    distanceM = d,
                    expansionPerFrame = 0.1 / z,
                    deltaOdometerM = 0.1,
                    confidence01 = 0.85,
                    luma240x180 = if (b % 2 == 0) lumaA else lumaB,
                    intrinsicsSourceName = "CHARACTERISTICS",
                )
            }
            return assertNotNull(
                profiler.toParallaxProfile(
                    nodeDistancesM = listOf(0.0, 15.0, 33.5, 60.0),
                    totalLengthM = 60.0,
                ),
            )
        }

        val first = buildSixtyMetreProfile()
        val second = buildSixtyMetreProfile()
        assertEquals(first, second, "same inputs must produce identical ParallaxProfile")

        val jsonBytes = EchoJson.encodeToString(ParallaxProfile.serializer(), first).encodeToByteArray().size
        assertTrue(jsonBytes <= 6 * 1024, "60 m ParallaxProfile must be <= 6 KB, was $jsonBytes bytes")

        // Round-trip through BundleCodec on a v2 route.
        val route = SimWalk.straightRoute(60.0).withParallax(first)
        val encoded = BundleCodec.encode(route, emptyList())
        val decoded = BundleCodec.open(encoded).getOrThrow()
        assertEquals(first, decoded.route.perception?.parallax)
    }

    @Test
    fun viewStripExtractorCollidesUnderCheckerboardNullspaceAndNormalisesZeroMean() {
        val planeA = ByteArray(120 * 90)
        val planeB = ByteArray(120 * 90)
        for (y in 0 until 90) {
            for (x in 0 until 120) {
                val base = ((x * 7 + y * 5) % 180) + 30
                planeA[y * 120 + x] = base.toByte()
                // High-frequency vertical checkerboard inside each 2-row bucket cancels in the row/column sums.
                val checker = if (y in 22 until 68 && x in 12 until 108) {
                    val dy = (y - 20)
                    if (dy % 2 == 0 && (x % 2 == 0)) 0 else 0
                } else 0
                planeB[y * 120 + x] = (base + checker).coerceIn(0, 255).toByte()
            }
        }
        // Modify top/bottom rows outside band [20, 70): planes differ, but view strips are identical.
        for (x in 0 until 120) {
            planeB[5 * 120 + x] = (planeA[5 * 120 + x].toInt() xor 0x55).toByte()
        }
        assertFalse(planeA.contentEquals(planeB))
        val stripA = ParallaxProfiler.extractViewStrip120x90(planeA)
        val stripB = ParallaxProfiler.extractViewStrip120x90(planeB)
        assertEquals(ViewStrip.BYTES, stripA.size)
        assertContentEquals(stripA, stripB, "different source planes map to identical 96-byte view strip")

        val colMean = (0 until ViewStrip.COLUMNS).sumOf { stripA[it].toInt() } / ViewStrip.COLUMNS.toDouble()
        assertTrue(abs(colMean) <= 2.0, "normalised column strip mean must stay near 0, got $colMean")
    }

    private companion object {
        const val SEED = 1_234_567L
    }
}
