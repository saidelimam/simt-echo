package app.simt.echo.ui.screens.record

import app.simt.echo.capture.CaptureSession
import app.simt.echo.capture.CaptureState
import app.simt.echo.capture.FaceDetector
import app.simt.echo.capture.FaceVerdict
import app.simt.echo.capture.GlimpseEntry
import app.simt.echo.data.DraftRepository
import app.simt.echo.data.FileStore
import app.simt.echo.model.CaptureLog
import app.simt.echo.model.CaptureSample
import app.simt.echo.model.EchoConfig
import app.simt.echo.model.Mark
import app.simt.echo.platform.CameraRecorder
import app.simt.echo.platform.HapticPattern
import app.simt.echo.platform.Haptics
import app.simt.echo.platform.MotionSource
import app.simt.echo.platform.MotionUpdate
import app.simt.echo.transfer.FakeStripMedia
import app.simt.echo.transfer.FakeV11Api
import app.simt.echo.transfer.NetworkGate
import app.simt.echo.transfer.NetworkKind
import app.simt.echo.transfer.ReadyUploader
import app.simt.echo.transfer.UploadPreparer
import app.simt.echo.transfer.WalkStager
import app.simt.echo.transfer.WalkStagerFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
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

    /**
     * A real stager over fakes. Its live log already holds a closed checkpoint, so
     * the first pass parks in the SAM probe, which records whether it was cancelled.
     */
    private class StagerProbe {
        private val fileSystem = FakeFileSystem()
        private val drafts = DraftRepository(FileStore(fileSystem, "/data".toPath()))
        val builtFor = mutableListOf<String>()
        var parked = false
        var cancelled = false

        val factory = WalkStagerFactory { draftId, _, _ ->
            builtFor += draftId
            WalkStager(
                cfg = EchoConfig(),
                drafts = drafts,
                draftId = draftId,
                preparer = UploadPreparer(drafts, FakeStripMedia(fileSystem), draftId, FACE_FREE, Dispatchers.Unconfined) { 0L },
                uploader = ReadyUploader(FakeV11Api(), NetworkGate { NetworkKind.UNMETERED }, drafts, draftId) { 0L },
                samEnabled = {
                    parked = true
                    try {
                        awaitCancellation()
                    } finally {
                        cancelled = true
                    }
                },
                liveLog = { WALKED },
                liveLedger = { emptyList() },
            )
        }

        private companion object {
            /** Straight for 20 m with a mark at 5 m: that checkpoint has long been final. */
            val WALKED = CaptureLog(
                draftId = "draft-1", startedAtEpochMs = 0L, motionSource = "echo_builtin_pdr", videoStartNs = 0L,
                samples = (0..40).map { CaptureSample(tNs = it * 1_000_000_000L, d = it * 0.5, yaw = 0.0) },
                marks = listOf(Mark(tNs = 0L, d = 5.0, label = "Spot")),
                startPhoto = "start.jpg",
            )

            val FACE_FREE = object : FaceDetector {
                override fun verdictFor(entry: GlimpseEntry): FaceVerdict = FaceVerdict.FACE_FREE
            }
        }
    }

    private fun viewModel(scope: CoroutineScope, mobileDataAllowed: Boolean = false, probe: StagerProbe? = null): Pair<RecordViewModel, CaptureSession> {
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
        return RecordViewModel(session, mobileDataAllowed, probe?.factory ?: WalkStagerFactory { _, _, _ -> null }) to session
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
        assertEquals("Find landmarks while I walk", wifi.optInLabel)
        assertTrue("over Wi-Fi only to Cloudflare R2" in wifi.consentBody)
        assertFalse("mobile data" in wifi.consentBody)
        // R12-08: the pre-walk sheet carries the two retention windows and the
        // fallback model, because staging starts before the walk is over.
        assertTrue("within 1 hour" in wifi.consentBody)
        assertTrue("within 24 hours" in wifi.consentBody)
        assertTrue("Gemma 4" in wifi.consentBody)

        val data = viewModel(this, mobileDataAllowed = true).first.readyOptIn.value
        assertEquals("Find landmarks while I walk", data.optInLabel)
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

    @Test
    fun consentedOptInStagesWhileRecordingAndStopsAtFinish() = runTest {
        val probe = StagerProbe()
        val (vm, session) = viewModel(this, probe = probe)
        vm.prepare(null)
        runCurrent()
        vm.setReadyOptIn(true)
        vm.requestStart()
        runCurrent()
        vm.grantReadyConsent()
        runCurrent()
        assertEquals(listOf("draft-1"), probe.builtFor)
        assertTrue(probe.parked, "the stager started its first pass")
        assertFalse(probe.cancelled)

        vm.beginFinish()
        runCurrent()
        assertTrue(probe.cancelled, "Finish stops the stager before it stops the recording")
        assertEquals(CaptureState.FINISHING, session.state.value)
    }

    @Test
    fun noStagerWithoutOptInOrWithoutConsent() = runTest {
        val probe = StagerProbe()
        val (plain, plainSession) = viewModel(this, probe = probe)
        plain.prepare(null)
        runCurrent()
        plain.requestStart()
        runCurrent()
        assertEquals(CaptureState.RECORDING, plainSession.state.value)
        plainSession.beginFinish()
        runCurrent()

        val (declined, declinedSession) = viewModel(this, probe = probe)
        declined.prepare(null)
        runCurrent()
        declined.setReadyOptIn(true)
        declined.requestStart()
        runCurrent()
        declined.declineReadyConsent()
        runCurrent()
        assertEquals(CaptureState.RECORDING, declinedSession.state.value)
        declinedSession.beginFinish()
        runCurrent()

        assertTrue(probe.builtFor.isEmpty(), "no stager is even built: ${probe.builtFor}")
    }
}
