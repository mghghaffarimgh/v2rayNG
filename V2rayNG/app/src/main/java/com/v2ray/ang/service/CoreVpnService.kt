package com.v2ray.ang.service

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Network
import android.net.ProxyInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.StrictMode
import com.v2ray.ang.AppConfig
import com.v2ray.ang.AppConfig.LOOPBACK
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.contracts.ServiceControl
import com.v2ray.ang.contracts.Tun2SocksControl
import com.v2ray.ang.core.CoreServiceManager
import com.v2ray.ang.handler.AppLocaleManager
import com.v2ray.ang.handler.LeakCheckManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.NotificationManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.root.RootLanSharing
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.Utils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.lang.ref.SoftReference
import java.util.concurrent.atomic.AtomicBoolean

@SuppressLint("VpnServicePolicy")
class CoreVpnService : VpnService(), ServiceControl {
    private lateinit var mInterface: ParcelFileDescriptor
    private var isRunning = false
    private var tun2SocksService: Tun2SocksControl? = null
    private val isStartingLock = AtomicBoolean(false)

    /** Scope owning every coroutine this service starts; cancelled on teardown and destroy. */
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var killSwitchJob: Job? = null
    private var leakCheckJob: Job? = null

    @Volatile
    private var isTearingDown = false

    @Volatile
    private var isKillSwitchBlocking = false

    override fun onCreate() {
        super.onCreate()
        LogUtil.i(AppConfig.TAG, "StartCore-VPN: Service created")
        val policy = StrictMode.ThreadPolicy.Builder().permitAll().build()
        StrictMode.setThreadPolicy(policy)
        CoreServiceManager.serviceControl = SoftReference(this)
    }

    override fun onRevoke() {
        LogUtil.w(AppConfig.TAG, "StartCore-VPN: Permission revoked")
        // Android hands the VPN slot to one app at a time, so this is also what happens when
        // another VPN app takes over. With the kill switch on, that is a bypass worth saying out
        // loud: our tunnel is gone and somebody else is carrying the traffic.
        if (SettingsManager.isKillSwitchEnabled()) {
            LogUtil.w(AppConfig.TAG, "StartCore-VPN: kill switch was on; the VPN slot was taken")
            NotificationManager.showKillSwitchTakenOver()
        }
        stopAllService()
    }

    /**
     * Reads the system lockdown state, stores it, and tells the user about every leak path that
     * applies to the current settings.
     *
     * The system lockdown ("Always-on VPN" with "Block connections without VPN") is the only
     * protection that holds across a reboot, a killed process and another VPN app taking the VPN
     * slot; only the user can turn it on. The in-app kill switch covers a core failure only, so
     * every other path is named instead of left for the user to discover.
     *
     * Runs in [serviceScope]: it reads settings and the selected server, and asks the system over
     * binder, none of which belongs on the main thread.
     */
    private fun reportLeakRisks() {
        serviceScope.launch {
            try {
                val state = readLockdownState()
                SettingsManager.setLastLockdownState(state)

                val risks = LeakRiskPolicy.risks(SettingsManager.getLeakRiskInputs())
                if (!LeakRiskPolicy.shouldWarn(risks, SettingsManager.isLeakWarningEnabled())) return@launch
                LogUtil.w(AppConfig.TAG, "StartCore-VPN: leak paths open, lockdown=$state risks=$risks")
                NotificationManager.showLeakRisks(risks)
            } catch (e: Exception) {
                LogUtil.w(AppConfig.TAG, "StartCore-VPN: could not report leak paths", e)
            }
        }
    }

    /**
     * Runs a leak check shortly after the tunnel comes up, then every configured interval while
     * it stays up. A newer run replaces an older one.
     */
    private fun startLeakChecks() {
        leakCheckJob?.cancel()
        val interval = LeakCheckManager.getIntervalMinutes()
        if (interval == 0) return

        leakCheckJob = serviceScope.launch {
            // Give the core a moment to start serving before its exit is tested.
            delay(LEAK_CHECK_FIRST_DELAY_MS)
            var trigger = LeakCheckPolicy.Trigger.TUNNEL_UP
            while (isActive && !isTearingDown && isRunning) {
                try {
                    val state = LeakCheckManager.ServiceState(
                        killSwitchBlocking = isKillSwitchBlocking,
                        coreHealthy = CoreServiceManager.isCoreHealthy(),
                        socksPort = SettingsManager.getSocksPort(),
                    )
                    LeakCheckManager.runCheck(this@CoreVpnService, trigger, state)
                } catch (e: Exception) {
                    LogUtil.e(AppConfig.TAG, "StartCore-VPN: leak check failed, trigger=$trigger", e)
                }
                trigger = LeakCheckPolicy.Trigger.PERIODIC
                delay(interval * 60_000L)
            }
        }
    }

