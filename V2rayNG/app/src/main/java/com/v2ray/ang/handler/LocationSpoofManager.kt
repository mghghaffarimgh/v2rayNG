package com.v2ray.ang.handler

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.provider.Settings
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import androidx.core.location.LocationCompat
import androidx.core.location.LocationManagerCompat
import com.v2ray.ang.AppConfig
import com.v2ray.ang.service.LocationSpoofService
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.TimeZone
import kotlin.coroutines.resume

/**
 * Owns the virtual location settings and starts or stops [LocationSpoofService].
 *
 * The position reaches apps through Android's mock location mechanism, which works only after the
 * user selects this app as the mock location app in Developer options; nothing an app can do
 * skips that step.
 */
object LocationSpoofManager {

    private const val LOCATION_PROBE_TIMEOUT_MS = 15_000L

    /**
     * The position to report.
     *
     * @property country ISO country of the position, null for a custom spot of unknown country.
     * @property latitude Degrees north.
     * @property longitude Degrees east.
     */
    data class Target(
        val country: String?,
        val latitude: Double,
        val longitude: Double,
    )

    /** Why the service could not report the position. */
    enum class Failure {
        /** The system refused the test providers: this app is not the selected mock location app. */
        NOT_MOCK_APP,

        /** Any other failure; the app log has the exception. */
        OTHER,
    }

    /** True while the user has the virtual location switched on. */
    fun isEnabled(): Boolean = MmkvManager.decodeSettingsBool(AppConfig.PREF_SPOOF_ENABLED, false)

    /** The configured position, or null when none was chosen. */
    fun getTarget(): Target? {
        val lat = MmkvManager.decodeSettingsString(AppConfig.PREF_SPOOF_LATITUDE)?.toDoubleOrNull()
        val lon = MmkvManager.decodeSettingsString(AppConfig.PREF_SPOOF_LONGITUDE)?.toDoubleOrNull()
        if (!LocationSpoofPolicy.isValidCoordinate(lat, lon)) return null
        val country = MmkvManager.decodeSettingsString(AppConfig.PREF_SPOOF_COUNTRY)?.takeIf { it.isNotBlank() }
        return Target(country, lat!!, lon!!)
    }

    /**
     * Stores the position; a running service picks it up on its next update.
     *
     * @param target The position; its coordinates must be valid.
     * @return False when the coordinates are invalid and nothing was stored.
     */
    fun setTarget(target: Target): Boolean {
        if (!LocationSpoofPolicy.isValidCoordinate(target.latitude, target.longitude)) return false
        MmkvManager.encodeSettings(AppConfig.PREF_SPOOF_COUNTRY, target.country.orEmpty())
        MmkvManager.encodeSettings(AppConfig.PREF_SPOOF_LATITUDE, target.latitude.toString())
        MmkvManager.encodeSettings(AppConfig.PREF_SPOOF_LONGITUDE, target.longitude.toString())
        return true
    }

    /** The reason the last run stopped, or null when it did not fail. */
    fun getLastFailure(): Failure? {
        val stored = MmkvManager.decodeSettingsString(AppConfig.PREF_SPOOF_LAST_ERROR)
        return Failure.entries.firstOrNull { it.name == stored }
    }

    /** Records why the service stopped, or clears it with null. */
    fun setLastFailure(failure: Failure?) {
        MmkvManager.encodeSettings(AppConfig.PREF_SPOOF_LAST_ERROR, failure?.name.orEmpty())
    }

