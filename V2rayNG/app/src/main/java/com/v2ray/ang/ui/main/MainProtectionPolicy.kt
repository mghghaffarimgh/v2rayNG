package com.v2ray.ang.ui.main

/**
 * Pure decisions for the protection controls on the main screen.
 */
object MainProtectionPolicy {

    /** What a tap on the virtual location control does. */
    enum class VirtualLocationTap {
        /** Switch it on or off right here. */
        TOGGLE,

        /** Open its screen, because switching it on here could not work. */
        OPEN_SCREEN,
    }

    /**
     * Tells whether the virtual location can be switched on from the main screen.
     *
     * @param hasTarget True when a position is chosen.
     * @param refused True when Android refused the last run because this app is not the mock
     *   location app.
     * @return True when switching it on can work without visiting its screen first.
     */
    fun virtualLocationReady(hasTarget: Boolean, refused: Boolean): Boolean = hasTarget && !refused

    /**
     * Decides what a tap on the virtual location control does.
     *
     * Turning it off always works in place. Turning it on without a position, or after Android
     * refused it, would only fail, so the tap opens the screen where both are fixed.
     *
     * @param enabled True while the virtual location is on.
     * @param ready The value of [virtualLocationReady].
     * @return The action.
     */
    fun virtualLocationTap(enabled: Boolean, ready: Boolean): VirtualLocationTap =
        if (enabled || ready) VirtualLocationTap.TOGGLE else VirtualLocationTap.OPEN_SCREEN
}
