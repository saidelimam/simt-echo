package app.simt.echo.ui.screens.record

import app.simt.echo.capture.CaptureSession
import app.simt.echo.capture.CaptureState
import app.simt.echo.data.DraftRepository
import app.simt.echo.data.FileStore
import app.simt.echo.platform.CameraRecorder
import app.simt.echo.platform.HapticPattern
import app.simt.echo.platform.Haptics
import app.simt.echo.platform.MotionSource
import app.simt.echo.platform.MotionUpdate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * W11-09 gate: the opt-in defaults off and the consent sheet always
 * precedes Start — recording never begins while consent is pending.
 */
class RecordViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private class FakeMotion : MotionSource {
        val flow = MutableSharedFlow<MotionUpdate>(extraBufferCapacity = 64)
        override val updates: Flow<MotionUpdate> = flow
        override fun start() = Unit
        override fun stop() = Unit
    }

    private class FakeCamera : CameraRecorder {
        private var out: Path? = null

        @androidx.compose.runtime.Composable
        override fun Preview(modifier: androidx.compose.ui.Modifier, onError: (String) -> Unit) = Unit

        override suspend fun startVideo(out: Path): Long {
            this.out = out
            return 0L
        }

        override suspend fun stopVideo(): Path = out ?: error("no video")
    }

    private class FakeHaptics : Haptics {
        override fun play(pattern: HapticPattern) = Unit
    }

    private fun viewModel(scope: CoroutineScope, mobileDataAllowed: Boolean = false): Pair<RecordViewModel, CaptureSession> {
        val drafts = DraftRepository(FileStore(FakeFileSystem(), "/data".toPath()))
        val session = CaptureSession(
            motion = FakeMotion(),
            camera = FakeCamera(),
            haptics = FakeHaptics(),
            drafts = drafts,
            // Test scope: runTest cancels the motion/autosave collectors;
            // advanceUntilIdle would spin on the 10 s autosave delay.
            scope = scope,
            nowMs = { 1_762_000_000_000L },
            newDraftId = { "draft-1" },
        )
        return RecordViewModel(session, mobileDataAllowed) to session
    }

    @Test
    fun optInDefaultsOff() = runTest {
        val (vm) = viewModel(this)
        assertEquals(ReadyOptInUiState(), vm.readyOptIn.value)
        assertFalse(vm.readyOptIn.value.optIn)
    }

    @Test
    fun theCopyNamesOnlyTheNetworksTheSettingAllows() = runTest {
        val wifi = viewModel(this).first.readyOptIn.value
        assertEquals("Find landmarks while I walk · Wi-Fi only", wifi.optInLabel)
        assertTrue("over Wi-Fi only so landmarks" in wifi.consentBody)
        assertFalse("mobile data" in wifi.consentBody)

        val data = viewModel(this, mobileDataAllowed = true).first.readyOptIn.value
        assertEquals("Find landmarks while I walk · Wi-Fi or mobile data", data.optInLabel)
        assertTrue("mobile data when there is no Wi-Fi (never while roaming)" in data.consentBody)
        assertFalse("Wi-Fi only" in data.consentBody)
        assertFalse(data.optIn, "the data setting never turns the opt-in on")
    }

    @Test
    fun startWithoutOptInRecordsImmediately() = runTest {
        val (vm, session) = viewModel(this)
        vm.prepare(null)
        runCurrent()
        vm.requestStart()
        runCurrent()
        assertEquals(CaptureState.RECORDING, session.state.value)
        assertFalse(vm.readyOptIn.value.consentPending)
        session.beginFinish()
        runCurrent()
    }

    @Test
    fun optInRoutesStartThroughConsentFirst() = runTest {
        val (vm, session) = viewModel(this)
        vm.prepare(null)
        runCurrent()
        vm.setReadyOptIn(true)
        vm.requestStart()
        runCurrent()
        assertTrue(vm.readyOptIn.value.consentPending)
        assertEquals(CaptureState.READY, session.state.value)
    }

    @Test
    fun grantStartsRecordingWithConsent() = runTest {
        val (vm, session) = viewModel(this)
        vm.prepare(null)
        runCurrent()
        vm.setReadyOptIn(true)
        vm.requestStart()
        runCurrent()
        vm.grantReadyConsent()
        runCurrent()
        assertTrue(vm.readyOptIn.value.consented)
        assertFalse(vm.readyOptIn.value.consentPending)
        assertEquals(CaptureState.RECORDING, session.state.value)
        session.beginFinish()
        runCurrent()
    }

    @Test
    fun dismissReturnsToOverlayWithoutStarting() = runTest {
        val (vm, session) = viewModel(this)
        vm.prepare(null)
        runCurrent()
        vm.setReadyOptIn(true)
        vm.requestStart()
        runCurrent()
        vm.dismissReadyConsent()
        runCurrent()
        assertFalse(vm.readyOptIn.value.consentPending)
        assertEquals(CaptureState.READY, session.state.value)
    }

    @Test
    fun declineOptsOutAndStartsWithoutUpload() = runTest {
        val (vm, session) = viewModel(this)
        vm.prepare(null)
        runCurrent()
        vm.setReadyOptIn(true)
        vm.requestStart()
        runCurrent()
        vm.declineReadyConsent()
        runCurrent()
        assertFalse(vm.readyOptIn.value.optIn)
        assertFalse(vm.readyOptIn.value.consented)
        assertEquals(CaptureState.RECORDING, session.state.value)
        session.beginFinish()
        runCurrent()
    }
}
