package com.v2ray.ang.ui.main

import com.v2ray.ang.dto.ConnectionTestResult
import com.v2ray.ang.dto.GroupMapItem
import com.v2ray.ang.dto.LocateTarget

/** Locale-neutral state formatted only when it reaches the main UI. */
sealed interface MainStatus {
    data object Disconnected : MainStatus
    data object Connected : MainStatus
    data object Testing : MainStatus
    data class TestProgress(val progress: String) : MainStatus
    data class ConnectionTest(val result: ConnectionTestResult) : MainStatus
    data class Routine(val step: RoutineStep) : MainStatus
}

/**
 * Main UI state
 */
data class MainUiState(
    val groups: List<GroupMapItem> = emptyList(),
    val selectedGroupId: String = "",
    val selectedGuid: String? = null,
    val isRunning: Boolean = false,
    val isTesting: Boolean = false,
    val status: MainStatus = MainStatus.Disconnected,
    val locateTarget: LocateTarget? = null,
    val confirmRemove: Boolean = false,
    val doubleColumnDisplay: Boolean = false,
    val shareQRCodeBitmap: android.graphics.Bitmap? = null,
    val protection: ProtectionState = ProtectionState(),
    /** The subscription routine's progress or verdict; null when none ran or it was dismissed. */
    val routine: RoutineState? = null,
    /** Set while the routine waits for the activity to (re)start the VPN with the chosen server. */
    val routineConnectRequested: Boolean = false,
)

/**
 * The protection features shown as one-tap controls on the main screen.
 *
 * @property killSwitch The kill switch setting.
 * @property virtualLocation True while the virtual location is switched on.
 * @property virtualLocationReady True when a position is chosen and Android has not refused it,
 *   so the main screen can switch it directly instead of sending the user to its screen.
 * @property shareRunning True while the VPN sharing hotspot is up.
 * @property lastLeakVerdict The verdict of the newest leak check, null before the first.
 * @property openRisks How many known leak paths the current settings leave open.
 */
data class ProtectionState(
    val killSwitch: Boolean = false,
    val virtualLocation: Boolean = false,
    val virtualLocationReady: Boolean = false,
    val shareRunning: Boolean = false,
    val lastLeakVerdict: com.v2ray.ang.service.LeakCheckPolicy.Verdict? = null,
    val openRisks: Int = 0,
)

/**
 * All possible user interaction intents
 */
sealed interface MainAction {
    data object Initialize : MainAction
    data object RefreshGroups : MainAction
    data object ToggleService : MainAction
    data object TestCurrentServer : MainAction
    data object TestAllServers : MainAction
    data object TestRealAllServers : MainAction
    data object CancelTesting : MainAction
    data object RemoveAllServers : MainAction
    data object RemoveDuplicateServers : MainAction
    data object RemoveInvalidServers : MainAction
    data object SortByTestResults : MainAction
    data object UpdateSubscriptions : MainAction
    data class UpdateGroupSubscription(val groupId: String) : MainAction
    data object ExportAll : MainAction

    data object ImportQRcode : MainAction
    data object ImportClipboard : MainAction
    data object ImportConfigLocal : MainAction
    data class ImportManually(val type: Int) : MainAction
    data object RestartService : MainAction
    data object LocateSelectedServer : MainAction

    data class SelectGroup(val groupId: String) : MainAction
    data class SelectServer(val guid: String) : MainAction
    data class RemoveServer(val guid: String) : MainAction
    data class EditServer(val guid: String, val profile: com.v2ray.ang.dto.entities.ProfileItem) : MainAction
    data class Search(val query: String) : MainAction
    data class ShareQRCode(val guid: String) : MainAction
    data class ShareClipboard(val guid: String) : MainAction
    data class ShareFullContent(val guid: String) : MainAction
    data object DismissQRCodeDialog : MainAction
    data class OpenSubscriptionLink(val url: String) : MainAction

    data class ImportBatchConfig(val configText: String) : MainAction

    data object LocateHandled : MainAction

    data object RefreshProtection : MainAction
    data class SetKillSwitch(val enabled: Boolean) : MainAction
    data class SetVirtualLocation(val enabled: Boolean) : MainAction

    /** Double-tap on a group: update it, test it, connect to the best server, check YouTube. */
    data class RunSubscriptionRoutine(val groupId: String) : MainAction
    data object RoutineConnectHandled : MainAction
    data object DismissRoutineResult : MainAction
}
