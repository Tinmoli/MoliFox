package dev.molifox.desktop

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.molifox.core.*
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import kotlinx.coroutines.*
import kotlinx.coroutines.swing.Swing
import org.jetbrains.skia.Image as SkiaImage

/**
 * PCL 风格的账户控制，仍放在开始页、紧挨启动操作。 本次重做的只包括视觉与交互：分段切换 34dp、账户行 44dp、选中账户左侧 2dp 竖条、行悬停淡入。 登录 / 切换 / 离线建档 /
 * 注销的回调与参数一律沿用原实现。
 */
@Composable
fun HomeAccountPanel(state: LauncherState, actions: UiActions, modifier: Modifier = Modifier) {
    val active = state.data.accounts.find { it.id == state.data.activeAccountId }
    var mode by remember(active?.type) { mutableStateOf(active?.type ?: AccountType.Microsoft) }
    var adding by remember { mutableStateOf(false) }
    var playerName by remember { mutableStateOf("") }
    var signingIn by remember { mutableStateOf(false) }
    var deviceCode by remember { mutableStateOf<DeviceCode?>(null) }
    var removing by remember { mutableStateOf<Account?>(null) }
    // 头像重新读取：刷新按钮只递增这个计数，不动任何业务状态
    var skinRevision by remember { mutableStateOf(0) }
    val shown = active?.takeIf { it.type == mode && !adding }
    val validName = Regex("[A-Za-z0-9_]{3,16}").matches(playerName)
    fun changeMode(value: AccountType) {
        mode = value
        val existing = state.data.accounts.firstOrNull { it.type == value }
        adding = existing == null
        if (existing != null) actions.run { actions.controller.accounts.select(existing.id) }
    }
    LaunchedEffect(state.toast) {
        if (state.toast?.startsWith("已登录：") == true) {
            deviceCode = null
            adding = false
        }
    }
    Column(modifier) {
        AccountModeSegments(mode) { changeMode(it) }
        Spacer(Modifier.height(12.dp))
        Box(Modifier.weight(1f).fillMaxWidth()) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(end = 2.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                AccountHeadline(
                    shown,
                    mode,
                    actions,
                    skinRevision,
                    onRefresh = { skinRevision++ },
                    onRemove = { removing = shown },
                )
                if (shown == null) {
                    if (mode == AccountType.Offline) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            CompactTextField(
                                playerName,
                                { playerName = it.take(16) },
                                Modifier.fillMaxWidth(),
                                label = "玩家名称",
                                placeholder = "例如 Steve",
                            )
                            // 与 HMCL 一致：输入不合法时就地给错误色说明，而不是只把按钮置灰
                            Text(
                                if (playerName.isNotEmpty() && !validName) "只能使用 3–16 位字母、数字或下划线"
                                else "3–16 位字母、数字或下划线",
                                fontSize = 11.sp,
                                color =
                                    if (playerName.isNotEmpty() && !validName)
                                        MaterialTheme.colorScheme.error
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Button(
                                {
                                    actions.run {
                                        actions.controller.accounts.addOffline(playerName)
                                        withContext(Dispatchers.Swing) {
                                            adding = false
                                            playerName = ""
                                        }
                                    }
                                },
                                Modifier.fillMaxWidth().height(Metrics.controlHeight),
                                enabled = validName,
                                shape = RoundedCornerShape(Metrics.innerRadius),
                            ) {
                                Text("添加账户", fontSize = 13.sp)
                            }
                        }
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                "使用拥有 Minecraft Java 版的微软账户登录。",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            SecondaryButton(
                                {
                                    signingIn = true
                                    actions.run {
                                        try {
                                            val code =
                                                actions.controller.accounts.beginMicrosoftLogin()
                                            withContext(Dispatchers.Swing) {
                                                deviceCode = code
                                            }
                                            actions.controller.platform.openUrl(
                                                code.verificationUri
                                            )
                                        } finally {
                                            withContext(Dispatchers.Swing) {
                                                signingIn = false
                                            }
                                        }
                                    }
                                },
                                Modifier.fillMaxWidth(),
                                enabled = !signingIn,
                                colors =
                                    ButtonDefaults.outlinedButtonColors(
                                        containerColor = MaterialTheme.colorScheme.primary,
                                        contentColor = MaterialTheme.colorScheme.onPrimary,
                                    ),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                            ) {
                                if (signingIn) {
                                    CircularProgressIndicator(
                                        Modifier.size(16.dp),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.onPrimary,
                                    )
                                    Spacer(Modifier.width(8.dp))
                                }
                                Text(
                                    if (signingIn) "正在连接…" else "微软登录",
                                    fontSize = 13.sp,
                                )
                            }
                        }
                    }
                } else {
                    Text(
                        if (shown.type == AccountType.Microsoft) "Minecraft Java · 微软账户"
                        else "离线角色 · 本地档案",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // 账户列表：44dp 行，选中行左侧 2dp 主题色竖条，悬停淡入
                if (state.data.accounts.isNotEmpty())
                    Column(Modifier.fillMaxWidth()) {
                        state.data.accounts.forEach { account ->
                            AccountRow(
                                account,
                                account.id == active?.id,
                                onClick = {
                                    mode = account.type
                                    adding = false
                                    actions.run {
                                        actions.controller.accounts.select(account.id)
                                    }
                                },
                            )
                        }
                    }
                SecondaryButton(
                    {
                        mode = AccountType.Offline
                        adding = true
                        playerName = ""
                    },
                    Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                ) {
                    Icon(Icons.Outlined.PersonAdd, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("添加离线账户", fontSize = 13.sp)
                }
                if (adding && active?.type == mode)
                    TextButton({ adding = false }, contentPadding = PaddingValues(0.dp)) {
                        Text("返回当前账户", fontSize = 12.sp)
                    }
            }
        }
    }
    deviceCode?.let { code ->
        Modal(
            "在浏览器中完成登录",
            "使用微软账户，输入下方验证码。",
            470.dp,
            {
                actions.controller.accounts.cancelLogin()
                deviceCode = null
            },
        ) {
            Surface(
                Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(Metrics.cardRadius),
                color = MaterialTheme.colorScheme.secondaryContainer,
            ) {
                SelectionContainer {
                    Text(
                        code.userCode,
                        Modifier.padding(20.dp),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 5.sp,
                    )
                }
            }
            MutedText("授权完成后会自动添加角色。服务错误可在任务与日志中查看。")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SecondaryButton({
                    Toolkit.getDefaultToolkit()
                        .systemClipboard
                        .setContents(StringSelection(code.userCode), null)
                    actions.controller.store.notify("验证码已复制")
                }) {
                    Text("复制验证码", fontSize = 13.sp)
                }
                PrimaryButton("打开登录页面", Icons.Outlined.OpenInNew) {
                    actions.run { actions.controller.platform.openUrl(code.verificationUri) }
                }
            }
        }
    }
    removing?.let { account ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("移除 " + account.name + "？") },
            text = { Text("此账户的本地登录凭据将移除，游戏存档会保留。") },
            confirmButton = {
                TextButton({
                    actions.run { actions.controller.accounts.remove(account.id) }
                    removing = null
                }) {
                    Text("移除账户")
                }
            },
            dismissButton = { TextButton({ removing = null }) { Text("取消") } },
        )
    }
}

