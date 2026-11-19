package com.v2ray.ang.ui.location

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.extension.toastError
import com.v2ray.ang.handler.LocationSpoofManager
import com.v2ray.ang.handler.LocationSpoofPolicy
import com.v2ray.ang.ui.base.BaseComponentActivity
import com.v2ray.ang.ui.compose.AppTopBar
import com.v2ray.ang.ui.compose.FormTextField
import com.v2ray.ang.ui.compose.NavigationBarsSpacer
import com.v2ray.ang.ui.compose.PreferenceGroupHeader
import com.v2ray.ang.ui.compose.SettingsListItem
import com.v2ray.ang.ui.compose.SettingsMenuItem
import com.v2ray.ang.ui.compose.SettingsSwitchItem
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Lets the user report a chosen position to apps instead of the real one, and shows what still
 * reveals the real location.
 */
class LocationSpoofActivity : BaseComponentActivity() {

    private val viewModel: LocationSpoofViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            // Developer options, time zone and VPN state change outside this screen.
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                viewModel.refresh()
            }
        }
    }

    private fun openSystemScreen(action: String) {
        try {
            startActivity(Intent(action))
        } catch (e: ActivityNotFoundException) {
            LogUtil.e(AppConfig.TAG, "LocationSpoof: cannot open $action", e)
            toastError(R.string.toast_failure)
        } catch (e: SecurityException) {
            LogUtil.e(AppConfig.TAG, "LocationSpoof: cannot open $action", e)
            toastError(R.string.toast_failure)
        }
    }

    @Composable
    override fun ScreenContent() {
        val state by viewModel.state.collectAsStateWithLifecycle()
        LocationSpoofScreen(
            state = state,
            onBackClick = { finish() },
            onEnabledChange = viewModel::setEnabled,
            onPresetSelected = viewModel::selectPreset,
            onApplyCustom = viewModel::applyCustom,
            onMatchVpnCountry = viewModel::matchVpnCountry,
            onOpenDeveloperOptions = { openSystemScreen(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS) },
            onOpenDateSettings = { openSystemScreen(Settings.ACTION_DATE_SETTINGS) },
            onOpenLocationSettings = { openSystemScreen(Settings.ACTION_LOCATION_SOURCE_SETTINGS) },
        )
    }
}

private const val CUSTOM_VALUE = "custom"

