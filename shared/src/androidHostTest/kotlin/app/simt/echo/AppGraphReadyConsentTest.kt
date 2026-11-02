package app.simt.echo

import app.simt.echo.platform.AndroidPlatform
import app.simt.echo.transfer.NetworkGate
import app.simt.echo.transfer.NetworkKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/**
 * W11-09 binding: the walk-time consent follows the persisted Settings
 * switch, read at grant time, and an unwired graph never sees a network.
 */
class AppGraphReadyConsentTest {

    private inline fun withGraph(gate: NetworkGate? = null, block: (AppGraph) -> Unit) {
        val dir = Files.createTempDirectory("echo-ready").toFile()
        try {
            val platform = AndroidPlatform(dataDir = dir.absolutePath)
            block(if (gate == null) AppGraph(platform = platform) else AppGraph(platform = platform, networkGate = gate))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun consentIsWifiOnlyUntilTheSettingIsOn() = withGraph { graph ->
        assertTrue(graph.readyUploadConsent().enabled)
        assertTrue(graph.readyUploadConsent().wifiOnly)

        graph.configRepository.update { it.copy(readyUploadMobileData = true) }
        assertFalse("a Settings change applies to the next consent", graph.readyUploadConsent().wifiOnly)

        graph.configRepository.update { it.copy(readyUploadMobileData = false) }
        assertTrue(graph.readyUploadConsent().wifiOnly)
    }

    @Test
    fun anUnwiredGraphFailsClosedOffline() = withGraph { graph ->
        assertEquals(NetworkKind.OFFLINE, graph.networkGate.kind())
    }

    @Test
    fun theWiredGateIsTheOneTheGraphExposes() = withGraph(NetworkGate { NetworkKind.METERED }) { graph ->
        assertEquals(NetworkKind.METERED, graph.networkGate.kind())
    }
}
