package com.v2ray.ang.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.location.Criteria
import android.location.Location
import android.location.LocationManager
import android.location.provider.ProviderProperties
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.annotation.RequiresApi
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.enums.NotificationChannelType
import com.v2ray.ang.handler.AppLocaleManager
import com.v2ray.ang.handler.LocationSpoofManager
import com.v2ray.ang.handler.LocationSpoofPolicy
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.helper.NotificationHelper
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.random.Random

/**
 * Reports the virtual position to apps through Android test location providers.
 *
 * Replaces the GPS, network and (Android 12+) fused providers with test providers and feeds them
 * a position that wanders a few meters around the chosen spot once a second. Removing the test
 * providers on stop gives apps the real position back.
 */
class LocationSpoofService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var feedJob: Job? = null
    private val installedProviders = mutableListOf<String>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun attachBaseContext(newBase: Context?) {
        super.attachBaseContext(newBase?.let(AppLocaleManager::localizedContext))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        NotificationHelper.startForeground(
            this,
            NotificationChannelType.LOCATION_SPOOF,
            getString(R.string.title_location_spoof),
            notificationText(LocationSpoofManager.getTarget()),
        )
        if (!LocationSpoofManager.isEnabled()) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (feedJob?.isActive != true) {
            feedJob = serviceScope.launch { feed() }
        }
        // A restart rebuilds everything from the stored settings, so sticky is safe here.
        return START_STICKY
    }

    override fun onDestroy() {
        feedJob?.cancel()
        serviceScope.cancel()
        removeProviders()
        NotificationHelper.stopForeground(this)
        super.onDestroy()
    }

    private suspend fun feed() {
        val manager = getSystemService(LocationManager::class.java)
        if (manager == null) {
            fail(LocationSpoofManager.Failure.OTHER, null)
            return
        }
        try {
            providersToReplace().forEach { provider ->
                installProvider(manager, provider)
                installedProviders += provider
            }
        } catch (e: SecurityException) {
            fail(LocationSpoofManager.Failure.NOT_MOCK_APP, e)
            return
        } catch (e: Exception) {
            fail(LocationSpoofManager.Failure.OTHER, e)
            return
        }

        val random = Random(System.nanoTime())
        var target = LocationSpoofManager.getTarget()
        var current = target?.let { it.latitude to it.longitude }
        while (serviceScope.isActive) {
            val latest = LocationSpoofManager.getTarget()
            if (latest == null) {
                fail(LocationSpoofManager.Failure.OTHER, null)
                return
            }
            if (latest != target) {
                target = latest
                current = latest.latitude to latest.longitude
                NotificationHelper.updateNotification(
                    NotificationChannelType.LOCATION_SPOOF, this,
                    getString(R.string.title_location_spoof), notificationText(latest),
                )
            }
            val (lat, lon) = LocationSpoofPolicy.wander(latest.latitude, latest.longitude, current!!.first, current.second, random)
            current = lat to lon
            try {
                installedProviders.forEach { manager.setTestProviderLocation(it, buildLocation(it, lat, lon, random)) }
            } catch (e: SecurityException) {
                // The user unselected this app as the mock location app while it ran.
                fail(LocationSpoofManager.Failure.NOT_MOCK_APP, e)
                return
            } catch (e: Exception) {
                LogUtil.w(AppConfig.TAG, "LocationSpoof: update failed", e)
            }
            delay(UPDATE_INTERVAL_MS)
        }
    }

    private fun providersToReplace(): List<String> {
        val providers = mutableListOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) providers += LocationManager.FUSED_PROVIDER
        return providers
    }

    private fun installProvider(manager: LocationManager, provider: String) {
        // A crash in a previous run can leave the provider installed; adding it again would fail.
        try {
            manager.removeTestProvider(provider)
        } catch (_: IllegalArgumentException) {
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            addTestProvider(manager, provider)
        } else {
            addTestProviderLegacy(manager, provider)
        }
        manager.setTestProviderEnabled(provider, true)
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun addTestProvider(manager: LocationManager, provider: String) {
        val properties = ProviderProperties.Builder()
            .setHasSatelliteRequirement(provider == LocationManager.GPS_PROVIDER)
            .setHasNetworkRequirement(provider == LocationManager.NETWORK_PROVIDER)
            .setHasAltitudeSupport(true)
            .setHasSpeedSupport(true)
            .setHasBearingSupport(true)
            .setPowerUsage(ProviderProperties.POWER_USAGE_LOW)
            .setAccuracy(ProviderProperties.ACCURACY_FINE)
            .build()
        manager.addTestProvider(provider, properties)
    }

    /**
     * Adds a test provider on Android 11 and older, which lack the [ProviderProperties] overload.
     * Remove when minSdk reaches 31.
     */
    @Suppress("DEPRECATION")
    private fun addTestProviderLegacy(manager: LocationManager, provider: String) {
        manager.addTestProvider(
            provider,
            provider == LocationManager.NETWORK_PROVIDER,
            provider == LocationManager.GPS_PROVIDER,
            false,
            false,
            true,
            true,
            true,
            Criteria.POWER_LOW,
            Criteria.ACCURACY_FINE,
        )
    }

    private fun buildLocation(provider: String, lat: Double, lon: Double, random: Random): Location {
        return Location(provider).apply {
            latitude = lat
            longitude = lon
            altitude = 40.0 + random.nextDouble() * 4
            accuracy = if (provider == LocationManager.NETWORK_PROVIDER) {
                18f + random.nextFloat() * 10
            } else {
                3f + random.nextFloat() * 5
            }
            speed = 0f
            bearing = 0f
            time = System.currentTimeMillis()
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                verticalAccuracyMeters = 3f + random.nextFloat() * 3
                speedAccuracyMetersPerSecond = 0.5f
                bearingAccuracyDegrees = 30f
            }
        }
    }

    private fun removeProviders() {
        val manager = getSystemService(LocationManager::class.java) ?: return
        installedProviders.forEach { provider ->
            try {
                manager.removeTestProvider(provider)
            } catch (e: Exception) {
                LogUtil.w(AppConfig.TAG, "LocationSpoof: could not remove test provider $provider", e)
            }
        }
        installedProviders.clear()
    }

    private fun fail(failure: LocationSpoofManager.Failure, error: Exception?) {
        val message = "LocationSpoof: stopped, failure=$failure"
        if (error != null) LogUtil.e(AppConfig.TAG, message, error) else LogUtil.e(AppConfig.TAG, message)
        LocationSpoofManager.setLastFailure(failure)
        MmkvManager.encodeSettings(AppConfig.PREF_SPOOF_ENABLED, false)
        val text = getString(
            if (failure == LocationSpoofManager.Failure.NOT_MOCK_APP) {
                R.string.location_spoof_error_not_mock_app
            } else {
                R.string.location_spoof_error_other
            }
        )
        NotificationHelper.notify(NotificationChannelType.LOCATION_SPOOF, this, getString(R.string.title_location_spoof), text)
        stopSelf()
    }

    private fun notificationText(target: LocationSpoofManager.Target?): String {
        if (target == null) return getString(R.string.location_spoof_not_set)
        val place = target.country?.let { Locale("", it).displayCountry }
            ?: String.format(Locale.US, "%.4f, %.4f", target.latitude, target.longitude)
        return getString(R.string.location_spoof_notification, place)
    }

    private companion object {
        const val UPDATE_INTERVAL_MS = 1_000L
    }
}