@Composable
fun LocationSpoofScreen(
    state: LocationSpoofState,
    onBackClick: () -> Unit,
    onEnabledChange: (Boolean) -> Unit,
    onPresetSelected: (String) -> Unit,
    onApplyCustom: (String, String) -> Unit,
    onMatchVpnCountry: () -> Unit,
    onOpenDeveloperOptions: () -> Unit,
    onOpenDateSettings: () -> Unit,
    onOpenLocationSettings: () -> Unit,
) {
    // Uncommitted input owned by the visible fields; committed through onApplyCustom.
    var customMode by rememberSaveable { mutableStateOf(false) }
    var latitudeText by rememberSaveable { mutableStateOf("") }
    var longitudeText by rememberSaveable { mutableStateOf("") }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            AppTopBar(
                title = stringResource(R.string.title_location_spoof),
                onBackClick = onBackClick,
                isLoading = state.loading,
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding)
                .imePadding()
                .verticalScroll(rememberScrollState())
        ) {
            state.lastFailure?.let { failure ->
                NoticeCard(
                    text = stringResource(
                        if (failure == LocationSpoofManager.Failure.NOT_MOCK_APP) {
                            R.string.location_spoof_error_not_mock_app
                        } else {
                            R.string.location_spoof_error_other
                        }
                    ),
                    isError = true,
                )
            }

            SettingsSwitchItem(
                title = stringResource(R.string.title_location_spoof),
                summary = stringResource(R.string.summary_location_spoof),
                checked = state.enabled,
                onCheckedChange = onEnabledChange,
            )

            val target = state.target
            val presetValues = LocationSpoofPolicy.PRESETS.map { it.country }
            val presetNames = LocationSpoofPolicy.PRESETS.map { Locale("", it.country).displayCountry }
            val selected = when {
                customMode -> CUSTOM_VALUE
                target == null -> ""
                target.country == null -> CUSTOM_VALUE
                else -> target.country
            }
            SettingsListItem(
                title = stringResource(R.string.location_spoof_position),
                entries = listOf(stringResource(R.string.location_spoof_custom)) + presetNames,
                values = listOf(CUSTOM_VALUE) + presetValues,
                selectedValue = selected,
                onSelected = { value ->
                    if (value == CUSTOM_VALUE) {
                        customMode = true
                        if (target != null) {
                            latitudeText = target.latitude.toString()
                            longitudeText = target.longitude.toString()
                        }
                    } else {
                        customMode = false
                        onPresetSelected(value)
                    }
                },
            )
            if (target != null) {
                Text(
                    text = String.format(Locale.US, "%.5f, %.5f", target.latitude, target.longitude),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            if (customMode || target?.country == null && target != null) {
                FormTextField(
                    label = stringResource(R.string.location_spoof_latitude),
                    value = latitudeText,
                    onValueChange = { latitudeText = it },
                    keyboardType = KeyboardType.Decimal,
                    maxLines = 1,
                )
                FormTextField(
                    label = stringResource(R.string.location_spoof_longitude),
                    value = longitudeText,
                    onValueChange = { longitudeText = it },
                    keyboardType = KeyboardType.Decimal,
                    maxLines = 1,
                )
                Button(
                    onClick = { onApplyCustom(latitudeText, longitudeText) },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Text(stringResource(R.string.location_spoof_apply))
                }
            }
            SettingsMenuItem(
                title = stringResource(R.string.location_spoof_match_vpn),
                subtitle = state.exitCountry?.let {
                    stringResource(R.string.location_spoof_vpn_country, Locale("", it).displayCountry)
                } ?: stringResource(R.string.location_spoof_vpn_country_unknown),
                onClick = {
                    customMode = false
                    onMatchVpnCountry()
                },
            )

            PreferenceGroupHeader(title = stringResource(R.string.location_spoof_consistency_title))
            if (!state.loading) {
                if (state.findings.isEmpty()) {
                    NoticeCard(text = stringResource(R.string.location_spoof_consistent), isError = false)
                } else {
                    // map is inline, so the composable string lookup may run inside it.
                    val lines = state.findings.map { "• " + findingText(it) }
                    NoticeCard(text = lines.joinToString("\n"), isError = true)
                }
            }
            SettingsMenuItem(
                title = stringResource(R.string.location_spoof_open_developer),
                subtitle = stringResource(R.string.location_spoof_open_developer_summary),
                onClick = onOpenDeveloperOptions,
            )
            SettingsMenuItem(
                title = stringResource(R.string.location_spoof_open_date),
                onClick = onOpenDateSettings,
            )
            SettingsMenuItem(
                title = stringResource(R.string.location_spoof_open_location),
                onClick = onOpenLocationSettings,
            )

            GuideText(R.string.location_spoof_setup_title, R.string.location_spoof_setup_body)
            GuideText(R.string.location_spoof_limits_title, R.string.location_spoof_limits_body)
            NavigationBarsSpacer()
        }
    }
}

@Composable
private fun findingText(finding: LocationSpoofPolicy.Finding): String = stringResource(
    when (finding) {
        LocationSpoofPolicy.Finding.NOT_MOCK_APP -> R.string.location_finding_not_mock_app
        LocationSpoofPolicy.Finding.VPN_NOT_CONNECTED -> R.string.location_finding_vpn_off
        LocationSpoofPolicy.Finding.VPN_COUNTRY_MISMATCH -> R.string.location_finding_vpn_country
        LocationSpoofPolicy.Finding.TIME_ZONE_MISMATCH -> R.string.location_finding_time_zone
        LocationSpoofPolicy.Finding.SIM_COUNTRY_MISMATCH -> R.string.location_finding_sim
        LocationSpoofPolicy.Finding.NETWORK_COUNTRY_MISMATCH -> R.string.location_finding_network
        LocationSpoofPolicy.Finding.WIFI_SCANNING_ON -> R.string.location_finding_wifi_scan
    }
)

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
    Text(
        text = stringResource(bodyRes),
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
    )
}
