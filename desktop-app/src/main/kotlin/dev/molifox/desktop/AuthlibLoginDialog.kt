// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责外置认证服务器选择、登记与账户登录界面。

package dev.molifox.desktop

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.molifox.core.AuthlibServer

/** 外置认证登录的两步：先确认服务器，再输入该服务器上的用户名与密码。 */
internal enum class AuthlibLoginStep {
    Server,
    Credentials,
}

/**
 * 账户页的两个认证服务器入口：[Server] 直接进入某台已保存服务器的用户名与密码步骤（不经过服务器列表、 不需要「下一步」、也不显示地址框），[NewServer]
 * 打开即把光标放进地址框，添加并保存新服务器。
 */
sealed interface AuthlibEntry {
    /** 已保存服务器各占账户页一行：点哪台就登录哪台。 */
    data class Server(
        val server: AuthlibServer,
    ) : AuthlibEntry

    /** 「添加认证服务器」入口：先填地址、校验保存，再输入用户名与密码。 */
    data object NewServer : AuthlibEntry
}

/**
 * 添加外置认证（authlib-injector / Yggdrasil）账户的两步对话框。
 *
 * - [AuthlibEntry.NewServer] 从第 1 步开始：把焦点放在地址框上，校验通过后 `addAuthlibServer`
 *   会先保存再用它登录；已保存的服务器也在这里列一次，方便在行尾移除（不影响已添加账户）；
 * - [AuthlibEntry.Server] 直接落在第 2 步：账户页上每台已保存服务器各有一行，点开就是这台服务器的 用户名与密码，没有服务器列表、没有「下一步」、没有地址框；
 * - 第 2 步用 `addAuthlibAccount` 登录：失败时按 [AuthlibError] 折算中文提示且不关闭对话框， 成功后关闭由账户列表的 store
 *   变更自动刷新（与离线账户、微软账户同一路径）。
 *
 * 请求期间按钮置灰并显示 14dp 转圈，界面线程不做阻塞等待；Esc 关闭，Enter 执行当前一步。
 */
