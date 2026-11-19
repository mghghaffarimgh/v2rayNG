package com.v2ray.ang.ui.leakguide

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.v2ray.ang.AppConfig
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.service.LeakRiskPolicy
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
 * What the leak guide shows about the current settings.
 *
 * @property loading True until the first read finished.
 * @property risks The open leak paths, most severe first.
 * @property lockdown What the VPN service last saw about the system lockdown.
 * @property leakWarningsEnabled The leak warning setting.
 */
data class LeakGuideState(
    val loading: Boolean = true,
    val risks: List<LeakRiskPolicy.Risk> = emptyList(),
    val lockdown: LeakRiskPolicy.LockdownState = LeakRiskPolicy.LockdownState.UNKNOWN,
    val leakWarningsEnabled: Boolean = true,
)

class LeakGuideViewModel(application: Application) : BaseViewModel(application) {

    private val _state = MutableStateFlow(LeakGuideState())
    val state: StateFlow<LeakGuideState> = _state.asStateFlow()

    private var refreshJob: Job? = null

    /**
     * Reads the settings again; a newer refresh replaces one still running.
     */
    fun refresh() {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            _state.value = withContext(Dispatchers.IO) { readState() }
        }
    }

    /**
     * Turns the start-up leak warning on or off.
     *
     * @param enabled The new value.
     */
    fun setLeakWarningsEnabled(enabled: Boolean) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                SettingsManager.setLeakWarningEnabled(enabled)
            }
            _state.value = _state.value.copy(leakWarningsEnabled = enabled)
        }
    }

    private fun readState(): LeakGuideState {
        return try {
            val inputs = SettingsManager.getLeakRiskInputs()
            LeakGuideState(
                loading = false,
                risks = LeakRiskPolicy.risks(inputs),
                lockdown = inputs.lockdown,
                leakWarningsEnabled = SettingsManager.isLeakWarningEnabled(),
            )
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "LeakGuide: could not read leak settings", e)
            LeakGuideState(loading = false)
        }
    }
}
