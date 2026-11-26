package com.v2ray.ang.ui.main

import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.dto.entities.ServersCache
import com.v2ray.ang.enums.EConfigType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MainSubscriptionRoutineTest {

    private fun server(guid: String, delay: Long) =
        ServersCache(guid, ProfileItem.create(EConfigType.VMESS).apply { remarks = guid }, delay)

    @Test
    fun pickBest_takesTheLowestMeasuredDelay() {
        val best = MainSubscriptionRoutine.pickBest(listOf(server("a", 420), server("b", 95), server("c", 300)))
        assertEquals("b", best?.guid)
    }

    @Test
    fun pickBest_neverTakesFailedOrUnmeasuredServers() {
        // -1 is a failed test, 0 one that was not measured.
        assertEquals("c", MainSubscriptionRoutine.pickBest(listOf(server("a", -1), server("b", 0), server("c", 800)))?.guid)
        assertNull(MainSubscriptionRoutine.pickBest(listOf(server("a", -1), server("b", 0))))
    }

    @Test
    fun pickBest_emptyListHasNoBest() {
        assertNull(MainSubscriptionRoutine.pickBest(emptyList()))
    }

    @Test
    fun youtubeReachable_onlyOnGenerate204Success() {
        assertTrue(MainSubscriptionRoutine.isYoutubeReachable(204))
        assertTrue(MainSubscriptionRoutine.isYoutubeReachable(200))
        // A block page redirects or answers 403; no answer at all is null.
        assertFalse(MainSubscriptionRoutine.isYoutubeReachable(302))
        assertFalse(MainSubscriptionRoutine.isYoutubeReachable(403))
        assertFalse(MainSubscriptionRoutine.isYoutubeReachable(null))
    }

    @Test
    fun initialState_hasNoRoutine() {
        val state = MainUiState()
        assertNull(state.routine)
        assertFalse(state.routineConnectRequested)
    }
}
