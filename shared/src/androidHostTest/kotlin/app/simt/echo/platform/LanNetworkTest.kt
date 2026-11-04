package app.simt.echo.platform

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsBytes
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowNetworkCapabilities
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * R14-01 gate: the LAN dial names the Wi-Fi network.
 *
 * Two failures compounded here. Android refuses cleartext on 9+ unless the
 * shipped policy allows it, and an offline hotspot or venue Wi-Fi is not the
 * process default network, so a plain socket follows cellular or a VPN and
 * times out. The policy half is asserted in [NfcManifestTest]; this is the
 * routing half: which network is chosen, and that the LAN client built for it
 * reaches the peer over that network. A real Wi-Fi interface is W21-05 device
 * evidence — no host test has a radio.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], manifest = Config.NONE)
class LanNetworkTest {

    private val context = RuntimeEnvironment.getApplication()

    private val manager
        get() = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    /** Robolectric starts with one default network; each case chooses its own. */
    private fun resetNetworks() {
        Shadows.shadowOf(manager).clearAllNetworks()
    }

    private fun network(id: Int, transport: Int) =
        ShadowNetwork.newInstance(id).also { network ->
            Shadows.shadowOf(manager).addNetwork(network, null)
            Shadows.shadowOf(manager).setNetworkCapabilities(
                network,
                Shadows.shadowOf(ShadowNetworkCapabilities.newInstance())
                    .addTransportType(transport),
            )
        }

    @Test
    fun withoutWiFiThereIsNoNetworkToBindTo() {
        resetNetworks()
        assertNull(activeLanNetwork(context), "no interface means ordinary platform routing")

        network(id = 7, transport = NetworkCapabilities.TRANSPORT_CELLULAR)
        assertNull(activeLanNetwork(context), "cellular is never the sharing network")
    }

    @Test
    fun theWiFiNetworkIsChosenEvenWhenCellularIsAlsoConnected() {
        resetNetworks()
        val wifi = network(id = 11, transport = NetworkCapabilities.TRANSPORT_WIFI)
        network(id = 12, transport = NetworkCapabilities.TRANSPORT_CELLULAR)

        assertEquals(wifi, activeLanNetwork(context))
    }

    @Test
    fun theLanClientBuiltForThatNetworkReachesThePeer() = runBlocking {
        resetNetworks()
        val wifi = network(id = 21, transport = NetworkCapabilities.TRANSPORT_WIFI)
        val payload = ByteArray(32) { 3 }
        val transport = KtorLanTransport(afterDownloadStopMs = 60_000, offerLifetimeMs = 60_000)
        try {
            val endpoint = transport.offer(payload, "bound", 1L)
            val body = lanHttpClient(wifi)
                .get("http://${endpoint.host}:${endpoint.port}/echo/bound")
                .bodyAsBytes()
            assertContentEquals(payload, body, "the network-bound client fetched the sealed bytes")
        } finally {
            transport.stopOffer()
        }
    }
}
