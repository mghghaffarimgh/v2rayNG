package com.v2ray.ang.ui.main

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.v2ray.ang.R
import com.v2ray.ang.service.LeakCheckPolicy

/**
 * One-tap controls for every protection feature, on the main screen next to the server list.
 *
 * Switches change their feature in place; the other chips open the feature's screen, where the
 * steps that need a permission or a choice happen.
 */
@Composable
fun MainProtectionBar(
    state: ProtectionState,
    onAction: (MainAction) -> Unit,
    onNavigate: (MainDestination) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(
            selected = state.killSwitch,
            onClick = { onAction(MainAction.SetKillSwitch(!state.killSwitch)) },
            label = { Text(stringResource(R.string.title_pref_kill_switch)) },
        )
        FilterChip(
            selected = state.virtualLocation,
            onClick = {
                when (MainProtectionPolicy.virtualLocationTap(state.virtualLocation, state.virtualLocationReady)) {
                    MainProtectionPolicy.VirtualLocationTap.TOGGLE ->
                        onAction(MainAction.SetVirtualLocation(!state.virtualLocation))

                    MainProtectionPolicy.VirtualLocationTap.OPEN_SCREEN ->
                        onNavigate(MainDestination.VirtualLocation)
                }
            },
            label = { Text(stringResource(R.string.title_location_spoof)) },
        )
        FilterChip(
            selected = state.shareRunning,
            onClick = { onNavigate(MainDestination.VpnShare) },
            label = { Text(stringResource(R.string.main_chip_vpn_share)) },
        )
        val leakTestLabel = when (state.lastLeakVerdict) {
            LeakCheckPolicy.Verdict.LEAK -> R.string.main_chip_leak_test_leak
            LeakCheckPolicy.Verdict.OK -> R.string.main_chip_leak_test_ok
            else -> R.string.main_chip_leak_test
        }
        val isLeak = state.lastLeakVerdict == LeakCheckPolicy.Verdict.LEAK
        AssistChip(
            onClick = { onNavigate(MainDestination.LeakTest) },
            label = { Text(stringResource(leakTestLabel)) },
            colors = if (isLeak) {
                AssistChipDefaults.assistChipColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    labelColor = MaterialTheme.colorScheme.onErrorContainer,
                )
            } else {
                AssistChipDefaults.assistChipColors()
            },
        )
        AssistChip(
            onClick = { onNavigate(MainDestination.LeakGuide) },
            label = {
                Text(
                    if (state.openRisks == 0) {
                        stringResource(R.string.main_chip_no_leak_paths)
                    } else {
                        stringResource(R.string.main_chip_leak_paths, state.openRisks)
                    }
                )
            },
        )
    }
}
