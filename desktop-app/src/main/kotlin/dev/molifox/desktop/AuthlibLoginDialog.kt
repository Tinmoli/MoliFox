// 负责外置认证服务器选择、登记与账户登录界面。

package dev.molifox.desktop

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.molifox.core.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext

/** 外置认证登录的两步：先确认服务器，再输入该服务器上的用户名与密码。 */
private enum class AuthlibLoginStep {
    Server,
    Credentials,
}

/**
 * 账户页的两个认证服务器入口：[SavedServers] 打开即列出已保存的服务器（默认含核心内置的 LittleSkin），[NewServer] 打开即把光标放进地址框，直接添加新服务器。
 */
enum class AuthlibEntry {
    SavedServers,
    NewServer,
}

/**
 * 添加外置认证（authlib-injector / Yggdrasil）账户的两步对话框。
 *
 * - 第 1 步是服务器选择：已保存的服务器（[AuthlibEntry.SavedServers] 入口的主体）点一下就直接 进入第 2
 *   步，不需要先点「下一步」；[AuthlibEntry.NewServer] 入口把焦点放在地址框上， 校验通过后 `addAuthlibServer`
 *   会先保存再用它登录。已保存行可在行尾移除（不影响已添加账户）；
 * - 第 2 步用 `addAuthlibAccount` 登录：失败时按 [AuthlibError] 折算中文提示且不关闭对话框， 成功后关闭由账户列表的 store
 *   变更自动刷新（与离线账户、微软账户同一路径）。
 *
 * 请求期间按钮置灰并显示 14dp 转圈，界面线程不做阻塞等待；Esc 关闭，Enter 执行当前一步。
 */
