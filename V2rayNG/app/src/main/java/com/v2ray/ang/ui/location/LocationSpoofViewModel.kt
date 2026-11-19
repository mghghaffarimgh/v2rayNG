package com.v2ray.ang.ui.location

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.handler.LeakCheckManager
import com.v2ray.ang.handler.LocationSpoofManager
import com.v2ray.ang.handler.LocationSpoofPolicy
import com.v2ray.ang.ui.base.BaseViewModel
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * What the virtual location screen shows.
 *
 * @property loading True until the first read finished.
 * @property enabled True while the virtual location is switched on.
 * @property target The configured position, null when none was chosen.
 * @property exitCountry Country of the last measured VPN exit, null when not measured.
 * @property findings What still points away from the virtual position.
 * @property lastFailure Why the last run stopped, null when it did not fail.
 */
data class LocationSpoofState(
    val loading: Boolean = true,
    val enabled: Boolean = false,
    val target: LocationSpoofManager.Target? = null,
    val exitCountry: String? = null,
    val findings: List<LocationSpoofPolicy.Finding> = emptyList(),
    val lastFailure: LocationSpoofManager.Failure? = null,
)

class LocationSpoofViewModel(application: Application) : BaseViewModel(application) {

    private val _state = MutableStateFlow(LocationSpoofState())
    val state: StateFlow<LocationSpoofState> = _state.asStateFlow()

    private var refreshJob: Job? = null

    /** Reads settings and signals again; a newer refresh replaces one still running. */
    fun refresh() {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            _state.value = try {
                val signals = LocationSpoofManager.readSignals(app)
                withContext(Dispatchers.IO) {
                    LocationSpoofState(
                        loading = false,
                        enabled = LocationSpoofManager.isEnabled(),
                        target = LocationSpoofManager.getTarget(),
                        exitCountry = LeakCheckManager.getLastExitCountry(),
                        findings = LocationSpoofPolicy.consistency(signals),
                        lastFailure = LocationSpoofManager.getLastFailure(),
                    )
                }
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "LocationSpoof: could not read state", e)
                _state.value.copy(loading = false)
            }
        }
    }

    /**
     * Switches the virtual location on or off; switching on needs a chosen position.
     *
     * @param enabled The new state.
     */
    fun setEnabled(enabled: Boolean) {
        if (enabled && _state.value.target == null) {
            toastError(R.string.location_spoof_choose_first)
            return
        }
        viewModelScope.launch {
            withContext(Dispatchers.IO) { LocationSpoofManager.setEnabled(app, enabled) }
            refresh()
        }
    }

    /**
     * Uses a preset as the position.
     *
     * @param country The preset's country code.
     */
    fun selectPreset(country: String) {
        val preset = LocationSpoofPolicy.presetFor(country) ?: return
        store(LocationSpoofManager.Target(preset.country, preset.latitude, preset.longitude))
    }

    /**
     * Uses a typed position.
     *
     * @param latitudeText The typed latitude.
     * @param longitudeText The typed longitude.
     */
    fun applyCustom(latitudeText: String, longitudeText: String) {
        val parsed = LocationSpoofPolicy.parseCoordinates(latitudeText, longitudeText)
        if (parsed == null) {
            toastError(R.string.location_spoof_invalid_coordinates)
            return
        }
        store(LocationSpoofManager.Target(null, parsed.first, parsed.second))
    }

    /** Uses the preset of the country the VPN exit was last measured in. */
    fun matchVpnCountry() {
        val preset = LocationSpoofPolicy.presetFor(_state.value.exitCountry)
        if (preset == null) {
            toastError(R.string.location_spoof_no_vpn_country)
            return
        }
        selectPreset(preset.country)
    }

    private fun store(target: LocationSpoofManager.Target) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { LocationSpoofManager.setTarget(target) }
            toastSuccess(R.string.location_spoof_saved)
            refresh()
        }
    }
}