/** 分段切换：34dp、圆角 4，选中底色与文字色用 120ms 过渡。 */
@Composable
private fun AccountModeSegments(mode: AccountType, onChange: (AccountType) -> Unit) {
    val shape = RoundedCornerShape(Metrics.innerRadius)
    Row(
        Modifier.fillMaxWidth()
            .height(Metrics.controlHeight)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant, shape)
            .padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        listOf(AccountType.Microsoft to "微软", AccountType.Offline to "离线").forEach { pair ->
            val selected = mode == pair.first
            val background by
                animateColorAsState(
                    if (selected) MaterialTheme.colorScheme.surface else Color.Transparent,
                    tween(Motion.fast),
                    label = "segment-bg",
                )
            val textColor by
                animateColorAsState(
                    if (selected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    tween(Motion.fast),
                    label = "segment-fg",
                )
            Box(
                Modifier.weight(1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(Metrics.innerRadius))
                    .background(background)
                    .then(
                        if (selected) Modifier
                        else Modifier.hoverHighlight(RoundedCornerShape(Metrics.innerRadius))
                    )
                    .clickable { onChange(pair.first) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    pair.second,
                    color = textColor,
                    fontSize = 13.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                )
            }
        }
    }
}

/** 当前账户：72dp 正面方形头像 + 名称 14sb + 类型 12 + 刷新 / 注销图标按钮。 */
@Composable
private fun AccountHeadline(
    shown: Account?,
    mode: AccountType,
    actions: UiActions,
    skinRevision: Int,
    onRefresh: () -> Unit,
    onRemove: () -> Unit,
) {
    var managingSkin by remember(shown?.id) { mutableStateOf(false) }
    if (managingSkin && shown != null)
        SkinManagerDialog(shown, actions, onRefresh, { managingSkin = false })
    // 在线账户尝试加载真实皮肤；离线与加载失败时回退到内置类 Steve 头像。
    val skinBitmap by
        produceState<ImageBitmap?>(
            null,
            shown?.id,
            shown?.skinUrl,
            shown?.localSkinId,
            skinRevision,
        ) {
            val account = shown
            if (account == null) {
                value = null
                return@produceState
            }
            value = null
            val cached = actions.controller.accountSkin(account, refresh = skinRevision > 0)
            value = cached?.let { path ->
                runCatching {
                    SkiaImage.makeFromEncoded(java.nio.file.Files.readAllBytes(path))
                        .toComposeImageBitmap()
                }
                    .getOrNull()
            }
        }
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (shown != null) SkinHead(skinBitmap, Modifier.size(72.dp))
        else
            Box(
                Modifier.size(72.dp)
                    .clip(RoundedCornerShape(Metrics.cardRadius))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Outlined.PersonOutline,
                    null,
                    Modifier.size(30.dp),
                    MaterialTheme.colorScheme.primary,
                )
            }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                shown?.name ?: if (mode == AccountType.Offline) "创建离线角色" else "登录微软账户",
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                when {
                    shown == null -> "尚未添加账户"
                    shown.type == AccountType.Microsoft -> "微软账户 · 已登录"
                    else -> "离线角色 · 本地档案"
                },
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (shown != null) {
            SmallIconButton(Icons.Outlined.Palette, "管理账户皮肤", { managingSkin = true })
            SmallIconButton(Icons.Outlined.Refresh, "刷新账户头像", onRefresh)
            SmallIconButton(Icons.Outlined.DeleteOutline, "注销当前账户", onRemove)
        }
    }
}

/** 账户行：44dp、悬停淡入，选中时左侧 2dp 主题色竖条（不用大色块）。 */
@Composable
private fun AccountRow(account: Account, current: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(Metrics.innerRadius)
    Row(
        Modifier.fillMaxWidth()
            .height(Metrics.rowHeight)
            .hoverHighlight(shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.width(2.dp)
                .height(20.dp)
                .background(
                    if (current) MaterialTheme.colorScheme.primary else Color.Transparent,
                    RoundedCornerShape(1.dp),
                )
        )
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier.size(24.dp)
                .clip(RoundedCornerShape(Metrics.innerRadius))
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                account.name.take(1).uppercase(),
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                account.name,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (account.type == AccountType.Microsoft) "微软账户" else "离线角色",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        if (current)
            Icon(
                Icons.Outlined.Check,
                "当前账户",
                Modifier.size(16.dp),
                MaterialTheme.colorScheme.primary,
            )
    }
}
