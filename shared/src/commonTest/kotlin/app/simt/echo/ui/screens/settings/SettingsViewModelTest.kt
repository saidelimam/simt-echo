package app.simt.echo.ui.screens.settings

import app.simt.echo.Fixtures
import app.simt.echo.data.ConfigRepository
import app.simt.echo.data.DraftRepository
import app.simt.echo.data.FileStore
import app.simt.echo.data.RouteRepository
import app.simt.echo.data.TrialRepository
import app.simt.echo.model.CaptureLog
import app.simt.echo.model.DriftMarker
import app.simt.echo.model.Trial
import app.simt.echo.model.TrialMode
import app.simt.echo.platform.CSV_MIME
import app.simt.echo.platform.FileSharing
import app.simt.echo.ui.copy.aiUnavailableCopy
import app.simt.echo.ui.copy.cloudAiStatusCopy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Settings state: the W5-02 camera toggle persists, and the W6-01 trials
 * CSV export writes `exports/trials.csv` and hands it to the share sheet.
 *
 * W9-07: the Advanced local-model section is removed for v1 (never verified
 * on a device here), so a fresh install carries no local-model UI surface.
 */
class SettingsViewModelTest {

    private val store = FileStore(FakeFileSystem(), "/data".toPath())
    private val configs = ConfigRepository(store)
    private val trials = TrialRepository(store)
    private val routes = RouteRepository(store)
    private val drafts = DraftRepository(store)
    private val sharing = FakeFileSharing()
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun vm() = SettingsViewModel(configs, trials, sharing)

    private fun fullVm() = SettingsViewModel(configs, trials, sharing, routes = routes, drafts = drafts)

    private fun trial(id: String) = Trial(
        trialId = id,
        routeId = "route-1",
        mode = TrialMode.ECHO,
        device = "pixel-8",
        startedAtEpochMs = 1_000L,
        durationS = 61.5,
        distanceM = 62.0,
        arrived = true,
        anchors = 3,
        confirmations = 2,
        uncertainEpisodes = 1,
        offRouteEpisodes = 0,
        mistakenConfirmations = 1,
        lighting = "bright, even",
        holdingStyle = "hand, portrait",
        markers = listOf(DriftMarker(20.0, 21.5, 2_000L)),
    )

    @Test
    fun manualKChangeClearsTheLearnedStride() {
        configs.setLearnedStride(1.1, 6)
        val vm = vm()
        assertEquals(6, vm.ui.value.strideLearnedLegs)

        vm.onMeasuredChange("9")
        vm.calibrate()
        assertEquals(null, configs.get().learnedStepK)
        assertEquals(0, configs.get().learnedStrideLegs)
        assertEquals(0, vm.ui.value.strideLearnedLegs)

        configs.setLearnedStride(1.2, 4)
        vm.resetK()
        assertEquals(null, configs.get().learnedStepK)
        assertEquals(0, configs.get().learnedStrideLegs)
        assertEquals(0, vm.ui.value.strideLearnedLegs)
    }

    @Test
    fun turnHeartbeatDefaultsOnAndPersistsWhenSwitchedOff() {
        val vm = vm()
        assertTrue(vm.ui.value.turnHeartbeat)
        vm.setTurnHeartbeat(false)
        assertFalse(vm.ui.value.turnHeartbeat)
        assertFalse(configs.get().turnHeartbeat)
    }

    @Test
    fun earconsAreOptInAndPersist() {
        val vm = vm()
        assertFalse(vm.ui.value.earcons, "sound cues must be off by default")
        assertFalse(configs.get().earcons)
        vm.setEarcons(true)
        assertTrue(vm.ui.value.earcons)
        assertTrue(configs.get().earcons)
    }

    @Test
    fun strideLearnedLabelPluralises() {
        assertEquals("Stride learned from 0 legs", strideLearnedLabel(0))
        assertEquals("Stride learned from 1 leg", strideLearnedLabel(1))
        assertEquals("Stride learned from 3 legs", strideLearnedLabel(3))
    }

    @Test
    fun cameraOverlayDefaultsOffAndPersistsWhenEnabled() {
        val vm = vm()
        assertFalse(vm.ui.value.cameraOverlayEnabled)
        assertFalse(configs.get().cameraOverlayEnabled)

        vm.setCameraOverlay(true)

        assertTrue(vm.ui.value.cameraOverlayEnabled)
        // A fresh ViewModel (next launch) reads the persisted value.
        assertTrue(vm().ui.value.cameraOverlayEnabled)
        assertTrue(configs.get().cameraOverlayEnabled)
        assertEquals(0.48, configs.get().stepK)
    }

