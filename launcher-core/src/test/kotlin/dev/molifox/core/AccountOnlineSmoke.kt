// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责执行需要用户参与的微软授权、刷新和本地退出验收。

package dev.molifox.core

import java.nio.file.Path
import kotlin.io.path.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

/** Explicit interactive acceptance. Never writes tokens or private device codes to reports. */
fun main() = runBlocking {
    val root = Path.of(System.getProperty("molifox.test.dir")).toAbsolutePath().normalize()
    val profile = root.resolve("profile")
    val status = root.resolve("status.json")
    val report =
        linkedMapOf<String, Any>(
            "platform" to System.getProperty("os.name"),
            "gameLaunched" to false,
            "applicationApproval" to "user-confirmed-2026-10-27",
        )
    try {
        LauncherController(dataDirectory = profile).use { controller ->
            val code = controller.accounts.beginMicrosoftLogin()
            writeJson(
                status,
                mapOf(
                    "stage" to "waiting-for-user",
                    "verificationUri" to code.verificationUri,
                    "userCode" to code.userCode,
                    "expiresAt" to code.expiresAt,
                ),
            )
            println("AUTH_WAITING_FOR_USER")
            while (controller.store.data.accounts.none { it.type == AccountType.Microsoft }) {
                check(System.currentTimeMillis() < code.expiresAt) { "授权等待已超时" }
                val failure = controller.state.value.logs.lastOrNull { it.isError }
                if (failure != null) {
                    writeJson(
                        status,
                        mapOf("stage" to "failed", "message" to failure.message.take(500)),
                    )
                    error("认证服务返回失败，请查看隔离测试状态")
                }
                delay(500)
            }
            report["profileRead"] = true
            writeJson(status, mapOf("stage" to "profile-read"))
            println("AUTH_PROFILE_READ")
        }
        // A new controller must read the encrypted refresh token from persistent storage.
        LauncherController(dataDirectory = profile).use { controller ->
            val account = controller.store.data.accounts.single { it.type == AccountType.Microsoft }
            val credentials = controller.accounts.credentials()
            check(
                credentials.uuid == account.uuid &&
                    credentials.accessToken.isNotBlank() &&
                    credentials.accessToken != "0"
            )
            report["restartAndRefresh"] = true
            controller.accounts.remove(account.id)
            check(
                controller.store.data.accounts.isEmpty() &&
                    controller.accounts.vault.read(account.id) == null
            )
            report["localSignOut"] = true
            println("AUTH_REFRESH_AND_SIGNOUT_PASSED")
        }
        report["result"] = "passed"
        writeJson(status, mapOf("stage" to "passed"))
    } catch (failure: Throwable) {
        report["result"] = "failed"
        report["failureType"] = failure.javaClass.simpleName
        if (!status.exists())
            writeJson(
                status,
                mapOf("stage" to "failed", "message" to failure.message.orEmpty().take(500)),
            )
        throw IllegalStateException("正版登录隔离验证未通过；未输出令牌")
    } finally {
        writeJson(Path.of(System.getProperty("molifox.account.report")), report)
    }
}
