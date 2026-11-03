package app.simt.echo.capture.visual

import app.simt.echo.capture.CameraIntrinsics
import app.simt.echo.capture.IntrinsicsSource
import app.simt.echo.sim.SimEnvironment
import app.simt.echo.sim.SimPhone
import app.simt.echo.sim.SimPose
import app.simt.echo.sim.SimWorld
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class EgoMotionFitTest {
    @Test
    fun yawUncertaintyIsSeparateFromRadialConfidence() {
        val flow = egoMotionTestFlow(foeX = 0.18, foeY = -0.11)
        for (i in 0 until flow.count) flow.dy[i] += if (i % 2 == 0) 0.0001 else -0.0001
        val result = EgoMotionFit().fit(flow, usability01 = 1.0)
        assertTrue(result.confidence01 > 0.5)
        assertTrue(result.yawSigmaRadPerSec.isFinite() && result.yawSigmaRadPerSec > 0.0)
        assertFalse(result.yawResolved)
        for (yaw in listOf(-0.0005, 0.0005)) {
            val resolved = EgoMotionFit().fit(egoMotionTestFlow(yawResidualRadPerSec = yaw), usability01 = 1.0)
            assertEquals(yaw, resolved.yawResidualRadPerSec, 1e-10)
            assertTrue(resolved.yawResolved)
            assertTrue(resolved.yawSigmaRadPerSec < abs(yaw) / 3.0)
        }
    }

    @Test
    fun exactRadialFieldsRecoverRatesAndFocusAcrossTheGrid() {
        val fitter = EgoMotionFit()
        for (dt in listOf(0.04, 0.125, 0.25)) {
            for (expansion in listOf(-0.2, 0.08, 0.24)) {
                for ((foeX, foeY) in listOf(-0.2 to 0.1, 0.0 to 0.0, 0.18 to -0.12)) {
                    val flow = egoMotionTestFlow(expansion, foeX, foeY, deltaSeconds = dt)
                    val result = fitter.fit(flow, usability01 = 1.0)
                    assertEquals(expansion, result.expansionPerSec, EXACT_TOLERANCE)
                    assertTrue(result.hasFoe)
                    assertEquals(foeX, result.foeX, EXACT_TOLERANCE)
                    assertEquals(foeY, result.foeY, EXACT_TOLERANCE)
                    assertEquals(atan(foeX), result.travelOffsetRad, EXACT_TOLERANCE)
                    assertEquals(0.0, result.yawResidualRadPerSec, EXACT_TOLERANCE)
                    assertEquals(BLOCK_CAPACITY, result.inliers)
                    assertTrue(result.confidence01 > 0.5 && result.confidence01 <= 1.0)
                }
            }
        }
    }

    @Test
    fun jointFitSeparatesGyroScaleYawDriftFromExpansionAndOffAxisTravel() {
        val fitter = EgoMotionFit()
        for (yawRate in listOf(-0.0005, 0.0005)) {
            val result = fitter.fit(
                egoMotionTestFlow(0.16, 0.18, -0.11, yawResidualRadPerSec = yawRate),
                usability01 = 1.0,
            )
            assertEquals(0.16, result.expansionPerSec, EXACT_TOLERANCE)
            assertEquals(yawRate, result.yawResidualRadPerSec, EXACT_TOLERANCE)
            assertTrue(result.hasFoe)
            assertEquals(0.18, result.foeX, EXACT_TOLERANCE)
            assertEquals(-0.11, result.foeY, EXACT_TOLERANCE)
            assertEquals(atan(0.18), result.travelOffsetRad, EXACT_TOLERANCE)
            assertEquals(BLOCK_CAPACITY, result.inliers)
            assertTrue(result.confidence01 > 0.5)
        }
    }

    @Test
    fun smallPureYawResidualDoesNotInventExpansionOrFocus() {
        for (yawRate in listOf(-0.0005, 0.0005)) {
            val result = EgoMotionFit().fit(
                egoMotionTestFlow(expansionPerSec = 0.0, yawResidualRadPerSec = yawRate),
                usability01 = 1.0,
            )
            assertEquals(0.0, result.expansionPerSec, EXACT_TOLERANCE)
            assertEquals(yawRate, result.yawResidualRadPerSec, EXACT_TOLERANCE)
            assertAbsentFocus(result)
            assertEquals(BLOCK_CAPACITY, result.inliers)
            assertTrue(result.confidence01 > 0.5)
        }
    }

    @Test
    fun standingAndSidewaysMotionClearFocusInTheSameReusableResult() {
        val fitter = EgoMotionFit()
        val result = fitter.fit(egoMotionTestFlow(foeX = 0.2, foeY = -0.1), usability01 = 1.0)
        assertTrue(result.hasFoe)

        val standing = fitter.fit(egoMotionTestFlow(expansionPerSec = 0.0), usability01 = 1.0)
        assertSame(result, standing)
        assertAbsentFocus(standing)
        assertEquals(0.0, standing.expansionPerSec, EXACT_TOLERANCE)
        assertEquals(0.0, standing.yawResidualRadPerSec, EXACT_TOLERANCE)
        assertEquals(BLOCK_CAPACITY, standing.inliers)
        assertTrue(standing.confidence01 > 0.5, "textured zero motion is a valid observation")

        val sidewaysFlow = egoMotionTestFlow(expansionPerSec = 0.0)
        sidewaysFlow.dx.fill(0.12 * sidewaysFlow.deltaSeconds)
        sidewaysFlow.dy.fill(-0.03 * sidewaysFlow.deltaSeconds)
        val sideways = fitter.fit(sidewaysFlow, usability01 = 1.0)
        assertSame(result, sideways)
        assertAbsentFocus(sideways)
        assertEquals(0.0, sideways.expansionPerSec, EXACT_TOLERANCE)
        assertEquals(0.0, sideways.yawResidualRadPerSec, EXACT_TOLERANCE)
        assertEquals(BLOCK_CAPACITY, sideways.inliers)
        assertTrue(sideways.confidence01 > 0.5)
    }

    @Test
    fun fiveBlocksCannotClaimHighConfidenceAndUnusedSlotsAreIgnored() {
        val flow = egoMotionTestFlow()
        flow.count = 5
        for (array in listOf(flow.x, flow.y, flow.dx, flow.dy, flow.texture, flow.quality)) {
            array.fill(Double.NaN, fromIndex = flow.count)
        }
        val result = EgoMotionFit().fit(flow, usability01 = 1.0)
        assertTrue(result.confidence01 in 0.0..0.5)
        assertTrue(result.inliers in 0..5)
        assertTrue(result.expansionPerSec.isFinite())
        assertTrue(result.yawResidualRadPerSec.isFinite())
    }

    @Test
    fun missingTextureAndFlatHoldCannotClaimUsableConfidence() {
        val fitter = EgoMotionFit()
        val textured = egoMotionTestFlow()
        assertTrue(fitter.fit(textured, usability01 = 1.0).confidence01 > 0.5)
        val blank = egoMotionTestFlow()
        blank.texture.fill(0.0)
        assertTrue(fitter.fit(blank, usability01 = 1.0).confidence01 in 0.0..<0.3)
        assertTrue(fitter.fit(textured, usability01 = 0.0).confidence01 in 0.0..<0.3)
    }

    @Test
    fun malformedMetadataFailsClosedWithoutRetainingThePreviousFit() {
        for (count in listOf(-1, 0, BLOCK_CAPACITY + 1)) {
            val flow = egoMotionTestFlow()
            flow.count = count
            assertFailsClosed(flow)
        }
        for (dt in listOf(0.0, -0.125, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            val flow = egoMotionTestFlow()
            flow.deltaSeconds = dt
            assertFailsClosed(flow)
        }
        for (focal in listOf(0.0, -94.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            val flow = egoMotionTestFlow()
            flow.fPx = focal
            assertFailsClosed(flow)
        }
        for (usability in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertFailsClosed(egoMotionTestFlow(), usability)
        }
    }

    @Test
    fun nonFiniteBlockDataAndSingularGeometryFailClosed() {
        for (field in 0 until 6) {
            for (invalid in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
                val flow = egoMotionTestFlow()
                val array = when (field) {
                    0 -> flow.x
                    1 -> flow.y
                    2 -> flow.dx
                    3 -> flow.dy
                    4 -> flow.texture
                    else -> flow.quality
                }
                array.fill(invalid)
                assertFailsClosed(flow)
            }
        }
        val singular = egoMotionTestFlow()
        singular.x.fill(0.0)
        singular.y.fill(0.0)
        assertFailsClosed(singular)
    }

    @Test
    fun renderedForwardTranslationRecoversFiniteExpansionWithinEightPercent() {
        val world = SimWorld(SimEnvironment.CORRIDOR, seed = TEST_SEED)
        val fitter = EgoMotionFit()
        for (distance in listOf(0.04, 0.08)) {
            val to = START.copy(y = START.y + distance)
            val flow = measuredFlow(world, START, to)
            val result = fitter.fit(flow, usability01 = 1.0)
            val expected = (to.y - START.y) / (END_WALL_Y_M - to.y) / INTERVAL_SECONDS
            assertRelativeExpansion(expected, result.expansionPerSec, 0.08)
            assertTrue(result.hasFoe)
            assertTrue(result.inliers >= MIN_BLOCKS)
            assertTrue(result.confidence01 > 0.5 && result.confidence01 <= 1.0)
        }
    }

    @Test
    fun renderedTenDegreeHoldOffsetsRecoverTravelDirectionWithinTwoDegrees() {
        for (holdYaw in listOf(-10.0, 10.0)) {
            val phone = SimPhone(holdYawOffsetRad = holdYaw * PI / 180.0)
            val world = SimWorld(SimEnvironment.CORRIDOR, phone, TEST_SEED)
            // Walk diagonally with a fixed camera normal to the wall, keeping depth uniform.
            val travelYaw = START.yawRad - phone.holdYawOffsetRad
            val to = START.copy(x = START.x + 0.08 * sin(travelYaw), y = START.y + 0.08 * cos(travelYaw))
            val flow = measuredFlow(world, START, to)
            val result = EgoMotionFit().fit(flow, usability01 = 1.0)
            assertTrue(result.hasFoe)
            assertEquals(-phone.holdYawOffsetRad, atan(result.foeX), 2.0 * PI / 180.0)
            assertEquals(atan(result.foeX), result.travelOffsetRad, EXACT_TOLERANCE)
            assertTrue(result.inliers >= MIN_BLOCKS)
            assertTrue(result.confidence01 > 0.5)
        }
    }

    @Test
    fun rotatedHoldRecoversTravelDirectionOnAMixedDepthWall() {
        val world = SimWorld(SimEnvironment.CORRIDOR, seed = TEST_SEED)
        for (holdDegrees in listOf(-10.0, 10.0)) {
            val hold = holdDegrees * PI / 180.0
            val from = START.copy(yawRad = hold)
            val to = from.copy(y = from.y + 0.08)
            val result = EgoMotionFit().fit(measuredFlow(world, from, to), usability01 = 1.0)
            assertTrue(result.hasFoe, "hold $holdDegrees° must publish a direction")
            assertEquals(-hold, result.travelOffsetRad, 2.0 * PI / 180.0)
            assertTrue(result.inliers >= MIN_BLOCKS)
            assertTrue(result.confidence01 > 0.5)
            assertFalse(result.yawResolved, "depth variation must not become a trusted yaw")
        }
        val pitch = 8.0 * PI / 180.0
        val pitchedFrom = START.copy(pitchRad = pitch)
        val pitched = EgoMotionFit().fit(
            measuredFlow(world, pitchedFrom, pitchedFrom.copy(y = pitchedFrom.y + 0.08)),
            usability01 = 1.0,
        )
        assertTrue(pitched.hasFoe)
        assertEquals(0.0, pitched.travelOffsetRad, 2.0 * PI / 180.0)
        assertEquals(pitch, atan(pitched.foeY), 2.0 * PI / 180.0)
        assertTrue(pitched.confidence01 > 0.5)
    }

    @Test
    fun contradictoryDirectionsStayAbsentAndBelowTheConfidenceGate() {
        val flow = egoMotionTestFlow(expansionPerSec = 0.16, foeX = -0.2)
        for (i in 6 until flow.count) {
            val otherFocus = 0.25
            flow.dx[i] = 0.16 * (flow.x[i] - otherFocus) * flow.deltaSeconds
            flow.dy[i] = 0.16 * flow.y[i] * flow.deltaSeconds
        }
        val result = EgoMotionFit().fit(flow, usability01 = 1.0)
        assertAbsentFocus(result)
        assertTrue(result.confidence01 < 0.5, "a tied rival direction must not look usable: ${result.confidence01}")
    }

    @Test
    fun coherentOutlierPlacementsCannotBeBothWrongAndConfident() {
        val velocities = listOf(0.024 to -0.012, -0.03 to 0.015, 0.01 to 0.02)
        for ((dx, dy) in velocities) {
            forEachFourBlocks { blocks ->
                val flow = egoMotionTestFlow(expansionPerSec = 0.16)
                for (index in blocks) {
                    flow.dx[index] = dx
                    flow.dy[index] = dy
                }
                val result = EgoMotionFit().fit(flow, usability01 = 1.0)
                val relativeError = abs(result.expansionPerSec - 0.16) / 0.16
                if (relativeError > 0.15) {
                    assertTrue(result.confidence01 < 0.5, "blocks ${blocks.toList()} error $relativeError confidence ${result.confidence01}")
                }
            }
        }
        val spotlight = egoMotionTestFlow(expansionPerSec = 0.16)
        for (index in intArrayOf(2, 3, 10, 11)) {
            spotlight.dx[index] = 0.024
            spotlight.dy[index] = -0.012
        }
        val spotlightResult = EgoMotionFit().fit(spotlight, usability01 = 1.0)
        assertRelativeExpansion(0.16, spotlightResult.expansionPerSec, 0.15)
        assertTrue(spotlightResult.confidence01 > 0.5)
        for (row in 0..1) {
            for (column in 0..2) {
                val flow = egoMotionTestFlow(expansionPerSec = 0.16)
                val blocks = intArrayOf(row * 4 + column, row * 4 + column + 1, (row + 1) * 4 + column, (row + 1) * 4 + column + 1)
                for (index in blocks) {
                    flow.dx[index] = 0.024
                    flow.dy[index] = -0.012
                }
                val result = EgoMotionFit().fit(flow, usability01 = 1.0)
                assertRelativeExpansion(0.16, result.expansionPerSec, 0.15)
            }
        }
    }

    @Test
    fun navigateFrameSpacingOnTheEndWallStaysAUsableFit() {
        val world = SimWorld(SimEnvironment.CORRIDOR, seed = TEST_SEED)
        val from = START
        val to = from.copy(y = from.y + 0.5)
        val flow = measuredFlow(world, from, to, intervalNs = 500_000_000L)
        val result = EgoMotionFit().fit(flow, usability01 = 1.0)
        val expected = (to.y - from.y) / (END_WALL_Y_M - to.y) / 0.5
        assertTrue(result.hasFoe)
        assertTrue(result.inliers >= MIN_BLOCKS)
        assertTrue(result.confidence01 > 0.5 && result.confidence01 <= 1.0)
        assertRelativeExpansion(expected, result.expansionPerSec, 0.15)
    }

    @Test
    fun fourMovingBillboardVectorsDoNotBiasRenderedExpansionBeyondFifteenPercent() {
        val world = SimWorld(SimEnvironment.CORRIDOR, seed = TEST_SEED)
        val to = START.copy(y = START.y + 0.08)
        val flow = measuredFlow(world, START, to)
        assertEquals(BLOCK_CAPACITY, flow.count, "the outlier fixture must replace four of twelve measured blocks")
        // A camera-relative billboard at constant depth moves 6 cm right and 3 cm up per frame.
        val billboardDepthM = 2.5
        val billboardRightStepM = 0.06
        val billboardUpStepM = 0.03
        for (i in intArrayOf(1, 2, 5, 6)) {
            flow.dx[i] = billboardRightStepM / billboardDepthM
            flow.dy[i] = -billboardUpStepM / billboardDepthM
        }
        val result = EgoMotionFit().fit(flow, usability01 = 1.0)
        val expected = (to.y - START.y) / (END_WALL_Y_M - to.y) / INTERVAL_SECONDS
        assertRelativeExpansion(expected, result.expansionPerSec, 0.15)
        assertTrue(result.inliers in MIN_BLOCKS..8, "the static majority must remain inliers, not the billboard")
        assertTrue(result.confidence01 > 0.5 && result.confidence01 <= 1.0)
        assertTrue(result.hasFoe)
    }

    private fun measuredFlow(world: SimWorld, from: SimPose, to: SimPose, intervalNs: Long = INTERVAL_NS): BlockFlow {
        val intrinsics = CameraIntrinsics(
            fPx = world.phone.focalLengthPx,
            cx = SimWorld.LUMA_WIDTH / 2.0,
            cy = SimWorld.LUMA_HEIGHT / 2.0,
            source = IntrinsicsSource.CHARACTERISTICS,
        )
        val tracker = VisualFlow()
        assertFalse(tracker.onFrame(world.render(from, START_NS).luma, START_NS, intrinsics))
        assertTrue(
            tracker.onFrame(
                world.render(to, START_NS + intervalNs).luma,
                START_NS + intervalNs,
                intrinsics,
                deltaYawRad = to.yawRad - from.yawRad,
                deltaPitchRad = to.pitchRad - from.pitchRad,
            ),
        )
        assertTrue(tracker.flow.count in MIN_BLOCKS..BLOCK_CAPACITY, "need at least six measured blocks, got ${tracker.flow.count}")
        assertEquals(intervalNs / 1_000_000_000.0, tracker.flow.deltaSeconds, EXACT_TOLERANCE)
        return tracker.flow
    }

    private fun forEachFourBlocks(check: (IntArray) -> Unit) {
        for (a in 0..8) {
            for (b in a + 1..9) {
                for (c in b + 1..10) {
                    for (d in c + 1..11) check(intArrayOf(a, b, c, d))
                }
            }
        }
    }

    private fun assertRelativeExpansion(expected: Double, actual: Double, tolerance: Double) {
        assertTrue(expected > 0.0, "fixture expansion must be nonzero")
        val relativeError = abs(actual - expected) / expected
        assertTrue(relativeError <= tolerance, "expansion $actual versus geometric $expected: relative error $relativeError > $tolerance")
    }

    private fun assertAbsentFocus(result: VisualEgo) {
        assertFalse(result.hasFoe)
        assertTrue(result.foeX.isNaN(), "absent FOE x must be primitive NaN")
        assertTrue(result.foeY.isNaN(), "absent FOE y must be primitive NaN")
        assertTrue(result.travelOffsetRad.isNaN(), "absent travel offset must be primitive NaN")
    }

    private fun assertFailsClosed(flow: BlockFlow, usability01: Double = 1.0) {
        val fitter = EgoMotionFit()
        val valid = egoMotionTestFlow(foeX = 0.2, foeY = -0.1)
        val previous = fitter.fit(valid, usability01 = 1.0)
        assertTrue(previous.hasFoe)
        val rejected = fitter.fit(flow, usability01)
        assertSame(previous, rejected)
        assertAbsentFocus(rejected)
        assertEquals(0.0, rejected.confidence01)
        assertEquals(0, rejected.inliers)
        assertEquals(0.0, rejected.expansionPerSec)
        assertEquals(0.0, rejected.yawResidualRadPerSec)
        val recovered = fitter.fit(valid, usability01 = 1.0)
        assertSame(previous, recovered)
        assertTrue(recovered.hasFoe)
        assertEquals(0.16, recovered.expansionPerSec, EXACT_TOLERANCE)
        assertTrue(recovered.confidence01 > 0.5)
    }

    companion object {
        private const val TEST_SEED = 1_234_567L
        private const val START_NS = 1_000_000_000L
        private const val INTERVAL_NS = 125_000_000L
        private const val INTERVAL_SECONDS = 0.125
        private const val END_WALL_Y_M = 60.0
        private const val BLOCK_CAPACITY = 12
        private const val MIN_BLOCKS = 6
        private const val EXACT_TOLERANCE = 1e-9
        private val START = SimPose(x = 0.0, y = 57.0, z = 1.5, yawRad = 0.0)
    }
}

internal fun egoMotionTestFlow(
    expansionPerSec: Double = 0.16,
    foeX: Double = 0.0,
    foeY: Double = 0.0,
    yawResidualRadPerSec: Double = 0.0,
    deltaSeconds: Double = 0.125,
): BlockFlow {
    val flow = BlockFlow()
    flow.count = 12
    flow.deltaSeconds = deltaSeconds
    flow.fPx = SimPhone().focalLengthPx / 2.0
    for (row in 0 until 3) {
        for (column in 0 until 4) {
            val i = row * 4 + column
            val x = (column - 1.5) * 0.32
            val y = (row - 1.0) * 0.32
            flow.x[i] = x
            flow.y[i] = y
            flow.dx[i] = (expansionPerSec * (x - foeX) - (1.0 + x * x) * yawResidualRadPerSec) * deltaSeconds
            flow.dy[i] = (expansionPerSec * (y - foeY) - x * y * yawResidualRadPerSec) * deltaSeconds
            flow.texture[i] = 256.0
            flow.quality[i] = 1.0
        }
    }
    return flow
}