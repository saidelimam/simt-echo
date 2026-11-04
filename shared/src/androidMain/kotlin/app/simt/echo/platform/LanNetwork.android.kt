package app.simt.echo.platform

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout

/**
 * The network LAN sharing may leave by (R14-01).
 *
 * A hotspot, or an offline venue Wi-Fi, is not the process default network:
 * Android keeps a validated cellular connection or a VPN there, and a plain
 * socket to `192.168.x.x` follows it and times out. The peer dial has to name
 * the Wi-Fi network explicitly, which is what [Network.socketFactory] does.
 * Resolved per call, so a Wi-Fi to hotspot hand-over is followed without
 * restarting anything. Null when no Wi-Fi is connected, and the caller then
 * falls back to ordinary platform routing.
 */
@Suppress("DEPRECATION") // allNetworks is the only way to see a connected,
// not-yet-validated Wi-Fi: the hotspot case this rule exists for.
fun activeLanNetwork(context: Context): Network? {
    val manager = context.applicationContext
        .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        ?: return null
    val wifi = runCatching {
        manager.allNetworks.filter { network ->
            manager.getNetworkCapabilities(network)
                ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }
    }.getOrDefault(emptyList())
    if (wifi.isEmpty()) return null
    val active = runCatching { manager.activeNetwork }.getOrNull()
    return wifi.firstOrNull { it == active } ?: wifi.first()
}

/**
 * The one LAN client policy: redirects off (plan 0005 §4.2), bounded, and —
 * when [network] is known — every socket created by that network, so the
 * dial cannot be hijacked by cellular or a VPN. Null keeps the platform
 * default, which is what previews, host tests and a wired host use.
 */
internal fun lanHttpClient(network: Network?): HttpClient = HttpClient(OkHttp) {
    followRedirects = false
    install(HttpTimeout) {
        connectTimeoutMillis = 4_000
        requestTimeoutMillis = 20_000
    }
    if (network != null) {
        engine {
            config {
                val factory = network.socketFactory
                socketFactory(factory)
            }
        }
    }
}

/**
 * The process LAN transport, dialing through the active Wi-Fi/hotspot
 * network (R14-01). Supplied by the Android entry point, so the address the
 * offer advertises and the interface the receiver dials are the same one.
 */
fun createLanTransport(context: Context): PeerTransport =
    KtorLanTransport(clientFactory = { lanHttpClient(activeLanNetwork(context)) })
