package app.simt.echo.media

import app.simt.echo.model.NodeType
import app.simt.echo.model.RouteNode
import app.simt.echo.model.TurnDir
import androidx.compose.ui.geometry.Rect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** W1-05 gate: keyframe specs, file naming, clamping, padding. */
class MediaToolsTest {

    private fun node(id: String, type: NodeType, tMs: Long? = 40_000L): RouteNode =
        RouteNode(
            id = id,
            type = type,
            atDistanceM = 20.0,
            label = "Test",
            turn = if (type == NodeType.TURN) TurnDir.RIGHT else null,
            captureTMs = tMs,
        )

    // ── keyframeTimes ──────────────────────────────────────────────────────

    @Test
    fun turnNodeGetsThreeFramesAroundCaptureTime() {
        val times = keyframeTimes(node("n1", NodeType.TURN, 40_000L))
        assertEquals(listOf(38_000L, 39_500L, 41_500L), times)
    }

    @Test
    fun checkpointNodeGetsTwoFrames() {
        val times = keyframeTimes(node("n2", NodeType.CHECKPOINT, 20_000L))
        assertEquals(listOf(18_500L, 20_000L), times)
    }

    @Test
    fun levelChangeNodeGetsTwoFrames() {
        val times = keyframeTimes(node("n3", NodeType.LEVEL_CHANGE, 10_000L))
        assertEquals(listOf(8_500L, 10_000L), times)
    }

    @Test
    fun destinationNodeGetsTwoFrames() {
        val times = keyframeTimes(node("n4", NodeType.DESTINATION, 60_000L))
        assertEquals(listOf(58_500L, 60_000L), times)
    }

    @Test
    fun startNodeHasNoVideoKeyframes() {
        val times = keyframeTimes(node("n0", NodeType.START, 0L))
        assertEquals(emptyList(), times)
    }

    @Test
    fun nullCaptureTimeYieldsNoKeyframes() {
        val times = keyframeTimes(node("n5", NodeType.TURN, null))
        assertEquals(emptyList(), times)
    }

    // ── heroIndex ──────────────────────────────────────────────────────────

    @Test
    fun turnHeroIsMiddleFrame() {
        assertEquals(1, heroIndex(NodeType.TURN, 3))
    }

    @Test
    fun nonTurnHeroIsLastFrame() {
        assertEquals(1, heroIndex(NodeType.CHECKPOINT, 2))
        assertEquals(1, heroIndex(NodeType.DESTINATION, 2))
    }

    @Test
    fun zeroCountHeroIsZero() {
        assertEquals(0, heroIndex(NodeType.TURN, 0))
    }

    @Test
    fun turnHeroClampsToLastWhenFewerThanTwo() {
        assertEquals(0, heroIndex(NodeType.TURN, 1))
    }

    // ── keyframeSpecs ──────────────────────────────────────────────────────

    @Test
    fun specsCoverEveryNodeWithCorrectShape() {
        val nodes = listOf(
            node("s", NodeType.START, 0L),
            node("t1", NodeType.TURN, 40_000L),
            node("c1", NodeType.CHECKPOINT, 20_000L),
            node("d", NodeType.DESTINATION, 60_000L),
        )
        val specs = keyframeSpecs(nodes)
        assertEquals(4, specs.size)

        // START: no frames, hero 0.
        assertEquals(emptyList<Long>(), specs[0].timesMs)
        assertEquals(0, specs[0].heroIndex)

        // TURN: 3 frames, hero 1.
        assertEquals(3, specs[1].timesMs.size)
        assertEquals(1, specs[1].heroIndex)

        // CHECKPOINT: 2 frames, hero 1 (last).
        assertEquals(2, specs[2].timesMs.size)
        assertEquals(1, specs[2].heroIndex)

        // DESTINATION: 2 frames, hero 1 (last).
        assertEquals(2, specs[3].timesMs.size)
        assertEquals(1, specs[3].heroIndex)
    }

    // ── fileName ───────────────────────────────────────────────────────────

    @Test
    fun fileNameFollowsKfConvention() {
        val spec = KeyframeSpec("node-42", listOf(1L, 2L, 3L), 1)
        assertEquals("kf/node-42_0.jpg", spec.fileName(0))
        assertEquals("kf/node-42_1.jpg", spec.fileName(1))
        assertEquals("kf/node-42_2.jpg", spec.fileName(2))
    }

    // ── clampFrameTime ─────────────────────────────────────────────────────

    @Test
    fun clampKeepsTimeWithinBounds() {
        assertEquals(5_000L, clampFrameTime(5_000L, 100_000L))
    }

    @Test
    fun clampNegativeToZero() {
        assertEquals(0L, clampFrameTime(-500L, 100_000L))
    }

    @Test
    fun clampBeyondDurationMinus100() {
        assertEquals(99_900L, clampFrameTime(100_000L, 100_000L))
    }

