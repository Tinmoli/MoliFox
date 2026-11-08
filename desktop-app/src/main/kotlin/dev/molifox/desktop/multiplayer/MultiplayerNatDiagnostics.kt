// 网络诊断：调用后端 STUN 测试并展示 NAT 类型与建议；界面不直接执行命令或访问网络。
package dev.molifox.desktop.multiplayer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.molifox.desktop.LocalStrings
import dev.molifox.desktop.MutedText
import dev.molifox.desktop.SecondaryButton
import dev.molifox.desktop.tr
import dev.molifox.multiplayer.common.CoreStatus
import dev.molifox.multiplayer.common.NatDiagnostics
import dev.molifox.multiplayer.common.NatType
import kotlinx.coroutines.launch

@Composable
internal fun MultiplayerNatDiagnosticsGroup(
    runtime: CoreStatus?,
    testNat: suspend () -> Result<NatDiagnostics>,
) {
    val strings = LocalStrings.current
    var testing by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<NatDiagnostics?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val installed = runtime?.installed == true
    // 测试中禁用按钮并忽略重复点击；结果只放本地状态，不写设置、不改联机状态。
    val startTest: () -> Unit = {
        if (installed && !testing) {
            testing = true
            error = null
            scope.launch {
                testNat().fold(
                    onSuccess = { result = it },
                    onFailure = { failure ->
                        error = failure.message ?: strings.t("dialogs.mp.nat.testFailed")
                    },
                )
                testing = false
            }
        }
    }
    MultiplayerGroup(
        Modifier.fillMaxWidth(),
        title = strings.t("dialogs.mp.nat.title"),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SecondaryButton(onClick = startTest, enabled = installed && !testing) {
                Text(if (testing) strings.t("dialogs.mp.nat.testing") else strings.t("dialogs.mp.nat.test"))
            }
            if (!installed) MutedText(strings.t("dialogs.mp.nat.needRuntime"))
            val summary = result
            if (summary != null) DiagnosticsSummary(summary)
            error?.let { message ->
                Text(message, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun DiagnosticsSummary(diagnostics: NatDiagnostics) {
    val strings = LocalStrings.current
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(strings.t("dialogs.mp.nat.udp", natTypeLabel(diagnostics.udpNatType)))
        Text(strings.t("dialogs.mp.nat.tcp", natTypeLabel(diagnostics.tcpNatType)))
        Text(
            strings.t(
                "dialogs.mp.nat.ipv6",
                if (diagnostics.supportsIpv6) {
                    strings.t("dialogs.mp.nat.supported")
                } else {
                    strings.t("dialogs.mp.nat.unsupported")
                },
            ),
        )
        MutedText(natAdvice(diagnostics))
    }
}

/** NAT 类型的中文名，避免用户看到协议缩写。 */
internal fun natTypeLabel(type: NatType): String =
    when (type) {
        NatType.OpenInternet -> tr("dialogs.mp.nat.open")
        NatType.NoPat -> tr("dialogs.mp.nat.noPat")
        NatType.FullCone -> tr("dialogs.mp.nat.fullCone")
        NatType.Restricted -> tr("dialogs.mp.nat.restricted")
        NatType.PortRestricted -> tr("dialogs.mp.nat.portRestricted")
        NatType.SymmetricEasy -> tr("dialogs.mp.nat.symmetricEasy")
        NatType.Symmetric -> tr("dialogs.mp.nat.symmetric")
        NatType.SymmetricFirewall -> tr("dialogs.mp.nat.symmetricFirewall")
        NatType.UdpBlocked -> tr("dialogs.mp.nat.udpBlocked")
        NatType.Unknown -> tr("dialogs.mp.nat.unknown")
    }

/** UDP 被封锁影响最大，先提示换网络；对称 NAT 再提示中继或局域网直连。 */
internal fun natAdvice(diagnostics: NatDiagnostics): String {
    val types = setOf(diagnostics.udpNatType, diagnostics.tcpNatType)
    val symmetric = setOf(NatType.Symmetric, NatType.SymmetricFirewall, NatType.SymmetricEasy)
    return when {
        NatType.UdpBlocked in types -> tr("dialogs.mp.nat.udpBlockedHint")
        types.any { it in symmetric } ->
            tr("dialogs.mp.nat.symmetricHint")
        else -> tr("dialogs.mp.nat.goodHint")
    }
}
