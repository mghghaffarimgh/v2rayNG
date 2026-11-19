package com.v2ray.ang.service

import com.v2ray.ang.service.LeakRiskPolicy.Inputs
import com.v2ray.ang.service.LeakRiskPolicy.LockdownState
import com.v2ray.ang.service.LeakRiskPolicy.Risk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LeakRiskPolicyTest {

    private val safe = Inputs(
        vpnMode = true,
        killSwitchEnabled = true,
        lockdown = LockdownState.ON,
        perAppEnabled = false,
        perAppHasApps = false,
        perAppBypassMode = false,
        bypassLan = false,
    )

    @Test
    fun fullyProtectedSetupHasNoRisk() {
        assertEquals(emptyList<Risk>(), LeakRiskPolicy.risks(safe))
    }

    @Test
    fun nonVpnModesReportNothing() {
        val inputs = safe.copy(vpnMode = false, killSwitchEnabled = false, lockdown = LockdownState.OFF, bypassLan = true)
        assertEquals(emptyList<Risk>(), LeakRiskPolicy.risks(inputs))
    }

    @Test
    fun killSwitchOffIsReported() {
        assertEquals(listOf(Risk.KILL_SWITCH_OFF), LeakRiskPolicy.risks(safe.copy(killSwitchEnabled = false)))
    }

    @Test
    fun lockdownOffAndUnknownAreBothReported() {
        assertEquals(listOf(Risk.SYSTEM_LOCKDOWN_OFF), LeakRiskPolicy.risks(safe.copy(lockdown = LockdownState.OFF)))
        assertEquals(listOf(Risk.SYSTEM_LOCKDOWN_OFF), LeakRiskPolicy.risks(safe.copy(lockdown = LockdownState.UNKNOWN)))
    }

    @Test
    fun perAppModesNeedSelectedApps() {
        val bypass = safe.copy(perAppEnabled = true, perAppHasApps = true, perAppBypassMode = true)
        assertEquals(listOf(Risk.PER_APP_BYPASS), LeakRiskPolicy.risks(bypass))
        assertEquals(listOf(Risk.PER_APP_ALLOW_LIST), LeakRiskPolicy.risks(bypass.copy(perAppBypassMode = false)))
        assertEquals(emptyList<Risk>(), LeakRiskPolicy.risks(bypass.copy(perAppHasApps = false)))
        assertEquals(emptyList<Risk>(), LeakRiskPolicy.risks(bypass.copy(perAppEnabled = false)))
    }

    @Test
    fun risksAreOrderedMostSevereFirst() {
        val inputs = Inputs(
            vpnMode = true,
            killSwitchEnabled = false,
            lockdown = LockdownState.UNKNOWN,
            perAppEnabled = true,
            perAppHasApps = true,
            perAppBypassMode = true,
            bypassLan = true,
        )
        assertEquals(
            listOf(Risk.KILL_SWITCH_OFF, Risk.SYSTEM_LOCKDOWN_OFF, Risk.PER_APP_BYPASS, Risk.BYPASS_LAN),
            LeakRiskPolicy.risks(inputs),
        )
    }

    @Test
    fun warningFollowsTheSettingAndNeedsARisk() {
        assertTrue(LeakRiskPolicy.shouldWarn(listOf(Risk.BYPASS_LAN), warningsEnabled = true))
        assertFalse(LeakRiskPolicy.shouldWarn(listOf(Risk.BYPASS_LAN), warningsEnabled = false))
        assertFalse(LeakRiskPolicy.shouldWarn(emptyList(), warningsEnabled = true))
    }

    @Test
    fun lockdownStateNeedsBothPlatformFlags() {
        assertEquals(LockdownState.ON, LeakRiskPolicy.lockdownState(supported = true, alwaysOn = true, lockdown = true))
        assertEquals(LockdownState.OFF, LeakRiskPolicy.lockdownState(supported = true, alwaysOn = true, lockdown = false))
        assertEquals(LockdownState.OFF, LeakRiskPolicy.lockdownState(supported = true, alwaysOn = false, lockdown = false))
        assertEquals(LockdownState.UNKNOWN, LeakRiskPolicy.lockdownState(supported = false, alwaysOn = true, lockdown = true))
    }
}
