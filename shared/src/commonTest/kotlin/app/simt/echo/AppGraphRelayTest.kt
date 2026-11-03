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
    fun aConfiguredRelayIsNotAPeerDeliveryFallback() {
        val graph = AppGraph(
            platform = FakePlatform(),
            apiBaseUrl = "https://echo.test",
            appToken = "tok",
        )
        assertTrue(graph.cloudAiConfigured)
        // Plan 0005 §1: the Worker refuses a code minted by another install
        // (R10-01), so a configured relay stays unusable for peer delivery
        // until a producer contract and a two-install test enable it. The
        // upload action must stay hidden even though the client exists.
        assertTrue(graph.transferService.relayConfigured, "the graph still supplies the client")
        assertFalse(graph.transferService.relayAvailable, "configured is not qualified")
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
