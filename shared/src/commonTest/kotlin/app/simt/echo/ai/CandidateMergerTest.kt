package app.simt.echo.ai

import app.simt.echo.media.CropBox
import app.simt.echo.model.Confirmation
import app.simt.echo.model.EchoRoute
import app.simt.echo.model.Landmark
import app.simt.echo.model.LandmarkSource
import app.simt.echo.model.NodeType
import app.simt.echo.model.RouteLeg
import app.simt.echo.model.RouteNode
import app.simt.echo.model.StartAnchor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** W3-07 gate: merge attaches ranked AI_SUGGESTED candidates and never moves geometry. */
class CandidateMergerTest {

    private fun candidate(
        nodeId: String = "n1",
        rank: Double = 0.5,
        box: List<Int> = listOf(412, 230, 590, 900),
    ) = AiCandidate(
        candidateId = "${nodeId}_0",
        title = "Red pillar",
        description = "A red pillar.",
        cue = "Turn right at the red pillar",
        samPhrase = "red pillar",
        keyframeId = "kf_n1_1",
        box = box,
        frameW = 720,
        frameH = 1280,
        samConfidence = 0.86,
        trackability = 0.78,
        visibleFromM = 9.4,
        rank = rank,
        source = "AI_GROUNDED",
        models = "muse-spark-1.3 + sam-3.1",
    )

    private fun result(vararg checkpoints: AiCheckpoint) = AiJobResult(
        jobId = "job-1",
        models = AiModels(scout = "muse-spark-1.3", ground = "sam-3.1"),
        checkpoints = checkpoints.toList(),
    )

    private fun route(): EchoRoute = EchoRoute(
        routeId = "draft-1",
        name = "Test walk",
        createdAtEpochMs = 1L,
        creatorLabel = "creator",
        motionSource = "echo_builtin_pdr",
        totalLengthM = 45.0,
        start = StartAnchor(description = "Entrance", photoAssetId = null, alignmentHint = ""),
        nodes = listOf(
            RouteNode(id = "n0", type = NodeType.START, atDistanceM = 0.0, label = "Entrance"),
            RouteNode(id = "n1", type = NodeType.TURN, atDistanceM = 20.0, label = "pillar"),
        ),
        legs = listOf(RouteLeg(fromNode = "n0", toNode = "n1", lengthM = 20.0, headingRelDeg = 0.0)),
        path = emptyList(),
    )

    @Test
    fun parseAcceptsWorkerResultAndRejectsGarbage() {
        val raw = """{"jobId":"job-1","models":{"scout":"s","ground":"none"},
            "checkpoints":[{"nodeId":"n1","candidates":[]}],"usage":{}}"""
        val parsed = CandidateMerger.parseResult(raw.replace("\n", ""))
        assertNotNull(parsed)
        assertEquals("job-1", parsed.jobId)
        assertNull(CandidateMerger.parseResult("not json"))
        assertNull(CandidateMerger.parseResult("""{"models":{}}"""))
    }

    @Test
    fun mergeRanksAndDropsUnknownNodesAndBadBoxes() {
        val merged = CandidateMerger.merge(
            route(),
            result(
                AiCheckpoint("n1", listOf(candidate(rank = 0.2), candidate(rank = 0.9))),
                AiCheckpoint("n99", listOf(candidate(nodeId = "n99"))),
                AiCheckpoint("n0", listOf(candidate(nodeId = "n0", box = listOf(1, 2, 3)))),
            ),
        )
        // Rank-descending, unknown node dropped, malformed box dropped (n0 absent).
        assertEquals(listOf("n1"), merged.keys.toList())
        assertEquals(listOf(0.9, 0.2), merged.getValue("n1").map { it.rank })
    }

    @Test
    fun mergeLeavesGeometryUntouched() {
        val before = route()
        val merged = CandidateMerger.merge(before, result(AiCheckpoint("n1", listOf(candidate()))))
        assertTrue(merged.isNotEmpty())
        // Nodes, legs, path, start and landmarks are sensor-owned: identical.
        assertEquals(before.nodes, route().nodes)
        assertEquals(before.legs, route().legs)
        assertEquals(before.path, route().path)
        assertEquals(before.start, route().start)
        assertEquals(before.landmarks, route().landmarks)
        assertEquals(before, route())
    }

    @Test
    fun cropBoxConvertsThroughPixelSpaceWithoutAssumingEqualSizes() {
        // Non-square SAM frame vs smaller keyframe: normalised output matches
        // the grid either way, proving the sizes cancel instead of being assumed.
        val box = CandidateMerger.candidateCropBox(candidate(box = listOf(0, 0, 1000, 1000)), kfW = 360, kfH = 640)
        assertEquals(CropBox(0f, 0f, 1f, 1f), box)
        val partial = CandidateMerger.candidateCropBox(candidate(), kfW = 360, kfH = 640)
        assertEquals(0.412f, partial.left, 0.001f)
        assertEquals(0.9f, partial.bottom, 0.001f)
        // Same grid through different keyframe dims: identical output.
        assertEquals(partial, CandidateMerger.candidateCropBox(candidate(), kfW = 720, kfH = 1280))
    }

    @Test
    fun landmarkSourceMapsWorkerSourceWithBoxOnlyDefault() {
        assertEquals(LandmarkSource.AI_GROUNDED, CandidateMerger.landmarkSourceOf(candidate()))
        assertEquals(LandmarkSource.AI_BOX_ONLY, CandidateMerger.landmarkSourceOf(candidate().copy(source = "AI_BOX_ONLY")))
        assertEquals(LandmarkSource.AI_BOX_ONLY, CandidateMerger.landmarkSourceOf(candidate().copy(source = "FUTURE_X")))
    }

    @Test
    fun statLineShowsChipsOnlyWhenSamRan() {
        assertEquals("SAM 0.86 · tracked 78% · visible ~9 m", CandidateMerger.statLine(candidate()))
        assertNull(CandidateMerger.statLine(candidate().copy(samConfidence = null)))
    }

    @Test
    fun modelsLineNamesTheModelsThatRan() {
        assertEquals("muse-spark-1.3 + sam-3.1", CandidateMerger.modelsLine(result()))
        assertEquals("cloud AI", CandidateMerger.modelsLine(AiJobResult(models = AiModels("none", "none"))))
    }

    @Test
    fun acceptedCandidateCarriesConfirmationShape() {
        // Documents the Use contract the VM test proves: accepted landmarks
        // are creator-verified, never AI_SUGGESTED.
        val landmark = Landmark(
            id = "lm-1",
            nodeId = "n1",
            title = "Red pillar",
            description = "A red pillar.",
            cue = "Turn right at the red pillar",
            source = CandidateMerger.landmarkSourceOf(candidate()),
            confirmation = Confirmation.CREATOR_VERIFIED,
        )
        assertEquals(Confirmation.CREATOR_VERIFIED, landmark.confirmation)
    }
}