@Composable
fun AuthlibLoginDialog(
    actions: UiActions,
    entry: AuthlibEntry = AuthlibEntry.SavedServers,
    onDismiss: () -> Unit,
) {
    val state by actions.controller.state.collectAsState()
    // 服务器列表跟随 launcher.json 的 authlibServers：新增或删除后对话框不必重开。
    val servers =
        remember(state.data.authlibServers) {
            actions.controller.accounts.authlibServers()
        }
    var step by remember { mutableStateOf(AuthlibLoginStep.Server) }
    var address by remember { mutableStateOf("") }
    var selectedUrl by remember { mutableStateOf("") }
    var resolved by remember { mutableStateOf<AuthlibServer?>(null) }
    // 已保存列表是「认证服务器」入口的主体；只有「添加认证服务器」入口或列表为空时才直接展开地址框。
    var showAddress by remember {
        mutableStateOf(entry == AuthlibEntry.NewServer || servers.isEmpty())
    }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var removing by remember { mutableStateOf<AuthlibServer?>(null) }
    val focus = remember { FocusRequester() }
    val addressFocus = remember { FocusRequester() }

    val addressVisible = showAddress || servers.isEmpty()
    val canValidate = !busy && address.trim().isNotEmpty()
    val canLogin = !busy && username.isNotBlank() && password.isNotEmpty()

    fun validateServer() {
        if (busy) return
        val candidate = address.trim()
        if (candidate.isEmpty()) {
            error = "请输入认证服务器地址"
            return
        }
        busy = true
        error = null
        actions.run {
            val result = actions.controller.accounts.addAuthlibServer(candidate)
            val failure = result.exceptionOrNull()
            withContext(Dispatchers.Swing) {
                busy = false
                if (failure != null) {
                    error = authlibErrorMessage(failure)
                } else {
                    val server = result.getOrThrow()
                    resolved = server
                    address = server.url
                    selectedUrl = server.url
                    step = AuthlibLoginStep.Credentials
                    error = null
                }
            }
        }
    }

    // 已保存的服务器都通过过元数据校验：点选即进入第 2 步，不再要求先点「下一步」。
    fun chooseSaved(server: AuthlibServer) {
        if (busy) return
        resolved = server
        address = server.url
        selectedUrl = server.url
        error = null
        step = AuthlibLoginStep.Credentials
    }

    fun signIn() {
        if (busy) return
        val server = resolved ?: return
        val name = username.trim()
        val secret = password
        if (name.isEmpty() || secret.isEmpty()) {
            error = "请输入用户名和密码"
            return
        }
        busy = true
        error = null
        actions.run {
            try {
                val result = actions.controller.accounts.addAuthlibAccount(server.url, name, secret)
                val failure = result.exceptionOrNull()
                withContext(Dispatchers.Swing) {
                    busy = false
                    if (failure == null) {
                        // 密码只用于本次登录，关闭前立刻丢掉。
                        password = ""
                        onDismiss()
                    } else {
                        error = authlibErrorMessage(failure)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                withContext(Dispatchers.Swing) {
                    busy = false
                    error = authlibErrorMessage(failure)
                }
            }
        }
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

    Modal(
        if (entry == AuthlibEntry.NewServer) "添加认证服务器" else "认证服务器",
        if (entry == AuthlibEntry.NewServer) "连接外置认证（Yggdrasil）服务器，登录后账户会出现在列表里。"
        else "选择一个已保存的认证服务器，输入用户名与密码即可登录；也可以添加新的服务器。",
        width = 480.dp,
        onDismiss = onDismiss,
    ) {
        Column(
            Modifier.fillMaxWidth()
                .focusRequester(focus)
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (event.key) {
                        Key.Escape -> {
                            onDismiss()
                            true
                        }
                        Key.Enter,
                        Key.NumPadEnter ->
                            when (step) {
                                AuthlibLoginStep.Server ->
                                    if (addressVisible && canValidate) {
                                        validateServer()
                                        true
                                    } else false
                                AuthlibLoginStep.Credentials ->
                                    if (canLogin) {
                                        signIn()
                                        true
                                    } else false
                            }
                        else -> false
                    }
                }
                .focusable(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            AuthlibStepLabel(
                if (step == AuthlibLoginStep.Server) "第 1 步 / 共 2 步 · 认证服务器"
                else "第 2 步 / 共 2 步 · 用户名与密码"
            )
            if (step == AuthlibLoginStep.Server) {
                if (servers.isEmpty()) {
                    MutedText("还没有已保存的认证服务器；填写地址后保存，下次就能直接点选。")
                } else {
                    Text(
                        "已保存的服务器",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Column(
                        Modifier.fillMaxWidth()
                            .heightIn(max = 160.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        servers.forEach { server ->
                            val label = server.name.ifBlank { server.url }
                            DialogListRow(
                                title = label,
                                detail = server.url,
                                selected = server.url == selectedUrl,
                                onClick = { chooseSaved(server) },
                                trailing = {
                                    SmallIconButton(
                                        Icons.Outlined.DeleteOutline,
                                        "移除服务器「" + label + "」",
                                        onClick = { if (!busy) removing = server },
                                        tint = MaterialTheme.colorScheme.error,
                                        enabled = !busy,
                                    )
                                },
                            )
                        }
                    }
                }
                if (addressVisible) {
                    CompactTextField(
                        address,
                        {
                            address = it
                            if (it != selectedUrl) selectedUrl = ""
                            error = null
                        },
                        Modifier.fillMaxWidth(),
                        label = if (servers.isEmpty()) "认证服务器" else "添加新的认证服务器",
                        placeholder = "认证服务器地址，例如 https://example.com",
                        enabled = !busy,
                        focusRequester = addressFocus,
                    )
                } else {
                    // 列表优先时不铺开表单，需要新服务器再展开，避免抢走「点一个就走」的主流程。
                    TextButton({ showAddress = true }, enabled = !busy) {
                        Text("添加新的认证服务器", fontSize = 12.sp)
                    }
                }
            } else {
                resolved?.let { server ->
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
                                    server.name,
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
                            Tag("已验证")
                        }
                    }
                }
                CompactTextField(
                    username,
                    {
                        username = it.take(320)
                        error = null
                    },
                    Modifier.fillMaxWidth(),
                    label = "用户名",
                    placeholder = "认证服务器上的用户名或邮箱",
                    enabled = !busy,
                )
                CompactTextField(
                    password,
                    {
                        password = it
                        error = null
                    },
                    Modifier.fillMaxWidth(),
                    label = "密码",
                    placeholder = "输入密码",
                    enabled = !busy,
                    visualTransformation = PasswordVisualTransformation(),
                )
                MutedText("密码只用于本次登录，不会写入启动器配置；登录成功后账户会自动保存并切换。")
            }
            error?.let { message ->
                Text(message, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
            }
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (busy)
                    CircularProgressIndicator(
                        Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                Spacer(Modifier.weight(1f))
                if (step == AuthlibLoginStep.Server) {
                    SecondaryButton(onDismiss, enabled = !busy) { Text("取消", fontSize = 13.sp) }
                    if (addressVisible)
                        PrimaryButton(
                            when {
                                busy -> "正在校验…"
                                servers.isEmpty() -> "下一步"
                                else -> "保存并登录"
                            },
                            enabled = canValidate,
                            onClick = { validateServer() },
                        )
                } else {
                    SecondaryButton(
                        onClick = {
                            step = AuthlibLoginStep.Server
                            error = null
                        },
                        enabled = !busy,
                    ) {
                        Text("上一步", fontSize = 13.sp)
                    }
                    PrimaryButton(
                        if (busy) "正在登录…" else "登录",
                        enabled = canLogin,
                        onClick = { signIn() },
                    )
                }
            }
        }
    }

    removing?.let { server ->
        val label = server.name.ifBlank { server.url }
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("移除认证服务器「" + label + "」？") },
            text = {
                Text("只从添加入口的列表里移除；已添加的账户会保留服务器地址与登录令牌，仍可正常启动。")
            },
            confirmButton = {
                TextButton({
                    actions.controller.accounts.removeAuthlibServer(server.url)
                    if (server.url == selectedUrl) selectedUrl = ""
                    if (resolved?.url == server.url) resolved = null
                    removing = null
                    actions.controller.store.notify("已移除认证服务器「" + label + "」")
                }) {
                    Text("移除服务器", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton({ removing = null }) { Text("取消") } },
        )
    }
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

/** 把 [AuthlibException] 的类别折算成一步就能照做的中文提示；未知异常保留原始文案。 密码错误与网络错误必须能区分开，避免用户反复重试密码。 */
private fun authlibErrorMessage(failure: Throwable): String {
    if (failure !is AuthlibException) return failure.message ?: "操作失败，请稍后重试"
    return when (failure.error) {
        AuthlibError.InvalidServer -> "不是有效的认证服务器地址"
        AuthlibError.NotYggdrasil -> "该地址不是 Yggdrasil 认证服务器"
        AuthlibError.Network -> "无法连接认证服务器，请检查网络后重试"
        AuthlibError.InvalidCredentials -> "用户名或密码错误"
        AuthlibError.SessionExpired -> "登录已过期，请重新输入密码"
        AuthlibError.NoProfile -> "该账户在认证服务器上没有可用的游戏角色"
        AuthlibError.DownloadFailed -> failure.message ?: "认证组件下载失败"
        AuthlibError.InvalidResponse -> "认证服务器返回的数据无效"
        AuthlibError.ServerError -> failure.message ?: "认证服务器返回错误"
    }
}
