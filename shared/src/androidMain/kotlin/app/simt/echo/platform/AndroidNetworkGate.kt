package app.simt.echo.platform

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import app.simt.echo.transfer.NetworkGate
import app.simt.echo.transfer.NetworkKind
import app.simt.echo.transfer.networkKindOf

/**
 * [NetworkGate] over `ConnectivityManager` (plan W11-09). Read on demand
 * from the active network's capabilities, so the walk uploader sees a
 * Wi-Fi to mobile hand-over on its next window, not a cached answer. Any
 * failure to ask is [NetworkKind.OFFLINE]: the uploader fails closed.
 */
class AndroidNetworkGate(context: Context) : NetworkGate {
    private val manager = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    override fun kind(): NetworkKind = runCatching {
        val connectivity = checkNotNull(manager)
        val caps = connectivity.getNetworkCapabilities(checkNotNull(connectivity.activeNetwork))
        networkKindOf(
            hasInternet = caps != null &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
            notMetered = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true,
            notRoaming = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING) == true,
        )
    }.getOrDefault(NetworkKind.OFFLINE)
}
