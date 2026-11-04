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
 * Picks the IPv4 a peer should dial (plan W2-03, review 0014 R14-03).
 *
 * Interfaces the phone is not sharing on are excluded before anything is
 * ranked: cellular (`rmnet*`, `ccmni*`, `pdp*`), tunnels and VPNs (`tun*`,
 * `tap*`, `ppp*`, `clat*`, `ip6tnl*`), host-only stubs (`dummy*`), loopback
 * and the `v4-*` translation stubs. Their private addresses are site-local
 * too, so accepting one embedded an address no peer could ever reach into
 * the QR and the NFC invitation.
 *
 * What survives is ranked hotspot first (`ap*`, `softap*`, `swlan*`, the
 * second radio `wlan1`/`wlan2`, `p2p*`, `rndis*`), then the primary Wi-Fi
 * (`wlan0`), then wired LAN (`eth*`, `en*`), then anything else. A stable
 * sort keeps the platform's own order within a rank, so the choice is
 * deterministic for the hardware it is given.
 */
fun selectLanIpv4(interfaces: List<LanInterface>): String? =
    interfaces
        .filter { it.isUp && !it.isLoopback && it.isSiteLocal && isDialable(it) }
        .sortedBy { rank(it.name) }
        .firstOrNull()
        ?.address

private fun isDialable(candidate: LanInterface): Boolean =
    isIpv4(candidate.address) &&
        NON_LAN_PREFIXES.none { prefix -> candidate.name.startsWith(prefix) }

/** Hotspot, then primary Wi-Fi, then wired, then anything still dialable. */
private fun rank(name: String): Int = when {
    name.startsWith("ap") ||
        name.startsWith("softap") ||
        name.startsWith("swlan") ||
        name.startsWith("p2p") ||
        name.startsWith("rndis") -> HOTSPOT
    // A second Wi-Fi interface is a concurrent hotspot, not the client uplink.
    name.startsWith("wlan") && name != PRIMARY_WIFI_INTERFACE -> HOTSPOT
    name == PRIMARY_WIFI_INTERFACE -> WIFI
    name.startsWith("eth") || name.startsWith("en") -> WIRED
    else -> OTHER
}

private fun isIpv4(address: String): Boolean {
    val parts = address.split('.')
    return parts.size == 4 && parts.all {
        it.length in 1..3 && it.all { c -> c.isDigit() } &&
            it.toIntOrNull()?.let { n -> n in 0..255 } == true
    }
}

private const val HOTSPOT = 0
private const val WIFI = 1
private const val WIRED = 2
private const val OTHER = 3

private const val PRIMARY_WIFI_INTERFACE = "wlan0"

/**
 * Interfaces whose addresses exist on this phone but are not reachable by a
 * peer on the sharing network. Ordered by the stack they belong to.
 */
private val NON_LAN_PREFIXES = listOf(
    "rmnet", // cellular data
    "ccmni", "pdp", "radio", // vendor cellular stacks
    "tun", "tap", "ppp", "ip6tnl", // tunnels and VPNs
    "clat", // IPv4-over-IPv6 translation, not the peer's LAN
    "dummy", // host-only stub
    "lo", // loopback
    "v4-", // grouped tunnel stubs
)
