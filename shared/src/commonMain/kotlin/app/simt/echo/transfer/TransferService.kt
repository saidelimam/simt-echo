package app.simt.echo.transfer

import app.simt.echo.bundle.BundleCodec
import app.simt.echo.data.RouteRepository
import app.simt.echo.model.Asset
import app.simt.echo.model.BundleBody
import app.simt.echo.platform.PeerTransport
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
) {

    suspend fun offer(routeId: String): OfferInfo {
        val route = routes.get(routeId)
            ?: error("offer: no route $routeId")
        val assets = collectAssets(routeId, route)
        val container = BundleCodec.encode(route, assets)
        val body = BundleBody(route = route, assets = assets.sortedBy { it.id })
        val (sealed, key) = BundleCodec.seal(container)
        val tokenBytes = randomBytes(QrPayload.TOKEN_BYTES)
        val endpoint = transport.offer(sealed, base64Url(tokenBytes))
        val payload = QrPayload(
            host = endpoint.host,
            port = endpoint.port,
            token = tokenBytes,
            key = key,
            sha8Hex = BundleCodec.sha8Hex(body),
            name = route.name,
        )
        return OfferInfo(qrUri = payload.build(), host = endpoint.host, port = endpoint.port)
    }

    suspend fun receive(qrUri: String): BundleBody {
        val payload = QrPayload.parse(qrUri).getOrThrow()
        val sealed = transport.fetch(
            app.simt.echo.platform.Endpoint(payload.host, payload.port),
            base64Url(payload.token),
        )
        val body = BundleCodec.open(sealed, payload.key, nowMs()).getOrThrow()
        check(BundleCodec.sha8Hex(body) == payload.sha8Hex) {
            "bundle fingerprint mismatch: QR promised ${payload.sha8Hex}"
        }
        val assetBytes = body.assets.associate { asset ->
            asset.id to Base64.decode(asset.b64)
        }
        routes.saveWithAssetBytes(body.route, assetBytes)
        return body
    }

    fun stopOffer() = transport.stopOffer()

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
