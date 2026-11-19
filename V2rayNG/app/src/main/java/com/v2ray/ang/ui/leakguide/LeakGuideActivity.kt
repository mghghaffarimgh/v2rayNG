package com.v2ray.ang.ui.leakguide

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.viewModels
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.extension.toastError
import com.v2ray.ang.handler.NotificationManager
import com.v2ray.ang.service.LeakRiskPolicy
import com.v2ray.ang.ui.base.BaseComponentActivity
import com.v2ray.ang.ui.compose.AppTopBar
import com.v2ray.ang.ui.compose.NavigationBarsSpacer
import com.v2ray.ang.ui.compose.PreferenceGroupHeader
import com.v2ray.ang.ui.compose.SettingsMenuItem
import com.v2ray.ang.ui.compose.SettingsSwitchItem
import com.v2ray.ang.ui.perappproxy.PerAppProxyActivity
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.launch

/**
 * Explains every known path for traffic to leave outside the VPN tunnel, shows which of them are
 * open for the current settings, and links to the place where each one is closed.
 */
class LeakGuideActivity : BaseComponentActivity() {

    private val viewModel: LeakGuideViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            // Settings change on other screens and in system settings, so read them on every return.
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                viewModel.refresh()
            }
        }
    }

    private fun openSystemVpnSettings() {
        try {
            startActivity(Intent(Settings.ACTION_VPN_SETTINGS))
        } catch (e: ActivityNotFoundException) {
            LogUtil.e(AppConfig.TAG, "LeakGuide: cannot open system VPN settings", e)
            toastError(R.string.toast_system_vpn_settings_unavailable)
        } catch (e: SecurityException) {
            LogUtil.e(AppConfig.TAG, "LeakGuide: cannot open system VPN settings", e)
            toastError(R.string.toast_system_vpn_settings_unavailable)
        }
    }

    @Composable
    override fun ScreenContent() {
        val state by viewModel.state.collectAsStateWithLifecycle()
        LeakGuideScreen(
            state = state,
            onBackClick = { finish() },
            onSystemVpnSettingsClick = ::openSystemVpnSettings,
            onPerAppClick = { startActivity(Intent(this, PerAppProxyActivity::class.java)) },
            onLeakWarningsChange = viewModel::setLeakWarningsEnabled,
        )
    }
}

/** One section of the guide: a heading and its explanation. */
private data class GuideSection(@StringRes val title: Int, @StringRes val body: Int)

private val guideSections = listOf(
    GuideSection(R.string.leak_guide_kill_switch_title, R.string.leak_guide_kill_switch_body),
    GuideSection(R.string.leak_guide_lockdown_title, R.string.leak_guide_lockdown_body),
    GuideSection(R.string.leak_guide_reboot_title, R.string.leak_guide_reboot_body),
    GuideSection(R.string.leak_guide_other_vpn_title, R.string.leak_guide_other_vpn_body),
    GuideSection(R.string.leak_guide_per_app_title, R.string.leak_guide_per_app_body),
    GuideSection(R.string.leak_guide_hotspot_title, R.string.leak_guide_hotspot_body),
    GuideSection(R.string.leak_guide_modes_title, R.string.leak_guide_modes_body),
    GuideSection(R.string.leak_guide_location_title, R.string.leak_guide_location_body),
)

@Composable
fun LeakGuideScreen(
    state: LeakGuideState,
    onBackClick: () -> Unit,
    onSystemVpnSettingsClick: () -> Unit,
    onPerAppClick: () -> Unit,
    onLeakWarningsChange: (Boolean) -> Unit,
) {
    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            AppTopBar(
                title = stringResource(R.string.title_leak_guide),
                onBackClick = onBackClick,
                isLoading = state.loading,
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
        ) {
            PreferenceGroupHeader(title = stringResource(R.string.leak_guide_status_title))
            if (!state.loading) {
                LeakStatusCard(state)
            }
            SettingsMenuItem(
                title = stringResource(R.string.title_system_vpn_settings),
                subtitle = stringResource(R.string.summary_system_vpn_settings),
                onClick = onSystemVpnSettingsClick,
            )
            SettingsMenuItem(
                title = stringResource(R.string.leak_guide_open_per_app),
                onClick = onPerAppClick,
            )
            SettingsSwitchItem(
                title = stringResource(R.string.title_pref_leak_warnings),
                summary = stringResource(R.string.summary_pref_leak_warnings),
                checked = state.leakWarningsEnabled,
                onCheckedChange = onLeakWarningsChange,
            )

            guideSections.forEach { section ->
                Text(
                    text = stringResource(section.title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp)
                        .semantics { heading() },
                )
                Text(
                    text = stringResource(section.body),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                )
            }
            NavigationBarsSpacer()
        }
    }
}

@Composable
private fun LeakStatusCard(state: LeakGuideState) {
    val hasRisks = state.risks.isNotEmpty()
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (hasRisks) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.secondaryContainer
            }
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            if (!hasRisks) {
                Text(
                    text = stringResource(R.string.leak_guide_no_risks),
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                state.risks.forEach { risk ->
                    Text(
                        text = "• " + stringResource(NotificationManager.riskTextRes(risk)),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(vertical = 2.dp),
                    )
                }
            }
            if (state.lockdown == LeakRiskPolicy.LockdownState.UNKNOWN) {
                Text(
                    text = stringResource(R.string.leak_guide_lockdown_unknown),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}
