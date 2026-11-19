package com.v2ray.ang.ui.leakguide

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.handler.LeakCheckManager
import com.v2ray.ang.handler.LeakLog
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.service.LeakCheckPolicy
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
import java.io.File

/**
 * What the leak guide shows about the current settings and the last leak check.
 *
 * @property loading True until the first read finished.
 * @property risks The open leak paths, most severe first.
 * @property lockdown What the VPN service last saw about the system lockdown.
 * @property leakWarningsEnabled The leak warning setting.
 * @property checkIntervalMinutes Minutes between periodic leak checks; 0 is off.
 * @property checkRunning True while a manual check runs.
 * @property lastVerdict The verdict of the newest check, null when none ran.
 * @property lastFindings The findings of the newest manual check from this screen.
 * @property lastCheckTime When the newest check ran, in epoch milliseconds; 0 when none ran.
 */
data class LeakGuideState(
    val loading: Boolean = true,
    val risks: List<LeakRiskPolicy.Risk> = emptyList(),
    val lockdown: LeakRiskPolicy.LockdownState = LeakRiskPolicy.LockdownState.UNKNOWN,
    val leakWarningsEnabled: Boolean = true,
    val checkIntervalMinutes: Int = LeakCheckPolicy.RANDOM_SCHEDULE,
    val checkRunning: Boolean = false,
    val lastVerdict: LeakCheckPolicy.Verdict? = null,
    val lastFindings: List<LeakCheckPolicy.Finding> = emptyList(),
    val lastCheckTime: Long = 0L,
)

class LeakGuideViewModel(application: Application) : BaseViewModel(application) {

    private val _state = MutableStateFlow(LeakGuideState())
    val state: StateFlow<LeakGuideState> = _state.asStateFlow()

    private var refreshJob: Job? = null
    private var checkJob: Job? = null

    /**
     * Reads the settings again; a newer refresh replaces one still running.
     */
    fun refresh() {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            val read = withContext(Dispatchers.IO) { readState() }
            val current = _state.value
            _state.value = read.copy(checkRunning = current.checkRunning, lastFindings = current.lastFindings)
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

    /**
     * Changes the interval between leak checks; it applies from the next VPN start.
     *
     * @param minutes One of [LeakCheckManager.INTERVAL_CHOICES]; 0 turns checks off.
     */
    fun setCheckInterval(minutes: Int) {
        if (minutes !in LeakCheckManager.INTERVAL_CHOICES) return
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                LeakCheckManager.setIntervalMinutes(app, minutes)
            }
            _state.value = _state.value.copy(checkIntervalMinutes = minutes)
        }
    }

    /**
     * Runs a leak check now; ignored while one is running.
     */
    fun runCheckNow() {
        if (checkJob?.isActive == true) return
        checkJob = viewModelScope.launch {
            _state.value = _state.value.copy(checkRunning = true)
            try {
                val result = LeakCheckManager.runCheck(app, LeakCheckPolicy.Trigger.MANUAL, serviceState = null)
                _state.value = _state.value.copy(
                    lastVerdict = result.verdict,
                    lastFindings = result.findings,
                    lastCheckTime = System.currentTimeMillis(),
                )
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "LeakGuide: manual leak check failed", e)
                toastError(R.string.toast_leak_check_failed)
            } finally {
                _state.value = _state.value.copy(checkRunning = false)
            }
        }
    }

    /**
     * Writes the leak log to a file for sharing.
     *
     * @param onReady Receives the file on the main thread; not called when the log is empty.
     */
    fun exportLog(onReady: (File) -> Unit) {
        viewModelScope.launch {
            val file = withContext(Dispatchers.IO) { LeakLog.exportToCache(app) }
            if (file == null) {
                toast(R.string.toast_leak_log_empty)
            } else {
                onReady(file)
            }
        }
    }

    /**
     * Deletes the leak log.
     */
    fun clearLog() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { LeakLog.clear(app) }
            toastSuccess(R.string.toast_leak_log_cleared)
        }
    }

    private fun readState(): LeakGuideState {
        return try {
            val inputs = SettingsManager.getLeakRiskInputs()
            val verdict = MmkvManager.decodeSettingsString(AppConfig.PREF_LEAK_LAST_VERDICT)
                ?.let { stored -> LeakCheckPolicy.Verdict.entries.firstOrNull { it.name == stored } }
            LeakGuideState(
                loading = false,
                risks = LeakRiskPolicy.risks(inputs),
                lockdown = inputs.lockdown,
                leakWarningsEnabled = SettingsManager.isLeakWarningEnabled(),
                checkIntervalMinutes = LeakCheckManager.getIntervalMinutes(),
                lastVerdict = verdict,
                lastCheckTime = MmkvManager.decodeSettingsLong(AppConfig.PREF_LEAK_LAST_CHECK_TIME, 0L),
            )
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "LeakGuide: could not read leak settings", e)
            LeakGuideState(loading = false)
        }
    }
}
