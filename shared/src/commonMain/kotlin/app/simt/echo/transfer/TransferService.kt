package app.simt.echo.transfer

import app.simt.echo.bundle.BundleCodec
import app.simt.echo.data.RouteRepository
import app.simt.echo.model.Asset
import app.simt.echo.model.BundleBody
import app.simt.echo.platform.Endpoint
import app.simt.echo.platform.PeerTransport
import kotlinx.coroutines.flow.Flow
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.random.Random

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
 */
@OptIn(ExperimentalEncodingApi::class)
class TransferService(
    private val transport: PeerTransport,
    private val routes: RouteRepository,
    private val randomBytes: (Int) -> ByteArray = { size -> Random.nextBytes(size) },
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
     */
    suspend fun offer(routeId: String, useRelay: Boolean = false): OfferInfo {
        val route = routes.get(routeId)
            ?: error("offer: no route $routeId")
        val assets = collectAssets(routeId, route)
        val container = BundleCodec.encode(route, assets)
        val body = BundleBody(route = route, assets = assets.sortedBy { it.id })
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

    /** Persists a previewed [BundleBody] with its asset bytes (W2-04 Save). */
    fun saveImported(body: BundleBody) {
        val assetBytes = body.assets.associate { asset ->
            asset.id to Base64.decode(asset.b64)
        }
        routes.saveWithAssetBytes(body.route, assetBytes)
    }

    fun stopOffer() = transport.stopOffer()

    /** Re-emits the transport's per-download signal (W2-04 Share "Sent ✓"). */
    val served: Flow<Unit> get() = transport.served

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
