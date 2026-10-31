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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.molifox.desktop.CompactSwitch
import dev.molifox.desktop.CompactTextField
import dev.molifox.desktop.MutedText
import dev.molifox.desktop.PrimaryButton
import dev.molifox.multiplayer.common.CoreStatus
import dev.molifox.multiplayer.common.MultiplayerPolicy
import dev.molifox.multiplayer.common.MultiplayerSettings
import dev.molifox.multiplayer.common.NatDiagnostics

@Composable
internal fun MultiplayerSettingsPane(
    settings: MultiplayerSettings,
    runtime: CoreStatus?,
    execute: ExecuteMultiplayer,
    save: suspend (MultiplayerSettings) -> Result<Unit>,
    testNat: suspend () -> Result<NatDiagnostics>,
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
        item("diagnostics") { MultiplayerNatDiagnosticsGroup(runtime, testNat) }
        item("advanced") { AdvancedGroup(draft, change) }
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
            SwitchRow("启用联机", checked = draft.enabled) { change(draft.copy(enabled = it)) }
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

/** 中继列表由后端实时获取；自定义地址优先于自动节点，按分号拆分并校验协议前缀。 */
@Composable
private fun RelayGroup(
    draft: MultiplayerSettings,
    change: (MultiplayerSettings) -> Unit,
) {
    MultiplayerGroup(
        Modifier.fillMaxWidth(),
        title = "中继节点",
        detail = "官方节点状态接口已停用，启动器会自动改用社区节点列表；都不可用时使用内置节点。这里的自定义地址优先级更高，多个用分号分隔。",
    ) {
        CompactTextField(
            draft.relayServer,
            { change(draft.copy(relayServer = it)) },
            Modifier.fillMaxWidth(),
            label = "自定义中继",
            placeholder = "tcp://public.easytier.top:11010",
            hint = "留空即可；需要固定中继或自建节点时填写，必须带协议前缀，例如 tcp://host:11010。",
        )
    }
}

/** 高级开关直接透传给 EasyTier 启动参数；不确定的用户保持默认即可，因此默认全部关闭。 */
@Composable
private fun AdvancedGroup(
    draft: MultiplayerSettings,
    change: (MultiplayerSettings) -> Unit,
) {
    MultiplayerGroup(
        Modifier.fillMaxWidth(),
        title = "高级联机开关",
        detail = "不确定就保持默认；改动随保存写入，下次创建或加入房间时生效。",
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SwitchRow(
                "低延迟优先",
                "优先选择延迟更低的线路，网络波动大时可开；默认关。",
                draft.latencyFirst,
            ) { change(draft.copy(latencyFirst = it)) }
            SwitchRow(
                "关闭 IPv6",
                "只走 IPv4；本机 IPv6 不稳定或连不上时再开，默认关。",
                draft.disableIpv6,
            ) { change(draft.copy(disableIpv6 = it)) }
            SwitchRow(
                "关闭对称 NAT 打洞",
                "部分网络打洞失败时可尝试；默认关，关闭后更依赖中继。",
                draft.disableSymHolePunching,
            ) { change(draft.copy(disableSymHolePunching = it)) }
        }
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

/**
 * 开关行：说明写在标题下方；开关自带“<标题> 开关”的无障碍名称，
 * 便于 GUI 检查按名字点击且不会误点到旁边的说明文字。
 */
@Composable
private fun SwitchRow(
    label: String,
    detail: String = "",
    checked: Boolean,
    change: (Boolean) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label)
            if (detail.isNotBlank()) MutedText(detail)
        }
        CompactSwitch(
            checked,
            change,
            Modifier.semantics { contentDescription = "$label 开关" },
        )
    }
}
