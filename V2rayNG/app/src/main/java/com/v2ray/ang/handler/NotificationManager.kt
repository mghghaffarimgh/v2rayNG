package com.v2ray.ang.handler

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.provider.Settings
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.core.CoreServiceManager
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.extension.delay
import com.v2ray.ang.extension.toSpeedString
import com.v2ray.ang.helper.NotificationHelper
import com.v2ray.ang.service.LeakRiskPolicy
import com.v2ray.ang.ui.leakguide.LeakGuideActivity
import com.v2ray.ang.ui.main.MainActivity
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.min

object NotificationManager {
    private const val NOTIFICATION_ID = 1
    private const val NOTIFICATION_PENDING_INTENT_CONTENT = 0
    private const val NOTIFICATION_PENDING_INTENT_STOP_V2RAY = 1
    private const val NOTIFICATION_PENDING_INTENT_RESTART_V2RAY = 2
    private const val NOTIFICATION_PENDING_INTENT_VPN_SETTINGS = 3
    private const val NOTIFICATION_PENDING_INTENT_LEAK_GUIDE = 4

    // One-off leak notices; ids 12 and 13 belong to NotificationChannelType.
    private const val NOTIFICATION_ID_LEAK_RISKS = 2
    private const val NOTIFICATION_ID_TAKEN_OVER = 3
    private const val NOTIFICATION_ID_SETUP_FAILED = 4
    private const val NOTIFICATION_ICON_THRESHOLD = 3000
    private const val QUERY_INTERVAL_MS = 3000L

    private var lastQueryTime = 0L
    private var mBuilder: NotificationCompat.Builder? = null
    private var speedNotificationJob: Job? = null
    private var mNotificationManager: NotificationManager? = null

