// 提供联机侧栏、页面状态绑定和说明弹窗，所有业务操作调用统一后端。
package dev.molifox.desktop.multiplayer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import dev.molifox.desktop.MoliTextButton as TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.molifox.desktop.FrostedPanel
import dev.molifox.desktop.LocalStrings
import dev.molifox.desktop.PrimaryButton
import dev.molifox.desktop.SettingsNavItem
import dev.molifox.multiplayer.common.LobbyPhase
import dev.molifox.multiplayer.common.MultiplayerPolicy
import dev.molifox.multiplayer.common.MultiplayerSettings
import dev.molifox.multiplayer.domain.MultiplayerBackend
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private enum class MultiplayerSection(
    val titleKey: String,
) {
    Lobby("dialogs.mp.tab.lobby"),
    HostGuide("dialogs.mp.tab.hostGuide"),
    GuestGuide("dialogs.mp.tab.guestGuide"),
    Settings("dialogs.mp.tab.settings"),
}

private class MultiplayerPageState {
    var section by mutableStateOf(MultiplayerSection.Lobby)
    var initialized by mutableStateOf(false)
    var declined by mutableStateOf(false)
    var message by mutableStateOf<String?>(null)

    /** 本次页面生命周期内是否已经自动发起过下载；无论成功失败都只自动发起一次。 */
    var runtimeDownloadRequested by mutableStateOf(false)

    /** 下载失败过：状态栏按钮改成“重试下载”，用户必须显式点才会再次下载。 */
    var runtimeDownloadFailed by mutableStateOf(false)

    /** 正在选择或安装本地 zip：按钮显示“正在安装…”，并阻止重复打开文件选择。 */
    var runtimeImporting by mutableStateOf(false)

    /** 当前下载协程；下载阶段才显示“取消下载”，取消时直接 cancel 这个 Job。 */
    var downloadJob by mutableStateOf<Job?>(null)

    /** 已请求取消、等待后端回到 Idle：按钮显示“正在取消…”并禁用，避免重复取消。 */
    var cancellingDownload by mutableStateOf(false)
}

