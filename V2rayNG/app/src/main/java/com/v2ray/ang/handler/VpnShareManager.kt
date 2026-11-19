package com.v2ray.ang.handler

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.v2ray.ang.AppConfig
import com.v2ray.ang.core.LauncherManager
import com.v2ray.ang.service.VpnShareService
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Owns VPN sharing: the share hotspot's live status, and the proxy sharing setting it depends on.
 *
 * The status lives in memory because [VpnShareService] and the share screen run in the same
 * process and the hotspot itself dies with that process; persisting it would only describe a
 * hotspot that no longer exists.
 */
object VpnShareManager {

    /**
     * The share hotspot as the service last saw it.
     *
     * @property running True while the hotspot is up.
     * @property starting True between the start request and the platform's answer.
     * @property ssid The network name, null until it is up.
     * @property passphrase The network password, null for an open network or until it is up.
     * @property interfaceName The hotspot's interface, null when it could not be identified.
     * @property failure Why the last start failed, null when it did not.
     */
    data class Status(
        val running: Boolean = false,
        val starting: Boolean = false,
        val ssid: String? = null,
        val passphrase: String? = null,
        val interfaceName: String? = null,
        val failure: VpnSharePolicy.HotspotFailure? = null,
    )

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()

    /** Called by [VpnShareService] whenever the hotspot changes. */
    internal fun publish(status: Status) {
        _status.value = status
        // The leak checker runs in other processes and must not count this hotspot as tethering.
        MmkvManager.encodeSettings(AppConfig.PREF_SHARE_INTERFACE, if (status.running) status.interfaceName.orEmpty() else "")
    }

    /** The interface of the running share hotspot, readable from any process; null when off. */
    fun getActiveShareInterface(): String? =
        MmkvManager.decodeSettingsString(AppConfig.PREF_SHARE_INTERFACE)?.takeIf { it.isNotBlank() }

    /**
     * Starts or stops the share hotspot, turning the proxy's LAN access on with it.
     *
     * The core listens on the LAN only when "Allow connections from the LAN" is on, so sharing
     * turns it on and restores the user's previous choice when sharing stops. A running core is
     * restarted to apply it.
     *
     * @param context Any context.
     * @param enabled The new state.
     */
    fun setHotspotEnabled(context: Context, enabled: Boolean) {
        val app = context.applicationContext
        val intent = Intent(app, VpnShareService::class.java)
        try {
            if (enabled) {
                if (!MmkvManager.decodeSettingsBool(AppConfig.PREF_SHARE_OWNS_PROXY_SHARING, false)) {
                    val wasOn = MmkvManager.decodeSettingsBool(AppConfig.PREF_PROXY_SHARING, false)
                    if (!wasOn) {
                        MmkvManager.encodeSettings(AppConfig.PREF_PROXY_SHARING, true)
                        MmkvManager.encodeSettings(AppConfig.PREF_SHARE_OWNS_PROXY_SHARING, true)
                        restartCoreIfRunning(app)
                    }
                }
                _status.value = Status(starting = true)
                ContextCompat.startForegroundService(app, intent)
            } else {
                app.stopService(intent)
                if (MmkvManager.decodeSettingsBool(AppConfig.PREF_SHARE_OWNS_PROXY_SHARING, false)) {
                    MmkvManager.encodeSettings(AppConfig.PREF_PROXY_SHARING, false)
                    MmkvManager.encodeSettings(AppConfig.PREF_SHARE_OWNS_PROXY_SHARING, false)
                    restartCoreIfRunning(app)
                }
            }
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "VpnShare: could not ${if (enabled) "start" else "stop"} sharing", e)
            publish(Status(failure = VpnSharePolicy.HotspotFailure.GENERIC))
        }
    }

    /** True when "Allow connections from the LAN" is on, so other devices can reach the proxy. */
    fun isProxySharingOn(): Boolean = MmkvManager.decodeSettingsBool(AppConfig.PREF_PROXY_SHARING, false)

    /** True when root LAN sharing routes tethered devices into the tunnel. */
    fun isRootSharingOn(): Boolean = MmkvManager.decodeSettingsBool(AppConfig.PREF_ROOT_LAN_SHARING, false)

    /**
     * Turns root LAN sharing on or off; it applies from the next VPN start.
     *
     * @param enabled The new state.
     */
    fun setRootSharing(context: Context, enabled: Boolean) {
        MmkvManager.encodeSettings(AppConfig.PREF_ROOT_LAN_SHARING, enabled)
        restartCoreIfRunning(context.applicationContext)
    }

    /** The port other devices connect to: SOCKS5 and HTTP on Xray, SOCKS5 otherwise. */
    fun getProxyPort(): Int = SettingsManager.getSocksPort()

    /** True when the proxy asks for a user name and password. */
    fun proxyHasPassword(): Boolean = SettingsManager.getSocksUsername() != null && SettingsManager.getSocksPassword() != null

    /**
     * Lists the interfaces that are up with their IPv4 addresses.
     *
     * @return The interfaces; empty when listing failed.
     */
    fun listInterfaces(): List<VpnSharePolicy.Interface> {
        return try {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .filter { it.isUp && !it.isLoopback }
                .map { iface ->
                    VpnSharePolicy.Interface(
                        iface.name,
                        iface.inetAddresses.toList().filterIsInstance<Inet4Address>().mapNotNull { it.hostAddress },
                    )
                }
        } catch (e: Exception) {
            LogUtil.w(AppConfig.TAG, "VpnShare: interface listing failed", e)
            emptyList()
        }
    }

    private fun restartCoreIfRunning(context: Context) {
        // The UI process cannot see the daemon's core; the restart command is acknowledged only
        // by a running daemon and is a no-op otherwise.
        try {
            LauncherManager.restartService(context)
        } catch (e: Exception) {
            LogUtil.w(AppConfig.TAG, "VpnShare: core restart request failed", e)
        }
    }
}
