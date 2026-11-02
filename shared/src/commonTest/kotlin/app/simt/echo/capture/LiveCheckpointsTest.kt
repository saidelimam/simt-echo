package app.simt.echo.capture

import app.simt.echo.model.CaptureLog
import app.simt.echo.model.CaptureSample
import app.simt.echo.model.EchoConfig
import app.simt.echo.model.Mark
import app.simt.echo.model.NodeType
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * W11-09: the walk-time trigger may stage a checkpoint only if the finished
 * walk will contain that exact checkpoint. The sweep below cuts each fixture
 * at every metre and compares the live answer with the final route, node by
 * node, ids and distances included.
 */
class LiveCheckpointsTest {

    private val cfg = EchoConfig()

    private data class Leg(val lengthM: Double, val headingDeg: Double)

    /** One sample every 0.5 m, 1 s apart, with mild deterministic yaw jitter like a real hand-held phone. */
    private fun walk(
        legs: List<Leg>,
        marks: List<Mark> = emptyList(),
        pressureAt: (Double) -> Double? = { null },
        jitterDeg: Double = 2.0,
    ): CaptureLog {
        val samples = mutableListOf<CaptureSample>()
        var d = 0.0
        var t = T0_NS
        var n = 0
        fun add(yawDeg: Double) {
            val jitter = jitterDeg * sin(n * 1.7)
            n++
            samples += CaptureSample(tNs = t, d = d, yaw = (yawDeg + jitter) * PI / 180.0, p = pressureAt(d))
        }
        add(legs.first().headingDeg)
        for (leg in legs) {
            var walked = 0.0
            while (walked < leg.lengthM - 1e-9) {
                d += (leg.lengthM - walked).coerceAtMost(0.5)
                walked += 0.5
                t += TICK_NS
                add(leg.headingDeg)
            }
        }
        return CaptureLog(
            draftId = "draft-live",
            startedAtEpochMs = 0L,
            motionSource = "echo_builtin_pdr",
            videoStartNs = T0_NS,
            samples = samples,
            marks = marks,
            startPhoto = "start.jpg",
        )
    }

    /** What the session holds when the walker has covered [d] metres: the samples and marks up to there. */
    private fun prefix(log: CaptureLog, d: Double): CaptureLog =
        log.copy(samples = log.samples.filter { it.d <= d }, marks = log.marks.filter { it.d <= d })

    private val fixtures: Map<String, CaptureLog> = mapOf(
        "L route" to walk(listOf(Leg(24.0, 0.0), Leg(30.0, 90.0))),
        "S route with three turns" to walk(listOf(Leg(18.0, 0.0), Leg(15.0, 90.0), Leg(20.0, 0.0), Leg(12.0, -90.0))),
        "U-turn" to walk(listOf(Leg(20.0, 0.0), Leg(20.0, 180.0))),
        "turn with marks around it" to walk(
            legs = listOf(Leg(20.0, 0.0), Leg(25.0, 90.0)),
            marks = listOf(Mark(tNs = 0L, d = 8.0, label = "Spot 1"), Mark(tNs = 0L, d = 21.5, label = "Spot 2"), Mark(tNs = 0L, d = 33.0, label = "Spot 3")),
        ),
        "level change between two marks" to walk(
            legs = listOf(Leg(60.0, 0.0)),
            marks = listOf(Mark(tNs = 0L, d = 10.0, label = "Spot 1"), Mark(tNs = 0L, d = 40.0, label = "Spot 2")),
            pressureAt = { d -> if (d <= 20.0) 1013.25 else 1012.85 },
        ),
        "straight walk with marks only" to walk(
            legs = listOf(Leg(50.0, 30.0)),
            marks = listOf(Mark(tNs = 0L, d = 12.0, label = "Spot 1"), Mark(tNs = 0L, d = 13.0, label = "Spot 2"), Mark(tNs = 0L, d = 31.0, label = "Spot 3")),
        ),
    )

    @Test
    fun everyClosedCheckpointIsInTheFinishedRouteUnchangedAtEveryCut() {
        val seen = mutableSetOf<NodeType>()
        for ((name, full) in fixtures) {
            val finalNodes = segment(full, cfg, full.draftId, 0L).nodes.associateBy { it.id }
            val total = full.samples.last().d
            var d = 0.0
            while (d <= total) {
                val live = liveCheckpoints(prefix(full, d), cfg)
                for (node in live.closed) {
                    seen += node.type
                    val settled = finalNodes[node.id]
                    assertEquals(settled?.type, node.type, "$name @ $d m: ${node.id} type")
                    assertEquals(settled?.atDistanceM ?: Double.NaN, node.atDistanceM, 1e-9, "$name @ $d m: ${node.id} distance")
                    assertEquals(settled?.captureTMs, node.captureTMs, "$name @ $d m: ${node.id} capture time")
                    assertEquals(settled?.turn, node.turn, "$name @ $d m: ${node.id} turn")
                }
                d += 1.0
            }
        }
        // The sweep must have actually exercised every kind a walk can stage.
        assertEquals(setOf(NodeType.TURN, NodeType.CHECKPOINT, NodeType.LEVEL_CHANGE), seen)
    }

    @Test
    fun theClosedListOnlyGrowsAsTheWalkerAdvances() {
        for ((name, full) in fixtures) {
            var previous = emptyList<String>()
            var d = 0.0
            while (d <= full.samples.last().d) {
                val ids = liveCheckpoints(prefix(full, d), cfg).closed.map { it.id }
                assertEquals(previous, ids.take(previous.size), "$name @ $d m: a closed checkpoint must never be renumbered or withdrawn")
                previous = ids
                d += 1.0
            }
        }
    }

    @Test
    fun aTurnClosesOnlyOnceTheWalkerIsMarginsPast() {
        val full = fixtures.getValue("L route")
        val turn = segment(full, cfg, full.draftId, 0L).nodes.single { it.type == NodeType.TURN }
        val justShort = prefix(full, turn.atDistanceM + LIVE_CLOSE_MARGIN_M - 0.5)
        val past = prefix(full, turn.atDistanceM + LIVE_CLOSE_MARGIN_M + 0.5)
        assertTrue(liveCheckpoints(justShort, cfg).closed.none { it.type == NodeType.TURN }, "not final yet")
        assertEquals(listOf(turn.id), liveCheckpoints(past, cfg).closed.map { it.id })
    }

    @Test
    fun theWalkersOwnPositionIsNeverACheckpoint() {
        for ((name, full) in fixtures) {
            val live = liveCheckpoints(full, cfg)
            assertTrue(live.closed.none { it.type == NodeType.START || it.type == NodeType.DESTINATION }, name)
        }
    }

    @Test
    fun anEmptyOrBareLogClosesNothing() {
        val empty = walk(listOf(Leg(1.0, 0.0))).copy(samples = emptyList())
        assertTrue(liveCheckpoints(empty, cfg).closed.isEmpty())
        assertTrue(liveCheckpoints(walk(listOf(Leg(3.0, 0.0))), cfg).closed.isEmpty())
    }

    private companion object {
        const val T0_NS = 1_000_000_000L
        const val TICK_NS = 1_000_000_000L
    }
}
