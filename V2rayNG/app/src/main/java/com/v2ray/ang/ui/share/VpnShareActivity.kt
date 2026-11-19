package com.v2ray.ang.ui.share

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.extension.toastError
import com.v2ray.ang.handler.VpnSharePolicy
import com.v2ray.ang.ui.base.BaseComponentActivity
import com.v2ray.ang.ui.compose.AppTopBar
import com.v2ray.ang.ui.compose.NavigationBarsSpacer
import com.v2ray.ang.ui.compose.PreferenceGroupHeader
import com.v2ray.ang.ui.compose.SettingsMenuItem
import com.v2ray.ang.ui.compose.SettingsSwitchItem
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.launch

/**
 * Shares the VPN with other devices: a local Wi-Fi network plus the proxy without root, or the
 * system hotspot, USB and Ethernet tethering through the tunnel with root.
 */
class VpnShareActivity : BaseComponentActivity() {

    private val viewModel: VpnShareViewModel by viewModels()

    private val hotspotPermissions: Array<String>
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        }

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.all { it }) {
            viewModel.setHotspotEnabled(true)
        } else {
            toastError(R.string.vpn_share_permission_denied)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            // Addresses, VPN state and root grants change outside this screen.
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                viewModel.refresh()
            }
        }
    }

    private fun onHotspotToggle(enabled: Boolean) {
        if (!enabled) {
            viewModel.setHotspotEnabled(false)
            return
        }
        val granted = hotspotPermissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
        if (granted) viewModel.setHotspotEnabled(true) else permissionLauncher.launch(hotspotPermissions)
    }

    private fun openTetherSettings() {
        // Android has no public action for the hotspot & tethering page; the wireless page links to it.
        try {
            startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS))
        } catch (e: ActivityNotFoundException) {
            LogUtil.e(AppConfig.TAG, "VpnShare: cannot open wireless settings", e)
            toastError(R.string.toast_failure)
        }
    }

    @Composable
    override fun ScreenContent() {
        val state by viewModel.state.collectAsStateWithLifecycle()
        val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
        VpnShareScreen(
            state = state,
            isLoading = isLoading,
            onBackClick = { finish() },
            onHotspotToggle = ::onHotspotToggle,
            onRootSharingToggle = viewModel::setRootSharing,
            onOpenTetherSettings = ::openTetherSettings,
        )
    }
}

@Composable
fun VpnShareScreen(
    state: VpnShareState,
    isLoading: Boolean,
    onBackClick: () -> Unit,
    onHotspotToggle: (Boolean) -> Unit,
    onRootSharingToggle: (Boolean) -> Unit,
    onOpenTetherSettings: () -> Unit,
) {
    val hotspot = state.hotspot
    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            AppTopBar(title = stringResource(R.string.title_vpn_share), onBackClick = onBackClick, isLoading = isLoading)
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
        ) {
            BodyText(stringResource(R.string.vpn_share_intro))
            if (state.vpnConnected == false) {
                NoticeCard(stringResource(R.string.vpn_share_vpn_off), isError = true)
            }

            PreferenceGroupHeader(title = stringResource(R.string.vpn_share_hotspot_title))
            SettingsSwitchItem(
                title = stringResource(R.string.vpn_share_hotspot_switch),
                summary = stringResource(R.string.vpn_share_hotspot_summary),
                checked = hotspot.running || hotspot.starting,
                onCheckedChange = onHotspotToggle,
            )
            when {
                hotspot.starting -> BodyText(stringResource(R.string.vpn_share_starting))
                hotspot.failure != null -> NoticeCard(stringResource(failureText(hotspot.failure)), isError = true)
            }
            if (hotspot.running) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        SelectionContainer {
                            Text(
                                text = stringResource(R.string.vpn_share_wifi_credentials, hotspot.ssid ?: "?", hotspot.passphrase ?: "—"),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                        state.wifiQr?.let { qr ->
                            Image(
                                bitmap = qr.asImageBitmap(),
                                contentDescription = stringResource(R.string.vpn_share_wifi_qr_description),
                                modifier = Modifier
                                    .padding(top = 12.dp)
                                    .size(220.dp),
                            )
                        }
                    }
                }
            }

            PreferenceGroupHeader(title = stringResource(R.string.vpn_share_proxy_title))
            if (state.addresses.isEmpty()) {
                BodyText(stringResource(R.string.vpn_share_no_address))
            } else {
                SelectionContainer {
                    Column {
                        state.addresses.forEach { address ->
                            BodyText(
                                stringResource(
                                    if (address.isShareHotspot) R.string.vpn_share_address_hotspot else R.string.vpn_share_address_lan,
                                    "${address.ip}:${state.proxyPort}",
                                )
                            )
                        }
                    }
                }
            }
            BodyText(stringResource(R.string.vpn_share_proxy_type))
            if (!state.proxyHasPassword) {
                NoticeCard(stringResource(R.string.vpn_share_no_password), isError = false)
            }

            GuideText(R.string.vpn_share_howto_title, R.string.vpn_share_howto_body)

            PreferenceGroupHeader(title = stringResource(R.string.vpn_share_root_title))
            SettingsSwitchItem(
                title = stringResource(R.string.title_root_lan_sharing),
                summary = stringResource(R.string.vpn_share_root_summary),
                checked = state.rootSharing,
                onCheckedChange = onRootSharingToggle,
            )
            SettingsMenuItem(
                title = stringResource(R.string.vpn_share_open_tethering),
                onClick = onOpenTetherSettings,
            )
            GuideText(R.string.vpn_share_pc_title, R.string.vpn_share_pc_body)
            NavigationBarsSpacer()
        }
    }
}

private fun failureText(failure: VpnSharePolicy.HotspotFailure): Int = when (failure) {
    VpnSharePolicy.HotspotFailure.NO_CHANNEL -> R.string.vpn_share_failure_no_channel
    VpnSharePolicy.HotspotFailure.INCOMPATIBLE_MODE -> R.string.vpn_share_failure_incompatible
    VpnSharePolicy.HotspotFailure.TETHERING_DISALLOWED -> R.string.vpn_share_failure_disallowed
    VpnSharePolicy.HotspotFailure.UNSUPPORTED -> R.string.vpn_share_failure_unsupported
    VpnSharePolicy.HotspotFailure.PERMISSION_MISSING -> R.string.vpn_share_permission_denied
    VpnSharePolicy.HotspotFailure.GENERIC -> R.string.vpn_share_failure_generic
}

@Composable
private fun BodyText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

@Composable
private fun NoticeCard(text: String, isError: Boolean) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(text = text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(16.dp))
    }
}

@Composable
private fun GuideText(titleRes: Int, bodyRes: Int) {
    Text(
        text = stringResource(titleRes),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp)
            .semantics { heading() },
    )
    BodyText(stringResource(bodyRes))
}
