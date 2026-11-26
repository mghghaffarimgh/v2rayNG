package com.v2ray.ang.ui.main

import com.v2ray.ang.dto.entities.ServersCache

/** The steps of the subscription routine started by double-tapping a group tab. */
enum class RoutineStep { Update, Test, Connect, Verify }

/** Why the subscription routine ended without a working connection. */
enum class RoutineFailure { NoServers, NoReachableServer, ConnectFailed, YoutubeFailed }

/**
 * Progress of the subscription routine, or its verdict once it ends.
 *
 * Kept in [MainUiState] so the step survives recomposition and the verdict waits on screen until
 * the user dismisses it.
 */
sealed interface RoutineState {
    data class Running(val groupId: String, val step: RoutineStep) : RoutineState

    /**
     * @property serverName The server the routine connected to; null when it got no further.
     * @property failure Null when YouTube opened through the VPN.
     */
    data class Finished(val serverName: String?, val failure: RoutineFailure?) : RoutineState
}

/** The decisions of the routine that do not need Android, so they are tested on the JVM. */
object MainSubscriptionRoutine {

    /** The lowest measured delay wins; servers that failed or were not measured never do. */
    fun pickBest(servers: List<ServersCache>): ServersCache? =
        servers.filter { it.testDelayMillis > 0L }.minByOrNull { it.testDelayMillis }

    /** generate_204 answers 204 through a working VPN; a block page answers anything else. */
    fun isYoutubeReachable(statusCode: Int?): Boolean = statusCode == 204 || statusCode == 200
}
