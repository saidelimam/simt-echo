package app.simt.echo.ui.screens.review

import app.simt.echo.ai.AiCandidate
import app.simt.echo.model.NodeType
import app.simt.echo.model.PathPoint
import app.simt.echo.model.RouteNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * W13-05 gate: the route-story selection model is pure — distance-ordered
 * strip, one shared selection for strip/cursor/timeline, nudge jumps,
 * null-safe partial decode, geometry-free merge.
 */
class ReviewStoryTest {

    private fun node(id: String, at: Double): ReviewNode =
        ReviewNode(
            node = RouteNode(id = id, type = NodeType.TURN, atDistanceM = at, label = id),
        )

    private fun candidate(id: String, rank: Double): AiCandidate = AiCandidate(
        candidateId = id,
        title = id,
        box = listOf(10, 10, 50, 50),
        rank = rank,
    )

    @Test
    fun maskTraceRunsSixHundredMilliseconds() {
        assertEquals(600L, MASK_TRACE_MS)
    }

    @Test
    fun filmStripOrdersByDistance() {
        val frames = filmStripFrames(listOf(node("b", 45.0), node("a", 0.0), node("c", 20.0)))
        assertEquals(listOf("a", "c", "b"), frames.map { it.nodeId })
    }

    @Test
    fun scrubIndexDefaultsToFirstFrame() {
        val frames = filmStripFrames(listOf(node("a", 0.0), node("b", 20.0)))
        assertEquals(0, scrubSelectedIndex(frames, null))
        assertEquals(0, scrubSelectedIndex(frames, "missing"))
        assertEquals(0, scrubSelectedIndex(emptyList(), "a"))
        assertEquals(1, scrubSelectedIndex(frames, "b"))
    }

    @Test
    fun cursorFollowsNearestPathPoint() {
        val path = listOf(PathPoint(0.0, 0.0, 0.0), PathPoint(20.0, 1.0, 1.0), PathPoint(45.0, 2.0, 2.0))
        assertEquals(0, pathIndexForDistance(path, 3.0))
        assertEquals(1, pathIndexForDistance(path, 23.0))
        assertEquals(2, pathIndexForDistance(path, 44.0))
        assertEquals(0, pathIndexForDistance(emptyList(), 10.0))
    }

    @Test
    fun nudgeJumpTargetsNearestNode() {
        val nodes = listOf(node("n0", 0.0), node("n1", 20.0), node("n2", 45.0))
        assertEquals("n1", nudgeTargetNodeId(nodes, "Add a landmark near 23 m"))
        assertNull(nudgeTargetNodeId(nodes, "Something else entirely"))
        assertNull(nudgeTargetNodeId(emptyList(), "Add a landmark near 23 m"))
    }

    @Test
    fun partialDecodeIsNullSafe() {
        assertTrue(parseCheckpointCandidates(null).isEmpty())
        assertTrue(parseCheckpointCandidates("").isEmpty())
        assertTrue(parseCheckpointCandidates("garbage {{{").isEmpty())
        val decoded = parseCheckpointCandidates("""[{"candidateId":"n1_0","title":"Pillar","box":[1,2,3,4]}]""")
        assertEquals(listOf("n1_0"), decoded.map { it.candidateId })
    }

    @Test
    fun partialMergeFiltersAndAppendsRankDescending() {
        val known = setOf("n1")
        val malformed = AiCandidate(candidateId = "bad", box = listOf(5, 5, 5, 5))
        val risky = candidate("risky", 9.0).copy(privacyRisk = true)
        assertEquals(emptyMap(), mergePartialSuggestions(emptyMap(), "n1", listOf(malformed, risky), known))
        assertEquals(emptyMap(), mergePartialSuggestions(emptyMap(), "n99", listOf(candidate("x", 1.0)), known))

        val first = mergePartialSuggestions(emptyMap(), "n1", listOf(candidate("a", 0.2)), known)
        val second = mergePartialSuggestions(first, "n1", listOf(candidate("b", 0.9), candidate("a", 0.2)), known)
        // Newcomers append without duplicating; rank decides the order.
        assertEquals(listOf("b", "a"), second.getValue("n1").map { it.candidateId })
    }
}
