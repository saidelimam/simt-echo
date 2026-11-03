package app.simt.echo.capture.visual

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.viewModelFactory
import app.simt.echo.Fixtures
import app.simt.echo.capture.CaptureSession
import app.simt.echo.capture.CaptureState
import app.simt.echo.capture.FrameRateGovernor
import app.simt.echo.capture.FrameTap
import app.simt.echo.capture.FrameTapMux
import app.simt.echo.data.DraftRepository
import app.simt.echo.data.FileStore
import app.simt.echo.data.RouteRepository
import app.simt.echo.model.EchoConfig
import app.simt.echo.model.EchoJson
import app.simt.echo.model.PerceptionMode
import app.simt.echo.platform.CameraRecorder
import app.simt.echo.platform.HapticPattern
import app.simt.echo.platform.Haptics
import app.simt.echo.platform.MotionSource
import app.simt.echo.platform.MotionUpdate
import app.simt.echo.ui.screens.navigate.NavigateViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okio.Path
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class VisualSessionWiringTest {
    private val dispatcher = StandardTestDispatcher()
    private val haptics = object : Haptics { override fun play(pattern: HapticPattern) = Unit }

    @BeforeTest fun setUp() { Dispatchers.setMain(dispatcher) }
    @AfterTest fun tearDown() { Dispatchers.resetMain() }

    private class Motion : MotionSource, VisualMotionSource {
        override val updates = MutableSharedFlow<MotionUpdate>(extraBufferCapacity = 8)
        var attached = false
        var requested = false
        var interval = 0L
        var detached = 0
        var drained = 0
        var evidence = false
        var failStart = false
        val frame = VisualMotionFrame()
        override val visualAssistActive: Boolean get() = attached && evidence
        override fun attachVisual(tap: FrameTap, enabled: Boolean, intervalNs: Long): Boolean {
            requested = enabled
            interval = intervalNs
            attached = enabled
            return enabled
        }
        override fun detachVisual() { attached = false; detached++ }
        override fun drainVisual(tNs: Long): VisualMotionFrame? { drained++; frame.tNs = tNs; return if (evidence) frame else null }
        override fun start() { check(!failStart) { "motion start failed" } }
        override fun stop() = Unit
    }

    private class Camera(val fail: Boolean = false) : CameraRecorder {
        override val frameTap = FrameTapMux()
        @Composable override fun Preview(modifier: Modifier, onError: (String) -> Unit) = Unit
        override suspend fun startVideo(out: Path): Long { check(!fail) { "camera start failed" }; return 1_000_000_000L }
        override suspend fun stopVideo(): Path = "/video.mp4".toPath()
    }

    private fun session(motion: Motion, camera: Camera, scope: CoroutineScope, enabled: Boolean) = CaptureSession(
        motion, camera, haptics, DraftRepository(FileStore(FakeFileSystem(), "/data".toPath())), scope,
        nowMs = { 1_000L }, newDraftId = { "visual-test" }, config = EchoConfig(visualAssist = enabled),
        perceptionMode = { PerceptionMode.LEDGER_ON },
    )

    private fun update(step: Boolean) = MotionUpdate(1_100_000_000L, 0.7, 0.0, 1, step, null, "wiring")

    @Test
    fun persistedFlagDefaultsOffAndRoundTripsOptIn() {
        assertFalse(EchoJson.decodeFromString<EchoConfig>("{}").visualAssist)
        val config = EchoConfig(visualAssist = true)
        assertEquals(config, EchoJson.decodeFromString<EchoConfig>(EchoJson.encodeToString(EchoConfig.serializer(), config)))
        assertEquals(PerceptionMode.LEDGER_ON, PerceptionMode.withVisual(PerceptionMode.LEDGER_ON, false))
        assertEquals(PerceptionMode.LEDGER_OFF, PerceptionMode.withVisual(PerceptionMode.LEDGER_OFF, true))
    }

    @Test
    fun recordRegistersAtLedgerRateAndKeepsAdmittedModeAfterStop() = runTest(dispatcher) {
        val motion = Motion()
        val recorder = session(motion, Camera(), backgroundScope, enabled = true)
        recorder.ready()
        recorder.start()
        runCurrent()
        assertTrue(motion.requested)
        assertEquals(FrameRateGovernor.RECORD_INTERVAL_NS, motion.interval)
        assertEquals(PerceptionMode.LEDGER_ON, recorder.liveLog()?.perceptionMode)
        motion.evidence = true
        motion.updates.emit(update(step = true))
        runCurrent()
        assertEquals(1, motion.drained)
        assertSame(motion.frame, recorder.visualFrame)
        recorder.beginFinish()
        assertFalse(motion.attached)
        assertEquals(PerceptionMode.LEDGER_VISUAL_ON, recorder.liveLog()?.perceptionMode)
    }

    @Test
    fun recordOffPreservesModeAndNonStepDoesNotDrain() = runTest(dispatcher) {
        val motion = Motion()
        val recorder = session(motion, Camera(), backgroundScope, enabled = false)
        recorder.ready()
        recorder.start()
        runCurrent()
        assertFalse(motion.requested)
        motion.updates.emit(update(step = false))
        runCurrent()
        assertEquals(0, motion.drained)
        assertNull(recorder.visualFrame)
        assertEquals(PerceptionMode.LEDGER_ON, recorder.liveLog()?.perceptionMode)
        recorder.beginFinish()
    }

    @Test
    fun failedMotionOrCameraStartDetachesVisualCapability() = runTest(dispatcher) {
        for (motionFails in listOf(false, true)) {
            val motion = Motion().also { it.failStart = motionFails }
            val recorder = session(motion, Camera(fail = !motionFails), backgroundScope, enabled = true)
            recorder.ready()
            assertFailsWith<IllegalStateException> { recorder.start() }
            assertFalse(motion.attached)
            assertTrue(motion.detached > 0)
            assertEquals(CaptureState.READY, recorder.state.value)
        }
    }

    @Test
    fun navigateAttachesDrainsAtStepsAndDetachesWithViewModel() = runTest(dispatcher) {
        val routes = RouteRepository(FileStore(FakeFileSystem(), "/data".toPath())).also { it.save(Fixtures.L_ROUTE) }
        for (enabled in listOf(false, true)) {
            val motion = Motion().also { it.evidence = enabled }
            val model = NavigateViewModel(Fixtures.L_ROUTE.routeId, routes, motion, haptics, EchoConfig(visualAssist = enabled)) {}
            val store = ViewModelStore()
            ViewModelProvider.create(store, viewModelFactory { addInitializer(NavigateViewModel::class) { model } })[NavigateViewModel::class]
            assertEquals(enabled, model.attachVisualTap(FrameTapMux()))
            assertEquals(enabled, motion.requested)
            // The step gate's ±0.4 s window needs frames faster than 2 Hz.
            assertEquals(FrameRateGovernor.VISUAL_GATE_INTERVAL_NS, motion.interval)
            model.start()
            runCurrent()
            motion.updates.emit(update(step = true))
            runCurrent()
            assertEquals(1, motion.drained)
            if (enabled) assertSame(motion.frame, model.visualFrame) else assertNull(model.visualFrame)
            store.clear()
            assertFalse(motion.attached)
        }
        val baseline = Motion()
        val model = NavigateViewModel(Fixtures.L_ROUTE.routeId, routes, baseline, haptics, EchoConfig(visualAssist = true, baselineMode = true)) {}
        assertFalse(model.attachVisualTap(FrameTapMux()))
        assertFalse(baseline.requested)
    }
}
