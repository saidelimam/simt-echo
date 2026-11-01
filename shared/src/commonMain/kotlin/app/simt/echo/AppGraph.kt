package app.simt.echo

import app.simt.echo.data.ConfigRepository
import app.simt.echo.data.DraftRepository
import app.simt.echo.data.FileStore
import app.simt.echo.data.RouteRepository
import app.simt.echo.data.TrialRepository
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
    /** On-phone LiteRT model file (W5-01), found by the platform entry
     * point in its models directory; null when none was pushed. */
    val onPhoneModelPath: String? = null,
) {
    /** Root of all on-disk state (plan §5.3). Empty only in previews/tests. */
    val fileStore: FileStore by lazy {
        FileStore(FileSystem.SYSTEM, platform.dataDir.toPath())
    }

    val routeRepository: RouteRepository by lazy { RouteRepository(fileStore) }
    val draftRepository: DraftRepository by lazy { DraftRepository(fileStore) }

    /** Persisted tunables (W4-02): capture, navigation and Settings all
     * read the calibrated step-K from here — never a hard-coded default. */
    val configRepository: ConfigRepository by lazy { ConfigRepository(fileStore) }

    /** Field-protocol trials (W4-01): baseline/drift logging + CSV export. */
    val trialRepository: TrialRepository by lazy { TrialRepository(fileStore) }

    /** Cloud API client (W3-02): consent upload, job polling, deletion. */
    val apiClient: EchoApiClient by lazy {
        EchoApiClient(createHttpClient(), apiBaseUrl, appToken)
    }

    /**
     * The configured local vision provider (W5-01), or null: a LAN
     * OpenAI-compatible server when URL + model are set in Settings,
     * else the on-phone engine when a model file was pushed. Read live
     * from config on every call so Settings changes take effect
     * without a restart.
     */
    fun localVision(): app.simt.echo.ai.LocalVision? {
        val config = configRepository.get()
        if (config.localModelBaseUrl.isNotBlank() && config.localModelName.isNotBlank()) {
            return app.simt.echo.ai.OpenAiCompatVision(
                http = createHttpClient(),
                baseUrl = config.localModelBaseUrl,
                modelId = config.localModelName,
            )
        }
        val path = onPhoneModelPath ?: return null
        return app.simt.echo.ai.createOnPhoneVision(path)
    }

    /** Router Review consults (W5-01); local part may be absent. */
    fun aiRouter(): app.simt.echo.ai.AiRouter =
        app.simt.echo.ai.AiRouter(localVision())

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
            store = fileStore,
            nowMs = { currentEpochMs() },
        )
    }
}
