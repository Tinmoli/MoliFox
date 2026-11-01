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
import dev.molifox.desktop.LocalStrings
import dev.molifox.desktop.MutedText
import dev.molifox.desktop.PrimaryButton
import dev.molifox.desktop.tr
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
    val strings = LocalStrings.current
    MultiplayerGroup(Modifier.fillMaxWidth(), title = strings.t("dialogs.mp.settings.basic")) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SwitchRow(strings.t("dialogs.mp.settings.enable"), checked = draft.enabled) {
                change(draft.copy(enabled = it))
            }
            CompactTextField(
                draft.nickname,
                { change(draft.copy(nickname = it)) },
                Modifier.fillMaxWidth(),
                label = strings.t("dialogs.mp.settings.nickname"),
                placeholder = strings.t("dialogs.mp.settings.nicknamePlaceholder"),
                hint = strings.t("dialogs.mp.settings.nicknameHint"),
            )
            MutedText(runtimeSummary(runtime))
        }
    }
}

/** 默认使用内置中继；自定义地址优先，按分号拆分并校验协议前缀。 */
@Composable
private fun RelayGroup(
    draft: MultiplayerSettings,
    change: (MultiplayerSettings) -> Unit,
) {
    val strings = LocalStrings.current
    MultiplayerGroup(
        Modifier.fillMaxWidth(),
        title = strings.t("dialogs.mp.settings.relayTitle"),
        detail = strings.t("dialogs.mp.settings.relayBody"),
    ) {
        CompactTextField(
            draft.relayServer,
            { change(draft.copy(relayServer = it)) },
            Modifier.fillMaxWidth(),
            label = strings.t("dialogs.mp.settings.relayCustom"),
            placeholder = "tcp://public.easytier.top:11010",
            hint = strings.t("dialogs.mp.settings.relayCustomHint"),
        )
    }
}

/** 高级开关直接透传给 EasyTier 启动参数；不确定的用户保持默认即可，因此默认全部关闭。 */
@Composable
private fun AdvancedGroup(
    draft: MultiplayerSettings,
    change: (MultiplayerSettings) -> Unit,
) {
    val strings = LocalStrings.current
    MultiplayerGroup(
        Modifier.fillMaxWidth(),
        title = strings.t("dialogs.mp.settings.advanced"),
        detail = strings.t("dialogs.mp.settings.advancedDetail"),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SwitchRow(
                strings.t("dialogs.mp.settings.lowLatency"),
                strings.t("dialogs.mp.settings.lowLatencyHint"),
                draft.latencyFirst,
            ) { change(draft.copy(latencyFirst = it)) }
            SwitchRow(
                strings.t("dialogs.mp.settings.disableIpv6"),
                strings.t("dialogs.mp.settings.disableIpv6Hint"),
                draft.disableIpv6,
            ) { change(draft.copy(disableIpv6 = it)) }
            SwitchRow(
                strings.t("dialogs.mp.settings.disableSymmetric"),
                strings.t("dialogs.mp.settings.disableSymmetricHint"),
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
    val strings = LocalStrings.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PrimaryButton(strings.t("dialogs.mp.settings.save"), enabled = canSave, onClick = save)
        if (saved) {
            Text(
                strings.t("dialogs.mp.settings.saved"),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

private fun runtimeSummary(runtime: CoreStatus?): String {
    val version = runtime?.version?.takeIf { it.isNotBlank() } ?: MultiplayerPolicy.CORE_VERSION
    val platform = runtime?.platform?.takeIf { it.isNotBlank() }
    val installed =
        if (runtime?.installed == true) {
            tr("dialogs.mp.settings.installed")
        } else {
            tr("dialogs.mp.settings.notDownloaded")
        }
    val suffix = platform?.let { " · $it" }.orEmpty()
    return tr("dialogs.mp.settings.runtimeLine", version, suffix, installed)
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
    val strings = LocalStrings.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label)
            if (detail.isNotBlank()) MutedText(detail)
        }
        CompactSwitch(
            checked,
            change,
            Modifier.semantics { contentDescription = strings.t("dialogs.mp.settings.switchLabel", label) },
        )
    }
}
