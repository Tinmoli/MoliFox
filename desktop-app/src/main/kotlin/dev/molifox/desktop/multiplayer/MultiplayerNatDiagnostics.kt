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
import dev.molifox.desktop.MutedText
import dev.molifox.desktop.SecondaryButton
import dev.molifox.multiplayer.common.CoreStatus
import dev.molifox.multiplayer.common.NatDiagnostics
import dev.molifox.multiplayer.common.NatType
import kotlinx.coroutines.launch

@Composable
internal fun MultiplayerNatDiagnosticsGroup(
    runtime: CoreStatus?,
    testNat: suspend () -> Result<NatDiagnostics>,
) {
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
                        error = failure.message ?: "网络测试失败，请稍后重试"
                    },
                )
                testing = false
            }
        }
    }
    MultiplayerGroup(
        Modifier.fillMaxWidth(),
        title = "网络诊断",
        detail = "用 EasyTier 自带的 STUN 测试当前网络类型，不启动联机核心、不需要管理员，通常几秒出结果。",
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SecondaryButton(onClick = startTest, enabled = installed && !testing) {
                Text(if (testing) "测试中…" else "测试网络类型（NAT）")
            }
            if (!installed) MutedText("先下载 EasyTier 运行时")
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
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("UDP 类型：${natTypeLabel(diagnostics.udpNatType)}")
        Text("TCP 类型：${natTypeLabel(diagnostics.tcpNatType)}")
        Text("IPv6：${if (diagnostics.supportsIpv6) "支持" else "不支持"}")
        MutedText(natAdvice(diagnostics))
    }
}

/** 与 PCL CE 工具页一致的 NAT 类型中文名，避免用户看到协议缩写。 */
internal fun natTypeLabel(type: NatType): String =
    when (type) {
        NatType.OpenInternet -> "开放网络"
        NatType.NoPat -> "无 NAT"
        NatType.FullCone -> "完全锥形"
        NatType.Restricted -> "受限锥形"
        NatType.PortRestricted -> "端口受限锥形"
        NatType.SymmetricEasy -> "对称 NAT（较好打洞）"
        NatType.Symmetric -> "对称 NAT"
        NatType.SymmetricFirewall -> "对称 NAT（带防火墙）"
        NatType.UdpBlocked -> "UDP 被封"
        NatType.Unknown -> "未知"
    }

/** UDP 被封锁影响最大，先提示换网络；对称 NAT 再提示中继或局域网直连。 */
internal fun natAdvice(diagnostics: NatDiagnostics): String {
    val types = setOf(diagnostics.udpNatType, diagnostics.tcpNatType)
    val symmetric = setOf(NatType.Symmetric, NatType.SymmetricFirewall, NatType.SymmetricEasy)
    return when {
        NatType.UdpBlocked in types -> "UDP 被封，联机可能不可用，请换网络或检查防火墙/安全软件。"
        types.any { it in symmetric } ->
            "对称 NAT 下打洞可能失败，启动器会自动改走内置中继；若中继只做信令或不允许转发，仍会失败。可换网络、局域网直连或填可转发的自定义中继。"
        else -> "网络类型适合直连或打洞。"
    }
}
