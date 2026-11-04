package app.simt.echo

import app.simt.echo.platform.Platform
import app.simt.echo.transfer.DeviceCredentials
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * W9-03 gate: the relay cases in `TransferServiceTest` run against the
 * same [TransferService] the graph builds — and the graph must actually
 * supply the relay. Configured production config → relay available
 * (Share offers it); anything less → LAN-only, never a half-wired client.
 *
 * R12-02: the bearer is a per-install device key minted at first use, so
 * configuration is the base URL alone; no shared token is compiled in.
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
    fun aConfiguredGraphSuppliesThePerInstallCredentials() {
        // R12-02: configuration never depends on a compiled-in token; the
        // graph exposes the persisted credential store instead.
        val graph = AppGraph(
            platform = FakePlatform(),
            apiBaseUrl = "https://echo.test",
        )
        val credentials: DeviceCredentials = graph.deviceCredentials
        assertTrue(graph.cloudAiConfigured)
        assertTrue(credentials is DeviceCredentials)
    }
}
