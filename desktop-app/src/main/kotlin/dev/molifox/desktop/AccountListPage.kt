// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责账户列表、账户切换和账户操作入口。

package dev.molifox.desktop

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.molifox.core.*
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext

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
    var offlineDialog by remember { mutableStateOf(false) }
    // 认证服务器入口的两种打开方式：已保存列表（默认含内置 LittleSkin）或直接填写新地址。
    var authlibEntry by remember { mutableStateOf<AuthlibEntry?>(null) }
    var removing by remember { mutableStateOf<Account?>(null) }
    var signingIn by remember { mutableStateOf(false) }
    var deviceCode by remember { mutableStateOf<DeviceCode?>(null) }
    // 验证码复制结果：null = 还在复制（首帧），true = 已复制到剪贴板，false = 复制失败需手动复制。
    var copied by remember { mutableStateOf<Boolean?>(null) }
    // 验证码剩余有效期（毫秒）：对话框每秒刷新，用于显示剩余时间并在到期时自动收起。
    var remainingMillis by remember { mutableStateOf(0L) }

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

    // 设备码一到就自动复制：结果写进 copied，失败时对话框中显示「验证码复制失败，请手动复制」。
    LaunchedEffect(deviceCode?.userCode) {
        val code = deviceCode ?: return@LaunchedEffect
        remainingMillis = code.expiresAt - System.currentTimeMillis()
        copied = copyToClipboard(code.userCode)
    }
    LaunchedEffect(state.toast) {
        // 登录成功、验证码过期或流程失败后立刻收掉验证码与「已复制」提示，不留过期码在屏幕上。
        if (loginSettled(state.toast)) {
            deviceCode = null
            copied = null
        }
    }

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        SmallIconButton(Icons.Outlined.ArrowBack, "返回上一页", onBack)
        Spacer(Modifier.width(8.dp))
        // 独立子页保留标题：18sp SemiBold 说明当前所在位置，与实例详情页头一致。
        Text(
            "账户列表",
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
        // 左栏：添加入口逐行铺满 220dp，行高 / 圆角 / 悬停行底色与全站列表行一致。
        // 已保存的认证服务器每台一行，服务器多了这一栏要能滚动，不能顶出可视区。
        Column(
            Modifier.width(220.dp).fillMaxHeight().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            AddAccountRow(Icons.Outlined.AccountCircle, "微软账户", enabled = !signingIn) {
                beginMicrosoftLogin()
            }
            AddAccountRow(Icons.Outlined.PersonOutline, "离线账户") { offlineDialog = true }
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
                        authlibEntry = AuthlibEntry.Server(server)
                    }
                }
            }
            AddAccountRow(Icons.Outlined.AddCircleOutline, "添加认证服务器") {
                authlibEntry = AuthlibEntry.NewServer
            }
        }
        // 右栏：账户卡片列表或空态，始终占满剩余宽度，空态也在这栏里居中。
        Column(Modifier.weight(1f).fillMaxHeight()) {
            if (state.data.accounts.isEmpty())
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        // 没有账户时 StaticSkinHead 会退回内置史蒂夫纹理。
                        StaticSkinHead(
                            null,
                            actions.controller.store.directories,
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
            else
                Column(
                    Modifier.fillMaxSize()
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
                                if (account.id != state.data.activeAccountId)
                                    actions.run { actions.controller.accounts.select(account.id) }
                            },
                            onDelete = { removing = account },
                        )
                    }
                }
        }
    }

    if (offlineDialog) OfflineAccountDialog(actions) { offlineDialog = false }

    authlibEntry?.let { entry ->
        // 同一个对话框：入口决定先看已保存列表，还是直接填地址。
        AuthlibLoginDialog(actions, entry) { authlibEntry = null }
    }

    removing?.let { account ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("删除「" + account.name + "」？") },
            text = { Text("此账户的本地登录凭据将删除，游戏存档会保留。") },
            confirmButton = {
                TextButton({
                    // 核心的 remove 在删除当前账户时会把当前账户落到剩下的第一个；
                    // 列表删空后 activeAccountId 变为 null，页面回到「没有游戏账户」态。
                    actions.run { actions.controller.accounts.remove(account.id) }
                    removing = null
                }) {
                    Text("删除账户", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton({ removing = null }) { Text("取消") } },
        )
    }

    deviceCode?.let { code ->
        // 有效期来自设备码响应的 expires_in：每秒刷新剩余时间，到期先关掉验证码，
        // 避免已作废的码继续留在对话框里（核心轮询仍由 AccountService 自己兜底）。
        LaunchedEffect(code.userCode) {
            remainingMillis = code.expiresAt - System.currentTimeMillis()
            while (remainingMillis > 0) {
                delay(1_000L)
                remainingMillis = code.expiresAt - System.currentTimeMillis()
            }
            actions.controller.accounts.cancelLogin()
            deviceCode = null
            copied = null
        }
        Modal(
            "在浏览器中完成登录",
            "使用微软账户，输入下方验证码。",
            470.dp,
            {
                actions.controller.accounts.cancelLogin()
                deviceCode = null
                copied = null
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
                            if (copied == false) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant,
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
}

/** 左栏入口行高度：40dp，与全站列表行同一节奏（比导航项 32 略高，因为它是带图标的操作行）。 */
private val addAccountRowHeight = 40.dp

/**
 * 左栏添加入口：整行铺满宽度、圆角 6、16dp 图标 + 13sp 文字、左侧 10dp 内边距。 悬停时行底色按 120ms 淡入（与账户卡片同一套
 * [rowHoverColor]），禁用行降到 38% 且不响应悬停与点击。
 */
@Composable
private fun AddAccountRow(
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
            tween(Motion.fast),
            label = "addAccountRowHover",
        )
    val tint =
        if (enabled) MaterialTheme.colorScheme.onSurface
        else MaterialTheme.colorScheme.onSurface.copy(alpha = .38f)
    Row(
        Modifier.fillMaxWidth()
            .height(addAccountRowHeight)
            .clip(shape)
            .background(hoverColor)
            .hoverable(interaction, enabled = enabled)
            .clickable(
                enabled = enabled,
                role = Role.Button,
                onClickLabel = label,
                onClick = onClick,
            )
            .padding(horizontal = 10.dp),
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
private fun accountSubtitle(account: Account, actions: UiActions): String =
    when (account.type) {
        AccountType.Microsoft -> "微软账户"
        AccountType.Offline -> "离线模式"
        AccountType.Authlib ->
            actions.controller.accounts.authlibServerName(account.authlibServer).ifBlank {
                account.authlibServer.ifBlank { "外置认证账户" }
            }
    }

/** 账户卡片：单选圆点 + 40dp 头像 + 名字 / 类型，悬停时行尾淡入「删除」。 */
@Composable
private fun AccountCard(
    account: Account,
    current: Boolean,
    subtitle: String,
    actions: UiActions,
    onSelect: () -> Unit,
    onDelete: () -> Unit,
) {
    val shape = RoundedCornerShape(Metrics.cardRadius)
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    // 悬停底色直接参与卡片底色（画在 surface 之上、内容之下），不会像整层遮罩那样把文字也染色。
    val hoverColor by
        animateColorAsState(
            if (hovered) rowHoverColor() else Color.Transparent,
            tween(Motion.fast),
            label = "accountCardHover",
        )
    // 头像只按账户与皮肤字段解析；皮肤概览 / 刷新已回到开始页，回到本页时重新进入组合即会重读。
    val skinPath by
        produceState<Path?>(null, account.id, account.skinUrl, account.localSkinId) {
            value = actions.controller.accountSkin(account)
        }
    Box(
        Modifier.fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .background(hoverColor)
            .border(
                1.dp,
                if (current) MaterialTheme.colorScheme.primary.copy(alpha = .45f) else hairline(),
                shape,
            )
            .hoverable(interaction)
            .clickable(
                role = Role.RadioButton,
                onClickLabel = "切换为当前账户",
                onClick = onSelect,
            )
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            RadioMark(current)
            Spacer(Modifier.width(12.dp))
            StaticSkinHead(
                skinPath,
                actions.controller.store.directories,
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
private fun AccountRowActions(visible: Boolean, onDelete: () -> Unit) {
    AnimatedVisibility(
        visible,
        enter = fadeIn(tween(Motion.fast)),
        exit = fadeOut(tween(Motion.fast)),
    ) {
        Row {
            AccountIconAction(
                Icons.Outlined.DeleteOutline,
                "删除账户",
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
        Modifier.size(16.dp)
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
private fun OfflineAccountDialog(actions: UiActions, onDismiss: () -> Unit) {
    var playerName by remember { mutableStateOf("") }
    val validName = Regex("[A-Za-z0-9_]{3,16}").matches(playerName)
    Modal("添加离线账户", "离线角色只在本机生效，不会登录微软服务。", 460.dp, onDismiss) {
        CompactTextField(
            playerName,
            { playerName = it.take(16) },
            Modifier.fillMaxWidth(),
            label = "玩家名称",
            placeholder = "例如 Steve",
        )
        // 输入不合法时就地给错误色说明，而不是只把按钮置灰。
        Text(
            if (playerName.isNotEmpty() && !validName) "只能使用 3–16 位字母、数字或下划线"
            else "3–16 位字母、数字或下划线",
            fontSize = 11.sp,
            color =
                if (playerName.isNotEmpty() && !validName) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Spacer(Modifier.weight(1f))
            SecondaryButton(onDismiss) { Text("取消", fontSize = 13.sp) }
            PrimaryButton(
                "添加账户",
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
private fun copyToClipboard(text: String): Boolean = runCatching {
    Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
}
    .isSuccess

/** 微软登录流程已经结束的提示：成功、验证码过期、被拒绝或失败都要收掉屏幕上的验证码。 */
private fun loginSettled(toast: String?): Boolean =
    toast != null &&
        (toast.startsWith("已登录：") ||
            toast.startsWith("登录验证码已过期") ||
            toast.startsWith("登录已过期") ||
            toast.startsWith("微软登录失败"))

/** 验证码剩余有效期：向上取整到秒，按 mm:ss 显示；已经过期时显示 00:00。 */
private fun remainingLabel(millis: Long): String {
    val seconds = ((millis + 999) / 1000).coerceAtLeast(0L)
    return "%02d:%02d".format(seconds / 60, seconds % 60)
}
