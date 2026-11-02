package app.simt.echo

import app.simt.echo.platform.Platform
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * W9-03 gate: the relay cases in `TransferServiceTest` run against the
 * same [TransferService] the graph builds — and the graph must actually
 * supply the relay. Configured production config → relay available
 * (Share offers it); anything less → LAN-only, never a half-wired client.
 */
class AppGraphRelayTest {

    private class FakePlatform : Platform {
        override val name: String = "test"
        override val dataDir: String = "/tmp"
    }

    @Test
    fun configuredGraphExposesRelayOnTransferService() {
        val graph = AppGraph(
            platform = FakePlatform(),
            apiBaseUrl = "https://echo.test",
            appToken = "tok",
        )
        assertTrue(graph.cloudAiConfigured)
        assertTrue(graph.transferService.relayAvailable)
    }

    @Test
    fun unconfiguredGraphStaysLanOnly() {
        val graph = AppGraph(platform = FakePlatform())
        assertFalse(graph.cloudAiConfigured)
        assertFalse(graph.transferService.relayAvailable)
    }

    @Test
    fun blankTokenStaysLanOnly() {
        val graph = AppGraph(
            platform = FakePlatform(),
            apiBaseUrl = "https://echo.test",
            appToken = "",
        )
        assertFalse(graph.cloudAiConfigured)
        assertFalse(graph.transferService.relayAvailable)
    }
}
