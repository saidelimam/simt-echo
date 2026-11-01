package app.simt.echo.ui.screens.settings

import app.simt.echo.data.ConfigRepository
import app.simt.echo.data.FileStore
import app.simt.echo.data.TrialRepository
import app.simt.echo.model.DriftMarker
import app.simt.echo.model.Trial
import app.simt.echo.model.TrialMode
import app.simt.echo.platform.CSV_MIME
import app.simt.echo.platform.FileSharing
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
 */
class SettingsViewModelTest {

    private val store = FileStore(FakeFileSystem(), "/data".toPath())
    private val configs = ConfigRepository(store)
    private val trials = TrialRepository(store)
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
