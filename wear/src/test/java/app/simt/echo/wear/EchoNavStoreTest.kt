package app.simt.echo.wear

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** W13-12 review: the store notifies, honours the closing snapshot, and the labels are never raw enums. */
class EchoNavStoreTest {

    private fun state(phase: String = "WALK", distanceM: Int = 12) =
        EchoNavState(arrowDeg = 30, distanceM = distanceM, cue = "Turn left", phase = phase)

    @After
    fun reset() {
        EchoNavStore.clear()
    }

    @Test
    fun listenersHearEverySnapshotAndStopWhenRemoved() {
        var heard = 0
        val listener = EchoNavStore.Listener { heard++ }
        EchoNavStore.addListener(listener)
        EchoNavStore.update(state(distanceM = 12), nowMs = 100L)
        EchoNavStore.update(state(distanceM = 11), nowMs = 200L)
        assertEquals(2, heard)
        assertEquals(11, EchoNavStore.latest?.distanceM)
        assertEquals(200L, EchoNavStore.latestReceivedAtMs)

        EchoNavStore.removeListener(listener)
        EchoNavStore.update(state(distanceM = 10), nowMs = 300L)
        assertEquals(2, heard)
    }

    @Test
    fun theEndedSnapshotClearsTheStoreAndNotifies() {
        var heard = 0
        val listener = EchoNavStore.Listener { heard++ }
        EchoNavStore.update(state(), nowMs = 100L)
        EchoNavStore.addListener(listener)

        EchoNavStore.update(state(phase = EchoNavStore.PHASE_ENDED), nowMs = 200L)

        assertNull(EchoNavStore.latest)
        assertNull(EchoNavStore.latestReceivedAtMs)
        assertEquals(1, heard)
        EchoNavStore.removeListener(listener)
    }

    @Test
    fun aMidWalkSnapshotTurnsPausedAfterFiveSecondsButArrivalDoesNot() {
        val walking = state(phase = "WALK")
        assertFalse(EchoNavPresentation.isPaused(walking, receivedAtMs = 0L, nowMs = 5_000L))
        assertTrue(EchoNavPresentation.isPaused(walking, receivedAtMs = 0L, nowMs = 5_001L))
        val arrived = state(phase = "ARRIVED")
        assertFalse(EchoNavPresentation.isPaused(arrived, receivedAtMs = 0L, nowMs = 60_000L))
    }

    @Test
    fun everyPhoneNavPhaseHasALabelAndNoneIsTheRawEnum() {
        val phonePhases = listOf(
            "ALIGN", "WALK", "APPROACH", "TURNING", "CONFIRM",
            "UNCERTAIN", "OFF_ROUTE", "ARRIVED", "RELOCKING",
        )
        val labels = phonePhases.map { EchoNavPresentation.label(it) }
        assertTrue("every known phase is labelled: $labels", labels.none { it == null })
        assertEquals(phonePhases.size, labels.toSet().size)
        // A phase from a newer phone is shown as distance only, never verbatim.
        assertNull(EchoNavPresentation.label("RELOCKING_V2"))
    }
}
