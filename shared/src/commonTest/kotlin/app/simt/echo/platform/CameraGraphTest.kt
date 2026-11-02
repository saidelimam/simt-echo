package app.simt.echo.platform

import app.simt.echo.capture.TimestampAlignment
import app.simt.echo.capture.timestampAlignmentFor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Plan W10-03: which use cases each graph binds, and when analysis is worth binding. */
class CameraGraphTest {

    @Test
    fun graphsWithAPreviewAlwaysBindAnalysis() {
        // A consumer may register after bind (Record arms it on Start).
        for (graph in listOf(CameraGraph.RECORD_FULL, CameraGraph.ALIGN, CameraGraph.NAVIGATE)) {
            assertTrue(graph.bindsAnalysis(hasConsumer = false), graph.name)
            assertTrue(graph.bindsAnalysis(hasConsumer = true), graph.name)
        }
    }

    @Test
    fun analysisOnlyBindsOnlyForARegisteredConsumer() {
        assertFalse(CameraGraph.ANALYSIS_ONLY.bindsAnalysis(hasConsumer = false))
        assertTrue(CameraGraph.ANALYSIS_ONLY.bindsAnalysis(hasConsumer = true))
    }

    @Test
    fun navigateWithoutOverlayIsTheAnalysisOnlyGraph() {
        assertEquals(CameraGraph.ANALYSIS_ONLY, cameraGraphFor(CaptureScreen.NAVIGATE, overlayEnabled = false))
        assertFalse(CameraGraph.ANALYSIS_ONLY.hasPreview)
    }

    @Test
    fun onlyRealtimeTimestampsAlignWithTheMotionClock() {
        assertEquals(TimestampAlignment.REALTIME, timestampAlignmentFor(sourceIsRealtime = true))
        // No honest offset exists for a non-REALTIME source: the tap is unusable.
        assertNull(timestampAlignmentFor(sourceIsRealtime = false))
    }
}