@Composable
fun MultiplayerScreen(
    backend: MultiplayerBackend,
    onCopy: (String) -> Unit,
    chooseArchive: suspend () -> String?,
) {
    val strings = LocalStrings.current
    val settings by backend.settings.collectAsState()
    val scope = rememberCoroutineScope()
    val page = remember { MultiplayerPageState() }
    val execute: ExecuteMultiplayer = { action ->
        scope.launch { action().onFailure { page.message = it.message ?: strings.t("dialogs.mp.opFailed") } }
    }
    // 下载单独走一条路径：先把“已发起”标记置位，自动下载逻辑就不会再启动第二次。
    val downloadRuntime: () -> Unit = {
        if (page.downloadJob?.isActive != true) {
            page.runtimeDownloadRequested = true
            page.runtimeDownloadFailed = false
            page.cancellingDownload = false
            page.downloadJob =
                scope.launch {
                    backend.prepareRuntime().fold(
                        onSuccess = { page.message = null },
                        onFailure = { failure ->
                            page.runtimeDownloadFailed = true
                            page.message = failure.message ?: strings.t("dialogs.mp.downloadRuntimeFailed")
                        },
                    )
                }
        }
    }
    // 真正的停止由后端完成：安装任务在 backend 后台作用域里，取消后回到 Idle 且不显示失败错误。
    // 这里只是让等待下载结果的协程随安装任务取消一起结束，不负责停止下载。
    val cancelDownload: () -> Unit = {
        if (!page.cancellingDownload) {
            page.cancellingDownload = true
            backend.cancelRuntimeInstall()
        }
    }
    // 文件选择与安装都在协程里：界面只拿路径字符串，不直接碰磁盘。
    val importRuntime: () -> Unit = {
        if (!page.runtimeImporting) {
            page.runtimeImporting = true
            scope.launch {
                val archive =
                    runCatching { chooseArchive() }.getOrElse { failure ->
                        page.message = failure.message ?: strings.t("dialogs.mp.chooseRuntimeFailed")
                        null
                    }
                if (archive != null) {
                    backend.importRuntime(archive).fold(
                        onSuccess = { page.message = null },
                        onFailure = { failure ->
                            page.message =
                                failure.message ?: strings.t("dialogs.mp.installLocalRuntimeFailed")
                        },
                    )
                }
                page.runtimeImporting = false
            }
        }
    }
    val runtimeInstall =
        RuntimeInstallActions(
            download = downloadRuntime,
            importArchive = importRuntime,
            cancelDownload = cancelDownload,
            downloadFailed = page.runtimeDownloadFailed,
            importing = page.runtimeImporting,
            cancelling = page.cancellingDownload,
            // 页面切走再回来时 downloadJob 会为空，但后端仍在下载：按钮跟随阶段显示即可。
            cancelVisible = true,
        )
    val acceptTerms: () -> Unit = {
        val consented = settings.copy(acceptedTermsRevision = MultiplayerPolicy.TERMS_REVISION)
        page.runtimeDownloadRequested = true
        scope.launch {
            backend.saveSettings(consented).fold(
                onSuccess = { downloadRuntime() },
                onFailure = { failure ->
                    page.message = failure.message ?: strings.t("dialogs.mp.saveConsentFailed")
                },
            )
        }
    }
    MultiplayerStartup(backend, page)
    MultiplayerDownloadState(backend, page)
    MultiplayerRuntimeAutoDownload(backend, page, downloadRuntime)
    Column(Modifier.fillMaxSize()) {
        val agreed = settings.acceptedTermsRevision == MultiplayerPolicy.TERMS_REVISION
        // 说明门只看「是否已同意」：initialized 只代表后端就绪。若把两者绑在一起，
        // 下载进行中切走再切回时 initialize 会晚些才完成，已同意的用户也会被拦在
        // 说明门里且按钮禁用；已同意时直接显示大厅，各操作由后端阶段自己把关。
        if (agreed) {
            MultiplayerContent(backend, page, execute, onCopy, runtimeInstall)
        } else {
            MultiplayerConsentGate(page)
        }
        PageMessage(page)
    }
    MultiplayerModals(settings, page, acceptTerms)
}

@Composable
private fun ColumnScope.MultiplayerContent(
    backend: MultiplayerBackend,
    page: MultiplayerPageState,
    execute: ExecuteMultiplayer,
    onCopy: (String) -> Unit,
    runtimeInstall: RuntimeInstallActions,
) {
    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        MultiplayerSidebar(page.section) { page.section = it }
        FrostedPanel(Modifier.weight(1f).fillMaxHeight()) {
            MultiplayerBody(backend, page, execute, onCopy, runtimeInstall)
        }
    }
}

@Composable
private fun ColumnScope.MultiplayerConsentGate(page: MultiplayerPageState) {
    val strings = LocalStrings.current
    FrostedPanel(
        Modifier.weight(1f).fillMaxWidth(),
        contentPadding = PaddingValues(16.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(strings.t("dialogs.mp.consentTitle"), style = MaterialTheme.typography.titleMedium)
            Text(strings.t("dialogs.mp.consentHint"))
            PrimaryButton(strings.t("dialogs.mp.readConsent"), enabled = page.initialized) {
                page.declined = false
            }
        }
    }
}

@Composable
private fun PageMessage(page: MultiplayerPageState) {
    val strings = LocalStrings.current
    page.message?.let { message ->
        Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Text(message, Modifier.weight(1f), color = MaterialTheme.colorScheme.error)
            TextButton(onClick = { page.message = null }) { Text(strings.t("dialogs.common.close")) }
        }
    }
}

@Composable
private fun MultiplayerStartup(
    backend: MultiplayerBackend,
    page: MultiplayerPageState,
) {
    val strings = LocalStrings.current
    LaunchedEffect(backend) {
        backend
            .initialize()
            .onFailure { page.message = strings.t("dialogs.mp.configLoadFailed") }
        // 初始化失败也放开页面：否则同意入口会永久禁用；运行时报错仍会经提示与下载重试暴露。
        page.initialized = true
    }
}

