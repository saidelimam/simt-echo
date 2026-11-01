package app.simt.echo.capture

import app.simt.echo.data.DraftRepository
import app.simt.echo.data.FileStore
import app.simt.echo.platform.CameraRecorder
import app.simt.echo.platform.HapticPattern
import app.simt.echo.platform.Haptics
import app.simt.echo.platform.MotionSource
import app.simt.echo.platform.MotionUpdate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okio.Path
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

/** W1-03 gate: start → samples/marks → finish writes a complete log. */
@OptIn(ExperimentalCoroutinesApi::class)
class CaptureSessionTest {

    private class FakeMotion : MotionSource {
        val flow = MutableSharedFlow<MotionUpdate>(extraBufferCapacity = 64)
        override val updates: Flow<MotionUpdate> = flow
        var started = false
        var stopped = false
        override fun start() { started = true }
        override fun stop() { stopped = true }
        suspend fun emit(update: MotionUpdate) = flow.emit(update)
    }

    private class FakeCamera : CameraRecorder {
        var photoPath: Path? = null
        var videoPath: Path? = null
        var stopCalls = 0

        @androidx.compose.runtime.Composable
        override fun Preview(modifier: androidx.compose.ui.Modifier) = Unit

        override suspend fun takePhoto(out: Path): Path {
            photoPath = out
            return out
        }

        override suspend fun startVideo(out: Path): Long {
            videoPath = out
            return VIDEO_START_NS
        }

        override suspend fun stopVideo(): Path {
            stopCalls++
            return videoPath ?: error("no video")
        }
    }

    private class FakeHaptics : Haptics {
        val played = mutableListOf<HapticPattern>()
        override fun play(pattern: HapticPattern) { played += pattern }
    }

    private fun update(tNs: Long, d: Double, step: Boolean, count: Int) = MotionUpdate(
        tNs = tNs, distanceM = d, yawRad = 0.0, stepCount = count,
        isStep = step, pressureHpa = null, source = "echo_builtin_pdr",
    )

    private fun newSession(
        motion: FakeMotion,
        camera: FakeCamera,
        haptics: FakeHaptics,
        drafts: DraftRepository,
        scope: CoroutineScope,
    ) = CaptureSession(
        motion = motion,
        camera = camera,
        haptics = haptics,
        drafts = drafts,
        scope = scope,
        nowMs = { 1_762_000_000_000L },
        newDraftId = { "draft-1" },
    )

    @Test
    fun fullLifecycleWritesCompleteCaptureLog() = runTest {
        val drafts = DraftRepository(FileStore(FakeFileSystem(), "/data".toPath()))
        val motion = FakeMotion()
        val camera = FakeCamera()
        val haptics = FakeHaptics()
        val session = newSession(motion, camera, haptics, drafts, this)

        assertEquals(CaptureState.IDLE, session.state.value)
        session.ready()
        assertEquals(CaptureState.READY, session.state.value)

        session.start()
        runCurrent() // let the motion collector subscribe
        assertEquals(CaptureState.RECORDING, session.state.value)
        assertTrue(motion.started)
        assertTrue(camera.photoPath.toString().endsWith("drafts/draft-1/start.jpg"))
        assertTrue(camera.videoPath.toString().endsWith("drafts/draft-1/video.mp4"))

        // Step at 0.6 s, heartbeat at 1.1 s, step at 1.2 s.
        motion.emit(update(600_000_000L, 0.7, step = true, count = 1))
        motion.emit(update(1_100_000_000L, 1.4, step = false, count = 1))
        motion.emit(update(1_150_000_000L, 1.4, step = false, count = 1)) // < 500 ms: dropped
        motion.emit(update(1_200_000_000L, 2.1, step = true, count = 2))
        runCurrent()

        session.mark()
        assertEquals(listOf(HapticPattern.CONFIRM), haptics.played)

        val log = session.finish("Garage to lift", "Elevator")
        assertEquals(CaptureState.SAVED, session.state.value)
        assertTrue(motion.stopped)
        assertEquals(1, camera.stopCalls)

        assertEquals("draft-1", log.draftId)
        assertEquals(VIDEO_START_NS, log.videoStartNs)
        assertEquals("Garage to lift", log.routeName)
        assertEquals("Elevator", log.destinationLabel)
        assertEquals(3, log.samples.size) // 2 steps + 1 heartbeat
        assertEquals(1, log.marks.size)
        assertEquals("Spot 1", log.marks.single().label)
        assertEquals(2.1, log.marks.single().d)

        // Persisted, loadable, and discoverable on Home.
        assertEquals(log, drafts.loadCapture("draft-1"))
        assertEquals(listOf("draft-1"), drafts.listDraftIds())
        assertNotNull(session.hud.value)
    }

    @Test
    fun startBeforeReadyThrows() = runTest {
        val drafts = DraftRepository(FileStore(FakeFileSystem(), "/data".toPath()))
        val session = newSession(FakeMotion(), FakeCamera(), FakeHaptics(), drafts, this)
        assertFailsWith<IllegalStateException> { session.start() }
    }

    @Test
    fun markOutsideRecordingIsIgnored() = runTest {
        val drafts = DraftRepository(FileStore(FakeFileSystem(), "/data".toPath()))
        val haptics = FakeHaptics()
        val session = newSession(FakeMotion(), FakeCamera(), haptics, drafts, this)
        session.mark()
        assertTrue(haptics.played.isEmpty())
        assertEquals(0, session.hud.value.marks)
    }

    private companion object {
        const val VIDEO_START_NS = 123_456_789L
    }
}
