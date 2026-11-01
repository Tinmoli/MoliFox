// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责账户列表、账户切换和账户操作入口。

package dev.molifox.desktop

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.molifox.core.Account
import dev.molifox.core.AccountService
import dev.molifox.core.AccountType
import dev.molifox.core.DeviceCode
import dev.molifox.core.LauncherNoticeKind
import dev.molifox.core.LauncherState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.nio.file.Path

private const val ACCOUNT_SECONDS_PER_MINUTE = 60

/**
 * 账户列表页：左侧常驻 220dp 添加入口栏 + 右侧账户卡片栏。
 *
 * - 顶栏固定：返回箭头 + 「账户列表」标题；
 * - 左栏入口逐行铺满宽度：微软账户 / 离线账户 / 每台已保存的认证服务器各一行（行名是服务器名）/ 添加认证服务器， 行高 40dp、图标 + 文字、悬停淡入行底色，与全站列表行同一节奏；
 *   点服务器行直接进该服务器的用户名与密码；没有已保存服务器时才显示灰掉的「认证服务器」行与提示， 添加认证服务器入口则始终在最后，打开同一个对话框并把焦点放在地址框上；
 * - 右栏是账户卡片：单选圆点、40dp 头像、名字 + 类型、悬停才出现的删除；
 * - 列表为空时右栏居中显示史蒂夫头像 + 「没有游戏账户」+ 一行「从左侧添加账户」提示。
 *
 * 认证服务器行打开 [AuthlibLoginDialog] 的 [AuthlibEntry.Server] 入口，直接落在用户名与密码； [AuthlibEntry.NewServer]
 * 入口先校验并保存 Yggdrasil 服务器。对话框保存账户后会写入同一个 store，本页直接随 [LauncherState] 重新组合，无需回调刷新。
 *
 * 切换与删除直接复用核心的 AccountService：[AccountService.select] 切换当前账户， [AccountService.remove]
 * 在删除当前账户时自动落到剩下的第一个账户上（列表空了就是无账户态）。 皮肤概览与刷新由开始页账户卡提供，此页只有删除入口。 视觉沿用本项目樱花粉：白卡片、1dp 细边、圆角
 * 6，当前账户只多一圈主题色描边与实心圆点。
 */
@Composable
fun ColumnScope.AccountListPage(
    state: LauncherState,
    actions: UiActions,
    onBack: () -> Unit,
) {
    val strings = LocalStrings.current
    var offlineDialog by remember { mutableStateOf(false) }
    // 认证服务器入口的两种打开方式：已保存列表（默认含内置 LittleSkin）或直接填写新地址。
    var authlibEntry by remember { mutableStateOf<AuthlibEntry?>(null) }
    var removing by remember { mutableStateOf<Account?>(null) }
    var signingIn by remember { mutableStateOf(false) }
    var deviceCode by remember { mutableStateOf<DeviceCode?>(null) }

    fun beginMicrosoftLogin() {
        if (signingIn) return
        signingIn = true
        actions.run {
            try {
                val code = actions.controller.accounts.beginMicrosoftLogin()
                withContext(Dispatchers.Swing) { deviceCode = code }
                actions.controller.platform.openUrl(code.verificationUri)
            } finally {
                withContext(Dispatchers.Swing) { signingIn = false }
            }
        }
    }

    LaunchedEffect(state.toast) {
        // 登录成功、验证码过期或流程失败后立刻收掉验证码，不留过期码在屏幕上。
        if (loginSettled(actions.controller.store.noticeKind())) deviceCode = null
    }

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        SmallIconButton(Icons.Outlined.ArrowBack, strings.t("dialogs.account.back"), onBack)
        Spacer(Modifier.width(8.dp))
        // 独立子页保留标题：18sp SemiBold 说明当前所在位置，与实例详情页头一致。
        Text(
            strings.t("dialogs.account.listTitle"),
            Modifier.weight(1f),
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
    Spacer(Modifier.height(14.dp))
    Row(
        Modifier.weight(1f).fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Metrics.cardGap),
    ) {
        AccountSidebar(
            state = state,
            signingIn = signingIn,
            onMicrosoft = { beginMicrosoftLogin() },
            onOffline = { offlineDialog = true },
            onServer = { server -> authlibEntry = AuthlibEntry.Server(server) },
            onAddServer = { authlibEntry = AuthlibEntry.NewServer },
        )
        AccountCards(state, actions) { account -> removing = account }
    }

    if (offlineDialog) OfflineAccountDialog(actions) { offlineDialog = false }

    authlibEntry?.let { entry ->
        // 同一个对话框：入口决定先看已保存列表，还是直接填地址。
        AuthlibLoginDialog(actions, entry) { authlibEntry = null }
    }

    removing?.let { account ->
        RemoveAccountDialog(account, actions) { removing = null }
    }

    deviceCode?.let { code ->
        MicrosoftDeviceCodeDialog(code, actions) { deviceCode = null }
    }
}

