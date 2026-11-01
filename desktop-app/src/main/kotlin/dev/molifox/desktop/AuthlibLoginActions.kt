// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责外置认证登录的动作与文案：服务器校验、账户登录、服务器移除与键盘提交。

package dev.molifox.desktop

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import com.google.gson.JsonParseException
import dev.molifox.core.AuthlibError
import dev.molifox.core.AuthlibException
import dev.molifox.core.AuthlibServer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext
import java.io.IOException

/** 把 [AuthlibException] 的类别折算成一步就能照做的中文提示；未知异常保留原始文案。 密码错误与网络错误必须能区分开，避免用户反复重试密码。 */
private fun authlibErrorMessage(failure: Throwable): String {
    if (failure !is AuthlibException) return failure.message ?: tr("dialogs.authlib.opFailed")
    return when (failure.error) {
        AuthlibError.InvalidServer -> tr("dialogs.authlib.invalidServer")
        AuthlibError.NotYggdrasil -> tr("dialogs.authlib.notYggdrasil")
        AuthlibError.Network -> tr("dialogs.authlib.networkError")
        AuthlibError.InvalidCredentials -> tr("dialogs.authlib.invalidCredentials")
        AuthlibError.SessionExpired -> tr("dialogs.authlib.sessionExpired")
        AuthlibError.NoProfile -> tr("dialogs.authlib.noProfile")
        AuthlibError.DownloadFailed -> failure.message ?: tr("dialogs.authlib.downloadFailed")
        AuthlibError.InvalidResponse -> tr("dialogs.authlib.invalidResponse")
        AuthlibError.ServerError -> failure.message ?: tr("dialogs.authlib.serverError")
    }
}

/** 第 1 步：校验并保存服务器地址；成功后带着地址进入第 2 步。 */
internal fun validateAuthlibServer(
    actions: UiActions,
    address: String,
    isBusy: () -> Boolean,
    onBusy: (Boolean) -> Unit,
    onError: (String?) -> Unit,
    onResolved: (AuthlibServer) -> Unit,
) {
    if (isBusy()) return
    val candidate = address.trim()
    if (candidate.isEmpty()) {
        onError(tr("dialogs.authlib.enterAddress"))
        return
    }
    onBusy(true)
    onError(null)
    actions.run {
        val result = actions.controller.accounts.addAuthlibServer(candidate)
        val failure = result.exceptionOrNull()
        withContext(Dispatchers.Swing) {
            onBusy(false)
            if (failure != null) {
                onError(authlibErrorMessage(failure))
            } else {
                onResolved(result.getOrThrow())
            }
        }
    }
}

/** 登录失败统一回到 UI 线程：先解除 busy，再按异常类别给出提示。 */
private suspend fun reportAuthlibFailure(
    failure: Throwable,
    onBusy: (Boolean) -> Unit,
    onError: (String?) -> Unit,
) {
    withContext(Dispatchers.Swing) {
        onBusy(false)
        onError(authlibErrorMessage(failure))
    }
}

// 登录的唯一提交路径：登录按钮、Enter、密码框的 IME「完成」都调这里；
// busy 期间直接返回，避免回车与点击叠成两次请求。
internal fun submitAuthlibLogin(
    actions: UiActions,
    server: AuthlibServer?,
    username: String,
    password: String,
    isBusy: () -> Boolean,
    onBusy: (Boolean) -> Unit,
    onError: (String?) -> Unit,
    onSuccess: () -> Unit,
) {
    if (isBusy()) return
    if (server == null) return
    val name = username.trim()
    val secret = password
    if (name.isEmpty() || secret.isEmpty()) {
        onError(tr("dialogs.authlib.enterCredentials"))
        return
    }
    onBusy(true)
    onError(null)
    actions.run {
        try {
            val result = actions.controller.accounts.addAuthlibAccount(server.url, name, secret)
            val failure = result.exceptionOrNull()
            withContext(Dispatchers.Swing) {
                onBusy(false)
                if (failure == null) {
                    onSuccess()
                } else {
                    onError(authlibErrorMessage(failure))
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: IOException) {
            reportAuthlibFailure(failure, onBusy, onError)
        } catch (failure: JsonParseException) {
            reportAuthlibFailure(failure, onBusy, onError)
        } catch (failure: IllegalStateException) {
            reportAuthlibFailure(failure, onBusy, onError)
        }
    }
}

/** 移除已保存的服务器：内置服务器由核心拒绝，这里按结果给提示，不谎报已移除。 */
internal fun removeAuthlibServer(
    actions: UiActions,
    server: AuthlibServer,
    label: String,
    isSelected: Boolean,
    isResolved: Boolean,
    onClearSelected: () -> Unit,
    onClearResolved: () -> Unit,
    onError: (String) -> Unit,
) {
    if (!actions.controller.accounts.removeAuthlibServer(server.url)) {
        onError(tr("dialogs.authlib.builtinLocked"))
        return
    }
    if (isSelected) onClearSelected()
    if (isResolved) onClearResolved()
    actions.controller.store.notify(tr("dialogs.authlib.removed", label))
}

/** 弹窗键盘：Esc 关闭，Enter 执行当前一步（第 2 步密码已填时直接提交）。 */
internal fun authlibKeyEvent(
    event: KeyEvent,
    step: AuthlibLoginStep,
    canValidate: Boolean,
    password: String,
    onDismiss: () -> Unit,
    onValidate: () -> Unit,
    onSubmit: () -> Unit,
): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    return when (event.key) {
        Key.Escape -> {
            onDismiss()
            true
        }
        Key.Enter,
        Key.NumPadEnter,
        ->
            when (step) {
                AuthlibLoginStep.Server ->
                    if (canValidate) {
                        onValidate()
                        true
                    } else {
                        false
                    }
                AuthlibLoginStep.Credentials ->
                    // 密码已填时，用户在用户名或密码任一框里按 Enter 都直接提交登录；
                    // 密码还空着则让给输入框自己处理，不提前弹「请输入用户名和密码」。
                    if (password.isNotEmpty()) {
                        onSubmit()
                        true
                    } else {
                        false
                    }
            }
        else -> false
    }
}

internal fun authlibDialogTitle(presetLabel: String?): String =
    if (presetLabel != null) tr("dialogs.authlib.loginServer", presetLabel) else tr("dialogs.account.addServer")

internal fun authlibDialogHint(presetLabel: String?): String =
    if (presetLabel != null) {
        tr("dialogs.authlib.loginTitleHint")
    } else {
        tr("dialogs.authlib.addServerHint")
    }

internal fun authlibStepTitle(
    step: AuthlibLoginStep,
    preset: Boolean,
): String =
    when {
        preset -> tr("dialogs.authlib.credentialsStep")
        step == AuthlibLoginStep.Server -> tr("dialogs.authlib.stepServer")
        else -> tr("dialogs.authlib.stepCredentials")
    }