/**
 * 下载阶段结束时清理 Job 与取消标记：这样「取消下载」只在 DownloadingRuntime 且确实有下载协程时出现，
 * 建房/加入等其它 busy 阶段不会误显示。
 */
@Composable
private fun MultiplayerDownloadState(
    backend: MultiplayerBackend,
    page: MultiplayerPageState,
) {
    val state by backend.state.collectAsState()
    LaunchedEffect(state.phase) {
        if (state.phase != LobbyPhase.DownloadingRuntime) {
            page.cancellingDownload = false
            page.downloadJob = null
        }
    }
}

/**
 * 已同意说明且本地没有 EasyTier 运行时时，进入联机页自动开始一次下载，不需要再点一次按钮。
 *
 * 只在空闲阶段触发，不打断房间流程；[MultiplayerPageState.runtimeDownloadRequested] 保证成功或失败后
 * 都不会自动重试，失败后只保留状态栏里的“重试下载”入口。
 */
@Composable
private fun MultiplayerRuntimeAutoDownload(
    backend: MultiplayerBackend,
    page: MultiplayerPageState,
    downloadRuntime: () -> Unit,
) {
    val state by backend.state.collectAsState()
    val settings by backend.settings.collectAsState()
    LaunchedEffect(
        page.initialized,
        settings.enabled,
        settings.acceptedTermsRevision,
        state.phase,
        state.runtime?.installed,
        state.busy,
    ) {
        if (!page.initialized || !settings.enabled) return@LaunchedEffect
        if (settings.acceptedTermsRevision != MultiplayerPolicy.TERMS_REVISION) {
            return@LaunchedEffect
        }
        if (state.busy || state.phase != LobbyPhase.Idle) return@LaunchedEffect
        if (state.runtime?.installed == true) return@LaunchedEffect
        if (page.runtimeDownloadRequested) return@LaunchedEffect
        downloadRuntime()
    }
}

@Composable
private fun MultiplayerSidebar(
    selected: MultiplayerSection,
    select: (MultiplayerSection) -> Unit,
) {
    val strings = LocalStrings.current
    FrostedPanel(Modifier.width(160.dp).fillMaxHeight(), contentPadding = PaddingValues(8.dp)) {
        Column {
            MultiplayerSection.entries.forEach { entry ->
                val icon =
                    when (entry) {
                        MultiplayerSection.Lobby -> Icons.Outlined.Groups
                        MultiplayerSection.Settings -> Icons.Outlined.Settings
                        else -> Icons.AutoMirrored.Outlined.HelpOutline
                    }
                SettingsNavItem(strings.t(entry.titleKey), icon, selected == entry) { select(entry) }
                if (entry == MultiplayerSection.Lobby) Spacer(Modifier.height(12.dp))
            }
        }
    }
}

@Composable
private fun MultiplayerBody(
    backend: MultiplayerBackend,
    page: MultiplayerPageState,
    execute: ExecuteMultiplayer,
    onCopy: (String) -> Unit,
    runtimeInstall: RuntimeInstallActions,
) {
    val state by backend.state.collectAsState()
    val settings by backend.settings.collectAsState()
    when (page.section) {
        MultiplayerSection.Lobby ->
            MultiplayerLobby(
                state = state,
                settings = settings,
                actions = multiplayerLobbyActions(backend, execute, runtimeInstall),
                copy = onCopy,
            )
        MultiplayerSection.HostGuide -> MultiplayerGuide(host = true)
        MultiplayerSection.GuestGuide -> MultiplayerGuide(host = false)
        MultiplayerSection.Settings ->
            MultiplayerSettingsPane(
                settings = settings,
                runtime = state.runtime,
                execute = execute,
                save = backend::saveSettings,
                testNat = backend::testNat,
            )
    }
}

@Composable
private fun MultiplayerModals(
    settings: MultiplayerSettings,
    page: MultiplayerPageState,
    acceptTerms: () -> Unit,
) {
    val agreed = settings.acceptedTermsRevision == MultiplayerPolicy.TERMS_REVISION
    if (page.initialized && !agreed && !page.declined) {
        MultiplayerTermsDialog(
            onAccept = acceptTerms,
            onDecline = { page.declined = true },
        )
    }
}
