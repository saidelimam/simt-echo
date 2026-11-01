package app.simt.echo

import app.simt.echo.data.DraftRepository
import app.simt.echo.data.FileStore
import app.simt.echo.data.RouteRepository
import app.simt.echo.platform.PeerTransport
import app.simt.echo.platform.Platform
import app.simt.echo.platform.createPeerTransport
import app.simt.echo.platform.createPlatform
import app.simt.echo.platform.currentEpochMs
import app.simt.echo.transfer.TransferService
import okio.FileSystem
import okio.Path.Companion.toPath

/**
 * Manual dependency graph for the shared module.
 *
 * One graph per process, created by the platform entry point
 * (`EchoApplication` on Android). Slices register their stores, sources and
 * transports here as they land; `shared` deliberately has no DI framework —
 * no DI code is vendored in either (see `docs/plans/0001-agentic-flow.md` §4).
 */
class AppGraph(
    val platform: Platform = createPlatform(),
) {
    /** Root of all on-disk state (plan §5.3). Empty only in previews/tests. */
    val fileStore: FileStore by lazy {
        FileStore(FileSystem.SYSTEM, platform.dataDir.toPath())
    }

    val routeRepository: RouteRepository by lazy { RouteRepository(fileStore) }
    val draftRepository: DraftRepository by lazy { DraftRepository(fileStore) }

    /** LAN bundle transport + offer/receive service (W2-03). */
    val peerTransport: PeerTransport by lazy { createPeerTransport() }

    // Relay (W4-05) stays unconfigured here on purpose: the Worker URL and
    // app token are W3-02's API configuration, injected as a RelayClient
    // when that lands. Until then transfers are LAN-only and Share does
    // not offer the relay.
    val transferService: TransferService by lazy {
        TransferService(
            transport = peerTransport,
            routes = routeRepository,
            nowMs = { currentEpochMs() },
        )
    }
}
