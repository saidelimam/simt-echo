package app.simt.echo

import app.simt.echo.capture.FaceDetector
import app.simt.echo.capture.FaceScanner
import app.simt.echo.capture.GlimpseEntry
import app.simt.echo.capture.ScanningFaceDetector
import app.simt.echo.data.ConfigRepository
import app.simt.echo.data.DraftRepository
import app.simt.echo.data.FileStore
import app.simt.echo.data.RouteMemoryRepository
import app.simt.echo.data.RouteRepository
import app.simt.echo.data.TrialRepository
import app.simt.echo.media.MediaTools
import app.simt.echo.media.readLedgerJpeg
import app.simt.echo.model.CaptureLog
import app.simt.echo.platform.LiveUpdateDirector
import app.simt.echo.platform.LiveUpdateSink
import app.simt.echo.platform.PeerTransport
import app.simt.echo.platform.Platform
import app.simt.echo.platform.createHttpClient
import app.simt.echo.platform.createPeerTransport
import app.simt.echo.platform.createPlatform
import app.simt.echo.platform.currentEpochMs
import app.simt.echo.platform.platformIoDispatcher
import app.simt.echo.transfer.EchoApiClient
import app.simt.echo.transfer.KtorRelayClient
import app.simt.echo.transfer.NetworkGate
import app.simt.echo.transfer.OfflineNetworkGate
import app.simt.echo.transfer.ReadyUploadConsent
import app.simt.echo.transfer.ReadyUploader
import app.simt.echo.transfer.RelayClient
import app.simt.echo.transfer.TransferService
import app.simt.echo.transfer.UploadPreparer
import app.simt.echo.transfer.V11CloudApi
import app.simt.echo.transfer.WalkStager
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
    /**
     * Where Live Update notifications are drawn (W13-10) and the SDK int
     * that picks their style. Null (previews, tests, iOS) means none are
     * posted; the Android entry point passes its notification sink.
     */
    private val liveUpdateSink: LiveUpdateSink? = null,
    private val sdkInt: Int = 0,
    /**
     * Platform face scan behind the privacy pre-filter (W11-04). Null
     * (previews, tests, iOS until Vision lands) means no pre-filter, which
     * is today's unfiltered behaviour — the consent copy stays honest
     * because it only mentions the filter when one is active.
     */
    private val faceScanner: FaceScanner? = null,
    /**
     * What the active network is, for the walk-time upload policy (W11-09).
     * Defaults to offline so a platform with no verified view never uploads.
     */
    val networkGate: NetworkGate = OfflineNetworkGate,
) {
    /** Root of all on-disk state (plan §5.3). Empty only in previews/tests. */
    val fileStore: FileStore by lazy {
        FileStore(FileSystem.SYSTEM, platform.dataDir.toPath())
    }

    val routeRepository: RouteRepository by lazy { RouteRepository(fileStore) }
    val routeMemoryRepository: RouteMemoryRepository by lazy { RouteMemoryRepository(fileStore) }
    val draftRepository: DraftRepository by lazy { DraftRepository(fileStore) }

    /**
     * Deletes routes whose privacy expiry has passed (audit 0007 R3, closed
     * in W14-04). Called at process start (`EchoApplication.onCreate`) and
     * on every foreground return (`MainActivity.onResume`), so a route that
     * expires while the app sits in the background is gone before its row
     * can render — share/export paths stay fail-closed regardless.
     * @return how many routes were purged.
     */
    fun purgeExpiredRoutes(nowMs: Long): Int {
        routeMemoryRepository.purgeExpired(nowMs)
        return routeRepository.purgeExpired(nowMs)
    }

    /**
     * Face pre-filter for one draft's ledger frames (W11-04), or null when
     * the platform has no scanner. Bound to [draftId]; one per draft so
     * its verdict cache never outlives the frames it describes.
     */
    fun faceDetectorFor(draftId: String): FaceDetector? =
        faceScanner?.let { scanner ->
            ScanningFaceDetector(scanner) { entry -> draftRepository.readLedgerJpeg(draftId, entry) }
        }

    /**
     * Consent the Record sheet grants for a walk: Wi-Fi or mobile data unless
     * the "mobile data" Settings switch is off (W11-09). Read at grant time, so a
     * Settings change applies to the next walk.
     */
    fun readyUploadConsent(): ReadyUploadConsent =
        ReadyUploadConsent.granted(mobileDataAllowed = configRepository.get().readyUploadMobileData)

    /**
     * The walk-time stager for [draftId] (W11-09), granted the consent the
     * Record sheet just collected, or null when this graph cannot stage
     * honestly: no cloud credentials, or no face filter (the consent copy
     * promises face-filtered strips, so an unfiltered stager is never built).
     */
    fun walkStager(
        draftId: String,
        media: MediaTools,
        liveLog: () -> CaptureLog?,
        liveLedger: () -> List<GlimpseEntry>,
        api: V11CloudApi = apiClient,
    ): WalkStager? {
        if (!cloudAiConfigured) return null
        val detector = faceDetectorFor(draftId) ?: return null
        val uploader = ReadyUploader(api, networkGate, draftRepository, draftId) { currentEpochMs() }
        uploader.grantConsent(readyUploadConsent())
        return WalkStager(
            cfg = configRepository.get(),
            drafts = draftRepository,
            draftId = draftId,
            preparer = UploadPreparer(draftRepository, media, draftId, detector, platformIoDispatcher) { currentEpochMs() },
            uploader = uploader,
            samEnabled = { runCatching { api.samEnabled() }.getOrDefault(false) },
            liveLog = liveLog,
            liveLedger = liveLedger,
        )
    }

    /** Live Updates director (W13-10), or null when no sink was provided. */
    val liveUpdates: LiveUpdateDirector? by lazy {
        liveUpdateSink?.let { LiveUpdateDirector(it, sdkInt) }
    }

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
     * Cloud AI config status (plan W7-07): false on every fresh install
     * until W9-01 config exists. Processing and Settings read this —
     * never a network probe — so the unconfigured state stays honest
     * and no fake "cloud" progress ever renders.
     */
    val cloudAiConfigured: Boolean
        get() = apiBaseUrl.isNotBlank() && appToken.isNotBlank()

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

    /**
     * Internet relay (W4-05, wired W9-03): the same production config as
     * [apiClient] — same Worker base URL, same app token surface —
     * since the relay endpoints live on that Worker (`/v1/relay`).
     * Null until that config exists, so Share stays LAN-only and
     * [TransferService.relayAvailable] stays false on unconfigured builds.
     */
    val relayClient: RelayClient? by lazy {
        if (cloudAiConfigured) KtorRelayClient(createHttpClient(), apiBaseUrl, appToken) else null
    }

    val transferService: TransferService by lazy {
        TransferService(
            transport = peerTransport,
            routes = routeRepository,
            store = fileStore,
            nowMs = { currentEpochMs() },
            relay = relayClient,
        )
    }
}
