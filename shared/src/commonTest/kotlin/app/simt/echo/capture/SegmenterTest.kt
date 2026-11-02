package app.simt.echo.capture

import app.simt.echo.model.CaptureLog
import app.simt.echo.model.CaptureSample
import app.simt.echo.model.EchoConfig
import app.simt.echo.model.Mark
import app.simt.echo.model.NodeType
import app.simt.echo.model.TurnDir
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** W1-04 gate: §6.3 fixture walks segment to the expected nodes. */
class SegmenterTest {

    /** Video clock origin; every sample timestamp ticks from here. */
    private companion object {
        const val T0_NS = 1_000_000_000L
        const val TICK_NS = 1_000_000_000L
    }

    /**
     * Builds a capture log from (length, heading) legs, sampled every 0.5 m
     * with 1 s timestamp ticks. Pressure is constant [pressure] or derived
     * per distance through [samplePressure] when given.
     */
    private fun log(
        legs: List<Pair<Double, Double>>,
        marks: List<Mark> = emptyList(),
        pressure: Double? = null,
        samplePressure: ((d: Double) -> Double?)? = null,
        destination: String = "End",
    ): CaptureLog {
        val pAt = { d: Double -> if (samplePressure != null) samplePressure(d) else pressure }
        val samples = mutableListOf<CaptureSample>()
        var d = 0.0
        var t = T0_NS
        samples += CaptureSample(tNs = t, d = 0.0, yaw = legs.firstOrNull()?.second ?: 0.0, p = pAt(0.0))
        for ((len, heading) in legs) {
            var walked = 0.0
            while (walked < len - 1e-9) {
                d += (len - walked).coerceAtMost(0.5)
                walked += 0.5
                t += TICK_NS
                samples += CaptureSample(tNs = t, d = d, yaw = heading, p = pAt(d))
            }
        }
        return CaptureLog(
            draftId = "draft-test",
            startedAtEpochMs = 0L,
            motionSource = "echo_builtin_pdr",
            videoStartNs = T0_NS,
            samples = samples,
            marks = marks,
            startPhoto = "start.jpg",
            routeName = "Test route",
            destinationLabel = destination,
        )
    }

    @Test
    fun lPathSegmentsToExpectedNodes() {
        // 20 m straight, 90° right, 15 m.
        val route = segment(
            log(legs = listOf(20.0 to 0.0, 15.0 to PI / 2)),
            EchoConfig(),
            "r-l",
            123L,
        )

        assertEquals(listOf(NodeType.START, NodeType.TURN, NodeType.DESTINATION), route.nodes.map { it.type })
        val turn = route.nodes[1]
        assertEquals(TurnDir.RIGHT, turn.turn)
        assertEquals(20.0, turn.atDistanceM, 1.5)
        assertEquals(0.0, route.nodes[0].atDistanceM, 1e-9)
        assertEquals(35.0, route.nodes[2].atDistanceM, 1e-9)
        assertEquals(20.0, route.legs[0].lengthM, 1.0)
        assertEquals(15.0, route.legs[1].lengthM, 1.0)
        // Legs carry the walked headings.
        assertEquals(0.0, route.legs[0].headingRelDeg, 1.0)
        assertEquals(90.0, route.legs[1].headingRelDeg, 1.0)
        // Capture times sit on the video clock.
        assertNotNull(route.nodes[0].captureTMs)
        // Turn at d≈20 m, 0.5 m per 1 s tick → t≈40 s.
        assertTrue(route.nodes[1].captureTMs!! in 38_000L..42_000L)
        // The path ends where the walk ended (corner rounding ≤ 0.5 m).
        val last = route.path.last()
        assertEquals(35.0, last.d, 1e-9)
        assertEquals(15.0, last.x, 0.5)
        assertEquals(20.0, last.y, 0.5)
    }

    @Test
    fun uTurnIsClassifiedAsUTurn() {
        // 10 m, 180°, 10 m back.
        val route = segment(
            log(legs = listOf(10.0 to 0.0, 10.0 to PI)),
            EchoConfig(),
            "r-u",
            123L,
        )

        val turn = route.nodes.singleOrNull { it.type == NodeType.TURN }
        assertNotNull(turn)
        assertEquals(TurnDir.U_TURN, turn.turn)
        assertEquals(10.0, turn.atDistanceM, 1.5)
        assertEquals(listOf(NodeType.START, NodeType.TURN, NodeType.DESTINATION), route.nodes.map { it.type })
    }

