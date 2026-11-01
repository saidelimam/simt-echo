package app.simt.echo.data

import app.simt.echo.model.EchoConfig
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** W4-02 test delta: config persists, corrupts safely, updates atomically. */
class ConfigRepositoryTest {

    private val store = FileStore(FakeFileSystem(), "/data".toPath())
    private val configs = ConfigRepository(store)

    @Test
    fun missingFileDegradesToDefaults() {
        assertEquals(EchoConfig(), configs.get())
    }

    @Test
    fun setThenGetRoundTrips() {
        configs.set(EchoConfig(stepK = 0.6, baselineMode = true))
        assertEquals(0.6, configs.get().stepK)
        assertTrue(configs.get().baselineMode)
    }

    @Test
    fun absurdStoredStepKFallsBackToDefault() {
        configs.set(EchoConfig(stepK = 48.0))
        assertEquals(0.48, configs.get().stepK)
        configs.set(EchoConfig(stepK = -3.0))
        assertEquals(0.48, configs.get().stepK)
    }

    @Test
    fun corruptFileDegradesToDefaults() {
        store.writeText("config/echo.json", "{not json")
        assertEquals(EchoConfig(), configs.get())
    }

    @Test
    fun emptyAndWrongShapeConfigDegradeToDefaults() {
        store.writeText("config/echo.json", "")
        assertEquals(EchoConfig(), configs.get())
        store.writeText("config/echo.json", "[1,2,3]")
        assertEquals(EchoConfig(), configs.get())
    }

    @Test
    fun updateTransformsAndStores() {
        val next = configs.update { it.copy(stepK = 0.55) }
        assertEquals(0.55, next.stepK)
        assertEquals(0.55, configs.get().stepK)
        assertFalse(configs.get().baselineMode)
    }

    @Test
    fun genericDocStoreServesOtherDocuments() {
        val flags = JsonDocStore(
            store = store,
            path = "config/flags.json",
            decode = { it == "on" },
            encode = { if (it) "on" else "off" },
        )
        assertEquals(false, flags.get(false))
        flags.set(true)
        assertEquals(true, flags.get(false))
        assertEquals(false, flags.update(false) { !it })
    }
}
