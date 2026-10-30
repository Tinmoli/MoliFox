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
import dev.molifox.desktop.PrimaryButton
import dev.molifox.multiplayer.common.MultiplayerSettings

@Composable
internal fun MultiplayerSettingsPane(
    settings: MultiplayerSettings,
    execute: (suspend () -> Result<Unit>) -> Unit,
    save: suspend (MultiplayerSettings) -> Result<Unit>,
) {
    var draft by remember(settings) { mutableStateOf(settings) }
    var saved by remember { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize()) {
        item("identity") {
            MultiplayerGroup(Modifier.fillMaxWidth(), title = "基本设置") {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SwitchRow("启用联机", draft.enabled) { draft = draft.copy(enabled = it) }
                    CompactTextField(
                        draft.nickname,
                        {
                            draft = draft.copy(nickname = it)
                            saved = false
                        },
                        Modifier.fillMaxWidth(),
                        label = "联机昵称",
                        placeholder = "留空时使用当前账户名",
                        hint = "只影响房间内的显示名称，不修改账户信息。",
                    )
                    Text("联机核心由陶瓦（Terracotta）提供，仅支持 Windows / Linux x86_64；首次使用时下载。")
                }
            }
        }
        item("save") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PrimaryButton("保存联机设置") {
                    execute { save(draft).onSuccess { saved = true } }
                }
                if (saved) {
                    Text("已保存。昵称在下次创建或加入房间时生效。", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
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
