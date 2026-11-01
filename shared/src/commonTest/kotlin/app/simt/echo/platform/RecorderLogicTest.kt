package app.simt.echo.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Unit gate for the recorder's testable core. CameraX itself needs a
 * device; the state machine and the clock math must hold on the host.
 */
class RecorderLogicTest {

    @Test
    fun idleIgnoresEverythingButPreviewReady() {
        assertEquals(RecorderState.PREVIEW, transition(RecorderState.IDLE, RecorderEvent.PREVIEW_READY))
        assertNull(transition(RecorderState.IDLE, RecorderEvent.START_VIDEO))
        assertNull(transition(RecorderState.IDLE, RecorderEvent.STOP_VIDEO))
        assertNull(transition(RecorderState.IDLE, RecorderEvent.PREVIEW_LOST))
    }

    @Test
    fun previewStartsVideoOrLosesPreview() {
        assertEquals(RecorderState.RECORDING, transition(RecorderState.PREVIEW, RecorderEvent.START_VIDEO))
        assertEquals(RecorderState.IDLE, transition(RecorderState.PREVIEW, RecorderEvent.PREVIEW_LOST))
        assertNull(transition(RecorderState.PREVIEW, RecorderEvent.STOP_VIDEO))
        assertNull(transition(RecorderState.PREVIEW, RecorderEvent.PREVIEW_READY))
    }

    @Test
    fun recordingOnlyStops() {
        assertEquals(RecorderState.PREVIEW, transition(RecorderState.RECORDING, RecorderEvent.STOP_VIDEO))
        assertNull(transition(RecorderState.RECORDING, RecorderEvent.START_VIDEO))
        assertNull(transition(RecorderState.RECORDING, RecorderEvent.PREVIEW_READY))
        assertNull(transition(RecorderState.RECORDING, RecorderEvent.PREVIEW_LOST))
    }

    @Test
    fun fullSessionWalk() {
        var state = RecorderState.IDLE
        for (event in listOf(RecorderEvent.PREVIEW_READY, RecorderEvent.START_VIDEO, RecorderEvent.STOP_VIDEO)) {
            state = transition(state, event) ?: error("rejected $event in $state")
        }
        assertEquals(RecorderState.PREVIEW, state)
    }

    @Test
    fun resolveVideoStartNsPrefersCameraEvent() {
        assertEquals(9_000L, resolveVideoStartNs(eventNs = 9_000L, callNs = 8_000L))
    }

    @Test
    fun resolveVideoStartNsFallsBackOnMissingEvent() {
        assertEquals(8_000L, resolveVideoStartNs(eventNs = null, callNs = 8_000L))
    }

    @Test
    fun resolveVideoStartNsFallsBackOnNonPositiveEvent() {
        assertEquals(8_000L, resolveVideoStartNs(eventNs = 0L, callNs = 8_000L))
        assertEquals(8_000L, resolveVideoStartNs(eventNs = -5L, callNs = 8_000L))
    }
}
