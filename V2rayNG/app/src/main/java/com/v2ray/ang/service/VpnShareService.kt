package com.v2ray.ang.service

import android.Manifest
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.enums.NotificationChannelType
import com.v2ray.ang.handler.AppLocaleManager
import com.v2ray.ang.handler.VpnShareManager
import com.v2ray.ang.handler.VpnSharePolicy
import com.v2ray.ang.helper.NotificationHelper
import com.v2ray.ang.util.LogUtil

/**
 * Holds the local-only hotspot other devices join to use the VPN through the proxy.
 *
 * Android keeps a local-only hotspot up only while the app holds its reservation, so this
 * foreground service owns it; stopping the service closes the reservation and the hotspot.
 */
class VpnShareService : Service() {

    private var reservation: WifiManager.LocalOnlyHotspotReservation? = null
    private var interfacesBefore: List<VpnSharePolicy.Interface> = emptyList()
    private var requested = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun attachBaseContext(newBase: Context?) {
        super.attachBaseContext(newBase?.let(AppLocaleManager::localizedContext))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        NotificationHelper.startForeground(
            this,
            NotificationChannelType.VPN_SHARE,
            getString(R.string.title_vpn_share),
            getString(R.string.vpn_share_notification_starting),
        )
        if (requested) return START_NOT_STICKY
        requested = true

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            fail(VpnSharePolicy.HotspotFailure.UNSUPPORTED, null)
            return START_NOT_STICKY
        }
        if (!hasHotspotPermission()) {
            fail(VpnSharePolicy.HotspotFailure.PERMISSION_MISSING, null)
            return START_NOT_STICKY
        }
        startHotspot()
        // The hotspot dies with the process and cannot be restored without the user, so no restart.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        try {
            // A reservation exists only on Android 8+, where the local-only hotspot API exists.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) reservation?.close()
        } catch (e: Exception) {
            LogUtil.w(AppConfig.TAG, "VpnShare: closing the hotspot failed", e)
        }
        reservation = null
        VpnShareManager.publish(VpnShareManager.Status())
        NotificationHelper.stopForeground(this)
        super.onDestroy()
    }

    private fun hasHotspotPermission(): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.NEARBY_WIFI_DEVICES
        } else {
            Manifest.permission.ACCESS_FINE_LOCATION
        }
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun startHotspot() {
        val wifi = applicationContext.getSystemService(WifiManager::class.java)
        if (wifi == null) {
            fail(VpnSharePolicy.HotspotFailure.UNSUPPORTED, null)
            return
        }
        interfacesBefore = VpnShareManager.listInterfaces()
        try {
            wifi.startLocalOnlyHotspot(object : WifiManager.LocalOnlyHotspotCallback() {
                override fun onStarted(started: WifiManager.LocalOnlyHotspotReservation) {
                    reservation = started
                    val (ssid, passphrase) = readCredentials(started)
                    val shareInterface = VpnSharePolicy.newInterfaces(interfacesBefore, VpnShareManager.listInterfaces()).firstOrNull()
                    LogUtil.i(AppConfig.TAG, "VpnShare: hotspot up on ${shareInterface ?: "unknown interface"}")
                    VpnShareManager.publish(
                        VpnShareManager.Status(running = true, ssid = ssid, passphrase = passphrase, interfaceName = shareInterface)
                    )
                    NotificationHelper.updateNotification(
                        NotificationChannelType.VPN_SHARE, this@VpnShareService,
                        getString(R.string.title_vpn_share),
                        getString(R.string.vpn_share_notification_running, ssid ?: "?"),
                    )
                }

                override fun onStopped() {
                    LogUtil.w(AppConfig.TAG, "VpnShare: the system stopped the hotspot")
                    stopSelf()
                }

                override fun onFailed(reason: Int) {
                    fail(VpnSharePolicy.hotspotFailure(reason), null)
                }
            }, Handler(Looper.getMainLooper()))
        } catch (e: SecurityException) {
            fail(VpnSharePolicy.HotspotFailure.PERMISSION_MISSING, e)
        } catch (e: IllegalStateException) {
            // Thrown when this app already holds a local-only hotspot request.
            fail(VpnSharePolicy.HotspotFailure.GENERIC, e)
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun readCredentials(reservation: WifiManager.LocalOnlyHotspotReservation): Pair<String?, String?> {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                readSoftApCredentials(reservation)
            } else {
                readLegacyCredentials(reservation)
            }
        } catch (e: Exception) {
            LogUtil.w(AppConfig.TAG, "VpnShare: could not read the hotspot credentials", e)
            null to null
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun readSoftApCredentials(reservation: WifiManager.LocalOnlyHotspotReservation): Pair<String?, String?> {
        val config = reservation.softApConfiguration
        val ssid = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            config.wifiSsid?.toString()?.removeSurrounding("\"")
        } else {
            readSoftApSsidLegacy(config)
        }
        return ssid to config.passphrase
    }

    /**
     * Reads the SSID on Android 11 and 12, which lack `wifiSsid`. Remove when minSdk reaches 33.
     */
    @RequiresApi(Build.VERSION_CODES.R)
    @Suppress("DEPRECATION")
    private fun readSoftApSsidLegacy(config: android.net.wifi.SoftApConfiguration): String? = config.ssid

    /**
     * Reads the credentials on Android 8 to 10, which expose them only through the deprecated
     * WifiConfiguration. Remove when minSdk reaches 30.
     */
    @RequiresApi(Build.VERSION_CODES.O)
    @Suppress("DEPRECATION")
    private fun readLegacyCredentials(reservation: WifiManager.LocalOnlyHotspotReservation): Pair<String?, String?> {
        val config = reservation.wifiConfiguration ?: return null to null
        return config.SSID?.removeSurrounding("\"") to config.preSharedKey?.removeSurrounding("\"")
    }

    private fun fail(failure: VpnSharePolicy.HotspotFailure, error: Exception?) {
        val message = "VpnShare: hotspot failed, reason=$failure"
        if (error != null) LogUtil.e(AppConfig.TAG, message, error) else LogUtil.e(AppConfig.TAG, message)
        VpnShareManager.publish(VpnShareManager.Status(failure = failure))
        stopSelf()
    }
}
