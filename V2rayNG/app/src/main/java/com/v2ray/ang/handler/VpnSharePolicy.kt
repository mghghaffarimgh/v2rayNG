package com.v2ray.ang.handler

/**
 * Pure decisions of VPN sharing.
 *
 * Without root, Android does not let an app route other devices into its VPN, and devices on the
 * system hotspot skip the VPN entirely. So the app opens a local-only hotspot (a Wi-Fi network
 * with no internet route of its own) and other devices reach the internet only through the
 * core's proxy port: whatever does not use the proxy simply has no way out, so nothing leaks.
 * With root, the existing LAN sharing routes the system hotspot, USB and Ethernet tethering into
 * the tunnel transparently.
 */
object VpnSharePolicy {

    /**
     * A network interface with its IPv4 addresses.
     *
     * @property name The interface name.
     * @property ipv4 Its IPv4 addresses as text.
     */
    data class Interface(val name: String, val ipv4: List<String>)

    /**
     * An address other devices can use to reach the proxy.
     *
     * @property interfaceName The interface it belongs to.
     * @property ip The IPv4 address.
     * @property isShareHotspot True when it is the hotspot this app opened.
     */
    data class ShareAddress(val interfaceName: String, val ip: String, val isShareHotspot: Boolean)

    /** Why the local hotspot could not start, mapped from the platform's failure codes. */
    enum class HotspotFailure {
        NO_CHANNEL,
        INCOMPATIBLE_MODE,
        TETHERING_DISALLOWED,
        UNSUPPORTED,
        PERMISSION_MISSING,
        GENERIC,
    }

    /** Cellular, VPN and loopback interfaces never accept connections from other devices. */
    private val NON_LAN_PREFIXES = listOf("lo", "tun", "rmnet", "ccmni", "pdp", "v4-", "dummy", "ifb", "p2p-dev")

    /**
     * Finds the interfaces that appeared between two listings, which is how the hotspot's
     * interface is found: apps cannot ask the platform for its name.
     *
     * @param before Interfaces up before the hotspot started.
     * @param after Interfaces up after it started.
     * @return Names of the new interfaces that carry an IPv4 address.
     */
    fun newInterfaces(before: List<Interface>, after: List<Interface>): List<String> {
        val known = before.map { it.name }.toSet()
        return after.filter { it.name !in known && it.ipv4.isNotEmpty() }.map { it.name }
    }

    /**
     * Lists the addresses other devices can use, the share hotspot first.
     *
     * @param interfaces The interfaces that are up.
     * @param shareInterface The share hotspot's interface, or null when it is not running.
     * @return The addresses, private IPv4 only.
     */
    fun shareAddresses(interfaces: List<Interface>, shareInterface: String?): List<ShareAddress> {
        return interfaces
            .filter { iface -> NON_LAN_PREFIXES.none { iface.name.startsWith(it) } }
            .flatMap { iface ->
                iface.ipv4.filter { isPrivateIpv4(it) }.map { ShareAddress(iface.name, it, iface.name == shareInterface) }
            }
            .sortedWith(compareByDescending<ShareAddress> { it.isShareHotspot }.thenBy { it.interfaceName })
    }

    /**
     * Builds the text of a Wi-Fi QR code that phone cameras join directly.
     *
     * @param ssid The network name.
     * @param passphrase The password, or null for an open network.
     * @return The `WIFI:` payload.
     */
    fun wifiQrPayload(ssid: String, passphrase: String?): String {
        val security = if (passphrase.isNullOrEmpty()) "nopass" else "WPA"
        val password = if (passphrase.isNullOrEmpty()) "" else "P:${escapeQr(passphrase)};"
        return "WIFI:T:$security;S:${escapeQr(ssid)};$password;"
    }

    /**
     * Maps a `LocalOnlyHotspotCallback` failure code.
     *
     * @param reason The platform's reason code.
     * @return The failure.
     */
    fun hotspotFailure(reason: Int): HotspotFailure = when (reason) {
        1 -> HotspotFailure.NO_CHANNEL
        3 -> HotspotFailure.INCOMPATIBLE_MODE
        4 -> HotspotFailure.TETHERING_DISALLOWED
        else -> HotspotFailure.GENERIC
    }

    /**
     * Checks whether an IPv4 address is in a private or carrier-grade NAT range.
     *
     * @return True for 10/8, 172.16/12, 192.168/16 and 100.64/10.
     */
    fun isPrivateIpv4(ip: String): Boolean {
        val parts = ip.split('.').mapNotNull { it.toIntOrNull() }
        if (parts.size != 4 || parts.any { it !in 0..255 }) return false
        val (a, b) = parts
        return a == 10 || (a == 172 && b in 16..31) || (a == 192 && b == 168) || (a == 100 && b in 64..127)
    }

    private fun escapeQr(text: String): String {
        val builder = StringBuilder()
        for (ch in text) {
            if (ch == '\\' || ch == ';' || ch == ',' || ch == ':' || ch == '"') builder.append('\\')
            builder.append(ch)
        }
        return builder.toString()
    }
}