    /**
     * Asks the system whether it blocks traffic outside the VPN.
     *
     * @return The lockdown state; unknown below Android 10, which does not report it.
     */
    private fun readLockdownState(): LeakRiskPolicy.LockdownState {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return LeakRiskPolicy.lockdownState(supported = false, alwaysOn = false, lockdown = false)
        }
        return LeakRiskPolicy.lockdownState(supported = true, alwaysOn = isAlwaysOn, lockdown = isLockdownEnabled)
    }

//    override fun onLowMemory() {
//        stopV2Ray()
//        super.onLowMemory()
//    }

    override fun onDestroy() {
        super.onDestroy()
        LogUtil.i(AppConfig.TAG, "StartCore-VPN: Service destroyed")

        // Ensure VPN interface is properly closed when the service is destroyed without
        // going through stopAllService() (e.g. when killed unexpectedly). isRunning is
        // set to false at the start of stopAllService(), so this guard prevents a double-close.
        if (isRunning) {
            try {
                if (::mInterface.isInitialized) {
                    mInterface.close()
                    LogUtil.i(AppConfig.TAG, "StartCore-VPN: VPN interface closed in onDestroy")
                }
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "StartCore-VPN: Failed to close interface in onDestroy", e)
            }
        }

        unlockStart()
        NotificationManager.cancelNotification()
        serviceScope.cancel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        NotificationManager.ensureForeground()
        // Android reuses this instance for a restart, so a teardown flag left by the previous run
        // would keep the kill switch disarmed for the whole new run.
        isTearingDown = false
        // Always-on VPN restarts from OS deliver intent.action == SERVICE_INTERFACE or null intent.
        // Reset any stuck start lock left by a killed process to allow setupVpnService() to run.
        val isSystemVpnStart = intent == null || intent.action == SERVICE_INTERFACE
        if (isSystemVpnStart) {
            unlockStart()
        }
        if (!tryLockStart()) {
            LogUtil.w(AppConfig.TAG, "StartCore-VPN: Start already in progress")
            return START_NOT_STICKY
        }
        LogUtil.i(AppConfig.TAG, "StartCore-VPN: Service command received, systemVpnStart=$isSystemVpnStart")
        if (!setupVpnService()) {
            unlockStart()
            // With no interface there is nothing to hold traffic back, so the kill switch cannot
            // keep its promise; say so instead of stopping silently.
            if (SettingsManager.isKillSwitchEnabled()) {
                NotificationManager.showKillSwitchSetupFailed()
            }
            // Stop service if setup fails to avoid infinite restart loops (START_STICKY)
            stopSelf()
            return START_NOT_STICKY
        }
        startService()
        reportLeakRisks()
        // The tunnel is established (serving or blocking): from now on, until the user stops it,
        // a missing VPN means traffic is leaving outside it. Settings and WorkManager stay off
        // the main thread.
        serviceScope.launch {
            try {
                LeakCheckManager.setVpnExpected(this@CoreVpnService, true)
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "StartCore-VPN: could not record the VPN as expected", e)
            }
        }
        startLeakChecks()
        return START_STICKY
    }

    override fun getService(): Service {
        return this
    }

    override fun startService() {
        if (!::mInterface.isInitialized) {
            LogUtil.e(AppConfig.TAG, "StartCore-VPN: Interface not initialized")
            return
        }
        if (!CoreServiceManager.startCoreLoop(mInterface)) {
            LogUtil.e(AppConfig.TAG, "StartCore-VPN: Failed to start core loop")
            if (!enterKillSwitchBlocking("core start failed")) {
                stopAllService()
            }
            return
        }

        exitKillSwitchBlocking()

        // Start LAN sharing if enabled in settings
        RootLanSharing.startClientSharing(this)

        startKillSwitchWatchdog()
    }

    override fun stopService() {
        stopAllService(true)
    }

    override fun vpnProtect(socket: Int): Boolean {
        return protect(socket)
    }

    override fun setUnderlyingNetworks(networks: Array<Network>?): Boolean {
        return super<VpnService>.setUnderlyingNetworks(networks)
    }

    override fun attachBaseContext(newBase: Context?) {
        val context = newBase?.let(AppLocaleManager::localizedContext)
        super.attachBaseContext(context)
    }

    /**
     * Sets up the VPN service.
     * Prepares the VPN and configures it if preparation is successful.
     */
    private fun setupVpnService(): Boolean {
        val prepare = prepare(this)
        if (prepare != null) {
            LogUtil.e(AppConfig.TAG, "StartCore-VPN: Permission not granted")
            return false
        }

        if (configureVpnService() != true) {
            LogUtil.e(AppConfig.TAG, "StartCore-VPN: Configuration failed")
            return false
        }

        runTun2socks()
        return true
    }

    /**
     * Configures the VPN service.
     * @return True if the VPN service was configured successfully, false otherwise.
     */
    private fun configureVpnService(): Boolean {
        val builder = Builder()

        // Configure network settings (addresses, routing and DNS)
        configureNetworkSettings(builder)

        // Configure app-specific settings (session name and per-app proxy)
        configurePerAppProxy(builder)

        // Configure platform-specific features
        configurePlatformFeatures(builder)

        // Establish the new interface before closing the old one. Closing first leaves a moment
        // with no VPN interface at all, and the system routes traffic over the physical network
        // during it; establishing while the old one is open hands the routes over in one step.
        val oldInterface = if (::mInterface.isInitialized) mInterface else null
        try {
            mInterface = builder.establish()!!
            isRunning = true
            closeReplacedInterface(oldInterface)
            return true
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to establish VPN interface", e)
            stopAllService()
        }
        return false
    }

    /**
     * Closes the interface a new one replaced, once the new one carries the routes.
     *
     * @param oldInterface The replaced interface, or null when there was none.
     */
    private fun closeReplacedInterface(oldInterface: ParcelFileDescriptor?) {
        if (oldInterface == null) return
        try {
            oldInterface.close()
        } catch (e: Exception) {
            LogUtil.w(AppConfig.TAG, "StartCore-VPN: Failed to close the replaced interface", e)
        }
    }

    /**
     * Configures the basic network settings for the VPN.
     * This includes IP addresses, routing rules, and DNS servers.
     *
     * @param builder The VPN Builder to configure
     */
    private fun configureNetworkSettings(builder: Builder) {
        val vpnConfig = SettingsManager.getCurrentVpnInterfaceAddressConfig()
        val bypassLan = SettingsManager.routingRulesetsBypassLan()

        // Configure IPv4 settings
        builder.setMtu(SettingsManager.getVpnMtu())
        builder.addAddress(vpnConfig.ipv4Client, 30)

        // Configure routing rules
        if (bypassLan) {
            AppConfig.ROUTED_IP_LIST.forEach {
                val addr = it.split('/')
                builder.addRoute(addr[0], addr[1].toInt())
            }
        } else {
            builder.addRoute("0.0.0.0", 0)
        }

        // Configure IPv6 if enabled
        if (MmkvManager.decodeSettingsBool(AppConfig.PREF_IPV6_ENABLED) == true) {
            builder.addAddress(vpnConfig.ipv6Client, 126)
            if (bypassLan) {
                builder.addRoute("2000::", 3) // Currently only 1/8 of total IPv6 is in use
                builder.addRoute("fc00::", 18) // Xray-core default FakeIPv6 Pool
            } else {
                builder.addRoute("::", 0)
            }
        }

        // Configure DNS servers
        //if (MmkvManager.decodeSettingsBool(AppConfig.PREF_LOCAL_DNS_ENABLED) == true) {
        //  builder.addDnsServer(PRIVATE_VLAN4_ROUTER)
        //} else {
        SettingsManager.getVpnDnsServers().forEach {
            if (Utils.isPureIpAddress(it)) {
                builder.addDnsServer(it)
            }
        }

        //builder.setSession(V2RayServiceManager.getRunningServerName())
    }

    /**
     * Configures platform-specific VPN features for different Android versions.
     *
     * @param builder The VPN Builder to configure
     */
    private fun configurePlatformFeatures(builder: Builder) {
        // Android Q (API 29) and above: Configure metering and HTTP proxy
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
            if (MmkvManager.decodeSettingsBool(AppConfig.PREF_APPEND_HTTP_PROXY)) {
                builder.setHttpProxy(ProxyInfo.buildDirectProxy(LOOPBACK, SettingsManager.getHttpPort()))
            }
        }
    }

    /**
     * Configures per-app proxy rules for the VPN builder.
     *
     * - If per-app proxy is not enabled, disallow the VPN service's own package.
     * - If no apps are selected, disallow the VPN service's own package.
     * - If bypass mode is enabled, disallow all selected apps (including self).
     * - If proxy mode is enabled, only allow the selected apps (excluding self).
     *
     * @param builder The VPN Builder to configure.
     */
    private fun configurePerAppProxy(builder: Builder) {
        val selfPackageName = BuildConfig.APPLICATION_ID

        // If per-app proxy is not enabled, disallow the VPN service's own package and return
        if (MmkvManager.decodeSettingsBool(AppConfig.PREF_PER_APP_PROXY) == false) {
            builder.addDisallowedApplication(selfPackageName)
            return
        }

        // If no apps are selected, disallow the VPN service's own package and return
        val apps = MmkvManager.decodeSettingsStringSet(AppConfig.PREF_PER_APP_PROXY_SET)
        if (apps.isNullOrEmpty()) {
            builder.addDisallowedApplication(selfPackageName)
            return
        }

        val bypassApps = MmkvManager.decodeSettingsBool(AppConfig.PREF_BYPASS_APPS)
        // Handle the VPN service's own package according to the mode
        if (bypassApps) apps.add(selfPackageName) else apps.remove(selfPackageName)

        apps.forEach {
            try {
                if (bypassApps) {
                    // In bypass mode, disallow the selected apps
                    builder.addDisallowedApplication(it)
                } else {
                    // In proxy mode, only allow the selected apps
                    builder.addAllowedApplication(it)
                }
            } catch (e: PackageManager.NameNotFoundException) {
                LogUtil.e(AppConfig.TAG, "StartCore-VPN: Failed to configure app", e)
            }
        }
    }

    /**
     * Runs the tun2socks process.
     * Starts the tun2socks process with the appropriate parameters.
     */
    private fun runTun2socks() {
        if (SettingsManager.isUsingHevTun()) {
            tun2SocksService = TProxyService(
                context = applicationContext,
                vpnInterface = mInterface,
                isRunningProvider = { isRunning },
                restartCallback = { runTun2socks() }
            )
        } else {
            tun2SocksService = null
        }

        tun2SocksService?.startTun2Socks()
    }

    /**
     * Keeps the tunnel open with no core behind it, so packets the system routes into the VPN
     * interface are dropped instead of leaving over the physical network.
     *
     * @param reason Why the core is gone, for the log.
     * @return True if the kill switch took the tunnel over, false if the caller must tear it down.
     */
    private fun enterKillSwitchBlocking(reason: String): Boolean {
        val keepTunnel = KillSwitchPolicy.shouldKeepTunnel(
            reason = KillSwitchPolicy.TeardownReason.CORE_FAILURE,
            killSwitchEnabled = SettingsManager.isKillSwitchEnabled(),
        )
        // With no established interface there is nothing holding the traffic back, so blocking
        // would only keep a dead service alive.
        if (!keepTunnel || isTearingDown || !::mInterface.isInitialized) return false

        if (!isKillSwitchBlocking) {
            isKillSwitchBlocking = true
            LogUtil.w(AppConfig.TAG, "StartCore-VPN: Kill switch blocking traffic, $reason")
            NotificationManager.showKillSwitchBlocking()
        }
        // The failed attempt owns the start lock; release it so the watchdog and a manual start
        // command can both retry on this same service instance.
        unlockStart()
        startKillSwitchWatchdog()
        return true
    }

    /**
     * Releases the blocked state once the core serves traffic again.
     */
    private fun exitKillSwitchBlocking() {
        if (!isKillSwitchBlocking) return

        isKillSwitchBlocking = false
        LogUtil.i(AppConfig.TAG, "StartCore-VPN: Kill switch released, core is serving traffic")
    }

    /**
     * Samples core health while the tunnel is up and restarts the core behind a blocked tunnel.
     *
     * The native core has no death callback, so health is polled. A reload after a network
     * handover is not a failure, which is why [CoreServiceManager.isCoreHealthy] is used instead
     * of the running flag.
     */
    private fun startKillSwitchWatchdog() {
        if (!SettingsManager.isKillSwitchEnabled()) return
        if (killSwitchJob?.isActive == true) return

        killSwitchJob = serviceScope.launch {
            var attempt = 0
            while (isActive) {
                delay(KillSwitchPolicy.HEALTH_CHECK_INTERVAL_MS)
                if (isTearingDown || !isRunning) break

                // Turning the setting off while traffic is blocked must give the device its
                // network back instead of leaving a tunnel nothing serves.
                if (isKillSwitchBlocking && !SettingsManager.isKillSwitchEnabled()) {
                    LogUtil.w(AppConfig.TAG, "StartCore-VPN: Kill switch turned off while blocking, stopping service")
                    stopAllService()
                    break
                }

                val canRecover = KillSwitchPolicy.shouldRecover(
                    coreHealthy = CoreServiceManager.isCoreHealthy(),
                    isTearingDown = isTearingDown,
                    killSwitchEnabled = SettingsManager.isKillSwitchEnabled(),
                )
                if (!canRecover) {
                    attempt = 0
                    continue
                }
                if (!enterKillSwitchBlocking("core stopped unexpectedly")) break

                attempt += 1
                delay(KillSwitchPolicy.retryDelayMillis(attempt))
                if (isTearingDown || !isRunning) break
                if (CoreServiceManager.isCoreHealthy()) continue

                // A manual start command may be setting the tunnel up right now; that attempt
                // owns the lock and this one is skipped instead of racing it.
                if (!tryLockStart()) continue

                LogUtil.i(AppConfig.TAG, "StartCore-VPN: Kill switch recovery attempt $attempt")
                try {
                    if (CoreServiceManager.startCoreLoop(mInterface)) {
                        exitKillSwitchBlocking()
                        attempt = 0
                    }
                } finally {
                    unlockStart()
                }
            }
        }
    }

    private fun stopAllService(isForced: Boolean = true) {
//        val configName = defaultDPreference.getPrefString(PREF_CURR_CONFIG_GUID, "")
//        val emptyInfo = VpnNetworkInfo()
//        val info = loadVpnNetworkInfo(configName, emptyInfo)!! + (lastNetworkInfo ?: emptyInfo)
//        saveVpnNetworkInfo(configName, info)
        isTearingDown = true
        killSwitchJob?.cancel()
        killSwitchJob = null
        leakCheckJob?.cancel()
        leakCheckJob = null
        isKillSwitchBlocking = false

        unlockStart()
        isRunning = false

        tun2SocksService?.stopTun2Socks()
        tun2SocksService = null

        RootLanSharing.stopClientSharing(this)

        CoreServiceManager.stopCoreLoop()

        if (isForced) {
            //stopSelf has to be called ahead of mInterface.close(). otherwise v2ray core cannot be stooped
            //It's strage but true.
            //This can be verified by putting stopself() behind and call stopLoop and startLoop
            //in a row for several times. You will find that later created v2ray core report port in use
            //which means the first v2ray core somehow failed to stop and release the port.
            stopSelf()

            // Add a small delay to allow the async core stop operation to complete
            // before closing the VPN interface, preventing a race condition that can
            // leave the VPN icon in the status bar after stopping the service.
            try {
                Thread.sleep(100)
            } catch (e: InterruptedException) {
                LogUtil.w(AppConfig.TAG, "StartCore-VPN: Sleep interrupted", e)
            }

            try {
                if (::mInterface.isInitialized) {
                    mInterface.close()
                    LogUtil.i(AppConfig.TAG, "StartCore-VPN: VPN interface closed")
                }
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "StartCore-VPN: Failed to close interface", e)
            }
        }
    }

    fun tryLockStart(): Boolean {
        LogUtil.w(AppConfig.TAG, "StartCore-VPN: tryLockStart: ${isStartingLock.get()}")
        return isStartingLock.compareAndSet(false, true)
    }

    fun unlockStart() {
        isStartingLock.set(false)
        LogUtil.w(AppConfig.TAG, "StartCore-VPN: unlockStart")
    }

    private companion object {
        /** Delay between the tunnel coming up and its first leak check. */
        const val LEAK_CHECK_FIRST_DELAY_MS = 8_000L
    }
}
