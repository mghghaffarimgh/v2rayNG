package com.v2ray.ang.service

/**
 * Pure decisions about which paths can carry traffic outside the VPN tunnel.
 *
 * Each [Risk] names one known way for traffic to leave without the tunnel. The service uses the
 * list to warn the user when the tunnel comes up, and the leak guide screen shows the same list,
 * so both always agree. Kept free of Android types so it is covered by JVM tests.
 */
object LeakRiskPolicy {

    /** One known path for traffic to leave the device without the tunnel. */
    enum class Risk {
        /** The kill switch setting is off, so a core failure releases the tunnel. */
        KILL_SWITCH_OFF,

        /**
         * The system does not block traffic outside the VPN, so a reboot, a killed process or
         * another VPN app leaves the network open until this app is running again.
         */
        SYSTEM_LOCKDOWN_OFF,

        /** Per-app bypass mode: the selected apps always use the physical network. */
        PER_APP_BYPASS,

        /** Per-app proxy mode: every app that is not selected uses the physical network. */
        PER_APP_ALLOW_LIST,

        /** Private and LAN address ranges are routed outside the tunnel. */
        BYPASS_LAN,
    }

    /** Whether the system lockdown ("Block connections without VPN") is known to be on. */
    enum class LockdownState {
        ON,
        OFF,

        /** Not read yet, or the device runs an Android version that does not report it. */
        UNKNOWN,
    }

    /**
     * The settings the risks depend on.
     *
     * @property vpnMode True when the app runs in VPN mode; the other modes have no tunnel.
     * @property killSwitchEnabled The kill switch setting.
     * @property lockdown What the system last reported about always-on lockdown.
     * @property perAppEnabled The per-app proxy setting.
     * @property perAppHasApps True when at least one app is selected for per-app proxy.
     * @property perAppBypassMode True when the selected apps bypass the tunnel.
     * @property bypassLan True when LAN ranges are routed outside the tunnel.
     */
    data class Inputs(
        val vpnMode: Boolean,
        val killSwitchEnabled: Boolean,
        val lockdown: LockdownState,
        val perAppEnabled: Boolean,
        val perAppHasApps: Boolean,
        val perAppBypassMode: Boolean,
        val bypassLan: Boolean,
    )

    /**
     * Lists the risks that apply to [inputs], most severe first.
     *
     * Proxy-only and root modes have no tunnel to leak from, so they report nothing here; the
     * guide explains that those modes give no leak protection at all.
     *
     * An unknown lockdown state is reported as off: claiming a protection that could not be
     * confirmed is worse than one unneeded warning.
     *
     * @param inputs The current settings.
     * @return The risks, empty when no known path applies.
     */
    fun risks(inputs: Inputs): List<Risk> {
        if (!inputs.vpnMode) return emptyList()

        val result = mutableListOf<Risk>()
        if (!inputs.killSwitchEnabled) result += Risk.KILL_SWITCH_OFF
        if (inputs.lockdown != LockdownState.ON) result += Risk.SYSTEM_LOCKDOWN_OFF
        if (inputs.perAppEnabled && inputs.perAppHasApps) {
            result += if (inputs.perAppBypassMode) Risk.PER_APP_BYPASS else Risk.PER_APP_ALLOW_LIST
        }
        if (inputs.bypassLan) result += Risk.BYPASS_LAN
        return result
    }

    /**
     * Decides whether the start-up warning is posted for [risks].
     *
     * @param risks The risks that apply.
     * @param warningsEnabled The leak warning setting.
     * @return True when the user must be told.
     */
    fun shouldWarn(risks: List<Risk>, warningsEnabled: Boolean): Boolean {
        return warningsEnabled && risks.isNotEmpty()
    }

    /**
     * Maps the platform's always-on and lockdown flags to a [LockdownState].
     *
     * @param supported False when the Android version does not report the flags.
     * @param alwaysOn The value of `VpnService.isAlwaysOn`.
     * @param lockdown The value of `VpnService.isLockdownEnabled`.
     * @return The state to show and store.
     */
    fun lockdownState(supported: Boolean, alwaysOn: Boolean, lockdown: Boolean): LockdownState {
        if (!supported) return LockdownState.UNKNOWN
        return if (alwaysOn && lockdown) LockdownState.ON else LockdownState.OFF
    }
}
