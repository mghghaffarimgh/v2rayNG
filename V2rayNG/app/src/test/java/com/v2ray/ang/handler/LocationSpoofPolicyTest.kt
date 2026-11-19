package com.v2ray.ang.handler

import com.v2ray.ang.handler.LocationSpoofPolicy.Finding
import com.v2ray.ang.handler.LocationSpoofPolicy.Signals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sqrt
import kotlin.random.Random

class LocationSpoofPolicyTest {

    private val berlinOffset = 3_600_000

    private val consistent = Signals(
        mockAppSelected = true,
        vpnConnected = true,
        targetCountry = "DE",
        vpnExitCountry = "DE",
        targetUtcOffsetMs = berlinOffset,
        deviceUtcOffsetMs = berlinOffset,
        simCountry = null,
        networkCountry = "",
        wifiScanAlwaysOn = false,
    )

    @Test
    fun consistentSetupHasNoFinding() {
        assertEquals(emptyList<Finding>(), LocationSpoofPolicy.consistency(consistent))
    }

    @Test
    fun everyContradictionIsReportedInOrder() {
        val signals = Signals(
            mockAppSelected = false,
            vpnConnected = true,
            targetCountry = "de",
            vpnExitCountry = "NL",
            targetUtcOffsetMs = berlinOffset,
            deviceUtcOffsetMs = 12_600_000,
            simCountry = "ir",
            networkCountry = "IR",
            wifiScanAlwaysOn = true,
        )
        assertEquals(
            listOf(
                Finding.NOT_MOCK_APP,
                Finding.VPN_COUNTRY_MISMATCH,
                Finding.TIME_ZONE_MISMATCH,
                Finding.SIM_COUNTRY_MISMATCH,
                Finding.NETWORK_COUNTRY_MISMATCH,
                Finding.WIFI_SCANNING_ON,
            ).sorted(),
            LocationSpoofPolicy.consistency(signals).sorted(),
        )
    }

    @Test
    fun vpnOffIsReportedAndSkipsTheExitComparison() {
        val off = consistent.copy(vpnConnected = false, vpnExitCountry = "NL")
        assertEquals(listOf(Finding.VPN_NOT_CONNECTED), LocationSpoofPolicy.consistency(off))
    }

    @Test
    fun unknownTargetSkipsCountryChecks() {
        val custom = consistent.copy(targetCountry = null, vpnExitCountry = "NL", simCountry = "IR", targetUtcOffsetMs = null)
        assertEquals(emptyList<Finding>(), LocationSpoofPolicy.consistency(custom))
    }

    @Test
    fun unmeasuredExitIsNotAMismatch() {
        assertEquals(emptyList<Finding>(), LocationSpoofPolicy.consistency(consistent.copy(vpnExitCountry = null)))
    }

    @Test
    fun presetLookupIgnoresCase() {
        assertEquals("DE", LocationSpoofPolicy.presetFor("de")?.country)
        assertNull(LocationSpoofPolicy.presetFor("ZZ"))
        assertNull(LocationSpoofPolicy.presetFor(null))
    }

    @Test
    fun presetsAreValidAndUnique() {
        val countries = LocationSpoofPolicy.PRESETS.map { it.country }
        assertEquals(countries.size, countries.toSet().size)
        LocationSpoofPolicy.PRESETS.forEach {
            assertTrue(LocationSpoofPolicy.isValidCoordinate(it.latitude, it.longitude), it.country)
            assertTrue(java.util.TimeZone.getAvailableIDs().contains(it.timeZone), it.timeZone)
        }
    }

    @Test
    fun coordinateValidation() {
        assertTrue(LocationSpoofPolicy.isValidCoordinate(-90.0, 180.0))
        assertFalse(LocationSpoofPolicy.isValidCoordinate(90.1, 0.0))
        assertFalse(LocationSpoofPolicy.isValidCoordinate(0.0, -180.5))
        assertFalse(LocationSpoofPolicy.isValidCoordinate(null, 0.0))
        assertFalse(LocationSpoofPolicy.isValidCoordinate(Double.NaN, 0.0))
    }

    @Test
    fun typedCoordinatesAreParsed() {
        assertEquals(52.52 to 13.405, LocationSpoofPolicy.parseCoordinates(" 52.52 ", "13.405"))
        assertEquals(52.52 to -13.405, LocationSpoofPolicy.parseCoordinates("52,52", "−13,405"))
        assertEquals(35.7 to 51.4, LocationSpoofPolicy.parseCoordinates("۳۵٫۷", "٥١.٤"))
        assertNull(LocationSpoofPolicy.parseCoordinates("", "13"))
        assertNull(LocationSpoofPolicy.parseCoordinates("abc", "13"))
        assertNull(LocationSpoofPolicy.parseCoordinates("95", "13"))
    }

    @Test
    fun wanderStaysNearTheCenter() {
        val random = Random(42)
        val centerLat = 52.52
        val centerLon = 13.405
        var lat = centerLat
        var lon = centerLon
        var moved = false
        repeat(1_000) {
            val next = LocationSpoofPolicy.wander(centerLat, centerLon, lat, lon, random)
            if (next.first != lat || next.second != lon) moved = true
            lat = next.first
            lon = next.second
            val north = (lat - centerLat) * 111_320.0
            val east = (lon - centerLon) * 111_320.0 * cos(centerLat * PI / 180.0)
            assertTrue(sqrt(north * north + east * east) <= 12.0 + 1e-6)
        }
        assertTrue(moved)
    }
}