    @Test
    fun exportWritesTheCsvAndHandsItToTheShareSheet() = runTest {
        trials.save(trial("t1"))
        trials.save(trial("t2"))
        val vm = vm()

        vm.exportTrials()
        advanceUntilIdle()

        assertEquals("Trials exported ✓", vm.ui.value.exportNote)
        // Exactly one share call, with the W6-01 name and MIME.
        assertEquals(1, sharing.calls.size)
        val (path, name, mime) = sharing.calls.single()
        assertEquals("ECHO trials", name)
        assertEquals(CSV_MIME, mime)
        // The shared file is the protocol CSV, byte-identical to exportCsv().
        val written = store.readText("exports/trials.csv")
        assertNotNull(written)
        assertEquals(trials.exportCsv(), written)
        assertTrue(path.toString().endsWith("exports/trials.csv"))
    }

    @Test
    fun exportWithNoTrialsIsAnHonestNoOp() = runTest {
        val vm = vm()

        vm.exportTrials()
        advanceUntilIdle()

        assertEquals("No trials to export yet.", vm.ui.value.exportNote)
        assertTrue(sharing.calls.isEmpty(), "no share sheet opens for an empty set")
        assertNull(store.readText("exports/trials.csv"), "nothing is written")
    }

    @Test
    fun failedShareShowsTheError() = runTest {
        trials.save(trial("t1"))
        sharing.result = false
        val vm = vm()

        vm.exportTrials()
        advanceUntilIdle()

        assertEquals("Could not open the share sheet", vm.ui.value.exportNote)
    }

    @Test
    fun cloudAiStatusDefaultsReadyAndReflectsUnconfiguredBuild() {
        // Configured builds (and old call sites) read ready.
        assertTrue(vm().ui.value.cloudAiConfigured)

        // W7-07: fresh installs surface the same honest status as Processing.
        val unconfigured = SettingsViewModel(
            configs,
            trials,
            sharing,
            cloudAiConfigured = false,
        )
        assertFalse(unconfigured.ui.value.cloudAiConfigured)
    }

    @Test
    fun aiStatusWordingMatchesW707() {
        // W8-11 preserves the W7-07 status copy verbatim.
        assertEquals("Landmark AI is ready.", cloudAiStatusCopy(fullVm().ui.value.cloudAiConfigured))
        val unconfigured = SettingsViewModel(
            configs,
            trials,
            sharing,
            routes = routes,
            drafts = drafts,
            cloudAiConfigured = false,
        )
        assertEquals(aiUnavailableCopy(), cloudAiStatusCopy(unconfigured.ui.value.cloudAiConfigured))
    }

    @Test
    fun v1ShipsWithNoLocalModelConfiguration() {
        // W9-07: the Advanced local-model section is removed for v1 because
        // local inference was never verified here (no ≥8 GB device, no
        // .litertlm, no LAN server). The provider seam (EchoConfig/AppGraph)
        // stays for a future slice, but a fresh install surfaces nothing
        // that implies local availability.
        val ui = vm().ui.value
        assertEquals("", ui.localModelBaseUrl)
        assertEquals("", ui.localModelName)
        assertFalse(ui.preferLocalModel)
        assertNull(vm().ui.value.onPhoneModel)
    }

    @Test
    fun deleteAllRemovesRoutesDraftsTrialsAndExportsAndSaysWhatItDid() {
        routes.save(Fixtures.L_ROUTE.copy(routeId = "r1"))
        routes.save(Fixtures.L_ROUTE.copy(routeId = "r2"))
        drafts.saveCapture(
            CaptureLog(
                draftId = "d1",
                startedAtEpochMs = 1L,
                motionSource = "echo_builtin_pdr",
                videoStartNs = 2L,
                samples = emptyList(),
                marks = emptyList(),
                startPhoto = "start.jpg",
            ),
        )
        trials.save(trial("t1"))
        trials.save(trial("t2"))
        store.writeText("exports/trials.csv", "a")
        store.writeText("exports/r1.echo", "b")
        val vm = fullVm()

        vm.deleteAll()

        assertTrue(routes.list().isEmpty())
        assertTrue(drafts.listDraftIds().isEmpty())
        assertTrue(trials.list().isEmpty())
        assertTrue(store.list("exports").isEmpty())
        assertEquals(
            "Removed 2 routes, 1 draft, 2 trials and 2 exported files.",
            vm.ui.value.deleteNote,
        )
    }

    @Test
    fun deleteAllOnEmptyStoreIsAnHonestNoOp() {
        val vm = fullVm()

        vm.deleteAll()

        assertEquals("Nothing to delete.", vm.ui.value.deleteNote)
    }

    /** Records every shareFile call; the result is configurable. */
    private class FakeFileSharing(
        var result: Boolean = true,
    ) : FileSharing {
        val calls = mutableListOf<Triple<Path, String, String>>()

        override suspend fun shareFile(path: Path, name: String, mime: String): Boolean {
            calls += Triple(path, name, mime)
            return result
        }

        override suspend fun pickDocument(): ByteArray? = null
    }
}
