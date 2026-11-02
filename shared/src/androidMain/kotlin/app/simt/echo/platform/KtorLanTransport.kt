package app.simt.echo.platform

import app.simt.echo.transfer.LanInterface
import app.simt.echo.transfer.newProgressFlow
import app.simt.echo.transfer.readWithProgress
import app.simt.echo.transfer.selectLanIpv4
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respond
import io.ktor.server.response.respondOutputStream
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * LAN [PeerTransport] (plan W2-03): the sealed bundle is served once over
 * plain HTTP on the local network — confidentiality comes from the
 * AES-GCM seal and the QR-carried key/token, not from TLS. The server
 * stops 3 s after the first successful download, or after 10 minutes.
 */
class KtorLanTransport : PeerTransport {

    private var server: EmbeddedServer<*, *>? = null
    private val scope = CoroutineScope(Dispatchers.IO)
    private val downloads = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val progressFlow = newProgressFlow()

    /** Emits once per successful peer download (W2-04 Share "Sent ✓"). */
    override val served: Flow<Unit> get() = downloads

    /**
     * Measured byte progress (W13-14): the server streams the payload in
     * chunks so the offering phone observes bytes served; the client
     * reports `onDownload` so the fetching phone observes bytes received.
     */
    override val progress: Flow<TransferProgress> get() = progressFlow
    private val client = HttpClient(OkHttp) {
        install(HttpTimeout) {
            connectTimeoutMillis = 4_000
            requestTimeoutMillis = 20_000
        }
    }

    override suspend fun offer(payload: ByteArray, token: String): Endpoint {
        stopOffer()
        val total = payload.size.toLong()
        val started = embeddedServer(CIO, port = 0, host = "0.0.0.0") {
            routing {
                get("/echo/{t}") {
                    if (call.parameters["t"] != token) {
                        call.respond(HttpStatusCode.NotFound)
                        return@get
                    }
                    // Streamed (not respondBytes) so each chunk served is
                    // observed: the Share particle flow is driven by bytes
                    // that actually left this phone.
                    call.respondOutputStream(ContentType.Application.OctetStream, contentLength = total) {
                        var sent = 0
                        while (sent < payload.size) {
                            val n = minOf(SERVE_CHUNK_BYTES, payload.size - sent)
                            write(payload, sent, n)
                            flush()
                            sent += n
                            progressFlow.tryEmit(TransferProgress(sent.toLong(), total))
                        }
                    }
                    downloads.tryEmit(Unit)
                    scope.launch {
                        delay(3_000)
                        stopOffer()
                    }
                }
            }
        }.start(wait = false)
        server = started
        scope.launch {
            delay(600_000)
            stopOffer()
        }
        val port = started.engine.resolvedConnectors().first().port
        val host = localIpv4()
            ?: error("Connect to Wi-Fi or turn on hotspot")
        return Endpoint(host, port)
    }

    override suspend fun fetch(endpoint: Endpoint, token: String): ByteArray =
        client.get("http://${endpoint.host}:${endpoint.port}/echo/$token")
            .readWithProgress { progressFlow.tryEmit(it) }

    override fun stopOffer() {
        server?.stop(500, 1_000)
        server = null
    }

    private companion object {
        /** Server stream quantum: one progress emit per chunk served. */
        const val SERVE_CHUNK_BYTES = 64 * 1024
    }

    private fun localIpv4(): String? {
        val interfaces = NetworkInterface.getNetworkInterfaces().toList().map { iface ->
            val address = iface.inetAddresses.toList()
                .filterIsInstance<Inet4Address>()
                .firstOrNull()
            LanInterface(
                name = iface.name,
                address = address?.hostAddress ?: "",
                isUp = iface.isUp,
                isLoopback = iface.isLoopback,
                isSiteLocal = address?.isSiteLocalAddress == true,
            )
        }
        return selectLanIpv4(interfaces)
    }
}
