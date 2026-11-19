package com.v2ray.ang.ui.leaktest

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.handler.LeakCheckManager
import com.v2ray.ang.handler.LocationLeakPolicy
import com.v2ray.ang.handler.LocationSpoofManager
import com.v2ray.ang.handler.LocationSpoofPolicy
import com.v2ray.ang.service.LeakCheckPolicy
import com.v2ray.ang.ui.base.BaseViewModel
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * What the leak tester shows.
 *
 * @property running True while a test runs.
 * @property ipReport The IP part of the last test, null before the first.
 * @property location The location part of the last test, null before the first.
 * @property consistency What still contradicts the virtual position, when it is on.
 * @property testedAt When the last test finished, in epoch milliseconds; 0 before the first.
 */
data class LeakTestState(
    val running: Boolean = false,
    val ipReport: LeakCheckManager.Report? = null,
    val location: LocationLeakPolicy.Result? = null,
    val consistency: List<LocationSpoofPolicy.Finding> = emptyList(),
    val testedAt: Long = 0L,
)

class LeakTestViewModel(application: Application) : BaseViewModel(application) {

    private val _state = MutableStateFlow(LeakTestState())
    val state: StateFlow<LeakTestState> = _state.asStateFlow()

    private var testJob: Job? = null

    /** Runs the IP and the location test together; ignored while a test runs. */
    fun runTest() {
        if (testJob?.isActive == true) return
        testJob = viewModelScope.launch {
            _state.value = _state.value.copy(running = true)
            try {
                val ip = async { LeakCheckManager.runCheckDetailed(app, LeakCheckPolicy.Trigger.MANUAL, serviceState = null) }
                val locationInput = async { LocationSpoofManager.probeLocation(app) }
                val location = LocationLeakPolicy.evaluate(locationInput.await())
                val consistency = if (LocationSpoofManager.isEnabled()) {
                    LocationSpoofPolicy.consistency(LocationSpoofManager.readSignals(app))
                } else {
                    emptyList()
                }
                val report = ip.await()
                withContext(Dispatchers.IO) { LeakCheckManager.logLocationTest(app, location, consistency) }
                _state.value = LeakTestState(
                    running = false,
                    ipReport = report,
                    location = location,
                    consistency = consistency,
                    testedAt = System.currentTimeMillis(),
                )
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "LeakTest: test failed", e)
                toastError(R.string.toast_leak_check_failed)
                _state.value = _state.value.copy(running = false)
            }
        }
    }
}