@Composable
fun AuthlibLoginDialog(
    actions: UiActions,
    entry: AuthlibEntry = AuthlibEntry.NewServer,
    onDismiss: () -> Unit,
) {
    val strings = LocalStrings.current
    val state by actions.controller.state.collectAsState()
    // 服务器列表跟随 launcher.json 的 authlibServers：新增或删除后对话框不必重开。
    val servers =
        remember(state.data.authlibServers) {
            actions.controller.accounts.authlibServers()
        }
    // 从账户页的服务器行进来时直接是第 2 步，服务器已经确定。
    val preset = (entry as? AuthlibEntry.Server)?.server
    var step by remember { mutableStateOf(initialAuthlibStep(entry)) }
    var address by remember { mutableStateOf(preset?.url.orEmpty()) }
    var selectedUrl by remember { mutableStateOf(preset?.url.orEmpty()) }
    var resolved by remember { mutableStateOf(preset) }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var removing by remember { mutableStateOf<AuthlibServer?>(null) }
    val focus = remember { FocusRequester() }
    val addressFocus = remember { FocusRequester() }

    val canValidate = !busy && address.trim().isNotEmpty()
    val canLogin = !busy && username.isNotBlank() && password.isNotEmpty()

    val validate = {
        validateAuthlibServer(
            actions = actions,
            address = address,
            isBusy = { busy },
            onBusy = { busy = it },
            onError = { error = it },
            onResolved = { server ->
                resolved = server
                address = server.url
                selectedUrl = server.url
                step = AuthlibLoginStep.Credentials
                error = null
            },
        )
    }
    // 已保存的服务器都通过过元数据校验：点选即进入第 2 步，不再要求先点「下一步」。
    val choose = { server: AuthlibServer ->
        if (!busy) {
            resolved = server
            address = server.url
            selectedUrl = server.url
            error = null
            step = AuthlibLoginStep.Credentials
        }
    }
    val submit = {
        submitAuthlibLogin(
            actions = actions,
            server = resolved,
            username = username,
            password = password,
            isBusy = { busy },
            onBusy = { busy = it },
            onError = { error = it },
            onSuccess = {
                // 密码只用于本次登录，关闭前立刻丢掉。
                password = ""
                onDismiss()
            },
        )
    }

    // 每一步都把焦点放回内容层：上一步移除的输入框失焦后，Enter 仍能触发当前一步。
    LaunchedEffect(step) { runCatching { focus.requestFocus() } }
    // 「添加认证服务器」入口：打开即把光标放进地址框；等首帧布局完成再请求，避免 FocusRequester 尚未挂载。
    LaunchedEffect(entry, step) {
        if (entry == AuthlibEntry.NewServer && step == AuthlibLoginStep.Server) {
            withFrameNanos {}
            runCatching { addressFocus.requestFocus() }
        }
    }

    val presetLabel = preset?.let { it.name.ifBlank { it.url } }
    Modal(
        authlibDialogTitle(presetLabel),
        authlibDialogHint(presetLabel),
        width = 480.dp,
        onDismiss = onDismiss,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .focusRequester(focus)
                .onPreviewKeyEvent { event ->
                    authlibKeyEvent(
                        event = event,
                        step = step,
                        canValidate = canValidate,
                        password = password,
                        onDismiss = onDismiss,
                        onValidate = validate,
                        onSubmit = submit,
                    )
                }.focusable(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            AuthlibStepLabel(authlibStepTitle(step, preset != null))
            if (step == AuthlibLoginStep.Server) {
                AuthlibServerStep(
                    servers = servers,
                    selectedUrl = selectedUrl,
                    address = address,
                    busy = busy,
                    addressFocus = addressFocus,
                    isBuiltin = { url -> actions.controller.accounts.isBuiltinAuthlibServer(url) },
                    onChoose = choose,
                    onLocked = { error = strings.t("dialogs.authlib.builtinLocked") },
                    onRemove = { server -> removing = server },
                    onAddressChange = {
                        address = it
                        if (it != selectedUrl) selectedUrl = ""
                        error = null
                    },
                )
            } else {
                AuthlibCredentialsStep(
                    server = resolved,
                    username = username,
                    password = password,
                    busy = busy,
                    onUsernameChange = {
                        username = it.take(320)
                        error = null
                    },
                    onPasswordChange = {
                        password = it
                        error = null
                    },
                    onSubmit = submit,
                )
            }
            error?.let { message ->
                Text(message, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
            }
            AuthlibDialogFooter(
                step = step,
                preset = preset != null,
                busy = busy,
                canValidate = canValidate,
                canLogin = canLogin,
                savedCount = servers.size,
                onCancel = onDismiss,
                onValidate = validate,
                onSubmit = submit,
                onBack = {
                    step = AuthlibLoginStep.Server
                    error = null
                },
            )
        }
    }

    removing?.let { server ->
        AuthlibRemoveDialog(
            server = server,
            onDismiss = { removing = null },
            onConfirm = {
                removing = null
                removeAuthlibServer(
                    actions = actions,
                    server = server,
                    label = server.name.ifBlank { server.url },
                    isSelected = server.url == selectedUrl,
                    isResolved = resolved?.url == server.url,
                    onClearSelected = { selectedUrl = "" },
                    onClearResolved = { resolved = null },
                    onError = { error = it },
                )
            },
        )
    }
}

/** 打开时落在第几步：「添加认证服务器」从第 1 步开始，账户页的服务器行直接是第 2 步。 */
private fun initialAuthlibStep(entry: AuthlibEntry): AuthlibLoginStep =
    if (entry == AuthlibEntry.NewServer) AuthlibLoginStep.Server else AuthlibLoginStep.Credentials

/** 第 1 步：已保存服务器列表 + 地址输入框。 */
@Composable
private fun AuthlibServerStep(
    servers: List<AuthlibServer>,
    selectedUrl: String,
    address: String,
    busy: Boolean,
    addressFocus: FocusRequester,
    isBuiltin: (String) -> Boolean,
    onChoose: (AuthlibServer) -> Unit,
    onLocked: () -> Unit,
    onRemove: (AuthlibServer) -> Unit,
    onAddressChange: (String) -> Unit,
) {
    val strings = LocalStrings.current
    if (servers.isEmpty()) {
        MutedText(strings.t("dialogs.authlib.noSavedServers"))
    } else {
        Text(
            strings.t("dialogs.authlib.savedServers"),
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 160.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            servers.forEach { server ->
                AuthlibServerRow(
                    server = server,
                    selected = server.url == selectedUrl,
                    busy = busy,
                    builtin = isBuiltin(server.url),
                    onChoose = { onChoose(server) },
                    onLocked = onLocked,
                    onRemove = { onRemove(server) },
                )
            }
        }
    }
    CompactTextField(
        address,
        onAddressChange,
        Modifier.fillMaxWidth(),
        label =
            if (servers.isEmpty()) {
                strings.t("dialogs.account.authServer")
            } else {
                strings.t("dialogs.authlib.addNewServer")
            },
        placeholder = strings.t("dialogs.authlib.addressPlaceholder"),
        enabled = !busy,
        focusRequester = addressFocus,
    )
}

/**
 * 一行已保存的服务器：内置默认服务器（核心 DEFAULT_AUTHLIB_SERVER，LittleSkin）不给删除入口：
 * 行尾换成灰掉的锁，点了就地提示，而不是静默忽略。
 */
@Composable
private fun AuthlibServerRow(
    server: AuthlibServer,
    selected: Boolean,
    busy: Boolean,
    builtin: Boolean,
    onChoose: () -> Unit,
    onLocked: () -> Unit,
    onRemove: () -> Unit,
) {
    val strings = LocalStrings.current
    val label = server.name.ifBlank { server.url }
    DialogListRow(
        title = label,
        detail = server.url,
        selected = selected,
        onClick = onChoose,
        trailing = {
            if (builtin) {
                SmallIconButton(
                    Icons.Outlined.Lock,
                    strings.t("dialogs.authlib.builtinLocked"),
                    onClick = { if (!busy) onLocked() },
                    tint =
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(
                            alpha = .5f,
                        ),
                    enabled = !busy,
                )
            } else {
                SmallIconButton(
                    Icons.Outlined.DeleteOutline,
                    strings.t("dialogs.authlib.removeServerLabel", label),
                    onClick = { if (!busy) onRemove() },
                    tint = MaterialTheme.colorScheme.error,
                    enabled = !busy,
                )
            }
        },
    )
}

/** 第 2 步：当前服务器摘要 + 用户名与密码。 */
@Composable
private fun AuthlibCredentialsStep(
    server: AuthlibServer?,
    username: String,
    password: String,
    busy: Boolean,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    val strings = LocalStrings.current
    server?.let { AuthlibServerSummary(it) }
    CompactTextField(
        username,
        onUsernameChange,
        Modifier.fillMaxWidth(),
        label = strings.t("dialogs.authlib.username"),
        placeholder = strings.t("dialogs.authlib.usernamePlaceholder"),
        enabled = !busy,
    )
    CompactTextField(
        password,
        onPasswordChange,
        Modifier.fillMaxWidth(),
        label = strings.t("dialogs.authlib.password"),
        placeholder = strings.t("dialogs.authlib.passwordPlaceholder"),
        enabled = !busy,
        visualTransformation = PasswordVisualTransformation(),
        // 软键盘「完成」与登录按钮走同一条提交路径；回车另由外层 onPreviewKeyEvent 兜住。
        imeAction = ImeAction.Done,
        onImeAction = onSubmit,
    )
    MutedText(strings.t("dialogs.authlib.passwordNote"))
}

/** 当前服务器摘要：名称、地址与「已保存」标签。 */
@Composable
private fun AuthlibServerSummary(server: AuthlibServer) {
    val strings = LocalStrings.current
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
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                Icons.Outlined.Dns,
                null,
                Modifier.size(16.dp),
                MaterialTheme.colorScheme.primary,
            )
            Column(
                Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                Text(
                    server.name.ifBlank { server.url },
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    server.url,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Tag(strings.t("dialogs.saveSettings.saved"))
        }
    }
}

