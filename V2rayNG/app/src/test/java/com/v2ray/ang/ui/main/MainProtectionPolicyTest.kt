package com.v2ray.ang.ui.main

import com.v2ray.ang.ui.main.MainProtectionPolicy.VirtualLocationTap
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MainProtectionPolicyTest {

    @Test
    fun readyNeedsATargetAndNoRefusal() {
        assertTrue(MainProtectionPolicy.virtualLocationReady(hasTarget = true, refused = false))
        assertFalse(MainProtectionPolicy.virtualLocationReady(hasTarget = false, refused = false))
        assertFalse(MainProtectionPolicy.virtualLocationReady(hasTarget = true, refused = true))
    }

    @Test
    fun tapTogglesWhenItCanWorkAndOpensTheScreenOtherwise() {
        assertEquals(VirtualLocationTap.TOGGLE, MainProtectionPolicy.virtualLocationTap(enabled = false, ready = true))
        assertEquals(VirtualLocationTap.TOGGLE, MainProtectionPolicy.virtualLocationTap(enabled = true, ready = false))
        assertEquals(VirtualLocationTap.OPEN_SCREEN, MainProtectionPolicy.virtualLocationTap(enabled = false, ready = false))
    }
}
