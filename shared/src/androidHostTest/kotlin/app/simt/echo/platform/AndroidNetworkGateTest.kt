package app.simt.echo.platform

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import app.simt.echo.transfer.NetworkKind
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetworkCapabilities
import kotlin.test.assertEquals

/**
 * W11-09: the production [AndroidNetworkGate] reads the active network's
 * real capabilities and fails closed on anything it cannot establish.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], manifest = Config.NONE)
class AndroidNetworkGateTest {

    private val context: Context = RuntimeEnvironment.getApplication()
    private val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private fun activeCapabilities(vararg capabilities: Int) {
        val caps = ShadowNetworkCapabilities.newInstance()
        capabilities.forEach { shadowOf(caps).addCapability(it) }
        shadowOf(manager).setNetworkCapabilities(manager.activeNetwork, caps)
    }

    private val internet = NetworkCapabilities.NET_CAPABILITY_INTERNET
    private val validated = NetworkCapabilities.NET_CAPABILITY_VALIDATED
    private val notMetered = NetworkCapabilities.NET_CAPABILITY_NOT_METERED
    private val notRoaming = NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING

    @Test
    fun wifiIsUnmetered() {
        activeCapabilities(internet, validated, notMetered, notRoaming)
        assertEquals(NetworkKind.UNMETERED, AndroidNetworkGate(context).kind())
    }

    @Test
    fun mobileDataIsMetered() {
        activeCapabilities(internet, validated, notRoaming)
        assertEquals(NetworkKind.METERED, AndroidNetworkGate(context).kind())
    }

    @Test
    fun roamingMobileDataIsRoaming() {
        activeCapabilities(internet, validated)
        assertEquals(NetworkKind.ROAMING, AndroidNetworkGate(context).kind())
    }

    @Test
    fun aNetworkWithoutValidatedInternetIsOffline() {
        activeCapabilities(internet, notMetered, notRoaming)
        assertEquals(NetworkKind.OFFLINE, AndroidNetworkGate(context).kind())
    }

    @Test
    fun noActiveNetworkIsOffline() {
        shadowOf(manager).setActiveNetworkInfo(null)
        assertEquals(NetworkKind.OFFLINE, AndroidNetworkGate(context).kind())
    }
}
