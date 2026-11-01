package app.simt.echo.ui.screens.settings

import app.simt.echo.data.ConfigRepository
import app.simt.echo.data.FileStore
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** W5-02 toggle: the camera HUD is opt-in, persisted, off by default. */
class SettingsViewModelTest {

    private val configs = ConfigRepository(FileStore(FakeFileSystem(), "/data".toPath()))

    @Test
    fun cameraOverlayDefaultsOffAndPersistsWhenEnabled() {
        val vm = SettingsViewModel(configs)
        assertFalse(vm.ui.value.cameraOverlayEnabled)
        assertFalse(configs.get().cameraOverlayEnabled)

        vm.setCameraOverlay(true)

        assertTrue(vm.ui.value.cameraOverlayEnabled)
        // A fresh ViewModel (next launch) reads the persisted value.
        assertTrue(SettingsViewModel(configs).ui.value.cameraOverlayEnabled)
        assertTrue(configs.get().cameraOverlayEnabled)
        assertEquals(0.48, configs.get().stepK)
    }
}