/** 左栏入口行高度：40dp，与全站列表行同一节奏（比导航项 32 略高，因为它是带图标的操作行）。 */
private val addAccountRowHeight = 40.dp

/**
 * 左栏添加入口：整行铺满宽度、圆角 6、16dp 图标 + 13sp 文字、左侧 10dp 内边距。 悬停时行底色按 120ms 淡入（与账户卡片同一套
 * [rowHoverColor]），禁用行降到 38% 且不响应悬停与点击。
 */
@Composable
internal fun AddAccountRow(
    icon: ImageVector,
    label: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(Metrics.cardRadius)
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val hoverColor by
        animateColorAsState(
            if (hovered && enabled) rowHoverColor() else Color.Transparent,
            tween(Motion.FAST),
            label = "addAccountRowHover",
        )
    val tint =
        if (enabled) {
            MaterialTheme.colorScheme.onSurface
        } else {
            MaterialTheme.colorScheme.onSurface.copy(alpha = .38f)
        }
    Row(
        Modifier
            .fillMaxWidth()
            .height(addAccountRowHeight)
            .clip(shape)
            .background(hoverColor)
            .hoverable(interaction, enabled = enabled)
            .clickable(
                enabled = enabled,
                role = Role.Button,
                onClickLabel = label,
                onClick = onClick,
            ).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(16.dp), tint)
        Spacer(Modifier.width(10.dp))
        Text(
            label,
            Modifier.weight(1f),
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = tint,
            maxLines = 1,
            // 服务器名可能很长（或直接退回地址），行宽固定 220dp，超出省略而不是撑开或截断到半个字。
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 账户卡片副标题：外置认证账户显示服务器名（已保存的名称，否则退回主机名）。 服务器记录被移除时核心不会抛错，仍识别不出时退回账户上的原始地址，列表照常渲染。 */
internal fun accountSubtitle(
    account: Account,
    actions: UiActions,
): String =
    when (account.type) {
        AccountType.Microsoft -> tr("dialogs.account.microsoft")
        AccountType.Offline -> tr("dialogs.account.offlineMode")
        AccountType.Authlib ->
            actions.controller.accounts.authlibServerName(account.authlibServer).ifBlank {
                account.authlibServer.ifBlank { tr("dialogs.account.authlibAccount") }
            }
    }

/** 账户卡片：单选圆点 + 40dp 头像 + 名字 / 类型，悬停时行尾淡入「删除」。 */
@Composable
internal fun AccountCard(
    account: Account,
    current: Boolean,
    subtitle: String,
    actions: UiActions,
    onSelect: () -> Unit,
    onDelete: () -> Unit,
) {
    val strings = LocalStrings.current
    val shape = RoundedCornerShape(Metrics.cardRadius)
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    // 悬停底色直接参与卡片底色（画在 surface 之上、内容之下），不会像整层遮罩那样把文字也染色。
    val hoverColor by
        animateColorAsState(
            if (hovered) rowHoverColor() else Color.Transparent,
            tween(Motion.FAST),
            label = "accountCardHover",
        )
    // 头像只按账户与皮肤字段解析；皮肤概览 / 刷新已回到开始页，回到本页时重新进入组合即会重读。
    val skinPath by
        produceState<Path?>(null, account.id, account.skinUrl, account.localSkinId) {
            value = actions.controller.accountSkin(account)
        }
    Box(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .background(hoverColor)
            .border(
                1.dp,
                if (current) MaterialTheme.colorScheme.primary.copy(alpha = .45f) else hairline(),
                shape,
            ).hoverable(interaction)
            .clickable(
                role = Role.RadioButton,
                onClickLabel = strings.t("dialogs.account.switchTo"),
                onClick = onSelect,
            ).padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            RadioMark(current)
            Spacer(Modifier.width(12.dp))
            StaticSkinHead(
                skinPath,
                { actions.controller.store.log(strings.t("dialogs.account.avatarReadFailed", it.message), true) },
                0,
                Modifier.size(40.dp).clip(RoundedCornerShape(Metrics.innerRadius)),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(
                    account.name,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    subtitle,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            Spacer(Modifier.width(8.dp))
            // 固定宽度承载悬停操作：图标淡入淡出时名字列不会跟着抖动。
            Box(Modifier.width(40.dp), contentAlignment = Alignment.CenterEnd) {
                AccountRowActions(visible = hovered, onDelete = onDelete)
            }
        }
    }
}

/** 行尾操作：悬停才淡入的「删除」，宽度固定由外层占位，不推挤名字列。 皮肤概览与刷新由开始页账户卡提供； 切换不单独放按钮：整张卡片本身就是单选行，点卡片即切换。 */
@Composable
private fun AccountRowActions(
    visible: Boolean,
    onDelete: () -> Unit,
) {
    val strings = LocalStrings.current
    AnimatedVisibility(
        visible,
        enter = fadeIn(tween(Motion.FAST)),
        exit = fadeOut(tween(Motion.FAST)),
    ) {
        Row {
            AccountIconAction(
                Icons.Outlined.DeleteOutline,
                strings.t("dialogs.account.deleteConfirm"),
                onDelete,
                tint = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/** 单选圆点：当前账户实心主题色 + 白色内点，其余只有 1.5dp 描边。 */
@Composable
private fun RadioMark(current: Boolean) {
    val colors = MaterialTheme.colorScheme
    Box(
        Modifier
            .size(16.dp)
            .clip(CircleShape)
            .background(if (current) colors.primary else Color.Transparent)
            .border(1.5.dp, if (current) colors.primary else colors.outline, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (current) Box(Modifier.size(6.dp).clip(CircleShape).background(colors.onPrimary))
    }
}

/** 离线账户创建：校验名称为 3–16 位字母、数字或下划线校验，添加后核心自动切为当前账户。 */
@Composable
private fun OfflineAccountDialog(
    actions: UiActions,
    onDismiss: () -> Unit,
) {
    val strings = LocalStrings.current
    var playerName by remember { mutableStateOf("") }
    val validName = Regex("[A-Za-z0-9_]{3,16}").matches(playerName)
    Modal(strings.t("dialogs.account.addOffline"), strings.t("dialogs.account.addOfflineHint"), 460.dp, onDismiss) {
        CompactTextField(
            playerName,
            { playerName = it.take(16) },
            Modifier.fillMaxWidth(),
            label = strings.t("dialogs.account.playerName"),
            placeholder = strings.t("dialogs.account.playerNamePlaceholder"),
        )
        // 输入不合法时就地给错误色说明，而不是只把按钮置灰。
        Text(
            if (playerName.isNotEmpty() && !validName) {
                strings.t("dialogs.account.nameRule")
            } else {
                strings.t("dialogs.account.nameRuleShort")
            },
            fontSize = 11.sp,
            color =
                if (playerName.isNotEmpty() && !validName) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Spacer(Modifier.weight(1f))
            SecondaryButton(onDismiss) { Text(strings.t("dialogs.common.cancel"), fontSize = 13.sp) }
            PrimaryButton(
                strings.t("dialogs.account.add"),
                enabled = validName,
                onClick = {
                    actions.run {
                        actions.controller.accounts.addOffline(playerName)
                        withContext(Dispatchers.Swing) { onDismiss() }
                    }
                },
            )
        }
    }
}

/** 复制文本到系统剪贴板。AWT 在剪贴板被占用或没有可用剪贴板时会抛异常， 这里统一折算成布尔结果，交给对话框显示「验证码复制失败，请手动复制」而不是静默失败。 */
internal fun copyToClipboard(text: String): Boolean =
    runCatching {
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
    }.isSuccess

/** 微软登录流程已经结束的提示：成功、验证码过期、被拒绝或失败都要收掉屏幕上的验证码。 */
private fun loginSettled(notice: LauncherNoticeKind?): Boolean =
    notice == LauncherNoticeKind.LoginSignedIn || notice == LauncherNoticeKind.LoginEnded

/** 验证码剩余有效期：向上取整到秒，按 mm:ss 显示；已经过期时显示 00:00。 */
internal fun remainingLabel(millis: Long): String {
    val seconds = ((millis + 999) / 1000).coerceAtLeast(0L)
    val minutes = seconds / ACCOUNT_SECONDS_PER_MINUTE
    val remainder = seconds % ACCOUNT_SECONDS_PER_MINUTE
    return "%02d:%02d".format(minutes, remainder)
}
