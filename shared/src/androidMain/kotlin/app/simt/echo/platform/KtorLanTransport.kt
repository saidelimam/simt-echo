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
import kotlin.time.Duration.Companion.milliseconds

/**
 * LAN [PeerTransport] (plan W2-03): the sealed bundle is served once over
 * plain HTTP on the local network — confidentiality comes from the
 * AES-GCM seal and the QR-carried key/token, not from TLS. The server
 * stops 3 s after the first successful download, or after 10 minutes.
 *
 * Every stop is bound to the server instance it was scheduled for (plan
 * 0005 §4.5). A re-offer replaces the server, and the old instance's
 * shutdown timer — or a stale generation-scoped stop — cannot take the
 * new one down with it. That is the hazard the plan calls out: unscoped
 * delayed stops used to kill whichever offer happened to be newest.
 */
class KtorLanTransport(
    private val afterDownloadStopMs: Long = AFTER_DOWNLOAD_STOP_MS,
    private val offerLifetimeMs: Long = OFFER_LIFETIME_MS,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO),
    /**
     * The sockets this transport dials through (R14-01). A phone supplies
     * [NetworkBoundSocketFactory] over its active Wi-Fi/hotspot network, so a
     * same-network fetch cannot leave over cellular or a VPN; the default
     * keeps previews and host tests on the ordinary socket factory.
     */
    private val clientFactory: () -> HttpClient = ::defaultClient,
) : PeerTransport {

    private var server: EmbeddedServer<*, *>? = null
    private var serverGeneration: Long = 0L
    private val downloads = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val downloadGenerations = MutableSharedFlow<Long>(extraBufferCapacity = 4)
    private val progressFlow = newProgressFlow()
    private val offerProgressFlow = MutableSharedFlow<OfferProgress>(extraBufferCapacity = 64)
    private val client: HttpClient by lazy(clientFactory)

    /** Emits once per successful peer download (W2-04 Share "Sent ✓"). */
    override val served: Flow<Unit> get() = downloads

    /** Downloads tagged with the offer generation that was serving. */
    override val servedOffers: Flow<Long> get() = downloadGenerations

    /**
     * Measured byte progress (W13-14): the server streams the payload in
     * chunks so the offering phone observes bytes served; the client
     * reports `onDownload` so the fetching phone observes bytes received.
     */
    override val progress: Flow<TransferProgress> get() = progressFlow

    /** The same samples, tagged with the offering generation. */
    override val offerProgress: Flow<OfferProgress> get() = offerProgressFlow

    override suspend fun offer(payload: ByteArray, token: String): Endpoint =
        offer(payload, token, generation = 0L)

    override suspend fun offer(payload: ByteArray, token: String, generation: Long): Endpoint {
        stopOffer()
        val total = payload.size.toLong()
        // The handler has to name the server it belongs to, which only
        // exists after start(): this holder keeps that binding explicit.
        val handle = ServerHandle()
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
                            val sample = TransferProgress(sent.toLong(), total)
                            progressFlow.tryEmit(sample)
                            offerProgressFlow.tryEmit(OfferProgress(generation, sample))
                        }
                    }
                    downloads.tryEmit(Unit)
                    downloadGenerations.tryEmit(generation)
                    scope.launch {
                        delay(afterDownloadStopMs.milliseconds)
                        handle.server?.let(::stopIfCurrent)
                    }
                }
            }
        }.start(wait = false)
        handle.server = started
        synchronized(this) {
            server = started
            serverGeneration = generation
        }
        scope.launch {
            delay(offerLifetimeMs.milliseconds)
            stopIfCurrent(started)
        }
        // A failed offer must close the server it already started: a host
        // lookup that finds no Wi-Fi is a failed offer, not a stray server.
        val port = try {
            started.engine.resolvedConnectors().first().port
        } catch (failure: Exception) {
            stopIfCurrent(started)
            throw failure
        }
        val host = localIpv4()
        if (host == null) {
            stopIfCurrent(started)
            error("Connect to Wi-Fi or turn on hotspot")
        }
        return Endpoint(host, port)
    }

    override suspend fun fetch(endpoint: Endpoint, token: String): ByteArray =
        client.get("http://${endpoint.host}:${endpoint.port}/echo/$token")
            .readWithProgress { progressFlow.tryEmit(it) }

    override fun stopOffer() {
        synchronized(this) {
            server?.stop(500, 1_000)
            server = null
        }
    }

    /** Ignores a stop aimed at an offer that has already been replaced. */
    override fun stopOffer(generation: Long) {
        synchronized(this) {
            if (server != null && serverGeneration == generation) {
                server?.stop(500, 1_000)
                server = null
            }
        }
    }

    /** Binds a routing handler to the server instance created for it. */
    private class ServerHandle {
        var server: EmbeddedServer<*, *>? = null
    }

    /** Stops [target] only while it is still the current server. */
    private fun stopIfCurrent(target: EmbeddedServer<*, *>) {
        synchronized(this) {
            if (server === target) {
                server = null
                target.stop(500, 1_000)
            }
        }
    }

    private companion object {
        /** Server stream quantum: one progress emit per chunk served. */
        const val SERVE_CHUNK_BYTES = 64 * 1024

        const val AFTER_DOWNLOAD_STOP_MS = 3_000L
        const val OFFER_LIFETIME_MS = 600_000L

        /**
         * Redirects off (plan 0005 §4.2): the LAN fetch follows no Location.
         * Every socket is created by [socketFactory], which is what pins the
         * dial to the sharing network rather than the process default.
         */
        fun defaultClient(): HttpClient = HttpClient(OkHttp) {
            followRedirects = false
            install(HttpTimeout) {
                connectTimeoutMillis = 4_000
                requestTimeoutMillis = 20_000
            }
        }
    }

    private fun localIpv4(): String? = selectLanIpv4(
        runCatching { NetworkInterface.getNetworkInterfaces() }.getOrNull()
            ?.toList()
            .orEmpty()
            .flatMap(::lanInterfaces),
    )

    /**
     * One [LanInterface] per IPv4 address (R14-03). Keeping only the first
     * address of an interface hid the hotspot address whenever the interface
     * also carried a link-local or translated one.
     */
    private fun lanInterfaces(iface: NetworkInterface): List<LanInterface> = runCatching {
        val isUp = iface.isUp
        val isLoopback = iface.isLoopback
        iface.inetAddresses.toList()
            .filterIsInstance<Inet4Address>()
            .map { address ->
                LanInterface(
                    name = iface.name,
                    address = address.hostAddress ?: "",
                    isUp = isUp,
                    isLoopback = isLoopback,
                    isSiteLocal = address.isSiteLocalAddress,
                )
            }
    }.getOrDefault(emptyList())
}
