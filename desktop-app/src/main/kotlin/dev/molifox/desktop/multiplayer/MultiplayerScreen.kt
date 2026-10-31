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
import androidx.compose.material3.TextButton
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
import dev.molifox.desktop.PrimaryButton
import dev.molifox.desktop.SettingsNavItem
import dev.molifox.multiplayer.common.LobbyPhase
import dev.molifox.multiplayer.common.MultiplayerPolicy
import dev.molifox.multiplayer.common.MultiplayerSettings
import dev.molifox.multiplayer.domain.MultiplayerBackend
import kotlinx.coroutines.launch

private enum class MultiplayerSection(
    val title: String,
) {
    Lobby("联机大厅"),
    HostGuide("房主教程"),
    GuestGuide("房客教程"),
    Settings("联机设置"),
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
}

@Composable
fun MultiplayerScreen(
    backend: MultiplayerBackend,
    onCopy: (String) -> Unit,
) {
    val settings by backend.settings.collectAsState()
    val scope = rememberCoroutineScope()
    val page = remember { MultiplayerPageState() }
    val execute: ExecuteMultiplayer = { action ->
        scope.launch { action().onFailure { page.message = it.message ?: "操作失败，请重试" } }
    }
    // 下载单独走一条路径：先把“已发起”标记置位，自动下载逻辑就不会再启动第二次。
    val downloadRuntime: () -> Unit = {
        page.runtimeDownloadRequested = true
        scope.launch {
            backend.prepareRuntime().fold(
                onSuccess = { page.message = null },
                onFailure = { failure ->
                    page.runtimeDownloadFailed = true
                    page.message = failure.message ?: "下载 EasyTier 失败，请检查网络后重试"
                },
            )
        }
    }
    val acceptTerms: () -> Unit = {
        val consented = settings.copy(acceptedTermsRevision = MultiplayerPolicy.TERMS_REVISION)
        page.runtimeDownloadRequested = true
        scope.launch {
            backend.saveSettings(consented).fold(
                onSuccess = { downloadRuntime() },
                onFailure = { failure ->
                    page.message = failure.message ?: "保存联机说明失败，请重试"
                },
            )
        }
    }
    MultiplayerStartup(backend, page)
    MultiplayerRuntimeAutoDownload(backend, page, downloadRuntime)
    Column(Modifier.fillMaxSize()) {
        val agreed = settings.acceptedTermsRevision == MultiplayerPolicy.TERMS_REVISION
        if (page.initialized && agreed) {
            MultiplayerContent(backend, page, execute, onCopy, downloadRuntime)
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
    downloadRuntime: () -> Unit,
) {
    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        MultiplayerSidebar(page.section) { page.section = it }
        FrostedPanel(Modifier.weight(1f).fillMaxHeight()) {
            MultiplayerBody(backend, page, execute, onCopy, downloadRuntime)
        }
    }
}

@Composable
private fun ColumnScope.MultiplayerConsentGate(page: MultiplayerPageState) {
    FrostedPanel(
        Modifier.weight(1f).fillMaxWidth(),
        contentPadding = PaddingValues(16.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("先阅读联机说明", style = MaterialTheme.typography.titleMedium)
            Text("同意说明后才能使用联机大厅、教程和联机设置。")
            PrimaryButton("阅读并同意联机说明", enabled = page.initialized) {
                page.declined = false
            }
        }
    }
}

@Composable
private fun PageMessage(page: MultiplayerPageState) {
    page.message?.let { message ->
        Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Text(message, Modifier.weight(1f), color = MaterialTheme.colorScheme.error)
            TextButton(onClick = { page.message = null }) { Text("关闭") }
        }
    }
}

@Composable
private fun MultiplayerStartup(
    backend: MultiplayerBackend,
    page: MultiplayerPageState,
) {
    LaunchedEffect(backend) {
        backend
            .initialize()
            .onFailure { page.message = "联机配置读取失败，请检查日志" }
        // 初始化失败也放开页面：否则同意入口会永久禁用；运行时报错仍会经提示与下载重试暴露。
        page.initialized = true
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
    FrostedPanel(Modifier.width(160.dp).fillMaxHeight(), contentPadding = PaddingValues(8.dp)) {
        Column {
            MultiplayerSection.entries.forEach { entry ->
                val icon =
                    when (entry) {
                        MultiplayerSection.Lobby -> Icons.Outlined.Groups
                        MultiplayerSection.Settings -> Icons.Outlined.Settings
                        else -> Icons.AutoMirrored.Outlined.HelpOutline
                    }
                SettingsNavItem(entry.title, icon, selected == entry) { select(entry) }
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
    downloadRuntime: () -> Unit,
) {
    val state by backend.state.collectAsState()
    val settings by backend.settings.collectAsState()
    when (page.section) {
        MultiplayerSection.Lobby ->
            MultiplayerLobby(
                state = state,
                settings = settings,
                actions =
                    multiplayerLobbyActions(
                        backend = backend,
                        execute = execute,
                        downloadRuntime = downloadRuntime,
                        runtimeDownloadFailed = page.runtimeDownloadFailed,
                    ),
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
