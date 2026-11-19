package com.v2ray.ang.handler

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Process
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkerParameters
import androidx.work.multiprocess.RemoteWorkManager
import com.v2ray.ang.AppConfig
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.service.LeakCheckPolicy
import com.v2ray.ang.service.LeakCheckPolicy.Evaluation
import com.v2ray.ang.service.LeakCheckPolicy.ProbeResult
import com.v2ray.ang.service.LeakCheckPolicy.Snapshot
import com.v2ray.ang.service.LeakCheckPolicy.Trigger
import com.v2ray.ang.service.LeakRiskPolicy
import com.v2ray.ang.util.JsonUtil
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.Inet6Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Proxy
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Runs IP leak checks, writes them to [LeakLog], and notifies when a leak is found.
 *
 * Two kinds of runs share this code: the periodic check inside the VPN service, which can also
 * test the tunnel's exit address, and a WorkManager watcher that runs in its own process every 15
 * minutes, survives reboots and a killed VPN process, and catches "the VPN should be on but is
 * not". Neither needs the other.
 */
object LeakCheckManager {

    /** Trace endpoints answering `ip=` and `loc=`; the second avoids DNS for networks that block it. */
    private val TRACE_URLS = listOf(
        "https://www.cloudflare.com/cdn-cgi/trace",
        "https://1.1.1.1/cdn-cgi/trace",
    )
    private const val PROBE_TIMEOUT_MS = 8_000L
    private const val VPN_LOOKUP_TIMEOUT_MS = 1_500L

    /** The real address changes rarely; reusing it keeps periodic checks from revealing it often. */
    private const val DIRECT_CACHE_MS = 30 * 60 * 1000L

    private const val WATCH_WORK_NAME = "leak_watch"
    private const val WATCH_INTERVAL_MINUTES = 15L

    /** Interface name prefixes Android uses for hotspot, USB, Ethernet-gadget and Bluetooth tethering. */
    private val TETHER_PREFIXES = listOf("ap", "swlan", "softap", "rndis", "ncm", "bt-pan")

    /** Interval values offered in the guide, in minutes; 0 turns checks off. */
    val INTERVAL_CHOICES = listOf(0, 1, 5, 15, 30)
    private const val DEFAULT_INTERVAL_MINUTES = 5

    private val directCache = ConcurrentHashMap<String, Pair<Long, ProbeResult>>()

    /**
     * State only the VPN service knows.
     *
     * @property killSwitchBlocking True while the kill switch holds a tunnel with no core.
     * @property coreHealthy True when the core runs or reloads.
     * @property socksPort The local SOCKS port the core listens on in this process.
     */
    data class ServiceState(
        val killSwitchBlocking: Boolean,
        val coreHealthy: Boolean,
        val socksPort: Int,
    )

    /** The configured interval between periodic checks, in minutes; 0 means off. */
    fun getIntervalMinutes(): Int {
        val stored = MmkvManager.decodeSettingsString(AppConfig.PREF_LEAK_CHECK_INTERVAL)?.toIntOrNull()
        return stored?.takeIf { it in INTERVAL_CHOICES } ?: DEFAULT_INTERVAL_MINUTES
    }

    /** Stores the interval between periodic checks, in minutes; 0 means off. */
    fun setIntervalMinutes(context: Context, minutes: Int) {
        MmkvManager.encodeSettings(AppConfig.PREF_LEAK_CHECK_INTERVAL, minutes.toString())
        if (minutes == 0) cancelWatcher(context)
    }

    /** True while the user has the VPN switched on, whether or not it is actually up. */
    fun isVpnExpected(): Boolean = MmkvManager.decodeSettingsBool(AppConfig.PREF_VPN_EXPECTED, false)

    /**
     * Records whether the user wants the VPN on.
     *
     * Set when the tunnel comes up, cleared only by a user stop: a crash, a kill or a reboot leaves
     * it set, which is how the watcher knows traffic is leaving while it should not.
     */
    fun setVpnExpected(context: Context, expected: Boolean) {
        MmkvManager.encodeSettings(AppConfig.PREF_VPN_EXPECTED, expected)
        if (expected) scheduleWatcher(context) else cancelWatcher(context)
    }

