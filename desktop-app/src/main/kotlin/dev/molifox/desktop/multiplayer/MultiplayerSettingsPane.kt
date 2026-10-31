// 编辑联机独立配置，保存与校验交由后端，不直接修改全局启动器设置。
package dev.molifox.desktop.multiplayer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.molifox.desktop.CompactSwitch
import dev.molifox.desktop.CompactTextField
import dev.molifox.desktop.MutedText
import dev.molifox.desktop.PrimaryButton
import dev.molifox.multiplayer.common.CoreStatus
import dev.molifox.multiplayer.common.MultiplayerPolicy
import dev.molifox.multiplayer.common.MultiplayerSettings

@Composable
internal fun MultiplayerSettingsPane(
    settings: MultiplayerSettings,
    runtime: CoreStatus?,
    execute: ExecuteMultiplayer,
    save: suspend (MultiplayerSettings) -> Result<Unit>,
) {
    var draft by remember(settings) { mutableStateOf(settings) }
    var saved by remember { mutableStateOf(false) }
    val change: (MultiplayerSettings) -> Unit = { updated ->
        draft = updated
        saved = false
    }
    LazyColumn(Modifier.fillMaxSize()) {
        item("identity") { IdentityGroup(draft, runtime, change) }
        item("relay") { RelayGroup(draft, change) }
        item("save") {
            SaveGroup(
                canSave = draft != settings,
                saved = saved,
                save = { execute { save(draft).onSuccess { saved = true } } },
            )
        }
    }
}

@Composable
private fun IdentityGroup(
    draft: MultiplayerSettings,
    runtime: CoreStatus?,
    change: (MultiplayerSettings) -> Unit,
) {
    MultiplayerGroup(Modifier.fillMaxWidth(), title = "基本设置") {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SwitchRow("启用联机", draft.enabled) { change(draft.copy(enabled = it)) }
            CompactTextField(
                draft.nickname,
                { change(draft.copy(nickname = it)) },
                Modifier.fillMaxWidth(),
                label = "联机昵称",
                placeholder = "留空时使用当前账户名",
                hint = "只影响房间内的显示名称，不修改账户信息。",
            )
            MutedText(runtimeSummary(runtime))
        }
    }
}

/** 中继留空表示使用 EasyTier 默认公共中继；自定义地址由后端按分号拆分并校验协议前缀。 */
@Composable
private fun RelayGroup(
    draft: MultiplayerSettings,
    change: (MultiplayerSettings) -> Unit,
) {
    MultiplayerGroup(
        Modifier.fillMaxWidth(),
        title = "中继节点",
        detail = "留空时使用 EasyTier 默认公共中继；自定义地址必须带协议前缀，多个地址用分号分隔。",
    ) {
        CompactTextField(
            draft.relayServer,
            { change(draft.copy(relayServer = it)) },
            Modifier.fillMaxWidth(),
            label = "自定义中继",
            placeholder = "tcp://public.easytier.top:11010",
            hint = "需要能连上中继才能建立虚拟网络；中继不可用时会连接失败。",
        )
    }
}

@Composable
private fun SaveGroup(
    canSave: Boolean,
    saved: Boolean,
    save: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PrimaryButton("保存联机设置", enabled = canSave, onClick = save)
        if (saved) {
            Text(
                "已保存。昵称与自定义中继在下次创建或加入房间时生效。",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

private fun runtimeSummary(runtime: CoreStatus?): String {
    val version = runtime?.version?.takeIf { it.isNotBlank() } ?: MultiplayerPolicy.CORE_VERSION
    val platform = runtime?.platform?.takeIf { it.isNotBlank() }
    val installed = if (runtime?.installed == true) "已安装" else "尚未下载"
    val suffix = platform?.let { " · $it" }.orEmpty()
    return "联机运行时：官方 EasyTier $version$suffix · $installed；当前仅支持 Windows / Linux x86_64。"
}

@Composable
private fun SwitchRow(
    label: String,
    checked: Boolean,
    change: (Boolean) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        CompactSwitch(checked, change)
    }
}
