package app.simt.echo.capture

import app.simt.echo.data.DraftRepository
import app.simt.echo.data.FileStore
import app.simt.echo.platform.CameraRecorder
import app.simt.echo.platform.CueOutput
import app.simt.echo.platform.HapticPattern
import app.simt.echo.platform.Haptics
import app.simt.echo.platform.MotionSource
import app.simt.echo.platform.MotionUpdate
import app.simt.echo.platform.NoCueOutput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okio.Path
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
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

    private class FakeCalibratableMotion : MotionSource, StepCalibratable {
        val flow = MutableSharedFlow<MotionUpdate>(extraBufferCapacity = 64)
        override val updates: Flow<MotionUpdate> = flow
        override var stepK: Double = 0.48
        override fun start() = Unit
        override fun stop() = Unit
    }

    private class FakeFrameTap : FrameTap {
        var consumer: FrameConsumer? = null
            private set
        override fun setConsumer(consumer: FrameConsumer?) {
            this.consumer = consumer
        }
    }

    private class FakeCamera : CameraRecorder {
        val tap = FakeFrameTap()
        override val frameTap: FrameTap = tap
        var videoPath: Path? = null
        var stopCalls = 0
        var startFailure: Exception? = null

        @androidx.compose.runtime.Composable
        override fun Preview(modifier: androidx.compose.ui.Modifier, onError: (String) -> Unit) = Unit

        override suspend fun startVideo(out: Path): Long {
            startFailure?.let { throw it }
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

    private class FakeCues : CueOutput {
        val earcons = mutableListOf<HapticPattern>()
        val arrows = mutableListOf<Double>()
        override val eyesFree: Boolean = false
        override fun earcon(pattern: HapticPattern, arrowRad: Double) {
            earcons += pattern
            arrows += arrowRad
        }
        override fun announce(text: String) = Unit
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
        cues: CueOutput = NoCueOutput,
    ) = CaptureSession(
        motion = motion,
        camera = camera,
        haptics = haptics,
        cues = cues,
        drafts = drafts,
        scope = scope,
        nowMs = { 1_762_000_000_000L },
        newDraftId = { "draft-1" },
    )

    /**
     * Regression: "Sound cues" was audible only while navigating. Recording had
     * no sonic channel at all, so a walk stayed silent with the switch on. A mark
     * plays the same confirm cue as its haptic, centre-panned (a mark has no
     * direction to point at).
     */
    @Test
    fun markPlaysTheConfirmEarconInTheCentre() = runTest {
        val drafts = DraftRepository(FileStore(FakeFileSystem(), "/data".toPath()))
        val haptics = FakeHaptics()
        val cues = FakeCues()
        val motion = FakeMotion()
        val session = newSession(motion, FakeCamera(), haptics, drafts, this, cues = cues)
        session.ready()
        session.start()
        runCurrent()

        session.mark()

        assertEquals(listOf(HapticPattern.CONFIRM), haptics.played)
        assertEquals(listOf(HapticPattern.CONFIRM), cues.earcons, "a mark must be heard, not only felt")
        assertEquals(listOf(0.0), cues.arrows, "a mark has no direction: it stays centre-panned")
        // Stops the autosave/collector jobs, so the test scope can finish.
        session.beginFinish()
    }

    @Test
    fun theLiveLogGrowsWithTheWalkAndHasNoDraftBeforeStart() = runTest {
        val drafts = DraftRepository(FileStore(FakeFileSystem(), "/data".toPath()))
        val motion = FakeMotion()
        val session = newSession(motion, FakeCamera(), FakeHaptics(), drafts, this)
        assertNull(session.activeDraftId)
        assertNull(session.liveLog(), "no log exists before start")
        assertTrue(session.liveLedger().isEmpty())

        session.ready()
        session.start()
        runCurrent()
        assertEquals("draft-1", session.activeDraftId)
        motion.emit(update(1_000_000_000L, 0.7, step = true, count = 1))
        runCurrent()
        session.mark()
        val first = checkNotNull(session.liveLog())
        assertEquals(listOf(0.7), first.samples.map { it.d })
        assertEquals(listOf("Spot 1"), first.marks.map { it.label })

        motion.emit(update(2_000_000_000L, 1.4, step = true, count = 2))
        runCurrent()
        val second = checkNotNull(session.liveLog())
        assertEquals(listOf(0.7, 1.4), second.samples.map { it.d })
        assertEquals(listOf(0.7), first.samples.map { it.d }, "an earlier snapshot never changes under the reader")
        session.finish("Walk", "Door")
    }

    @Test
    fun resumeReloadsAutosavedLogAndContinuesSameDraft() = runTest {
        // Kill-mid-record, simulated: an autosaved log exists, the app died.
        val drafts = DraftRepository(FileStore(FakeFileSystem(), "/data".toPath()))
        drafts.saveCapture(
            app.simt.echo.model.CaptureLog(
                draftId = "draft-1",
                startedAtEpochMs = 42L,
                motionSource = "echo_builtin_pdr",
                videoStartNs = 0L,
                samples = listOf(
                    app.simt.echo.model.CaptureSample(tNs = 600_000_000L, d = 0.7, yaw = 0.0, p = null),
                ),
                marks = listOf(app.simt.echo.model.Mark(tNs = 600_000_000L, d = 0.7, label = "Spot 1")),
                startPhoto = "start.jpg",
            ),
        )
        val motion = FakeMotion()
        val session = newSession(motion, FakeCamera(), FakeHaptics(), drafts, this)

        assertTrue(session.resume("draft-1"))
        assertEquals(CaptureState.READY, session.state.value)
        assertEquals(0.7, session.hud.value.distanceM)
        assertEquals(1, session.hud.value.marks)

        session.start()
        runCurrent()
        motion.emit(update(1_200_000_000L, 1.4, step = true, count = 1))
        runCurrent()
        val log = session.finish("Walk", "Door")

        // Same draft, original samples first, new samples appended.
        assertEquals("draft-1", log.draftId)
        assertEquals(42L, log.startedAtEpochMs)
        assertTrue(log.samples.size >= 2)
        assertEquals(0.7, log.samples.first().d)
        assertEquals("draft-1", drafts.loadCapture("draft-1")?.draftId)
    }

    @Test
    fun ledgerOffersReachSubscribersOnlyWhileRecording() = runTest {
        val drafts = DraftRepository(FileStore(FakeFileSystem(), "/data".toPath()))
        val camera = FakeCamera()
        val session = newSession(FakeMotion(), camera, FakeHaptics(), drafts, this)
        val offers = mutableListOf<LedgerOffer>()
        val collector = launch { session.ledgerOffers.collect { offers += it } }
        val frame = FrameSample(
            tNs = 5L,
            width = 32,
            height = 32,
            decimation = 1,
            luma = ByteArray(32 * 32) { i -> (if (((i % 32) / 4 + (i / 32) / 4) % 2 == 0) 200 else 40).toByte() },
            jpeg = { byteArrayOf(7) },
        )

        session.ready()
        session.start()
        runCurrent()
        camera.tap.consumer?.onFrame(frame)
        runCurrent()
        assertEquals(listOf("Kept"), offers.map { it::class.simpleName })

        session.beginFinish()
        assertNull(camera.tap.consumer, "frames stop with the consumer, so no offer can follow Finish")
        collector.cancel()
    }

    @Test
    fun resumeOfMissingDraftReturnsFalse() = runTest {
        val drafts = DraftRepository(FileStore(FakeFileSystem(), "/data".toPath()))
        val session = newSession(FakeMotion(), FakeCamera(), FakeHaptics(), drafts, this)
        assertEquals(false, session.resume("nope"))
        assertEquals(CaptureState.IDLE, session.state.value)
    }

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
        // No still is taken: the start photo is derived from the ledger later.
        assertFalse(drafts.draftFileExists("draft-1", CaptureSession.START_PHOTO))
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
    fun configuredStepKReachesCalibratableMotionAtStart() = runTest {
        // W4-02 wiring: the persisted config's K is pushed into the motion
        // source when capture starts — calibration must not be a no-op.
        val drafts = DraftRepository(FileStore(FakeFileSystem(), "/data".toPath()))
        val motion = FakeCalibratableMotion()
        val session = CaptureSession(
            motion = motion,
            camera = FakeCamera(),
            haptics = FakeHaptics(),
            drafts = drafts,
            scope = this,
            nowMs = { 1_762_000_000_000L },
            newDraftId = { "draft-1" },
            config = app.simt.echo.model.EchoConfig(stepK = 0.62),
        )
        session.ready()
        session.start()
        assertEquals(0.62, motion.stepK)
        session.finish("Walk", "Door")
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

    private fun sharpSample(tNs: Long, jpeg: ByteArray) = FrameSample(
        tNs = tNs,
        width = 32,
        height = 32,
        decimation = 1,
        luma = ByteArray(32 * 32) { i -> (if (((i % 32) / 4 + (i / 32) / 4) % 2 == 0) 200 else 40).toByte() },
        jpeg = { jpeg },
    )

    @Test
    fun ledgerFedByTheFrameTapIsPersistedIntoTheDraft() = runTest {
        val drafts = DraftRepository(FileStore(FakeFileSystem(), "/data".toPath()))
        val motion = FakeMotion()
        val camera = FakeCamera()
        val session = newSession(motion, camera, FakeHaptics(), drafts, this)
        session.ready()
        session.start()
        runCurrent()
        val consumer = assertNotNull(camera.tap.consumer, "recording registers a frame consumer")

        motion.emit(update(600_000_000L, 0.1, step = true, count = 1))
        runCurrent()
        consumer.onFrame(sharpSample(650_000_000L, byteArrayOf(1, 2, 3)))
        motion.emit(update(1_200_000_000L, 0.7, step = true, count = 2))
        runCurrent()
        consumer.onFrame(sharpSample(1_250_000_000L, byteArrayOf(4, 5)))

        session.finish("Walk", "Door")

        val entries = assertNotNull(drafts.loadGlimpseEntries("draft-1"))
        assertEquals(listOf(0L, 1L), entries.map { it.bucketIndex })
        assertEquals(listOf("glimpse/0.jpg", "glimpse/1.jpg"), entries.map { it.jpegPath })
        assertEquals(listOf<Byte>(1, 2, 3), drafts.readDraftFile("draft-1", "glimpse/0.jpg")?.toList())
        assertEquals(listOf<Byte>(4, 5), drafts.readDraftFile("draft-1", "glimpse/1.jpg")?.toList())
    }

    @Test
    fun frameConsumerIsUnregisteredAsSoonAsFinishBegins() = runTest {
        val drafts = DraftRepository(FileStore(FakeFileSystem(), "/data".toPath()))
        val camera = FakeCamera()
        val session = newSession(FakeMotion(), camera, FakeHaptics(), drafts, this)
        session.ready()
        assertNull(camera.tap.consumer)
        session.start()
        assertNotNull(camera.tap.consumer)

        session.beginFinish()

        assertNull(camera.tap.consumer)
    }

    @Test
    fun failedStartLeavesNothingRunning() = runTest {
        val drafts = DraftRepository(FileStore(FakeFileSystem(), "/data".toPath()))
        val motion = FakeMotion()
        val camera = FakeCamera().apply { startFailure = IllegalStateException("no camera") }
        val session = newSession(motion, camera, FakeHaptics(), drafts, this)
        session.ready()

        assertFailsWith<IllegalStateException> { session.start() }

        assertNull(camera.tap.consumer)
        assertTrue(motion.stopped)
        assertEquals(CaptureState.READY, session.state.value)
    }

    private companion object {
        const val VIDEO_START_NS = 123_456_789L
    }
}