    @Test
    fun thirtyDegreeWiggleProducesNoTurn() {
        // 10 m, +30°, 10 m, back to the original heading, 10 m.
        val w = 30.0 * PI / 180.0
        val route = segment(
            log(legs = listOf(10.0 to 0.0, 10.0 to w, 10.0 to 0.0)),
            EchoConfig(),
            "r-w",
            123L,
        )

        assertFalse(route.nodes.any { it.type == NodeType.TURN })
        assertEquals(listOf(NodeType.START, NodeType.DESTINATION), route.nodes.map { it.type })
    }

    @Test
    fun pressureRiseOnOneLegInsertsLevelChange() {
        // 40 m straight; pressure drops 0.4 hPa past 20 m (≈ +3.3 m, going
        // up) → LEVEL_CHANGE with levelDelta = +1 at the half-change point.
        val route = segment(
            log(
                legs = listOf(40.0 to 0.0),
                samplePressure = { d -> if (d <= 20.0) 1013.25 else 1012.85 },
            ),
            EchoConfig(),
            "r-level",
            123L,
        )

        val level = route.nodes.singleOrNull { it.type == NodeType.LEVEL_CHANGE }
        assertNotNull(level)
        assertEquals(1, level.levelDelta)
        assertEquals(20.0, level.atDistanceM, 2.0)
        // START and DESTINATION survive the drop rule.
        assertEquals(NodeType.START, route.nodes.first().type)
        assertEquals(NodeType.DESTINATION, route.nodes.last().type)
    }

    @Test
    fun markNearTurnMergesIntoTurnNode() {
        val route = segment(
            log(
                legs = listOf(20.0 to 0.0, 15.0 to PI / 2),
                marks = listOf(Mark(tNs = T0_NS + 21 * TICK_NS, d = 20.5, label = "Red pillar")),
            ),
            EchoConfig(),
            "r-mark",
            123L,
        )

        val turn = route.nodes.single { it.type == NodeType.TURN }
        assertEquals("Red pillar", turn.label)
        assertEquals(1, route.nodes.count { it.type == NodeType.TURN })
    }

    @Test
    fun markFarFromAnyTurnBecomesCheckpoint() {
        val route = segment(
            log(
                legs = listOf(30.0 to 0.0),
                marks = listOf(Mark(tNs = T0_NS + 15 * TICK_NS, d = 15.0, label = "Fountain")),
            ),
            EchoConfig(),
            "r-cp",
            123L,
        )

        val cp = route.nodes.single { it.type == NodeType.CHECKPOINT }
        assertEquals("Fountain", cp.label)
        assertEquals(15.0, cp.atDistanceM, 1e-9)
    }

    @Test
    fun magneticSamplesBecomeMagProfileOnSave() {
        // 10 m straight, every 0.5 m sample carrying |B| rising 48→49 µT
        // and a flat 40 µT vertical: 21 dense buckets, µT×10 quantised.
        val samples = mutableListOf<CaptureSample>()
        var t = T0_NS
        var d = 0.0
        while (d <= 10.0 + 1e-9) {
            samples += CaptureSample(
                tNs = t,
                d = d,
                yaw = 0.0,
                p = null,
                mag = 48.0 + d * 0.1,
                magV = 40.0,
            )
            d += 0.5
            t += TICK_NS
        }
        val withMag = segment(
            CaptureLog(
                draftId = "draft-mag",
                startedAtEpochMs = 0L,
                motionSource = "echo_builtin_pdr",
                videoStartNs = T0_NS,
                samples = samples,
                marks = emptyList(),
                startPhoto = "start.jpg",
                routeName = "Mag route",
                destinationLabel = "End",
            ),
            EchoConfig(),
            "r-mag",
            123L,
        )
        val profile = assertNotNull(withMag.perception?.magProfile)
        assertEquals(21, profile.magnitude.size)
        assertEquals(21, profile.vertical.size)
        assertEquals(480.toShort(), profile.magnitude[0])
        assertEquals(490.toShort(), profile.magnitude[20])
        assertTrue(profile.vertical.all { it == 400.toShort() })

        // No magnetometer: the route saves without perception, as before.
        val withoutMag = segment(
            log(legs = listOf(10.0 to 0.0)),
            EchoConfig(),
            "r-nomag",
            123L,
        )
        assertEquals(null, withoutMag.perception?.magProfile)
    }
}
