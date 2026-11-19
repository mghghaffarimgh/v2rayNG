package com.v2ray.ang.ui.leaktest

import android.Manifest
import android.content.Intent
import android.text.format.DateUtils
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.v2ray.ang.R
import com.v2ray.ang.handler.LeakCheckManager
import com.v2ray.ang.handler.LocationLeakPolicy
import com.v2ray.ang.handler.LocationSpoofManager
import com.v2ray.ang.handler.LocationSpoofPolicy
import com.v2ray.ang.handler.NotificationManager
import com.v2ray.ang.service.LeakCheckPolicy
import com.v2ray.ang.ui.base.BaseComponentActivity
import com.v2ray.ang.ui.compose.AppTopBar
import com.v2ray.ang.ui.compose.NavigationBarsSpacer
import com.v2ray.ang.ui.compose.SettingsMenuItem
import com.v2ray.ang.ui.leakguide.LeakGuideActivity
import com.v2ray.ang.ui.location.LocationSpoofActivity
import java.util.Locale

/**
 * One-tap leak tester: checks the IP address traffic leaves with and the position apps see, and
 * says plainly whether either leaks.
 */
class LeakTestActivity : BaseComponentActivity() {

    private val viewModel: LeakTestViewModel by viewModels()

    // The location part reads the position the way other apps do, which needs the permission;
    // without it the IP part still runs and the location part says it could not test.
    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        viewModel.runTest()
    }

    private fun startTest() {
        if (LocationSpoofManager.hasLocationPermission(this)) {
            viewModel.runTest()
        } else {
            permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }
    }

    @Composable
    override fun ScreenContent() {
        val state by viewModel.state.collectAsStateWithLifecycle()
        LeakTestScreen(
            state = state,
            onBackClick = { finish() },
            onStartTest = ::startTest,
            onOpenGuide = { startActivity(Intent(this, LeakGuideActivity::class.java)) },
            onOpenLocation = { startActivity(Intent(this, LocationSpoofActivity::class.java)) },
        )
    }
}

@Composable
fun LeakTestScreen(
    state: LeakTestState,
    onBackClick: () -> Unit,
    onStartTest: () -> Unit,
    onOpenGuide: () -> Unit,
    onOpenLocation: () -> Unit,
) {
    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            AppTopBar(title = stringResource(R.string.title_leak_tester), onBackClick = onBackClick, isLoading = state.running)
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
        ) {
            BodyText(stringResource(R.string.leak_tester_intro))
            Button(
                onClick = onStartTest,
                enabled = !state.running,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Text(stringResource(if (state.running) R.string.leak_test_running else R.string.leak_tester_start))
            }
            if (state.testedAt > 0L) {
                val time = DateUtils.getRelativeTimeSpanString(
                    state.testedAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS
                ).toString()
                BodyText(stringResource(R.string.leak_test_last_run, time))
            }

            state.ipReport?.let { IpResult(it) }
            state.location?.let { LocationResult(it, state.consistency) }

            BodyText(stringResource(R.string.leak_tester_schedule_note))
            SettingsMenuItem(title = stringResource(R.string.title_leak_guide), onClick = onOpenGuide)
            SettingsMenuItem(title = stringResource(R.string.title_location_spoof), onClick = onOpenLocation)
            NavigationBarsSpacer()
        }
    }
}

@Composable
private fun IpResult(report: LeakCheckManager.Report) {
    val evaluation = report.evaluation
    val headline = when (evaluation.verdict) {
        LeakCheckPolicy.Verdict.OK -> R.string.leak_tester_ip_ok
        LeakCheckPolicy.Verdict.WARNING -> R.string.leak_tester_ip_warning
        LeakCheckPolicy.Verdict.LEAK -> R.string.leak_tester_ip_leak
        LeakCheckPolicy.Verdict.SKIPPED -> R.string.leak_test_verdict_skipped
    }
    val lines = buildList {
        report.tunnel?.let { tunnel ->
            add(
                if (tunnel.ip != null) {
                    stringResource(R.string.leak_tester_exit_ip, tunnel.ip, countryName(tunnel.country))
                } else {
                    stringResource(R.string.leak_tester_exit_ip_unknown)
                }
            )
        }
        report.direct?.ip?.let { real ->
            add(stringResource(R.string.leak_tester_real_ip, LeakCheckPolicy.maskIp(real) ?: "?", countryName(report.direct.country)))
        }
        evaluation.findings.forEach { add("• " + stringResource(NotificationManager.findingTextRes(it))) }
    }
    ResultCard(
        title = stringResource(R.string.leak_tester_ip_title),
        headline = stringResource(headline),
        lines = lines,
        isLeak = evaluation.verdict == LeakCheckPolicy.Verdict.LEAK,
    )
}

@Composable
private fun LocationResult(result: LocationLeakPolicy.Result, consistency: List<LocationSpoofPolicy.Finding>) {
    val headline = when (result.finding) {
        LocationLeakPolicy.Finding.NO_PERMISSION -> R.string.leak_tester_loc_no_permission
        LocationLeakPolicy.Finding.LOCATION_OFF -> R.string.leak_tester_loc_off
        LocationLeakPolicy.Finding.NO_FIX -> R.string.leak_tester_loc_no_fix
        LocationLeakPolicy.Finding.REAL_POSITION_VISIBLE -> R.string.leak_tester_loc_real_visible
        LocationLeakPolicy.Finding.VIRTUAL_NOT_APPLIED -> R.string.leak_tester_loc_virtual_not_applied
        LocationLeakPolicy.Finding.VIRTUAL_DETECTABLE -> R.string.leak_tester_loc_virtual_ok
    }
    val lines = buildList {
        result.distanceToTargetM?.let {
            add(stringResource(R.string.leak_tester_loc_distance, LocationLeakPolicy.distanceBucket(it) ?: ""))
        }
        consistency.forEach { add("• " + stringResource(locationFindingText(it))) }
    }
    ResultCard(
        title = stringResource(R.string.leak_tester_loc_title),
        headline = stringResource(headline),
        lines = lines,
        isLeak = result.verdict == LeakCheckPolicy.Verdict.LEAK,
    )
}

private fun locationFindingText(finding: LocationSpoofPolicy.Finding): Int = when (finding) {
    LocationSpoofPolicy.Finding.NOT_MOCK_APP -> R.string.location_finding_not_mock_app
    LocationSpoofPolicy.Finding.VPN_NOT_CONNECTED -> R.string.location_finding_vpn_off
    LocationSpoofPolicy.Finding.VPN_COUNTRY_MISMATCH -> R.string.location_finding_vpn_country
    LocationSpoofPolicy.Finding.TIME_ZONE_MISMATCH -> R.string.location_finding_time_zone
    LocationSpoofPolicy.Finding.SIM_COUNTRY_MISMATCH -> R.string.location_finding_sim
    LocationSpoofPolicy.Finding.NETWORK_COUNTRY_MISMATCH -> R.string.location_finding_network
    LocationSpoofPolicy.Finding.WIFI_SCANNING_ON -> R.string.location_finding_wifi_scan
}

private fun countryName(country: String?): String =
    country?.let { Locale("", it).displayCountry }?.takeIf { it.isNotBlank() } ?: "?"

@Composable
private fun ResultCard(title: String, headline: String, lines: List<String>, isLeak: Boolean) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp)
            .semantics { heading() },
    )
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (isLeak) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = headline, style = MaterialTheme.typography.bodyLarge)
            lines.forEach {
                Text(text = it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
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
