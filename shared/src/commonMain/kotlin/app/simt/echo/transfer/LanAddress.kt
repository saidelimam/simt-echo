package app.simt.echo.transfer

/** One network interface as the platform sees it (name + IPv4 address). */
data class LanInterface(
    val name: String,
    val address: String,
    val isUp: Boolean,
    val isLoopback: Boolean,
    val isSiteLocal: Boolean,
)

/**
 * Picks the IPv4 a peer should dial (plan W2-03): up, non-loopback,
 * site-local interfaces only, preferring Wi-Fi (`wlan*`) over hotspot
 * (`ap*`/`swlan*`) over anything else. Pure so the choice is host-tested;
 * the Android actual only enumerates interfaces.
 */
fun selectLanIpv4(interfaces: List<LanInterface>): String? {
    val candidates = interfaces.filter {
        it.isUp && !it.isLoopback && it.isSiteLocal && isIpv4(it.address)
    }
    return candidates.firstOrNull { it.name.startsWith("wlan") }?.address
        ?: candidates.firstOrNull {
            it.name.startsWith("ap") || it.name.startsWith("swlan")
        }?.address
        ?: candidates.firstOrNull()?.address
}

private fun isIpv4(address: String): Boolean {
    val parts = address.split('.')
    return parts.size == 4 && parts.all {
        it.length in 1..3 && it.all { c -> c.isDigit() } &&
            it.toIntOrNull()?.let { n -> n in 0..255 } == true
    }
}
