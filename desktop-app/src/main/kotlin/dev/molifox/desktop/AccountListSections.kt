// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 账户列表页的分区实现：左栏入口、账户卡片栏与两个对话框。

package dev.molifox.desktop

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.AddCircleOutline
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.molifox.core.Account
import dev.molifox.core.AuthlibServer
import dev.molifox.core.DeviceCode
import dev.molifox.core.LauncherState
import kotlinx.coroutines.delay

/** 左栏：添加入口逐行铺满 220dp，已保存的认证服务器每台一行，列表长了这一栏可以滚动。 */
@Composable
internal fun AccountSidebar(
    state: LauncherState,
    signingIn: Boolean,
    onMicrosoft: () -> Unit,
    onOffline: () -> Unit,
    onServer: (AuthlibServer) -> Unit,
    onAddServer: () -> Unit,
) {
    Column(
        Modifier.width(220.dp).fillMaxHeight().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        AddAccountRow(Icons.Outlined.AccountCircle, "微软账户", enabled = !signingIn) {
            onMicrosoft()
        }
        AddAccountRow(Icons.Outlined.PersonOutline, "离线账户") { onOffline() }
        // 已保存的认证服务器每台各占一行，行名就是服务器名：点开直接是这台服务器的用户名与密码，
        // 不经过服务器列表、不需要「下一步」、也不显示地址框。核心默认带 LittleSkin，开箱即有一行。
        val savedServers = state.data.authlibServers
        if (savedServers.isEmpty()) {
            AddAccountRow(Icons.Outlined.Dns, "认证服务器", enabled = false) {}
            Text(
                "还没有已保存的认证服务器",
                Modifier.padding(start = 10.dp, top = 2.dp),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            savedServers.forEach { server ->
                AddAccountRow(Icons.Outlined.Dns, server.name.ifBlank { server.url }) {
                    onServer(server)
                }
            }
        }
        AddAccountRow(Icons.Outlined.AddCircleOutline, "添加认证服务器") { onAddServer() }
    }
}

/** 右栏：账户卡片列表或空态，始终占满剩余宽度，空态也在这栏里居中。 */
@Composable
internal fun RowScope.AccountCards(
    state: LauncherState,
    actions: UiActions,
    onRemove: (Account) -> Unit,
) {
    Column(Modifier.weight(1f).fillMaxHeight()) {
        if (state.data.accounts.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    // 没有账户时 StaticSkinHead 会退回内置史蒂夫纹理。
                    StaticSkinHead(
                        null,
                        { actions.controller.store.log("账户头像读取失败：${it.message}", true) },
                        0,
                        Modifier.size(72.dp).clip(RoundedCornerShape(Metrics.cardRadius)),
                    )
                    Text("没有游戏账户", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "从左侧添加账户",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(end = 2.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                state.data.accounts.forEach { account ->
                    AccountCard(
                        account = account,
                        current = account.id == state.data.activeAccountId,
                        subtitle = accountSubtitle(account, actions),
                        actions = actions,
                        onSelect = {
                            if (account.id != state.data.activeAccountId) {
                                actions.run { actions.controller.accounts.select(account.id) }
                            }
                        },
                        onDelete = { onRemove(account) },
                    )
                }
            }
        }
    }
}

/** 删除确认：核心的 remove 会把当前账户落到剩下的第一个，列表删空后回到无账户态。 */
@Composable
internal fun RemoveAccountDialog(
    account: Account,
    actions: UiActions,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("删除「" + account.name + "」？") },
        text = { Text("此账户的本地登录凭据将删除，游戏存档会保留。") },
        confirmButton = {
            TextButton({
                actions.run { actions.controller.accounts.remove(account.id) }
                onDismiss()
            }) {
                Text("删除账户", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onDismiss) { Text("取消") } },
    )
}

/** 微软设备码登录：自动复制验证码、每秒刷新剩余有效期，到期或取消都收起对话框。 */
@Composable
internal fun MicrosoftDeviceCodeDialog(
    code: DeviceCode,
    actions: UiActions,
    onClose: () -> Unit,
) {
    // 验证码复制结果：null = 还在复制（首帧），true = 已复制到剪贴板，false = 复制失败需手动复制。
    var copied by remember { mutableStateOf<Boolean?>(null) }
    // 验证码剩余有效期（毫秒）：对话框每秒刷新，用于显示剩余时间并在到期时自动收起。
    var remainingMillis by remember { mutableStateOf(0L) }
    // 设备码一到就自动复制：结果写进 copied，失败时对话框中显示「验证码复制失败，请手动复制」。
    LaunchedEffect(code.userCode) {
        remainingMillis = code.expiresAt - System.currentTimeMillis()
        copied = copyToClipboard(code.userCode)
    }
    // 有效期来自设备码响应的 expires_in：每秒刷新剩余时间，到期先关掉验证码，
    // 避免已作废的码继续留在对话框里（核心轮询仍由 AccountService 自己兜底）。
    LaunchedEffect(code.userCode) {
        remainingMillis = code.expiresAt - System.currentTimeMillis()
        while (remainingMillis > 0) {
            delay(1_000L)
            remainingMillis = code.expiresAt - System.currentTimeMillis()
        }
        actions.controller.accounts.cancelLogin()
        onClose()
    }
    Modal(
        "在浏览器中完成登录",
        "使用微软账户，输入下方验证码。",
        470.dp,
        {
            actions.controller.accounts.cancelLogin()
            onClose()
        },
    ) {
        // 验证码是这一步唯一要做的事：大号等宽字体、主题色、可选中，复制状态紧贴其下。
        Surface(
            Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(Metrics.cardRadius),
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = .5f),
            border =
                BorderStroke(
                    1.dp,
                    MaterialTheme.colorScheme.primary.copy(alpha = .35f),
                ),
        ) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                SelectionContainer {
                    Text(
                        code.userCode,
                        fontFamily = LocalLauncherFont.current,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 6.sp,
                        color = MaterialTheme.colorScheme.primary,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                    )
                }
                Text(
                    when (copied) {
                        true -> "验证码已复制到剪贴板"
                        false -> "验证码复制失败，请手动复制"
                        null -> "正在复制验证码…"
                    },
                    fontSize = 12.sp,
                    color =
                        if (copied == false) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    textAlign = TextAlign.Center,
                )
                Text(
                    "剩余有效期 " + remainingLabel(remainingMillis),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SecondaryButton({
                // 重新复制同样要给结果：成功提示已复制，失败继续显示手动复制的引导。
                val ok = copyToClipboard(code.userCode)
                copied = ok
                actions.controller.store.notify(if (ok) "验证码已复制" else "验证码复制失败，请手动复制")
            }) {
                Text("重新复制", fontSize = 13.sp)
            }
            PrimaryButton("打开验证页面", Icons.Outlined.OpenInNew) {
                actions.run { actions.controller.platform.openUrl(code.verificationUri) }
            }
        }
        MutedText("授权完成后会自动添加角色并切换为当前账户。服务错误可在设置 → 日志中查看。")
    }
}