    @Test
    fun clampZeroDurationYieldsZero() {
        assertEquals(0L, clampFrameTime(5_000L, 0L))
    }

    @Test
    fun clampNegativeDurationYieldsZero() {
        assertEquals(0L, clampFrameTime(5_000L, -1L))
    }

    @Test
    fun clampShortDuration() {
        // duration 200 ms → valid range [0, 100]
        assertEquals(100L, clampFrameTime(500L, 200L))
        assertEquals(0L, clampFrameTime(-10L, 200L))
    }

    // ── padBox ─────────────────────────────────────────────────────────────

    @Test
    fun padBoxWidensByEightPercent() {
        val box = CropBox(0.25f, 0.25f, 0.75f, 0.75f)
        val padded = padBox(box)
        // width = 0.5, pad = 0.04 on each side
        assertEquals(0.21f, padded.left, 0.001f)
        assertEquals(0.21f, padded.top, 0.001f)
        assertEquals(0.79f, padded.right, 0.001f)
        assertEquals(0.79f, padded.bottom, 0.001f)
    }

    @Test
    fun padBoxClampsToUnitSquare() {
        val box = CropBox(0f, 0f, 0.1f, 0.1f)
        val padded = padBox(box)
        assertEquals(0f, padded.left, 0.001f)
        assertEquals(0f, padded.top, 0.001f)
        assertTrue(padded.right > 0.1f)
        assertTrue(padded.bottom > 0.1f)
    }

    @Test
    fun padBoxClampsTopRightCorner() {
        val box = CropBox(0.9f, 0.9f, 1f, 1f)
        val padded = padBox(box)
        assertTrue(padded.left < 0.9f)
        assertTrue(padded.top < 0.9f)
        assertEquals(1f, padded.right, 0.001f)
        assertEquals(1f, padded.bottom, 0.001f)
    }

    @Test
    fun padBoxCustomFraction() {
        val box = CropBox(0.3f, 0.3f, 0.7f, 0.7f)
        val padded = padBox(box, padFraction = 0.1f)
        // width = 0.4, pad = 0.04 on each side
        assertEquals(0.26f, padded.left, 0.001f)
        assertEquals(0.74f, padded.right, 0.001f)
    }

    // ── cropBoxFromRect ────────────────────────────────────────────────────

    @Test
    fun cropBoxNormalisesATopLeftToBottomRightDrag() {
        // 1000 × 500 px canvas; the drag runs (100, 50) → (300, 150).
        val box = cropBoxFromRect(Rect(100f, 50f, 300f, 150f), 1000f, 500f)
        assertEquals(0.1f, box.left, 0.001f)
        assertEquals(0.1f, box.top, 0.001f)
        assertEquals(0.3f, box.right, 0.001f)
        assertEquals(0.3f, box.bottom, 0.001f)
    }

    @Test
    fun cropBoxHandlesInvertedDrags() {
        // The same box dragged right→left / bottom→top must map identically.
        val normal = cropBoxFromRect(Rect(100f, 50f, 300f, 150f), 1000f, 500f)
        val inverted = cropBoxFromRect(Rect(300f, 150f, 100f, 50f), 1000f, 500f)
        assertEquals(normal, inverted)
    }

    @Test
    fun cropBoxClampsDragsPastTheCanvasEdges() {
        // Overshooting the canvas on every side yields a full-frame crop.
        val box = cropBoxFromRect(Rect(-50f, -100f, 1100f, 600f), 1000f, 500f)
        assertEquals(0f, box.left, 0.001f)
        assertEquals(0f, box.top, 0.001f)
        assertEquals(1f, box.right, 0.001f)
        assertEquals(1f, box.bottom, 0.001f)
    }

    @Test
    fun cropBoxDegenerateZeroAreaDrag() {
        // A tap with no movement is a zero-area box at the tap position —
        // the current overlay accepts it; the mapping must stay stable.
        val box = cropBoxFromRect(Rect(250f, 250f, 250f, 250f), 1000f, 500f)
        assertEquals(0.25f, box.left, 0.001f)
        assertEquals(0.25f, box.right, 0.001f)
        assertEquals(0.5f, box.top, 0.001f)
        assertEquals(0.5f, box.bottom, 0.001f)
    }

    @Test
    fun cropBoxFullFrameDrag() {
        val box = cropBoxFromRect(Rect(0f, 0f, 1000f, 500f), 1000f, 500f)
        assertEquals(CropBox(0f, 0f, 1f, 1f), box)
    }

    @Test
    fun cropBoxSurvivesAZeroSizeCanvas() {
        // Before first layout the canvas can measure 0 × 0; the 1 px floor
        // keeps the division finite.
        val box = cropBoxFromRect(Rect(0f, 0f, 1f, 1f), 0f, 0f)
        assertEquals(0f, box.left, 0.001f)
        assertEquals(1f, box.right, 0.001f)
        assertEquals(0f, box.top, 0.001f)
        assertEquals(1f, box.bottom, 0.001f)
    }
}
