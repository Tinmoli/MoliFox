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

    /** 本次页面生命周期内是否已经发起过运行时下载，避免失败后自动重复下载。 */
    var runtimeDownloadRequested by mutableStateOf(false)
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
    MultiplayerStartup(backend, page)
    MultiplayerRuntimeAutoDownload(backend, page, execute)
    Column(Modifier.fillMaxSize()) {
        val agreed = settings.acceptedTermsRevision == MultiplayerPolicy.TERMS_REVISION
        if (page.initialized && agreed) {
            MultiplayerContent(backend, page, execute, onCopy)
        } else {
            MultiplayerConsentGate(page)
        }
        PageMessage(page)
    }
    MultiplayerModals(backend, settings, page, execute)
}

@Composable
private fun ColumnScope.MultiplayerContent(
    backend: MultiplayerBackend,
    page: MultiplayerPageState,
    execute: ExecuteMultiplayer,
    onCopy: (String) -> Unit,
) {
    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        MultiplayerSidebar(page.section) { page.section = it }
        FrostedPanel(Modifier.weight(1f).fillMaxHeight()) {
            MultiplayerBody(backend, page, execute, onCopy)
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
            .onSuccess { page.initialized = true }
            .onFailure { page.message = "联机配置读取失败，请检查日志" }
    }
}

/**
 * 已同意说明但本地没有 EasyTier 运行时时，进入页面就直接下载；只在本次页面生命周期内自动触发一次，
 * 失败后由大厅状态栏的下载按钮显式重试，避免失败后反复下载。
 */
@Composable
private fun MultiplayerRuntimeAutoDownload(
    backend: MultiplayerBackend,
    page: MultiplayerPageState,
    execute: ExecuteMultiplayer,
) {
    val state by backend.state.collectAsState()
    val settings by backend.settings.collectAsState()
    LaunchedEffect(
        page.initialized,
        settings.enabled,
        settings.acceptedTermsRevision,
        state.runtime?.installed,
        state.busy,
    ) {
        if (!page.initialized || !settings.enabled) return@LaunchedEffect
        if (settings.acceptedTermsRevision != MultiplayerPolicy.TERMS_REVISION) {
            return@LaunchedEffect
        }
        if (state.busy || state.runtime?.installed != false) return@LaunchedEffect
        if (page.runtimeDownloadRequested) return@LaunchedEffect
        page.runtimeDownloadRequested = true
        execute { backend.prepareRuntime() }
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
) {
    val state by backend.state.collectAsState()
    val settings by backend.settings.collectAsState()
    when (page.section) {
        MultiplayerSection.Lobby ->
            MultiplayerLobby(
                state = state,
                settings = settings,
                actions = multiplayerLobbyActions(backend, execute),
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
            )
    }
}

@Composable
private fun MultiplayerModals(
    backend: MultiplayerBackend,
    settings: MultiplayerSettings,
    page: MultiplayerPageState,
    execute: ExecuteMultiplayer,
) {
    val agreed = settings.acceptedTermsRevision == MultiplayerPolicy.TERMS_REVISION
    if (page.initialized && !agreed && !page.declined) {
        MultiplayerTermsDialog(
            onAccept = {
                val consented =
                    settings.copy(acceptedTermsRevision = MultiplayerPolicy.TERMS_REVISION)
                // 先标记已发起下载，避免同意状态刷新后自动下载逻辑再启动一次。
                page.runtimeDownloadRequested = true
                execute {
                    backend.saveSettings(consented).fold(
                        onSuccess = { backend.prepareRuntime() },
                        onFailure = { failure -> Result.failure(failure) },
                    )
                }
            },
            onDecline = { page.declined = true },
        )
    }
}
