package com.v2ray.ang.handler

import kotlin.math.PI
import kotlin.math.cos
import kotlin.random.Random

/**
 * Pure decisions of the virtual location feature.
 *
 * The VPN changes the address the internet sees; this feature changes the position the device
 * reports to apps. Neither hides every signal, so [consistency] lists what still points elsewhere
 * and the screen tells the user how to fix the parts that can be fixed.
 */
object LocationSpoofPolicy {

    /**
     * A ready-made virtual position.
     *
     * The visible name is the country's display name from the system locale, so presets need no
     * translated strings.
     *
     * @property country ISO 3166-1 alpha-2 country code.
     * @property timeZone IANA time zone of the position.
     * @property latitude Degrees north.
     * @property longitude Degrees east.
     */
    data class Preset(
        val country: String,
        val timeZone: String,
        val latitude: Double,
        val longitude: Double,
    )

    /** One position per country, in a large city, so the spot is not suspicious on its own. */
    val PRESETS = listOf(
        Preset("DE", "Europe/Berlin", 52.5200, 13.4050),
        Preset("NL", "Europe/Amsterdam", 52.3676, 4.9041),
        Preset("GB", "Europe/London", 51.5072, -0.1276),
        Preset("FR", "Europe/Paris", 48.8566, 2.3522),
        Preset("SE", "Europe/Stockholm", 59.3293, 18.0686),
        Preset("FI", "Europe/Helsinki", 60.1699, 24.9384),
        Preset("CH", "Europe/Zurich", 47.3769, 8.5417),
        Preset("TR", "Europe/Istanbul", 41.0082, 28.9784),
        Preset("RU", "Europe/Moscow", 55.7558, 37.6173),
        Preset("AM", "Asia/Yerevan", 40.1872, 44.5152),
        Preset("AE", "Asia/Dubai", 25.2048, 55.2708),
        Preset("IR", "Asia/Tehran", 35.6892, 51.3890),
        Preset("SG", "Asia/Singapore", 1.3521, 103.8198),
        Preset("HK", "Asia/Hong_Kong", 22.3193, 114.1694),
        Preset("JP", "Asia/Tokyo", 35.6762, 139.6503),
        Preset("US", "America/New_York", 40.7128, -74.0060),
        Preset("CA", "America/Toronto", 43.6532, -79.3832),
        Preset("AU", "Australia/Sydney", -33.8688, 151.2093),
    )

    /** Something that still points away from the virtual position. */
    enum class Finding {
        /** This app is not selected as the mock location app, so nothing can be reported. */
        NOT_MOCK_APP,

        /** The VPN is not connected, so the real address contradicts the position. */
        VPN_NOT_CONNECTED,

        /** The VPN exit is in another country than the virtual position. */
        VPN_COUNTRY_MISMATCH,

        /** The device clock uses a time zone with another offset than the virtual position. */
        TIME_ZONE_MISMATCH,

        /** The SIM card belongs to another country. */
        SIM_COUNTRY_MISMATCH,

        /** The mobile network the phone is registered on is in another country. */
        NETWORK_COUNTRY_MISMATCH,

        /** Wi-Fi scanning stays on with Wi-Fi off, letting location services see nearby networks. */
        WIFI_SCANNING_ON,
    }

    /**
     * Signals that can contradict the virtual position.
     *
     * @property mockAppSelected False when the system refused this app as a test provider.
     * @property vpnConnected True while this app's VPN is up.
     * @property targetCountry Country of the virtual position, null for unknown custom spots.
     * @property vpnExitCountry Country of the VPN exit address, null when not measured.
     * @property targetUtcOffsetMs UTC offset of the virtual position now, null when unknown.
     * @property deviceUtcOffsetMs UTC offset of the device time zone now.
     * @property simCountry Country of the SIM card, null or empty without one.
     * @property networkCountry Country of the registered mobile network, null or empty without one.
     * @property wifiScanAlwaysOn The "Wi-Fi scanning" system setting.
     */
    data class Signals(
        val mockAppSelected: Boolean,
        val vpnConnected: Boolean,
        val targetCountry: String?,
        val vpnExitCountry: String?,
        val targetUtcOffsetMs: Int?,
        val deviceUtcOffsetMs: Int,
        val simCountry: String?,
        val networkCountry: String?,
        val wifiScanAlwaysOn: Boolean,
    )