    /**
     * Runs one check, logs it when [LeakCheckPolicy.shouldLog] says so, and notifies on a new leak.
     *
     * @param context Any context.
     * @param trigger What started the check.
     * @param serviceState What the VPN service knows, or null outside the service.
     * @return The evaluation.
     */
    suspend fun runCheck(context: Context, trigger: Trigger, serviceState: ServiceState?): Evaluation =
        withContext(Dispatchers.IO) {
            val app = context.applicationContext
            val snapshot = takeSnapshot(app, trigger, serviceState)
            val evaluation = LeakCheckPolicy.evaluate(snapshot)

            val previous = MmkvManager.decodeSettingsString(AppConfig.PREF_LEAK_LAST_VERDICT)
                ?.let { stored -> LeakCheckPolicy.Verdict.entries.firstOrNull { it.name == stored } }
            if (LeakCheckPolicy.shouldLog(evaluation, previous, trigger)) {
                LeakLog.append(app, buildLogLine(trigger, snapshot, evaluation, serviceState))
            }
            if (evaluation.verdict != LeakCheckPolicy.Verdict.SKIPPED) {
                MmkvManager.encodeSettings(AppConfig.PREF_LEAK_LAST_VERDICT, evaluation.verdict.name)
                MmkvManager.encodeSettings(AppConfig.PREF_LEAK_LAST_CHECK_TIME, System.currentTimeMillis())
            }

            val lastNotified = MmkvManager.decodeSettingsString(AppConfig.PREF_LEAK_LAST_NOTIFIED)
            if (LeakCheckPolicy.shouldNotify(evaluation, lastNotified)) {
                NotificationManager.showLeakDetected(app, evaluation.findings)
            }
            // Forget the notified leak once it is gone, so the same leak notifies again if it returns.
            val signature = if (evaluation.verdict == LeakCheckPolicy.Verdict.LEAK) LeakCheckPolicy.signature(evaluation) else ""
            MmkvManager.encodeSettings(AppConfig.PREF_LEAK_LAST_NOTIFIED, signature)

            LogUtil.i(AppConfig.TAG, "LeakCheck: trigger=$trigger verdict=${evaluation.verdict} findings=${evaluation.findings}")
            evaluation
        }

    private suspend fun takeSnapshot(context: Context, trigger: Trigger, serviceState: ServiceState?): Snapshot {
        val vpnMode = SettingsManager.isVpnMode() && !SettingsManager.isRootMode()
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val vpnNetworks = if (connectivity != null) findVpnOwnership(connectivity) else null
        val ourVpnUp = when {
            serviceState != null -> true
            vpnNetworks == null -> null
            else -> vpnNetworks.first
        }
        val otherVpnUp = vpnNetworks?.second ?: false

        val tunnel = if (vpnMode && ourVpnUp != false) probeTunnel(serviceState) else null
        val direct = if (vpnMode) probeDirect(connectivity, forceFresh = trigger != Trigger.PERIODIC && trigger != Trigger.WATCHER) else null

        return Snapshot(
            vpnMode = vpnMode,
            vpnExpected = isVpnExpected(),
            ourVpnUp = ourVpnUp,
            otherVpnUp = otherVpnUp,
            killSwitchBlocking = serviceState?.killSwitchBlocking ?: false,
            tunnel = tunnel,
            direct = direct,
            ipv6Tunneled = MmkvManager.decodeSettingsBool(AppConfig.PREF_IPV6_ENABLED, false),
            underlyingGlobalIpv6 = connectivity?.let { hasGlobalIpv6(it) } ?: false,
            tetheringInterfaces = findTetheringInterfaces(),
            rootLanSharing = MmkvManager.decodeSettingsBool(AppConfig.PREF_ROOT_LAN_SHARING, false),
        )
    }

