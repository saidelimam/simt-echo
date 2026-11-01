package app.simt.echo.ai

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** W5-01 gate: routing assembles worker-shaped results from local output. */
class AiRouterTest {

    private class FakeVision(
        private val answers: Map<String, List<AiCandidate>>,
        private val failing: Set<String> = emptySet(),
    ) : LocalVision {
        override val modelId: String = "fake-local"

        override suspend fun propose(checkpoint: LocalCheckpoint): List<AiCandidate> {
            if (checkpoint.nodeId in failing) throw LocalModelException("down at ${checkpoint.nodeId}")
            return answers[checkpoint.nodeId] ?: emptyList()
        }
    }

    private fun cp(nodeId: String) = LocalCheckpoint(
        nodeId = nodeId,
        keyframeId = "kf_${nodeId}_1",
        context = "ctx",
        heroJpeg = byteArrayOf(1),
    )

    private fun cand(nodeId: String, title: String) = AiCandidate(
        candidateId = "${nodeId}_local_0",
        title = title,
        keyframeId = "kf_${nodeId}_1",
        box = listOf(1, 2, 3, 4),
        source = "AI_BOX_ONLY",
        models = "fake-local (local)",
    )

    @Test
    fun assemblesWorkerShapedResultAndToleratesOneBadCheckpoint() = runTest {
        val router = AiRouter(
            FakeVision(
                answers = mapOf("n1" to listOf(cand("n1", "Pillar"))),
                failing = setOf("n2"),
            ),
        )

        val result = router.proposeLocal(listOf(cp("n1"), cp("n2"), cp("n3")))

        assertEquals("local", result.jobId)
        assertEquals("fake-local (local)", result.models.scout)
        assertEquals("none", result.models.ground)
        assertEquals(listOf("n1"), result.checkpoints.map { it.nodeId })
        assertTrue(router.localAvailable)
    }

    @Test
    fun allCheckpointsFailingSurfacesTheTypedError() = runTest {
        val router = AiRouter(FakeVision(emptyMap(), failing = setOf("n1")))
        assertFailsWith<LocalModelException> {
            router.proposeLocal(listOf(cp("n1")))
        }
    }

    @Test
    fun unconfiguredRouterHasNoLocal() {
        assertEquals(null, AiRouter(null).localOrNull())
        assertTrue(!AiRouter(null).localAvailable)
    }
}
