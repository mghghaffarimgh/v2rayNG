package com.v2ray.ang.handler

import com.v2ray.ang.service.LeakCheckPolicy.Verdict
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pure decisions of the location leak test.
 *
 * The test reads the position the system gives apps right now, the same way any app with
 * location permission would, and compares it with what the user wants apps to see: the virtual
 * position when it is on, nothing in particular when it is off.
 */
object LocationLeakPolicy {

    /** How far from the virtual position a reported position may be and still count as it. */
    const val VIRTUAL_MATCH_RADIUS_M = 1_000.0

    /** One observation of the location test. */
    enum class Finding(val severity: Verdict) {
        /** This app may not read the location, so it cannot see what other apps see. */
        NO_PERMISSION(Verdict.SKIPPED),

        /** Location services are off: apps get no position at all. */
        LOCATION_OFF(Verdict.OK),

        /** Location is on but no position arrived in time. */
        NO_FIX(Verdict.SKIPPED),

        /** The virtual location is off, so apps with permission see the real position. */
        REAL_POSITION_VISIBLE(Verdict.WARNING),

        /** The virtual location is on, but apps get a position far from it: the real one leaks. */
        VIRTUAL_NOT_APPLIED(Verdict.LEAK),

        /** Apps get the virtual position, marked as simulated, which apps can check. */
        VIRTUAL_DETECTABLE(Verdict.WARNING),
    }

    /**
     * A position as apps receive it.
     *
     * @property latitude Degrees north.
     * @property longitude Degrees east.
     * @property isMock True when Android marks it as coming from a test provider; shown to the
     *   user, since apps can read the same flag.
     */
    data class Reported(val latitude: Double, val longitude: Double, val isMock: Boolean)

    /**
     * What the test saw.
     *
     * @property permissionGranted True when this app may read the location.
     * @property locationEnabled True when location services are on.
     * @property reported The position apps get, null when none arrived.
     * @property spoofEnabled True when the virtual location is switched on.
     * @property targetLatitude The virtual position's latitude, null without one.
     * @property targetLongitude The virtual position's longitude, null without one.
     */
    data class Input(
        val permissionGranted: Boolean,
        val locationEnabled: Boolean,
        val reported: Reported?,
        val spoofEnabled: Boolean,
        val targetLatitude: Double?,
        val targetLongitude: Double?,
    )

    /**
     * The result.
     *
     * @property verdict The overall verdict.
     * @property finding What was observed.
     * @property distanceToTargetM Distance between the reported and the virtual position, when both exist.
     */
    data class Result(
        val verdict: Verdict,
        val finding: Finding,
        val distanceToTargetM: Double?,
    )

    /**
     * Turns the observation into a result.
     *
     * @param input What the test saw.
     * @return The result.
     */
    fun evaluate(input: Input): Result {
        if (!input.permissionGranted) return result(Finding.NO_PERMISSION, null)
        if (!input.locationEnabled) return result(Finding.LOCATION_OFF, null)
        val reported = input.reported ?: return result(Finding.NO_FIX, null)

        val lat = input.targetLatitude
        val lon = input.targetLongitude
        if (!input.spoofEnabled || lat == null || lon == null) return result(Finding.REAL_POSITION_VISIBLE, null)

        val distance = distanceMeters(reported.latitude, reported.longitude, lat, lon)
        // Test providers always mark their positions as simulated, so a matching position is the
        // virtual one and detectable; it is never reported as invisible.
        val finding = if (distance > VIRTUAL_MATCH_RADIUS_M) Finding.VIRTUAL_NOT_APPLIED else Finding.VIRTUAL_DETECTABLE
        return result(finding, distance)
    }

    /**
     * Great-circle distance between two positions.
     *
     * @return The distance in meters.
     */
    fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val earthRadius = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return 2 * earthRadius * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }

    /**
     * Rounds a distance for logging, so the log never holds a precise position.
     *
     * @return "<1 km", "1-10 km", "10-100 km" or ">100 km"; null for null.
     */
    fun distanceBucket(meters: Double?): String? = when {
        meters == null -> null
        meters < 1_000 -> "<1 km"
        meters < 10_000 -> "1-10 km"
        meters < 100_000 -> "10-100 km"
        else -> ">100 km"
    }

    private fun result(finding: Finding, distance: Double?) = Result(finding.severity, finding, distance)
}
