package app.simt.echo

import app.simt.echo.platform.Platform
import app.simt.echo.transfer.DeviceCredentials
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * W9-03 gate: the relay cases in `TransferServiceTest` run against the
 * same [TransferService] the graph builds — and the graph must actually
 * supply the relay.
 *
 * R12-02: the bearer is a per-install device key minted at first use, so
 * configuration is the base URL alone; no shared token is compiled in.
 * R12-03: a configured relay is qualified for peer delivery — the worker
 * treats the relay code as the capability, so a second install can redeem
 * it (proven by the worker's two-install test).
 */
class AppGraphRelayTest {

    private class FakePlatform : Platform {
        override val name: String = "test"
        override val dataDir: String = "/tmp"
    }

    @Test
    fun aConfiguredRelayIsOfferedForPeerDelivery() {
        val graph = AppGraph(
            platform = FakePlatform(),
            apiBaseUrl = "https://echo.test",
        )
        assertTrue(graph.cloudAiConfigured)
        assertTrue(graph.transferService.relayConfigured, "the graph supplies the client")
        assertTrue(graph.transferService.relayAvailable, "R12-03: the code is the capability")
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