    /**
     * Starts the speed notification.
     * @param currentConfig The current profile configuration.
     */
    fun startSpeedNotification() {
        if (MmkvManager.decodeSettingsBool(AppConfig.PREF_SPEED_ENABLED) != true) return
        if (speedNotificationJob != null || CoreServiceManager.isRunning() == false) return

        var lastZeroSpeed = false

        speedNotificationJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive) {
                lastZeroSpeed = updateSpeedNotificationOnce(lastZeroSpeed)
                delay(QUERY_INTERVAL_MS)
            }
        }
    }

    /**
     * Shows the notification.
     * @param currentConfig The current profile configuration.
     */
    fun showNotification(currentConfig: ProfileItem?) {
        val service = getService() ?: return

        // Reset last query time to avoid querying stats too soon after showing the notification
        lastQueryTime = System.currentTimeMillis()

        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT

        val startMainIntent = Intent(service, MainActivity::class.java)
        val contentPendingIntent = PendingIntent.getActivity(service, NOTIFICATION_PENDING_INTENT_CONTENT, startMainIntent, flags)

        val stopV2RayIntent = Intent(AppConfig.BROADCAST_ACTION_SERVICE)
        stopV2RayIntent.`package` = AppConfig.ANG_PACKAGE
        stopV2RayIntent.putExtra("key", AppConfig.MSG_STATE_STOP)
        val stopV2RayPendingIntent = PendingIntent.getBroadcast(service, NOTIFICATION_PENDING_INTENT_STOP_V2RAY, stopV2RayIntent, flags)

        val restartV2RayIntent = Intent(AppConfig.BROADCAST_ACTION_SERVICE)
        restartV2RayIntent.`package` = AppConfig.ANG_PACKAGE
        restartV2RayIntent.putExtra("key", AppConfig.MSG_STATE_RESTART)
        val restartV2RayPendingIntent = PendingIntent.getBroadcast(service, NOTIFICATION_PENDING_INTENT_RESTART_V2RAY, restartV2RayIntent, flags)

        val channelId =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                createNotificationChannel()
            } else {
                // If earlier version channel ID is not used
                // https://developer.android.com/reference/android/support/v4/app/NotificationCompat.Builder.html#NotificationCompat.Builder(android.content.Context)
                ""
            }

        mBuilder = NotificationCompat.Builder(service, channelId)
            .setSmallIcon(R.drawable.ic_stat_name)
            .setContentTitle(currentConfig?.remarks ?: service.getString(R.string.app_name))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentPendingIntent)
            .addAction(
                R.drawable.ic_delete_24dp,
                service.getString(R.string.notification_action_stop_v2ray),
                stopV2RayPendingIntent
            )
            .addAction(
                R.drawable.ic_restore_24dp,
                service.getString(R.string.title_service_restart),
                restartV2RayPendingIntent
            )

        //mBuilder?.setDefaults(NotificationCompat.FLAG_ONLY_ALERT_ONCE)

        service.startForeground(NOTIFICATION_ID, mBuilder?.build())
    }

    /**
     * Fulfills or refreshes the foreground-service contract before a start command can
     * return early. A duplicate startForegroundService call still requires the service
     * to enter foreground state promptly, even when the core is already running.
     */
    fun ensureForeground() {
        val service = getService() ?: return
        val notification = mBuilder?.build()
        if (notification == null) showNotification(null) else service.startForeground(NOTIFICATION_ID, notification)
    }

    /**
     * Switches the ongoing notification to the kill switch blocked state.
     *
     * The tunnel is still up while the core is gone, so the speed loop has nothing to report and
     * is stopped until the core serves traffic again.
     */
    fun showKillSwitchBlocking() {
        val service = getService() ?: return
        if (mBuilder == null) showNotification(null)

        speedNotificationJob?.cancel()
        speedNotificationJob = null
        updateNotification(service.getString(R.string.notification_kill_switch_blocking), 0, 0)
    }

    /**
     * Lists the leak paths open for the current settings, one line each.
     *
     * Tapping opens the leak guide, which explains every path and how to close it. When the
     * system lockdown is off, a second action opens the Android VPN settings directly, since that
     * is the one fix only the user can make.
     *
     * @param risks The open paths, most severe first; nothing is posted when empty.
     */
    fun showLeakRisks(risks: List<LeakRiskPolicy.Risk>) {
        if (risks.isEmpty()) return
        val context = getService()?.applicationContext ?: return
        val text = risks.joinToString("\n") { "• " + context.getString(riskTextRes(it)) }
        val vpnSettings = if (LeakRiskPolicy.Risk.SYSTEM_LOCKDOWN_OFF in risks) {
            vpnSettingsPendingIntent(context)
        } else {
            null
        }
        postLeakNotice(
            context,
            id = NOTIFICATION_ID_LEAK_RISKS,
            title = context.getString(R.string.notification_leak_risks_title),
            text = text,
            vpnSettings = vpnSettings,
        )
    }

    /**
     * Says that another VPN app took the VPN slot while the kill switch was on.
     */
    fun showKillSwitchTakenOver() {
        val context = getService()?.applicationContext ?: return
        postLeakNotice(
            context,
            id = NOTIFICATION_ID_TAKEN_OVER,
            title = context.getString(R.string.notification_kill_switch_taken_over_title),
            text = context.getString(R.string.notification_kill_switch_taken_over),
            vpnSettings = vpnSettingsPendingIntent(context),
        )
    }

    /**
     * Says that the tunnel could not be set up while the kill switch was on, so nothing holds
     * traffic back.
     */
    fun showKillSwitchSetupFailed() {
        val context = getService()?.applicationContext ?: return
        postLeakNotice(
            context,
            id = NOTIFICATION_ID_SETUP_FAILED,
            title = context.getString(R.string.notification_kill_switch_setup_failed_title),
            text = context.getString(R.string.notification_kill_switch_setup_failed),
            vpnSettings = vpnSettingsPendingIntent(context),
        )
    }

    /**
     * Maps a leak path to its one-line description.
     *
     * @param risk The leak path.
     * @return The string resource naming it.
     */
    fun riskTextRes(risk: LeakRiskPolicy.Risk): Int = when (risk) {
        LeakRiskPolicy.Risk.KILL_SWITCH_OFF -> R.string.leak_risk_kill_switch_off
        LeakRiskPolicy.Risk.SYSTEM_LOCKDOWN_OFF -> R.string.leak_risk_system_lockdown_off
        LeakRiskPolicy.Risk.PER_APP_BYPASS -> R.string.leak_risk_per_app_bypass
        LeakRiskPolicy.Risk.PER_APP_ALLOW_LIST -> R.string.leak_risk_per_app_allow_list
        LeakRiskPolicy.Risk.BYPASS_LAN -> R.string.leak_risk_bypass_lan
    }

    private fun vpnSettingsPendingIntent(context: Context): PendingIntent {
        val intent = Intent(Settings.ACTION_VPN_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return PendingIntent.getActivity(
            context, NOTIFICATION_PENDING_INTENT_VPN_SETTINGS, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /**
     * Posts a leak notice on the leak alert channel.
     *
     * The service channel is silent by design, which is right for the ongoing notification and
     * wrong for a warning that traffic may be leaving outside the tunnel, so leak notices use a
     * channel of their own that the user can tune separately.
     */
    private fun postLeakNotice(
        context: Context,
        id: Int,
        title: String,
        text: String,
        vpnSettings: PendingIntent?,
    ) {
        try {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            NotificationHelper.ensureNotificationChannel(
                context = context,
                channelId = AppConfig.LEAK_ALERT_CHANNEL_ID,
                channelNameRes = R.string.notification_channel_leak_alerts,
                importance = NotificationManager.IMPORTANCE_HIGH,
            )
            val guideIntent = Intent(context, LeakGuideActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val guide = PendingIntent.getActivity(
                context, NOTIFICATION_PENDING_INTENT_LEAK_GUIDE, guideIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val builder = NotificationCompat.Builder(context, AppConfig.LEAK_ALERT_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_name)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_ERROR)
                .setAutoCancel(true)
                .setContentIntent(guide)
                .addAction(0, context.getString(R.string.action_leak_guide), guide)
            if (vpnSettings != null) {
                builder.addAction(0, context.getString(R.string.title_system_vpn_settings), vpnSettings)
            }
            manager.notify(id, builder.build())
        } catch (e: Exception) {
            LogUtil.w(AppConfig.TAG, "Notification: could not post leak notice id=$id", e)
        }
    }

    /**
     * Cancels the notification.
     */
    fun cancelNotification() {
        val service = getService() ?: return
        service.stopForeground(Service.STOP_FOREGROUND_REMOVE)

        mBuilder = null
        speedNotificationJob?.cancel()
        speedNotificationJob = null
        mNotificationManager = null
    }

    /**
     * Stops the speed notification.
     */
    fun stopSpeedNotification() {
        speedNotificationJob?.let {
            it.cancel()
            speedNotificationJob = null
            updateNotification("", 0, 0)
        }
    }

    /**
     * Creates a notification channel for Android O and above.
     * @return The channel ID.
     */
    @RequiresApi(Build.VERSION_CODES.O)
    private fun createNotificationChannel(): String {
        val channelId = AppConfig.RAY_NG_CHANNEL_ID
        val service = getService() ?: return channelId
        NotificationHelper.ensureNotificationChannel(
            context = service,
            channelId = channelId,
            channelNameRes = R.string.notification_channel_service,
            // Foreground-service notifications must remain visible; LOW is silent but valid.
            importance = NotificationManager.IMPORTANCE_LOW,
        ) {
            lightColor = Color.DKGRAY
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        }
        return channelId
    }

    /**
     * Updates the notification with the given content text and traffic data.
     * @param contentText The content text.
     * @param proxyTraffic The proxy traffic.
     * @param directTraffic The direct traffic.
     */
    private fun updateNotification(contentText: String?, proxyTraffic: Long, directTraffic: Long) {
        if (mBuilder != null) {
            if (proxyTraffic < NOTIFICATION_ICON_THRESHOLD && directTraffic < NOTIFICATION_ICON_THRESHOLD) {
                mBuilder?.setSmallIcon(R.drawable.ic_stat_name)
            } else if (proxyTraffic > directTraffic) {
                mBuilder?.setSmallIcon(R.drawable.ic_stat_proxy)
            } else {
                mBuilder?.setSmallIcon(R.drawable.ic_stat_direct)
            }
            mBuilder?.setStyle(NotificationCompat.BigTextStyle().bigText(contentText))
            mBuilder?.setContentText(contentText)
            getNotificationManager()?.notify(NOTIFICATION_ID, mBuilder?.build())
        }
    }

    /**
     * Gets the notification manager.
     * @return The notification manager.
     */
    private fun getNotificationManager(): NotificationManager? {
        if (mNotificationManager == null) {
            val service = getService() ?: return null
            mNotificationManager = service.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        }
        return mNotificationManager
    }

    /**
     * Appends the speed string to the given text.
     * @param text The text to append to.
     * @param name The name of the tag.
     * @param up The uplink speed.
     * @param down The downlink speed.
     */
    private fun appendSpeedString(text: StringBuilder, name: String?, up: Double, down: Double) {
        var n = name ?: "no tag"
        n = n.take(min(n.length, 6))
        text.append(n)
        for (i in n.length..6 step 2) {
            text.append("\t")
        }
        text.append("•  ${up.toLong().toSpeedString()}↑  ${down.toLong().toSpeedString()}↓\n")
    }

    /**
     * Updates the speed notification once.
     * Queries traffic stats, separates proxy and direct, and updates the notification.
     * @param lastZeroSpeed The previous zero speed state.
     * @return The current zero speed state.
     */
    private fun updateSpeedNotificationOnce(lastZeroSpeed: Boolean): Boolean {
        val queryTime = System.currentTimeMillis()
        val sinceLastQueryIn = (queryTime - lastQueryTime)

        // If the query interval is too short, skip this round to avoid excessive CPU usage
        if (sinceLastQueryIn < QUERY_INTERVAL_MS) {
            LogUtil.w(AppConfig.TAG, "Query interval too short: ${sinceLastQueryIn}ms, skipping")
            lastQueryTime = queryTime
            return lastZeroSpeed
        }
        val sinceLastQueryInSeconds = sinceLastQueryIn / 1000.0

        var proxyUplink = 0L
        var proxyDownlink = 0L
        var directUplink = 0L
        var directDownlink = 0L

        CoreServiceManager.queryAllOutboundTrafficStats().forEach { stat ->
            when {
                stat.tag == AppConfig.TAG_DIRECT -> {
                    when (stat.direction) {
                        AppConfig.UPLINK -> directUplink += stat.value
                        AppConfig.DOWNLINK -> directDownlink += stat.value
                    }
                }

                // Accumulate stats for all proxy outbounds (including custom subscription tags)
                stat.tag != AppConfig.TAG_BLOCKED -> {
                    when (stat.direction) {
                        AppConfig.UPLINK -> proxyUplink += stat.value
                        AppConfig.DOWNLINK -> proxyDownlink += stat.value
                    }
                }
            }
        }

        val proxyTotal = proxyUplink + proxyDownlink
        val directTotal = directUplink + directDownlink
        val zeroSpeed = proxyTotal + directTotal == 0L
        if (!zeroSpeed || !lastZeroSpeed) {
            val text = StringBuilder()
            appendSpeedString(
                text, AppConfig.TAG_PROXY,
                proxyUplink / sinceLastQueryInSeconds,
                proxyDownlink / sinceLastQueryInSeconds
            )

            appendSpeedString(
                text, AppConfig.TAG_DIRECT,
                directUplink / sinceLastQueryInSeconds,
                directDownlink / sinceLastQueryInSeconds
            )
            updateNotification(text.toString(), proxyTotal, directTotal)
        }
        lastQueryTime = queryTime
        return zeroSpeed
    }

    /**
     * Gets the service instance.
     * @return The service instance.
     */
    private fun getService(): Service? {
        return CoreServiceManager.serviceControl?.get()?.getService()
    }
}
