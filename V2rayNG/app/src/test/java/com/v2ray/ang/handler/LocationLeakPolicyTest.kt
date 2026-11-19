package com.v2ray.ang.handler

import com.v2ray.ang.handler.LocationLeakPolicy.Finding
import com.v2ray.ang.handler.LocationLeakPolicy.Input
import com.v2ray.ang.handler.LocationLeakPolicy.Reported
import com.v2ray.ang.service.LeakCheckPolicy.Verdict
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LocationLeakPolicyTest {

    private val berlin = Reported(52.5200, 13.4050, isMock = true)
    private val tehran = Reported(35.6892, 51.3890, isMock = false)

    private val spoofing = Input(
        permissionGranted = true,
        locationEnabled = true,
        reported = berlin,
        spoofEnabled = true,
        targetLatitude = 52.5200,
        targetLongitude = 13.4050,
    )

    @Test
    fun virtualPositionSeenByAppsIsDetectableNotLeaking() {
        val result = LocationLeakPolicy.evaluate(spoofing)
        assertEquals(Finding.VIRTUAL_DETECTABLE, result.finding)
        assertEquals(Verdict.WARNING, result.verdict)
        assertTrue(result.distanceToTargetM!! < 1.0)
    }

    @Test
    fun realPositionWhileSpoofingIsALeak() {
        val result = LocationLeakPolicy.evaluate(spoofing.copy(reported = tehran))
        assertEquals(Finding.VIRTUAL_NOT_APPLIED, result.finding)
        assertEquals(Verdict.LEAK, result.verdict)
        assertTrue(result.distanceToTargetM!! > 3_000_000)
    }

    @Test
    fun spoofOffShowsTheRealPosition() {
        val result = LocationLeakPolicy.evaluate(spoofing.copy(spoofEnabled = false, reported = tehran))
        assertEquals(Finding.REAL_POSITION_VISIBLE, result.finding)
        assertEquals(Verdict.WARNING, result.verdict)
        assertNull(result.distanceToTargetM)
    }

    @Test
    fun missingPermissionLocationOffAndNoFix() {
        assertEquals(Finding.NO_PERMISSION, LocationLeakPolicy.evaluate(spoofing.copy(permissionGranted = false)).finding)
        assertEquals(Verdict.SKIPPED, LocationLeakPolicy.evaluate(spoofing.copy(permissionGranted = false)).verdict)
        assertEquals(Finding.LOCATION_OFF, LocationLeakPolicy.evaluate(spoofing.copy(locationEnabled = false)).finding)
        assertEquals(Verdict.OK, LocationLeakPolicy.evaluate(spoofing.copy(locationEnabled = false)).verdict)
        assertEquals(Finding.NO_FIX, LocationLeakPolicy.evaluate(spoofing.copy(reported = null)).finding)
    }

    @Test
    fun spoofOnWithoutTargetCountsAsOff() {
        val noTarget = spoofing.copy(targetLatitude = null, targetLongitude = null, reported = tehran)
        assertEquals(Finding.REAL_POSITION_VISIBLE, LocationLeakPolicy.evaluate(noTarget).finding)
    }

    @Test
    fun radiusBoundary() {
        // About 0.009 degrees of latitude is 1 km.
        val near = spoofing.copy(reported = Reported(52.5200 + 0.0085, 13.4050, isMock = true))
        val far = spoofing.copy(reported = Reported(52.5200 + 0.0095, 13.4050, isMock = true))
        assertEquals(Finding.VIRTUAL_DETECTABLE, LocationLeakPolicy.evaluate(near).finding)
        assertEquals(Finding.VIRTUAL_NOT_APPLIED, LocationLeakPolicy.evaluate(far).finding)
    }

    @Test
    fun distancesAreBucketedForTheLog() {
        assertNull(LocationLeakPolicy.distanceBucket(null))
        assertEquals("<1 km", LocationLeakPolicy.distanceBucket(10.0))
        assertEquals("1-10 km", LocationLeakPolicy.distanceBucket(5_000.0))
        assertEquals("10-100 km", LocationLeakPolicy.distanceBucket(50_000.0))
        assertEquals(">100 km", LocationLeakPolicy.distanceBucket(3_000_000.0))
    }
}
