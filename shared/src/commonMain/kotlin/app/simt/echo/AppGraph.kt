package app.simt.echo

import app.simt.echo.data.DraftRepository
import app.simt.echo.data.FileStore
import app.simt.echo.data.RouteRepository
import app.simt.echo.platform.PeerTransport
import app.simt.echo.platform.Platform
import app.simt.echo.platform.createHttpClient
import app.simt.echo.platform.createPeerTransport
import app.simt.echo.platform.createPlatform
import app.simt.echo.platform.currentEpochMs
import app.simt.echo.transfer.EchoApiClient
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
    /**
     * Cloud API base URL + app token (plan §0.6: secrets never ship in
     * git). Empty in previews/tests; `EchoApiClient` fails closed then.
     * Production passes androidApp BuildConfig values (local.properties).
     */
    val apiBaseUrl: String = "",
    val appToken: String = "",
) {
    /** Root of all on-disk state (plan §5.3). Empty only in previews/tests. */
    val fileStore: FileStore by lazy {
        FileStore(FileSystem.SYSTEM, platform.dataDir.toPath())
    }

    val routeRepository: RouteRepository by lazy { RouteRepository(fileStore) }
    val draftRepository: DraftRepository by lazy { DraftRepository(fileStore) }

    /** Cloud API client (W3-02): consent upload, job polling, deletion. */
    val apiClient: EchoApiClient by lazy {
        EchoApiClient(createHttpClient(), apiBaseUrl, appToken)
    }

    /** LAN bundle transport + offer/receive service (W2-03). */
    val peerTransport: PeerTransport by lazy { createPeerTransport() }
    val transferService: TransferService by lazy {
        TransferService(
            transport = peerTransport,
            routes = routeRepository,
            nowMs = { currentEpochMs() },
        )
    }
}
