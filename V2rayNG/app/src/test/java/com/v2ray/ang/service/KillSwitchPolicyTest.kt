package com.v2ray.ang.service

import com.v2ray.ang.service.KillSwitchPolicy.TeardownReason
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class KillSwitchPolicyTest {

    @Test
    fun coreFailureKeepsTheTunnelOnlyWhenTheSettingIsOn() {
        assertTrue(KillSwitchPolicy.shouldKeepTunnel(TeardownReason.CORE_FAILURE, true))
        assertFalse(KillSwitchPolicy.shouldKeepTunnel(TeardownReason.CORE_FAILURE, false))
    }

    @Test
    fun deliberateTeardownAlwaysReleasesTheTunnel() {
        assertFalse(KillSwitchPolicy.shouldKeepTunnel(TeardownReason.USER_STOP, true))
        assertFalse(KillSwitchPolicy.shouldKeepTunnel(TeardownReason.REVOKED, true))
        assertFalse(KillSwitchPolicy.shouldKeepTunnel(TeardownReason.USER_STOP, false))
        assertFalse(KillSwitchPolicy.shouldKeepTunnel(TeardownReason.REVOKED, false))
    }

    @Test
    fun recoveryRunsOnlyForAnUnhealthyCoreOfALiveService() {
        assertTrue(KillSwitchPolicy.shouldRecover(coreHealthy = false, isTearingDown = false, killSwitchEnabled = true))
        assertFalse(KillSwitchPolicy.shouldRecover(coreHealthy = true, isTearingDown = false, killSwitchEnabled = true))
        assertFalse(KillSwitchPolicy.shouldRecover(coreHealthy = false, isTearingDown = true, killSwitchEnabled = true))
        assertFalse(KillSwitchPolicy.shouldRecover(coreHealthy = false, isTearingDown = false, killSwitchEnabled = false))
    }

    @Test
    fun retryDelayBacksOffExponentiallyAndIsCapped() {
        assertEquals(KillSwitchPolicy.MIN_RETRY_DELAY_MS, KillSwitchPolicy.retryDelayMillis(0))
        assertEquals(KillSwitchPolicy.MIN_RETRY_DELAY_MS, KillSwitchPolicy.retryDelayMillis(1))
        assertEquals(4_000L, KillSwitchPolicy.retryDelayMillis(2))
        assertEquals(8_000L, KillSwitchPolicy.retryDelayMillis(3))
        assertEquals(16_000L, KillSwitchPolicy.retryDelayMillis(4))
        assertEquals(32_000L, KillSwitchPolicy.retryDelayMillis(5))
        assertEquals(KillSwitchPolicy.MAX_RETRY_DELAY_MS, KillSwitchPolicy.retryDelayMillis(6))
        assertEquals(KillSwitchPolicy.MAX_RETRY_DELAY_MS, KillSwitchPolicy.retryDelayMillis(99))
    }
}
