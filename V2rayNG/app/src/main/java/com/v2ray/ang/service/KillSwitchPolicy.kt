package com.v2ray.ang.service

/**
 * Pure kill switch decisions owned by the VPN run mode.
 *
 * The VPN interface is the only thing that keeps traffic inside the tunnel: once it is closed the
 * system routes every packet over the physical network again. These helpers decide when the
 * interface must outlive the native core, so the decisions stay testable on the JVM while
 * [CoreVpnService] keeps the Android resources.
 */
object KillSwitchPolicy {

    /** Why the tunnel is being torn down. */
    enum class TeardownReason {
        /** The user stopped the service, or asked for a restart. */
        USER_STOP,

        /** Android revoked the VPN permission, usually because another VPN app took over. */
        REVOKED,

        /** The native core failed to start, or stopped on its own while the service kept running. */
        CORE_FAILURE,
    }

    /** How often the watchdog samples core health while the tunnel is up. */
    const val HEALTH_CHECK_INTERVAL_MS = 3_000L

    /** Delay before the first recovery attempt. */
    const val MIN_RETRY_DELAY_MS = 2_000L

    /** Upper bound for the exponential backoff between recovery attempts. */
    const val MAX_RETRY_DELAY_MS = 60_000L

    /**
     * Decides whether the VPN interface must stay open after [reason].
     *
     * Only a core failure keeps it open: a user stop and a revoke are deliberate hand-backs of the
     * tunnel, and blocking traffic after them would leave the device offline with no way out.
     *
     * @param reason Why the tunnel is being torn down.
     * @param killSwitchEnabled The current value of the kill switch setting.
     * @return True when the interface must be kept open and traffic blocked.
     */
    fun shouldKeepTunnel(reason: TeardownReason, killSwitchEnabled: Boolean): Boolean {
        return killSwitchEnabled && reason == TeardownReason.CORE_FAILURE
    }

    /**
     * Decides whether the watchdog may try to bring the core back.
     *
     * @param coreHealthy True when the core is running or reloading after a network handover.
     * @param isTearingDown True once the service started releasing its resources.
     * @param killSwitchEnabled The current value of the kill switch setting.
     * @return True when a recovery attempt is allowed.
     */
    fun shouldRecover(coreHealthy: Boolean, isTearingDown: Boolean, killSwitchEnabled: Boolean): Boolean {
        return killSwitchEnabled && !isTearingDown && !coreHealthy
    }

    /**
     * Exponential backoff between recovery attempts, capped at [MAX_RETRY_DELAY_MS].
     *
     * @param attempt The attempt number, counted from 1.
     * @return The delay in milliseconds before that attempt.
     */
    fun retryDelayMillis(attempt: Int): Long {
        if (attempt <= 1) return MIN_RETRY_DELAY_MS

        var delay = MIN_RETRY_DELAY_MS
        repeat(attempt - 1) {
            if (delay >= MAX_RETRY_DELAY_MS) return MAX_RETRY_DELAY_MS
            delay *= 2
        }
        return minOf(delay, MAX_RETRY_DELAY_MS)
    }
}
