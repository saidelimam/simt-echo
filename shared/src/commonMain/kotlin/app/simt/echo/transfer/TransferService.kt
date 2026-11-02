package app.simt.echo.transfer

import app.simt.echo.bundle.BundleCodec
import app.simt.echo.data.FileStore
import app.simt.echo.data.RouteRepository
import app.simt.echo.data.isSafeStorageId
import app.simt.echo.model.Asset
import app.simt.echo.model.BundleBody
import app.simt.echo.platform.Endpoint
import app.simt.echo.platform.PeerTransport
import app.simt.echo.platform.TransferProgress
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.merge
import okio.Path
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/** What the Share screen shows: the QR URI plus where we are serving. */
data class OfferInfo(
    val qrUri: String,
    val host: String,
    val port: Int,
)

/**
 * Offer/receive orchestration (plan W2-03):
 * offer = seal → token → transport.offer → QR string;
 * receive = parse → fetch → open → verify `s` fingerprint → save.
 * The transport moves sealed bytes only; identity comes from the QR.
 * File fallback (W2-07): [exportFile] writes the plain container,
 * [importFile] opens it back.
 */
@OptIn(ExperimentalEncodingApi::class)
class TransferService(
    private val transport: PeerTransport,
    private val routes: RouteRepository,
    private val store: FileStore,
    private val randomBytes: (Int) -> ByteArray = DEFAULT_RANDOM_BYTES,
    private val nowMs: () -> Long = { 0L },
    /** Internet relay (W4-05); null until W3-02 supplies Worker config. */
    private val relay: RelayClient? = null,
) {

    /** True when Share may offer the internet relay for this offer. */
    val relayAvailable: Boolean get() = relay != null

    /**
     * Starts a LAN offer; with [useRelay] the sealed bytes are also uploaded
     * to the relay and the QR gains `c=` so a receiver whose LAN attempt
     * fails can fall back to it. The bundle key stays in the QR either way.
     *
     * With [selection] (W3-08 preview sheet) the bundle holds the filtered
     * route copy only — toggled-off crops are absent, never just hidden.
     */
    suspend fun offer(
        routeId: String,
        useRelay: Boolean = false,
        selection: ExportSelection? = null,
    ): OfferInfo {
        val route = routes.get(routeId)
            ?: error("offer: no route $routeId")
        checkNotExpired(route)
        val exportRoute = selection?.let { applySelection(route, it, nowMs()) } ?: route
        val assets = collectAssets(routeId, exportRoute)
        val container = BundleCodec.encode(exportRoute, assets)
        val body = BundleBody(route = exportRoute, assets = assets.sortedBy { it.id })
        val (sealed, key) = BundleCodec.seal(container)
        val tokenBytes = randomBytes(QrPayload.TOKEN_BYTES)
        val endpoint = transport.offer(sealed, base64Url(tokenBytes))
        val relayCode = if (useRelay) {
            val client = relay ?: error("offer: internet relay is not configured")
            client.upload(sealed)
        } else {
            null
        }
        val payload = QrPayload(
            host = endpoint.host,
            port = endpoint.port,
            token = tokenBytes,
            key = key,
            sha8Hex = BundleCodec.sha8Hex(body),
            name = route.name,
            relayCode = relayCode,
        )
        return OfferInfo(qrUri = payload.build(), host = endpoint.host, port = endpoint.port)
    }

    suspend fun receive(qrUri: String): BundleBody {
        val body = fetchPreview(qrUri)
        saveImported(body)
        return body
    }

    /**
     * Fetches, opens and verifies the bundle behind [qrUri] without saving
     * (W2-04 Receive import sheet). The caller shows the returned body and
     * persists it with [saveImported] only after the user confirms Save.
     */
    suspend fun fetchPreview(qrUri: String): BundleBody {
        val payload = QrPayload.parse(qrUri).getOrThrow()
        val sealed = fetchSealed(payload)
        val body = BundleCodec.open(sealed, payload.key, nowMs()).getOrThrow()
        check(BundleCodec.sha8Hex(body) == payload.sha8Hex) {
            "bundle fingerprint mismatch: QR promised ${payload.sha8Hex}"
        }
        return body
    }

    /**
     * LAN first, relay second (W4-05): only a transport failure falls back —
     * a bundle that arrives but fails to open or verify is never masked by
     * fetching a second copy.
     */
    private suspend fun fetchSealed(payload: QrPayload): ByteArray {
        try {
            return transport.fetch(
                Endpoint(payload.host, payload.port),
                base64Url(payload.token),
            )
        } catch (lanFailure: Exception) {
            val code = payload.relayCode ?: throw lanFailure
            val client = relay ?: throw lanFailure
            return client.download(code)
        }
    }

    /**
     * Persists a previewed [BundleBody] with its asset bytes (W2-04 Save).
     * Imported files are untrusted: ids must be safe single path
     * components, and an existing route with the same id is replaced
     * cleanly — never overwritten in place with stale assets left
     * behind (review 0003 R1).
     */
    fun saveImported(body: BundleBody) {
        require(isSafeStorageId(body.route.routeId)) { "This file names an unsafe route id." }
        body.assets.forEach { asset ->
            require(isSafeStorageId(asset.id)) { "This file names an unsafe asset." }
        }
        if (routes.get(body.route.routeId) != null) {
            routes.delete(body.route.routeId)
        }
        val assetBytes = body.assets.associate { asset ->
            asset.id to Base64.decode(asset.b64)
        }
        routes.saveWithAssetBytes(body.route, assetBytes)
    }

    /**
     * Preview rows for the Share export sheet (W3-08): start photo plus
     * every landmark crop, with privacy-risk defaults applied.
     */
    fun describeExport(routeId: String): List<ExportItem> {
        val route = routes.get(routeId)
            ?: error("describeExport: no route $routeId")
        return describeExport(route)
    }

    /** Route-aware default selection (preserves stored expiry). */
    fun defaultSelectionFor(routeId: String): ExportSelection {
        val route = routes.get(routeId)
            ?: error("defaultSelection: no route $routeId")
        return defaultSelection(route)
    }

    /** Honest preview summary for the current [selection]. */
    fun summaryFor(routeId: String, selection: ExportSelection): ExportSummary {
        val route = routes.get(routeId)
            ?: error("summary: no route $routeId")
        return exportSummary(route, selection, nowMs())
    }

    private fun checkNotExpired(route: app.simt.echo.model.EchoRoute) {
        val expiry = route.privacy.expiresAtEpochMs
        if (expiry != null && expiry <= nowMs()) throw RouteExpiredException(expiry)
    }

    /** Deletes pending plaintext exports for one route only (`*-<routeId>.echo`). */
    fun deletePlainExportsFor(routeId: String) {
        store.list(FileStore.EXPORTS_DIR)
            .filter { it.name.endsWith("-$routeId.echo") }
            .forEach { store.deleteRecursively("${FileStore.EXPORTS_DIR}/${it.name}") }
    }

    /**
     * File-fallback export (W2-07): writes the **plain** container `C`
     * (unsealed — no key) to `exports/<name>.echo` under app storage and
     * returns its path. The caller must have shown the privacy warning
     * before invoking this; the bytes are readable without the QR key.
     * With [selection] the same W3-08 filter as [offer] applies.
     */
    suspend fun exportFile(routeId: String, selection: ExportSelection? = null): Path {
        val route = routes.get(routeId)
            ?: error("exportFile: no route $routeId")
        checkNotExpired(route)
        val exportRoute = selection?.let { applySelection(route, it, nowMs()) } ?: route
        val assets = collectAssets(routeId, exportRoute)
        val container = BundleCodec.encode(exportRoute, assets)
        // Route id in the filename: two same-named routes must not
        // overwrite each other's pending plaintext export.
        val name = echoFileName(route.name).removeSuffix(".echo") +
            "-${route.routeId}.echo"
        val relative = "${FileStore.EXPORTS_DIR}/$name"
        store.writeBytes(relative, container)
        return store.resolvePath(relative)
    }

    /** Deletes every pending plaintext export (bounded lifetime, R1). */
    fun purgePlainExports() {
        store.deleteRecursively(FileStore.EXPORTS_DIR)
    }

    /**
     * File-fallback import (W2-07): opens a plain `C` container from the
     * picked bytes. No key is needed because the file is unsealed.
     * Oversize files are refused before any decoding, and expiry is
     * enforced exactly as on the sealed path (review 0003 R1).
     */
    suspend fun importFile(bytes: ByteArray): BundleBody {
        require(bytes.size <= MAX_ECHO_FILE_BYTES) {
            "That file is too large to be an ECHO route."
        }
        return BundleCodec.open(bytes, nowMs = nowMs()).getOrThrow()
    }

    /**
     * Maps a route name to a safe `<name>.echo` filename, collapsing
     * path-hostile characters so the export stays in one directory.
     */
    fun echoFileName(name: String): String {
        val cleaned = name
            .trim()
            .map { if (it in PATH_HOSTILE) '_' else it }
            .joinToString("")
        return (if (cleaned.isEmpty()) "route" else cleaned) + ".echo"
    }

    internal companion object {
        /** Offer-token source: the platform CSPRNG (audit 0007 R1). */
        val DEFAULT_RANDOM_BYTES: (Int) -> ByteArray = ::secureRandomBytes

        /** Hard cap for a picked `.echo` file, enforced pre-decode (R1). */
        const val MAX_ECHO_FILE_BYTES = 50_000_000
        val PATH_HOSTILE = setOf('/', '\\', ':', '*', '?', '"', '<', '>', '|', ' ')
    }

    fun stopOffer() = transport.stopOffer()

    /** Re-emits the transport's per-download signal (W2-04 Share "Sent ✓"). */
    val served: Flow<Unit> get() = transport.served

    /**
     * Forwards byte-count progress (W13-14) from whichever path is active —
     * LAN transport or relay. Monotonic per transfer, ends at total; the
     * wire format, keys and tokens are untouched.
     */
    val progress: Flow<TransferProgress> get() = merge(
        transport.progress,
        relay?.progress ?: emptyFlow(),
    )

    private fun collectAssets(
        routeId: String,
        route: app.simt.echo.model.EchoRoute,
    ): List<Asset> {
        val ids = buildList {
            route.start.photoAssetId?.let { add(it) }
            route.landmarks.mapNotNullTo(this) { it.cropAssetId }
        }.distinct()
        return ids.mapNotNull { assetId ->
            routes.readAsset(routeId, assetId)?.let { bytes ->
                Asset(id = assetId, mime = "image/jpeg", b64 = Base64.encode(bytes))
            }
        }
    }
}
