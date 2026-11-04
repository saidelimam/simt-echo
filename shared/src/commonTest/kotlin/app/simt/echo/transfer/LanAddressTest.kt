package app.simt.echo.transfer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * R14-03 gate: the address in the invitation must be one a peer can dial.
 *
 * The old selector took the first IPv4 of each interface and accepted
 * anything site-local, so a phone whose hotspot interface was not named
 * `ap*`/`swlan*` advertised its carrier CGNAT address (`rmnet_data0`) or a
 * VPN tunnel (`tun0`) — an address that never answers from the other phone.
 * These cases are the interfaces real phones report.
 */
class LanAddressTest {

    private fun iface(
        name: String,
        address: String,
        isSiteLocal: Boolean = true,
        isUp: Boolean = true,
        isLoopback: Boolean = false,
    ) = LanInterface(
        name = name,
        address = address,
        isUp = isUp,
        isLoopback = isLoopback,
        isSiteLocal = isSiteLocal,
    )

    @Test
    fun cellularAndVpnAddressesYieldToTheHotspot() {
        val selected = selectLanIpv4(
            listOf(
                iface("rmnet_data0", "10.24.1.5"),
                iface("tun0", "10.8.0.2"),
                iface("softap0", "192.168.43.1"),
            ),
        )
        assertEquals("192.168.43.1", selected)
    }

    @Test
    fun onlyCellularAndVpnMeansNoLanAddressAtAll() {
        // No reachable LAN is an honest failure: the screens say "Connect to
        // Wi-Fi or turn on hotspot" instead of minting an unreachable offer.
        assertNull(
            selectLanIpv4(
                listOf(
                    iface("rmnet_data0", "10.24.1.5"),
                    iface("tun0", "10.8.0.2"),
                    iface("clat4", "192.0.0.4"),
                ),
            ),
        )
    }

    @Test
    fun aConcurrentHotspotWinsOverTheClientUplink() {
        // STA+AP: wlan0 is joined to someone else's network, wlan1 is ours.
        val selected = selectLanIpv4(
            listOf(
                iface("wlan0", "192.168.1.10"),
                iface("wlan1", "192.168.43.1"),
            ),
        )
        assertEquals("192.168.43.1", selected)
    }

    @Test
    fun aMultiAddressInterfaceKeepsItsSiteLocalIpv4() {
        // The old reader kept only the first address on the interface, which
        // here is a link-local one no peer can reach.
        val selected = selectLanIpv4(
            listOf(
                iface("wlan0", "169.254.7.9", isSiteLocal = false),
                iface("wlan0", "192.168.1.10"),
            ),
        )
        assertEquals("192.168.1.10", selected)
    }

    @Test
    fun downLoopbackAndMalformedEntriesAreIgnored() {
        assertNull(
            selectLanIpv4(
                listOf(
                    iface("wlan0", "192.168.1.10", isUp = false),
                    iface("lo", "127.0.0.1", isSiteLocal = false, isLoopback = true),
                    iface("wlan0", "not-an-address"),
                ),
            ),
        )
    }

    @Test
    fun wiredLanIsTheLastResortAndLosesToWiFi() {
        assertEquals("192.168.0.20", selectLanIpv4(listOf(iface("eth0", "192.168.0.20"))))
        assertEquals(
            "192.168.1.10",
            selectLanIpv4(listOf(iface("eth0", "192.168.0.20"), iface("wlan0", "192.168.1.10"))),
        )
    }
}
