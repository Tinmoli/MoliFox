// 提供联机侧栏、页面状态绑定和确认弹窗，所有业务操作调用统一后端。
package dev.molifox.desktop.multiplayer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import dev.molifox.multiplayer.common.CoreStatus
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
    var downloadPrompt by mutableStateOf<CoreStatus?>(null)
}

private typealias ExecuteMultiplayer = (suspend () -> Result<Unit>) -> Unit

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
    Column(Modifier.fillMaxSize()) {
        if (page.initialized &&
            settings.acceptedTermsRevision == MultiplayerPolicy.TERMS_REVISION
        ) {
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MultiplayerSidebar(page.section) { page.section = it }
                FrostedPanel(Modifier.weight(1f).fillMaxHeight()) {
                    MultiplayerBody(backend, page, execute, onCopy)
                }
            }
        } else {
            FrostedPanel(
                Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(16.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("先阅读联机说明", style = MaterialTheme.typography.titleMedium)
                    Text("同意说明后才能使用联机大厅、教程和联机设置。")
                    PrimaryButton("阅读联机说明", enabled = page.initialized) { page.declined = false }
                }
            }
        }
        page.message?.let { message ->
            Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Text(message, Modifier.weight(1f), color = MaterialTheme.colorScheme.error)
                TextButton(onClick = { page.message = null }) { Text("关闭") }
            }
        }
    }
    MultiplayerModals(backend, settings, page, execute)
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
                execute = execute,
                host = backend::host,
                join = backend::join,
                leave = backend::leave,
                requestDownload = { page.downloadPrompt = state.core },
                copy = onCopy,
            )
        MultiplayerSection.HostGuide -> MultiplayerGuide(host = true)
        MultiplayerSection.GuestGuide -> MultiplayerGuide(host = false)
        MultiplayerSection.Settings ->
            MultiplayerSettingsPane(
                settings,
                execute,
                backend::saveSettings,
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
    val accepted = settings.acceptedTermsRevision == MultiplayerPolicy.TERMS_REVISION
    if (page.initialized && !accepted && !page.declined) {
        MultiplayerTermsDialog(
            onAccept = {
                execute {
                    backend.saveSettings(
                        settings.copy(acceptedTermsRevision = MultiplayerPolicy.TERMS_REVISION),
                    )
                }
            },
            onDecline = { page.declined = true },
        )
    }
    page.downloadPrompt?.takeIf { accepted }?.let { status ->
        CoreDownloadDialog(
            status,
            onDownload = {
                page.downloadPrompt = null
                execute(backend::prepareCore)
            },
            onCancel = { page.downloadPrompt = null },
        )
    }
}
