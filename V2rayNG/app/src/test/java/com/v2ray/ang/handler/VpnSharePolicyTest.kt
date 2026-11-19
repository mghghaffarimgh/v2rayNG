package com.v2ray.ang.handler

import com.v2ray.ang.handler.VpnSharePolicy.HotspotFailure
import com.v2ray.ang.handler.VpnSharePolicy.Interface
import com.v2ray.ang.handler.VpnSharePolicy.ShareAddress
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class VpnSharePolicyTest {

    @Test
    fun newInterfaceIsTheHotspot() {
        val before = listOf(Interface("wlan0", listOf("192.168.1.20")), Interface("rmnet0", listOf("10.1.2.3")))
        val after = before + Interface("ap0", listOf("192.168.49.1")) + Interface("swlan0", emptyList())
        assertEquals(listOf("ap0"), VpnSharePolicy.newInterfaces(before, after))
        assertEquals(emptyList<String>(), VpnSharePolicy.newInterfaces(after, after))
    }

    @Test
    fun shareAddressesSkipNonLanInterfacesAndPutTheHotspotFirst() {
        val interfaces = listOf(
            Interface("wlan0", listOf("192.168.1.20")),
            Interface("rmnet_data0", listOf("10.20.30.40")),
            Interface("tun0", listOf("10.10.14.1")),
            Interface("lo", listOf("127.0.0.1")),
            Interface("ap0", listOf("192.168.49.1")),
            Interface("eth0", listOf("8.8.8.8")),
        )
        assertEquals(
            listOf(
                ShareAddress("ap0", "192.168.49.1", true),
                ShareAddress("wlan0", "192.168.1.20", false),
            ),
            VpnSharePolicy.shareAddresses(interfaces, shareInterface = "ap0"),
        )
    }

    @Test
    fun wifiQrEscapesSpecialCharacters() {
        assertEquals("WIFI:T:WPA;S:AndroidShare_1234;P:pa\\;ss\\:w\\\\d;;", VpnSharePolicy.wifiQrPayload("AndroidShare_1234", "pa;ss:w\\d"))
        assertEquals("WIFI:T:nopass;S:Open\\,Net;;", VpnSharePolicy.wifiQrPayload("Open,Net", null))
    }

    @Test
    fun hotspotFailureCodesAreMapped() {
        assertEquals(HotspotFailure.NO_CHANNEL, VpnSharePolicy.hotspotFailure(1))
        assertEquals(HotspotFailure.GENERIC, VpnSharePolicy.hotspotFailure(2))
        assertEquals(HotspotFailure.INCOMPATIBLE_MODE, VpnSharePolicy.hotspotFailure(3))
        assertEquals(HotspotFailure.TETHERING_DISALLOWED, VpnSharePolicy.hotspotFailure(4))
        assertEquals(HotspotFailure.GENERIC, VpnSharePolicy.hotspotFailure(99))
    }

    @Test
    fun privateRanges() {
        assertTrue(VpnSharePolicy.isPrivateIpv4("10.0.0.1"))
        assertTrue(VpnSharePolicy.isPrivateIpv4("172.31.255.1"))
        assertTrue(VpnSharePolicy.isPrivateIpv4("192.168.43.1"))
        assertTrue(VpnSharePolicy.isPrivateIpv4("100.64.0.1"))
        assertFalse(VpnSharePolicy.isPrivateIpv4("172.32.0.1"))
        assertFalse(VpnSharePolicy.isPrivateIpv4("8.8.8.8"))
        assertFalse(VpnSharePolicy.isPrivateIpv4("192.168.1"))
        assertFalse(VpnSharePolicy.isPrivateIpv4("300.1.1.1"))
    }
}