    /**
     * Switches the virtual location on or off.
     *
     * @param context Any context.
     * @param enabled The new state.
     */
    fun setEnabled(context: Context, enabled: Boolean) {
        MmkvManager.encodeSettings(AppConfig.PREF_SPOOF_ENABLED, enabled)
        val intent = Intent(context, LocationSpoofService::class.java)
        try {
            if (enabled) {
                setLastFailure(null)
                ContextCompat.startForegroundService(context, intent)
            } else {
                context.stopService(intent)
            }
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "LocationSpoof: could not ${if (enabled) "start" else "stop"} the service", e)
            if (enabled) {
                MmkvManager.encodeSettings(AppConfig.PREF_SPOOF_ENABLED, false)
                setLastFailure(Failure.OTHER)
            }
        }
    }

    /**
     * Collects the signals that can contradict the virtual position.
     *
     * @param context Any context.
     * @return The signals for [LocationSpoofPolicy.consistency].
     */
    suspend fun readSignals(context: Context): LocationSpoofPolicy.Signals = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val target = getTarget()
        val preset = LocationSpoofPolicy.presetFor(target?.country)
        val now = System.currentTimeMillis()
        val telephony = app.getSystemService(TelephonyManager::class.java)
        LocationSpoofPolicy.Signals(
            mockAppSelected = getLastFailure() != Failure.NOT_MOCK_APP,
            vpnConnected = LeakCheckManager.isOurVpnUp(app) == true,
            targetCountry = target?.country,
            vpnExitCountry = LeakCheckManager.getLastExitCountry(),
            targetUtcOffsetMs = preset?.let { TimeZone.getTimeZone(it.timeZone).getOffset(now) },
            deviceUtcOffsetMs = TimeZone.getDefault().getOffset(now),
            simCountry = telephony?.simCountryIso,
            networkCountry = telephony?.networkCountryIso,
            wifiScanAlwaysOn = readWifiScanAlwaysOn(app),
        )
    }

    /**
     * Reads the position the system gives apps right now, the way any app with location
     * permission would, for the location leak test.
     *
     * @param context Any context.
     * @return What the test saw, for [LocationLeakPolicy.evaluate].
     */
    suspend fun probeLocation(context: Context): LocationLeakPolicy.Input = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val target = getTarget()
        val base = LocationLeakPolicy.Input(
            permissionGranted = hasLocationPermission(app),
            locationEnabled = false,
            reported = null,
            spoofEnabled = isEnabled(),
            targetLatitude = target?.latitude,
            targetLongitude = target?.longitude,
        )
        val manager = app.getSystemService(LocationManager::class.java) ?: return@withContext base
        if (!base.permissionGranted) return@withContext base
        if (!LocationManagerCompat.isLocationEnabled(manager)) return@withContext base

        val location = withTimeoutOrNull(LOCATION_PROBE_TIMEOUT_MS) { currentLocation(app, manager) }
        base.copy(
            locationEnabled = true,
            reported = location?.let {
                LocationLeakPolicy.Reported(it.latitude, it.longitude, LocationCompat.isMock(it))
            },
        )
    }

    /** True when this app may read the approximate or precise location. */
    fun hasLocationPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /**
     * Asks for one fresh position from the best enabled provider; null when none answers.
     */
    private suspend fun currentLocation(context: Context, manager: LocationManager): Location? {
        val providers = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(LocationManager.FUSED_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
            add(LocationManager.GPS_PROVIDER)
        }
        val provider = providers.firstOrNull { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }
            ?: return null
        if (!hasLocationPermission(context)) return null
        return suspendCancellableCoroutine { continuation ->
            val cancel = CancellationSignal()
            continuation.invokeOnCancellation { cancel.cancel() }
            try {
                // The callback only resumes the coroutine, so it may run on the calling thread.
                LocationManagerCompat.getCurrentLocation(manager, provider, cancel, Runnable::run) { location ->
                    if (continuation.isActive) continuation.resume(location)
                }
            } catch (e: SecurityException) {
                LogUtil.w(AppConfig.TAG, "LocationSpoof: location permission withdrawn during the probe", e)
                if (continuation.isActive) continuation.resume(null)
            }
        }
    }

    /**
     * Reads the "Wi-Fi scanning" setting, which lets location services scan nearby networks even
     * with Wi-Fi off. The key is not in the public API, so an unreadable value counts as off.
     */
    private fun readWifiScanAlwaysOn(context: Context): Boolean {
        return try {
            Settings.Global.getInt(context.contentResolver, "wifi_scan_always_enabled", 0) == 1
        } catch (e: Exception) {
            LogUtil.w(AppConfig.TAG, "LocationSpoof: cannot read the Wi-Fi scanning setting", e)
            false
        }
    }
}
