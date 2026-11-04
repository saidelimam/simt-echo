package app.simt.echo.transfer

import app.simt.echo.bundle.BundleCodec
import app.simt.echo.bundle.BundleCodecException
import app.simt.echo.bundle.BundleError
import app.simt.echo.data.FileStore
import app.simt.echo.data.RouteRepository
import app.simt.echo.data.isSafeStorageId
import app.simt.echo.model.Asset
import app.simt.echo.model.BundleBody
import app.simt.echo.model.EchoRoute
import app.simt.echo.platform.Endpoint
import app.simt.echo.platform.OfferProgress
import app.simt.echo.platform.PeerTransport
import app.simt.echo.platform.TransferProgress
import app.simt.echo.platform.platformIoDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.withContext
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
 *
 * One invitation, two representations (plan 0005 §4.5): the exact v1 URI
 * this returns is what both the QR and the NFC offer carry, and it is
 * owned by an [OfferSession] generation so a replaced offer can never
 * publish, report progress or claim completion again.
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
    /**
     * Whether the configured relay is qualified for peer delivery (plan
     * 0005 §1). True since R12-03: `worker/src/relay.ts` treats the
     * 10-character code as the capability — sender and receiver are
     * different installs, and the payload is ciphertext whose key never
     * reaches the worker — so a receiver can redeem a sender's code.
     * Configuring a relay is what makes it usable; an unconfigured graph
     * still offers nothing.
     */
    private val peerRelayQualified: Boolean = true,
    /**
     * Where the heavy bundle work runs (plan 0005 §4.5: never Main).
     * Injectable so a host test can keep preparation on its own dispatcher
     * and observe the ordered result deterministically.
     */
    private val ioDispatcher: kotlinx.coroutines.CoroutineDispatcher = platformIoDispatcher,
) {

    private val session = OfferSession(
        nowMs = nowMs,
        stopServing = { transport.stopOffer() },
    )

    /** A relay is configured on this build (it may still be unusable). */
    val relayConfigured: Boolean get() = relay != null

    /**
     * True when Share may offer peer relay delivery for this offer: a relay
     * is configured and this backend is qualified for cross-install reads
     * (plan 0005 §1, R12-03). Without a configured relay the action hides.
     */
    val relayAvailable: Boolean get() = relay != null && peerRelayQualified

    /** The offer currently being served, or null while preparing or stopped. */
    val currentOffer: ActiveOffer? get() = session.current

    /** Offer generation that owns the session right now (monotonic). */
    val offerGeneration: Long get() = session.currentGeneration

    /**
     * Starts a LAN offer; with [useRelay] the sealed bytes are also uploaded
     * to a qualified relay and the QR gains `c=`. The bundle key stays in
     * the QR either way.
     *
     * With [selection] (W3-08 preview sheet) the bundle holds the filtered
     * route copy only — toggled-off crops are absent, never just hidden.
     *
     * The previous offer stops the instant this call is made, and the new
     * one is published only if it is still the newest: a late completion
     * from a replaced call throws [OfferSupersededException] instead of
     * publishing a QR nothing is serving.
     */
    suspend fun offer(
        routeId: String,
        useRelay: Boolean = false,
        selection: ExportSelection? = null,
    ): OfferInfo {
        val route = routes.get(routeId)
            ?: error("offer: no route $routeId")
        checkNotExpired(route)
        if (useRelay && !relayAvailable) {
            error("offer: peer relay is not available for this backend")
        }
        val published = session.replace { generation ->
            // Heavy bundle preparation off Main (plan 0005 §4.5).
            val prepared = withContext(ioDispatcher) {
                prepareBundle(routeId, route, selection)
            }
            if (!session.isCurrent(generation)) return@replace null
            val endpoint = transport.offer(
                prepared.sealed,
                base64Url(prepared.token),
                generation,
            )
            val relayCode = if (useRelay) {
                val client = relay ?: error("offer: internet relay is not configured")
                client.upload(prepared.sealed)
            } else {
                null
            }
            if (!session.isCurrent(generation)) return@replace null
            val payload = QrPayload(
                host = endpoint.host,
                port = endpoint.port,
                token = prepared.token,
                key = prepared.key,
                sha8Hex = BundleCodec.sha8Hex(prepared.body),
                name = route.name,
                relayCode = relayCode,
            )
            session.stamp(generation, payload.build(), endpoint.host, endpoint.port)
        } ?: throw OfferSupersededException()
        return OfferInfo(qrUri = published.qrUri, host = published.host, port = published.port)
    }

    /** Downloads attributed to the offer still being served. */
    val offerServed: Flow<Long> get() =
        transport.servedOffers.filter { generation -> session.isCurrent(generation) }

    /** Byte progress attributed to the offer still being served. */
    val offerProgress: Flow<OfferProgress> get() =
        transport.offerProgress.filter { sample -> session.isCurrent(sample.generation) }

    suspend fun receive(qrUri: String): BundleBody {
        val body = fetchPreview(qrUri)
        saveImported(body)
        return body
    }

    /**
     * Fetches, opens and verifies the bundle behind [qrUri] without saving
     * (W2-04 Receive import sheet). The caller shows the returned body and
     * persists it with [saveImported] only after the user confirms Save.
     *
     * The URI passes the shared bounded validation first, so no caller can
     * reach the network with a URI that [PairingInvitation] would reject.
     */
    suspend fun fetchPreview(qrUri: String): BundleBody {
        val invitation = PairingInvitation.parseOrThrow(qrUri)
        val sealed = fetchSealed(invitation.payload)
        val body = BundleCodec.open(sealed, invitation.payload.key, nowMs()).getOrThrow()
        // Integrity, not connectivity: typed so the screen says "couldn't
        // verify" instead of blaming the network (plan 0005 §2).
        if (BundleCodec.sha8Hex(body) != invitation.payload.sha8Hex) {
            throw BundleCodecException(BundleError.HASH_MISMATCH, "bundle fingerprint mismatch")
        }
        return body
    }

    /**
     * LAN first, relay second (W4-05): only a transport failure falls back —
     * a bundle that arrives but fails to open or verify is never masked by
     * fetching a second copy.
     *
     * Cancellation is not a transport failure (plan 0005 §4.5): it is
     * rethrown before any fallback is considered, so leaving the screen can
     * never start a relay download. The fallback also requires a qualified
     * peer relay, which the current backend is not.
     */
    private suspend fun fetchSealed(payload: QrPayload): ByteArray {
        try {
            return transport.fetch(
                Endpoint(payload.host, payload.port),
                base64Url(payload.token),
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (lanFailure: Exception) {
            val code = payload.relayCode ?: throw lanFailure
            if (!relayAvailable) throw lanFailure
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

    private fun checkNotExpired(route: EchoRoute) {
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
     * With [selection] the same W3-08 filter as [offer] applies. Unlike
     * the sealed [offer], a plain file carries no encryption, so on-device
     * perception data (W10-05) is left out unless [includePerception].
     */
    suspend fun exportFile(
        routeId: String,
        selection: ExportSelection? = null,
        includePerception: Boolean = false,
    ): Path {
        val route = routes.get(routeId)
            ?: error("exportFile: no route $routeId")
        checkNotExpired(route)
        return withContext(ioDispatcher) {
            val filtered = selection?.let { applySelection(route, it, nowMs()) } ?: route
            val exportRoute = if (includePerception) filtered else filtered.withoutPerception()
            val assets = collectAssets(routeId, exportRoute)
            val container = BundleCodec.encode(exportRoute, assets)
            // Route id in the filename: two same-named routes must not
            // overwrite each other's pending plaintext export.
            val name = echoFileName(route.name).removeSuffix(".echo") +
                "-${route.routeId}.echo"
            val relative = "${FileStore.EXPORTS_DIR}/$name"
            store.writeBytes(relative, container)
            store.resolvePath(relative)
        }
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

    /** Stops serving, whatever generation owns it. Safe to repeat. */
    fun stopOffer() {
        session.invalidate()
    }

    /** Stops serving only while [generation] still owns the session. */
    fun unpublishOffer(generation: Long) {
        if (session.isCurrent(generation)) session.invalidate()
    }

    /**
     * Drops an offer that outlived its ten-minute window.
     * @return the expired generation, or null when nothing expired.
     */
    fun expireOffer(): Long? = session.expireIfNeeded()

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

    /** Everything the bundle must carry, prepared once per offer. */
    private class PreparedBundle(
        val sealed: ByteArray,
        val key: ByteArray,
        val token: ByteArray,
        val body: BundleBody,
    )

    /** Seals the filtered route; runs off Main and touches only this offer. */
    private suspend fun prepareBundle(
        routeId: String,
        route: EchoRoute,
        selection: ExportSelection?,
    ): PreparedBundle {
        val exportRoute = selection?.let { applySelection(route, it, nowMs()) } ?: route
        val assets = collectAssets(routeId, exportRoute)
        val container = BundleCodec.encode(exportRoute, assets)
        val body = BundleBody(route = exportRoute, assets = assets.sortedBy { it.id })
        val (sealed, key) = BundleCodec.seal(container)
        return PreparedBundle(
            sealed = sealed,
            key = key,
            token = randomBytes(QrPayload.TOKEN_BYTES),
            body = body,
        )
    }

    private fun collectAssets(
        routeId: String,
        route: EchoRoute,
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