    /**
     * Lists what still points away from the virtual position, most important first.
     *
     * @param signals What the device shows.
     * @return The findings, empty when nothing known contradicts the position.
     */
    fun consistency(signals: Signals): List<Finding> {
        val result = mutableListOf<Finding>()
        if (!signals.mockAppSelected) result += Finding.NOT_MOCK_APP
        if (!signals.vpnConnected) result += Finding.VPN_NOT_CONNECTED

        val target = signals.targetCountry?.uppercase()
        if (target != null) {
            val exit = signals.vpnExitCountry?.uppercase()
            if (signals.vpnConnected && exit != null && exit != target) result += Finding.VPN_COUNTRY_MISMATCH
            if (differs(signals.simCountry, target)) result += Finding.SIM_COUNTRY_MISMATCH
            if (differs(signals.networkCountry, target)) result += Finding.NETWORK_COUNTRY_MISMATCH
        }
        val offset = signals.targetUtcOffsetMs
        if (offset != null && offset != signals.deviceUtcOffsetMs) result += Finding.TIME_ZONE_MISMATCH
        if (signals.wifiScanAlwaysOn) result += Finding.WIFI_SCANNING_ON
        return result
    }

    /**
     * Finds the preset for a country, used to follow the VPN exit country.
     *
     * @param country ISO country code, any case.
     * @return The preset, or null when none exists for that country.
     */
    fun presetFor(country: String?): Preset? {
        if (country.isNullOrBlank()) return null
        return PRESETS.firstOrNull { it.country.equals(country, ignoreCase = true) }
    }

    /**
     * Checks a custom position.
     *
     * @return True for a latitude in [-90, 90] and a longitude in [-180, 180].
     */
    fun isValidCoordinate(latitude: Double?, longitude: Double?): Boolean {
        if (latitude == null || longitude == null) return false
        if (latitude.isNaN() || longitude.isNaN()) return false
        return latitude in -90.0..90.0 && longitude in -180.0..180.0
    }

    /**
     * Reads a custom position typed by the user.
     *
     * Accepts a dot or a comma as decimal separator and Persian or Arabic digits, as typed on
     * those keyboards.
     *
     * @param latitudeText The typed latitude.
     * @param longitudeText The typed longitude.
     * @return The position, or null when either part is not a valid coordinate.
     */
    fun parseCoordinates(latitudeText: String, longitudeText: String): Pair<Double, Double>? {
        val lat = normalizeNumber(latitudeText).toDoubleOrNull()
        val lon = normalizeNumber(longitudeText).toDoubleOrNull()
        return if (isValidCoordinate(lat, lon)) lat!! to lon!! else null
    }

    private fun normalizeNumber(text: String): String {
        val builder = StringBuilder()
        for (ch in text.trim()) {
            builder.append(
                when (ch) {
                    in '۰'..'۹' -> '0' + (ch - '۰')
                    in '٠'..'٩' -> '0' + (ch - '٠')
                    ',', '٫' -> '.'
                    '−' -> '-'
                    else -> ch
                }
            )
        }
        return builder.toString()
    }

    /**
     * Moves the reported position a few meters around the chosen spot.
     *
     * A position that never changes by a millimeter is a hint by itself; real GPS wanders. The
     * walk stays within [maxRadiusMeters] of the center so the position does not drift away.
     *
     * @param centerLat The chosen latitude.
     * @param centerLon The chosen longitude.
     * @param currentLat The last reported latitude.
     * @param currentLon The last reported longitude.
     * @param random The random source, injectable for tests.
     * @param stepMeters The largest move per update.
     * @param maxRadiusMeters The largest distance from the center.
     * @return The next latitude and longitude.
     */
    fun wander(
        centerLat: Double,
        centerLon: Double,
        currentLat: Double,
        currentLon: Double,
        random: Random,
        stepMeters: Double = 1.5,
        maxRadiusMeters: Double = 12.0,
    ): Pair<Double, Double> {
        val metersPerDegLat = 111_320.0
        val metersPerDegLon = (metersPerDegLat * cos(centerLat * PI / 180.0)).coerceAtLeast(1.0)

        var north = (currentLat - centerLat) * metersPerDegLat + (random.nextDouble() * 2 - 1) * stepMeters
        var east = (currentLon - centerLon) * metersPerDegLon + (random.nextDouble() * 2 - 1) * stepMeters
        val distance = kotlin.math.sqrt(north * north + east * east)
        if (distance > maxRadiusMeters) {
            val scale = maxRadiusMeters / distance
            north *= scale
            east *= scale
        }
        return (centerLat + north / metersPerDegLat) to (centerLon + east / metersPerDegLon)
    }

    private fun differs(observed: String?, target: String): Boolean {
        if (observed.isNullOrBlank()) return false
        return !observed.equals(target, ignoreCase = true)
    }
}