/** 底部动作区：转圈 + 取消/上一步 + 当前一步的主按钮。 */
@Composable
private fun AuthlibDialogFooter(
    step: AuthlibLoginStep,
    preset: Boolean,
    busy: Boolean,
    canValidate: Boolean,
    canLogin: Boolean,
    savedCount: Int,
    onCancel: () -> Unit,
    onValidate: () -> Unit,
    onSubmit: () -> Unit,
    onBack: () -> Unit,
) {
    val strings = LocalStrings.current
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (busy) {
            CircularProgressIndicator(
                Modifier.size(14.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Spacer(Modifier.weight(1f))
        if (step == AuthlibLoginStep.Server) {
            SecondaryButton(onCancel, enabled = !busy) { Text(strings.t("dialogs.common.cancel"), fontSize = 13.sp) }
            PrimaryButton(
                when {
                    busy -> strings.t("dialogs.authlib.validating")
                    savedCount == 0 -> strings.t("dialogs.authlib.next")
                    else -> strings.t("dialogs.authlib.saveAndLogin")
                },
                enabled = canValidate,
                onClick = onValidate,
            )
        } else {
            // 从账户页的服务器行进来时没有第 1 步可回，不显示「上一步」。
            if (!preset) {
                SecondaryButton(
                    onClick = onBack,
                    enabled = !busy,
                ) {
                    Text(strings.t("dialogs.authlib.back"), fontSize = 13.sp)
                }
            }
            PrimaryButton(
                if (busy) strings.t("dialogs.authlib.loggingIn") else strings.t("dialogs.authlib.login"),
                enabled = canLogin,
                onClick = onSubmit,
            )
        }
    }
}

/** 移除服务器的确认框：只影响添加入口的列表，不动已添加的账户。 */
@Composable
private fun AuthlibRemoveDialog(
    server: AuthlibServer,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val strings = LocalStrings.current
    val label = server.name.ifBlank { server.url }
    AlertDialog(
        properties =
            androidx.compose.ui.window
                .DialogProperties(dismissOnClickOutside = false),
        onDismissRequest = onDismiss,
        title = { Text(strings.t("dialogs.authlib.removeTitle", label)) },
        text = {
            Text(strings.t("dialogs.authlib.removeNote"))
        },
        confirmButton = {
            TextButton(onConfirm) {
                Text(strings.t("dialogs.authlib.removeConfirm"), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onDismiss) { Text(strings.t("dialogs.common.cancel")) } },
    )
}

/** 步骤标题：11sp 主题色，与设置分区的小标题同一层级，不抢弹窗标题。 */
@Composable
private fun AuthlibStepLabel(text: String) {
    Text(
        text,
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.primary,
    )
}
