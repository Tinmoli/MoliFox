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
import dev.molifox.desktop.MoliTextButton as TextButton
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
    val strings = LocalStrings.current
    Column(
        Modifier.width(220.dp).fillMaxHeight().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        AddAccountRow(Icons.Outlined.AccountCircle, strings.t("dialogs.account.microsoft"), enabled = !signingIn) {
            onMicrosoft()
        }
        AddAccountRow(Icons.Outlined.PersonOutline, strings.t("dialogs.account.offline")) { onOffline() }
        // 已保存的认证服务器每台各占一行，行名就是服务器名：点开直接是这台服务器的用户名与密码，
        // 不经过服务器列表、不需要「下一步」、也不显示地址框。核心默认带 LittleSkin，开箱即有一行。
        val savedServers = state.data.authlibServers
        if (savedServers.isEmpty()) {
            AddAccountRow(Icons.Outlined.Dns, strings.t("dialogs.account.authServer"), enabled = false) {}
            Text(
                strings.t("dialogs.account.noSavedServers"),
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
        AddAccountRow(Icons.Outlined.AddCircleOutline, strings.t("dialogs.account.addServer")) { onAddServer() }
    }
}

/** 右栏：账户卡片列表或空态，始终占满剩余宽度，空态也在这栏里居中。 */
@Composable
internal fun RowScope.AccountCards(
    state: LauncherState,
    actions: UiActions,
    onRemove: (Account) -> Unit,
) {
    val strings = LocalStrings.current
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
                        {
                            actions.controller.store.log(
                                strings.t("dialogs.account.avatarReadFailed", it.message),
                                true,
                            )
                        },
                        0,
                        Modifier.size(72.dp).clip(RoundedCornerShape(Metrics.cardRadius)),
                    )
                    Text(strings.t("dialogs.account.empty"), style = MaterialTheme.typography.titleMedium)
                    Text(
                        strings.t("dialogs.account.emptyHint"),
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
    val strings = LocalStrings.current
    AlertDialog(
        properties =
            androidx.compose.ui.window
                .DialogProperties(dismissOnClickOutside = false),
        onDismissRequest = onDismiss,
        title = { Text(strings.t("dialogs.account.deleteTitle", account.name)) },
        text = { Text(strings.t("dialogs.account.deleteMessage")) },
        confirmButton = {
            TextButton({
                actions.run { actions.controller.accounts.remove(account.id) }
                onDismiss()
            }) {
                Text(strings.t("dialogs.account.deleteConfirm"), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onDismiss) { Text(strings.t("dialogs.common.cancel")) } },
    )
}

/** 微软设备码登录：自动复制验证码、每秒刷新剩余有效期，到期或取消都收起对话框。 */
@Composable
internal fun MicrosoftDeviceCodeDialog(
    code: DeviceCode,
    actions: UiActions,
    onClose: () -> Unit,
) {
    val strings = LocalStrings.current
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
        strings.t("dialogs.account.msLogin.title"),
        strings.t("dialogs.account.msLogin.message"),
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
                        true -> strings.t("dialogs.account.codeCopied")
                        false -> strings.t("dialogs.account.codeCopyFailed")
                        null -> strings.t("dialogs.account.codeCopying")
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
                    strings.t("dialogs.account.codeExpiry", remainingLabel(remainingMillis)),
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
                actions.controller.store.notify(
                    if (ok) {
                        strings.t("dialogs.account.codeCopiedShort")
                    } else {
                        strings.t("dialogs.account.codeCopyFailed")
                    },
                )
            }) {
                Text(strings.t("dialogs.account.copyAgain"), fontSize = 13.sp)
            }
            PrimaryButton(strings.t("dialogs.account.openCodePage"), Icons.Outlined.OpenInNew) {
                actions.run { actions.controller.platform.openUrl(code.verificationUri) }
            }
        }
        MutedText(strings.t("dialogs.account.msLogin.hint"))
    }
}
