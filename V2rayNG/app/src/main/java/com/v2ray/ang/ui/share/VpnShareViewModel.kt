package com.v2ray.ang.ui.share

import android.app.Application
import android.graphics.Bitmap
import androidx.lifecycle.viewModelScope
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.handler.LeakCheckManager
import com.v2ray.ang.handler.VpnShareManager
import com.v2ray.ang.handler.VpnSharePolicy
import com.v2ray.ang.root.RootManager
import com.v2ray.ang.ui.base.BaseViewModel
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.QRCodeDecoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * What the VPN sharing screen shows.
 *
 * @property hotspot The share hotspot's status.
 * @property wifiQr A QR code other phones scan to join the hotspot, null until it is up.
 * @property addresses Addresses other devices use to reach the proxy.
 * @property proxyPort The proxy port.
 * @property proxyHasPassword True when the proxy asks for a user name and password.
 * @property vpnConnected True while this app's VPN is up; null when it cannot be told.
 * @property rootSharing True when root LAN sharing is on.
 * @property rootAvailable True when root access was granted.
 */
data class VpnShareState(
    val hotspot: VpnShareManager.Status = VpnShareManager.Status(),
    val wifiQr: Bitmap? = null,
    val addresses: List<VpnSharePolicy.ShareAddress> = emptyList(),
    val proxyPort: Int = 0,
    val proxyHasPassword: Boolean = false,
    val vpnConnected: Boolean? = null,
    val rootSharing: Boolean = false,
    val rootAvailable: Boolean = false,
)

class VpnShareViewModel(application: Application) : BaseViewModel(application) {

    private val _state = MutableStateFlow(VpnShareState())
    val state: StateFlow<VpnShareState> = _state.asStateFlow()

    private var refreshJob: Job? = null
    private var qrFor: String? = null

    init {
        viewModelScope.launch {
            VpnShareManager.status.collect { refresh() }
        }
    }

    /** Reads everything again; a newer refresh replaces one still running. */
    fun refresh() {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            try {
                val hotspot = VpnShareManager.status.value
                val read = withContext(Dispatchers.IO) {
                    VpnShareState(
                        hotspot = hotspot,
                        wifiQr = _state.value.wifiQr,
                        addresses = VpnSharePolicy.shareAddresses(VpnShareManager.listInterfaces(), hotspot.interfaceName),
                        proxyPort = VpnShareManager.getProxyPort(),
                        proxyHasPassword = VpnShareManager.proxyHasPassword(),
                        vpnConnected = LeakCheckManager.isOurVpnUp(app),
                        rootSharing = VpnShareManager.isRootSharingOn(),
                        rootAvailable = RootManager.cachedRoot(),
                    )
                }
                _state.value = read.copy(wifiQr = qrFor(hotspot))
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "VpnShare: could not read state", e)
            }
        }
    }

    /**
     * Starts or stops the share hotspot. The caller has already obtained the Wi-Fi permission.
     *
     * @param enabled The new state.
     */
    fun setHotspotEnabled(enabled: Boolean) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { VpnShareManager.setHotspotEnabled(app, enabled) }
            refresh()
        }
    }

    /**
     * Turns root LAN sharing on or off, asking for root first when it is not granted yet.
     *
     * @param enabled The new state.
     */
    fun setRootSharing(enabled: Boolean) {
        launchLoading {
            if (enabled && !withContext(Dispatchers.IO) { RootManager.refresh() }) {
                toastError(R.string.toast_root_required)
                return@launchLoading
            }
            withContext(Dispatchers.IO) { VpnShareManager.setRootSharing(app, enabled) }
            refresh()
        }
    }

    /** Builds the Wi-Fi QR code once per network. */
    private suspend fun qrFor(hotspot: VpnShareManager.Status): Bitmap? {
        val ssid = hotspot.ssid
        if (!hotspot.running || ssid == null) {
            qrFor = null
            return null
        }
        val payload = VpnSharePolicy.wifiQrPayload(ssid, hotspot.passphrase)
        if (payload == qrFor && _state.value.wifiQr != null) return _state.value.wifiQr
        qrFor = payload
        return withContext(Dispatchers.Default) { QRCodeDecoder.createQRCode(payload, QR_SIZE_PX) }
    }

    private companion object {
        const val QR_SIZE_PX = 600
    }
}