    /**
     * Finds VPN networks and whether this app owns them.
     *
     * Uses a short-lived network callback, which delivers every matching network at once, because
     * the one-shot network list is deprecated. Android 10+ shows a VPN's owner to its own app only,
     * which is enough to tell this app's VPN from another; below that every VPN counts as ours.
     *
     * @return (ours up, other up), or null when the lookup failed.
     */
    private suspend fun findVpnOwnership(connectivity: ConnectivityManager): Pair<Boolean, Boolean>? {
        val owners = ConcurrentHashMap<Network, Boolean>()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                owners[network] = isOwnedByUs(caps)
            }

            override fun onLost(network: Network) {
                owners.remove(network)
            }
        }
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        return try {
            connectivity.registerNetworkCallback(request, callback)
            delay(VPN_LOOKUP_TIMEOUT_MS)
            owners.values.any { it } to owners.values.any { !it }
        } catch (e: Exception) {
            LogUtil.w(AppConfig.TAG, "LeakCheck: VPN lookup failed", e)
            null
        } finally {
            try {
                connectivity.unregisterNetworkCallback(callback)
            } catch (_: Exception) {
            }
        }
    }

    private fun isOwnedByUs(caps: NetworkCapabilities): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return true
        return caps.ownerUid == Process.myUid()
    }

    /**
     * Asks the trace endpoint through the core's local SOCKS port, which follows the same routing
     * as traffic entering the tunnel.
     *
     * The UI and watcher processes cannot know a dynamic port, and OkHttp cannot authenticate to
     * SOCKS, so those cases return null (not probed) rather than a false failure.
     */
    private fun probeTunnel(serviceState: ServiceState?): ProbeResult? {
        if (SettingsManager.getSocksUsername() != null) return null
        val port = serviceState?.socksPort
            ?: if (MmkvManager.decodeSettingsBool(AppConfig.PREF_DYNAMIC_SOCKS_PORT, false)) return null
            else SettingsManager.getSocksPort()
        val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress(AppConfig.LOOPBACK, port))
        return fetchTrace(OkHttpClient.Builder().proxy(proxy))
    }

    /**
     * Asks the trace endpoint without the tunnel.
     *
     * This app is excluded from its own VPN, so a plain request leaves over the physical network
     * and shows the real address. The result is cached per network to reveal it rarely.
     */
    private fun probeDirect(connectivity: ConnectivityManager?, forceFresh: Boolean): ProbeResult {
        val key = connectivity?.activeNetwork?.toString() ?: "none"
        val cached = directCache[key]
        val now = System.currentTimeMillis()
        if (!forceFresh && cached != null && now - cached.first < DIRECT_CACHE_MS) return cached.second

        val result = fetchTrace(OkHttpClient.Builder().proxy(Proxy.NO_PROXY))
        if (result.ip != null) directCache[key] = now to result
        return result
    }

    private fun fetchTrace(builder: OkHttpClient.Builder): ProbeResult {
        val client = builder
            .connectTimeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .callTimeout(PROBE_TIMEOUT_MS * 2, TimeUnit.MILLISECONDS)
            .build()
        var lastError = "no endpoint answered"
        for (url in TRACE_URLS) {
            try {
                val request = Request.Builder().url(url).header("Connection", "close").build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        lastError = "http ${response.code}"
                    } else {
                        val parsed = LeakCheckPolicy.parseTrace(response.body?.string())
                        if (parsed.ip != null) return parsed
                        lastError = parsed.error ?: lastError
                    }
                }
            } catch (e: Exception) {
                lastError = e.javaClass.simpleName
            }
        }
        return ProbeResult(null, null, lastError)
    }

    private fun hasGlobalIpv6(connectivity: ConnectivityManager): Boolean {
        val properties: LinkProperties = connectivity.getLinkProperties(connectivity.activeNetwork) ?: return false
        return properties.linkAddresses.any { link ->
            val address = link.address
            address is Inet6Address &&
                    !address.isLinkLocalAddress &&
                    !address.isLoopbackAddress &&
                    !address.isSiteLocalAddress &&
                    (address.address[0].toInt() and 0xfe) != 0xfc // fc00::/7 unique local
        }
    }

    /**
     * Lists up tethering interfaces by name.
     *
     * Apps cannot read the tethering state directly, so this relies on the interface names
     * Android and vendors use for hotspot, USB and Bluetooth tethering; a vendor using another
     * name is missed, which only means a missing warning.
     */
    private fun findTetheringInterfaces(): List<String> {
        return try {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .filter { iface ->
                    iface.isUp && !iface.isLoopback &&
                            TETHER_PREFIXES.any { iface.name.startsWith(it) } &&
                            iface.inetAddresses.toList().isNotEmpty()
                }
                .map { it.name }
                .sorted()
        } catch (e: Exception) {
            LogUtil.w(AppConfig.TAG, "LeakCheck: interface listing failed", e)
            emptyList()
        }
    }

    private fun buildLogLine(trigger: Trigger, snapshot: Snapshot, evaluation: Evaluation, serviceState: ServiceState?): String {
        val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US).apply { timeZone = TimeZone.getDefault() }
        fun probe(result: ProbeResult?) = result?.let {
            mapOf("ip" to LeakCheckPolicy.maskIp(it.ip), "country" to it.country, "error" to it.error)
        }
        val entry = linkedMapOf(
            "ts" to format.format(Date()),
            "trigger" to trigger.name,
            "verdict" to evaluation.verdict.name,
            "findings" to evaluation.findings.map { it.name },
            "vpnMode" to snapshot.vpnMode,
            "rootMode" to SettingsManager.isRootMode(),
            "killSwitch" to SettingsManager.isKillSwitchEnabled(),
            "lockdown" to SettingsManager.getLastLockdownState().name,
            "vpnExpected" to snapshot.vpnExpected,
            "ourVpnUp" to snapshot.ourVpnUp,
            "otherVpnUp" to snapshot.otherVpnUp,
            "killSwitchBlocking" to snapshot.killSwitchBlocking,
            "coreHealthy" to serviceState?.coreHealthy,
            "tunnel" to probe(snapshot.tunnel),
            "direct" to probe(snapshot.direct),
            "sameIp" to LeakCheckPolicy.isSameAddress(snapshot.tunnel?.ip, snapshot.direct?.ip),
            "ipv6Tunneled" to snapshot.ipv6Tunneled,
            "underlyingGlobalIpv6" to snapshot.underlyingGlobalIpv6,
            "tethering" to snapshot.tetheringInterfaces,
            "rootLanSharing" to snapshot.rootLanSharing,
            "risks" to LeakRiskPolicy.risks(SettingsManager.getLeakRiskInputs()).map { it.name },
            "server" to MmkvManager.getSelectServer(),
            "app" to BuildConfig.VERSION_NAME,
            "sdk" to Build.VERSION.SDK_INT,
        )
        return JsonUtil.toJson(entry)
    }

    private fun scheduleWatcher(context: Context) {
        if (getIntervalMinutes() == 0) return
        try {
            val request = PeriodicWorkRequestBuilder<WatchWorker>(WATCH_INTERVAL_MINUTES, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            RemoteWorkManager.getInstance(context.applicationContext)
                .enqueueUniquePeriodicWork(WATCH_WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "LeakCheck: watcher scheduling failed", e)
        }
    }

    private fun cancelWatcher(context: Context) {
        try {
            RemoteWorkManager.getInstance(context.applicationContext).cancelUniqueWork(WATCH_WORK_NAME)
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "LeakCheck: watcher cancel failed", e)
        }
    }

    /**
     * Background watcher: runs every 15 minutes in the WorkManager process, also after a reboot or
     * when the VPN process was killed, and reports a VPN that should be up but is not.
     */
    class WatchWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result {
            if (getIntervalMinutes() == 0 || !isVpnExpected()) return Result.success()
            try {
                runCheck(applicationContext, Trigger.WATCHER, serviceState = null)
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "LeakCheck: watcher run failed", e)
            }
            return Result.success()
        }
    }
}
