package app.simt.echo.nav

import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * W13-12 gate: the `/echo/nav` codec round-trips, the send gate holds the
 * change-driven ≤ 1 Hz rule, and anything that is not v1 nav decodes to
 * null without throwing.
 */
class WearNavSyncTest {

    private fun state(
        arrowDeg: Int = 90,
        distanceM: Int = 12,
        cue: String = "Turn left",
        phase: String = "WALK",
    ) = NavWearState(arrowDeg, distanceM, cue, phase)

    @Test
    fun roundTripsEveryPhase() {
        for (phase in NavPhase.entries) {
            val original = state(phase = phase.name)
            assertEquals(original, WearNavSync.decode(WearNavSync.encode(original)))
        }
    }

    @Test
    fun roundTripKeepsArrowDistanceAndCue() {
        val original = state(arrowDeg = -135, distanceM = 0, cue = "You have arrived", phase = "ARRIVED")
        assertEquals(original, WearNavSync.decode(WearNavSync.encode(original)))
    }

    @Test
    fun cueEscapesSurviveTheTrip() {
        val original = state(cue = "Turn; left\\now\nat the café")
        assertEquals(original, WearNavSync.decode(WearNavSync.encode(original)))
    }

    @Test
    fun pathIsEchoScoped() {
        assertTrue(WearNavSync.MESSAGE_PATH.startsWith("/echo/"))
        assertEquals("/echo/nav", WearNavSync.MESSAGE_PATH)
    }

    @Test
    fun rejectsNonV1Payloads() {
        assertNull(WearNavSync.decode(byteArrayOf()))
        assertNull(WearNavSync.decode("hello".encodeToByteArray()))
        assertNull(WearNavSync.decode("v2\n90\n12\nWALK\nTurn left".encodeToByteArray()))
        assertNull(WearNavSync.decode("v1\n90\n12\nWALK".encodeToByteArray()))
        assertNull(WearNavSync.decode("v1\n90\n12\nWALK\ncue\nextra".encodeToByteArray()))
        assertNull(WearNavSync.decode("V1\n90\n12\nWALK\nTurn left".encodeToByteArray()))
    }

    @Test
    fun rejectsOutOfRangeNumerics() {
        assertNull(WearNavSync.decode("v1\n181\n12\nWALK\ncue".encodeToByteArray()))
        assertNull(WearNavSync.decode("v1\n-181\n12\nWALK\ncue".encodeToByteArray()))
        assertNull(WearNavSync.decode("v1\nfar\n12\nWALK\ncue".encodeToByteArray()))
        assertNull(WearNavSync.decode("v1\n90\n-1\nWALK\ncue".encodeToByteArray()))
        assertNull(WearNavSync.decode("v1\n90\nmany\nWALK\ncue".encodeToByteArray()))
    }

    @Test
    fun rejectsEmptyPhaseAndBadEscapes() {
        assertNull(WearNavSync.decode("v1\n90\n12\n\ncue".encodeToByteArray()))
        assertNull(WearNavSync.decode("v1\n90\n12\nWALK\nbad\\escape".encodeToByteArray()))
        assertNull(WearNavSync.decode("v1\n90\n12\nWALK\ntrailing\\".encodeToByteArray()))
    }

    @Test
    fun keepsUnknownPhaseVerbatim() {
        val decoded = WearNavSync.decode("v1\n0\n5\nRELOCKING_V2\nFinding you…".encodeToByteArray())
        assertEquals(state(arrowDeg = 0, distanceM = 5, cue = "Finding you…", phase = "RELOCKING_V2"), decoded)
    }

    @Test
    fun encodeTruncatesLongCues() {
        val decoded = WearNavSync.decode(WearNavSync.encode(state(cue = "x".repeat(400))))
        assertEquals("x".repeat(WearNavSync.MAX_CUE_CHARS), decoded?.cue)
    }

    @Test
    fun gateSendsFirstSnapshotUnconditionally() {
        val gate = WearNavSendGate()
        assertTrue(gate.shouldSend(0L, state()))
    }

    @Test
    fun gateDropsRepeats() {
        val gate = WearNavSendGate()
        assertTrue(gate.shouldSend(0L, state()))
        assertFalse(gate.shouldSend(10_000L, state()))
    }

    @Test
    fun gateHoldsOneHertzOnChange() {
        val gate = WearNavSendGate()
        assertTrue(gate.shouldSend(0L, state()))
        assertFalse(gate.shouldSend(999L, state(distanceM = 11)))
        assertTrue(gate.shouldSend(1000L, state(distanceM = 11)))
    }

    @Test
    fun gateComparesAgainstLastSentNotLastSeen() {
        val gate = WearNavSendGate()
        assertTrue(gate.shouldSend(0L, state(distanceM = 12)))
        assertFalse(gate.shouldSend(500L, state(distanceM = 11)))
        assertFalse(gate.shouldSend(700L, state(distanceM = 11)))
        assertTrue(gate.shouldSend(1000L, state(distanceM = 10)))
    }

    @Test
    fun mappingQuantizesArrowToFiveDegrees() {
        assertEquals(90, uiWith(arrowDeg = 90.0).toWearState().arrowDeg)
        assertEquals(90, uiWith(arrowDeg = 92.0).toWearState().arrowDeg)
        assertEquals(-5, uiWith(arrowDeg = -7.0).toWearState().arrowDeg)
        assertEquals(0, uiWith(arrowDeg = 2.0).toWearState().arrowDeg)
    }

    @Test
    fun mappingNormalizesOverflowAndRoundsDistance() {
        assertEquals(-175, uiWith(arrowDeg = 181.0).toWearState().arrowDeg)
        assertEquals(13, uiWith(distanceM = 12.6).toWearState().distanceM)
        assertEquals(0, uiWith(distanceM = -0.4).toWearState().distanceM)
    }

    @Test
    fun mappingCarriesPhaseAndTruncatesCue() {
        val mapped = uiWith(phase = NavPhase.APPROACH, cue = "y".repeat(400)).toWearState()
        assertEquals("APPROACH", mapped.phase)
        assertEquals("y".repeat(WearNavSync.MAX_CUE_CHARS), mapped.cue)
    }

    private fun uiWith(
        arrowDeg: Double = 0.0,
        distanceM: Double = 5.0,
        cue: String = "Walk on",
        phase: NavPhase = NavPhase.WALK,
    ) = NavUi(
        phase = phase,
        legIndex = 0,
        instruction = cue,
        distanceToNextM = distanceM,
        arrowRad = arrowDeg * PI / 180.0,
        nextTurn = null,
        landmark = null,
        uncertaintyM = 1.0,
        progress01 = 0.0,
    )
}
