// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责界面用的图标、服务器状态与目录体积查询。

package dev.molifox.core

import dev.molifox.core.i18n.I18n
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.nio.file.Path

/** 界面列表需要的只读查询：模组图标、服务器状态与实例目录体积。 */
internal interface LauncherQueryApi {
    suspend fun modIconBytes(jar: Path): ByteArray?

    suspend fun serverStatus(address: String): ServerStatus?

    suspend fun instanceDirectorySize(id: String): Long?
}

/** 实现由 [LauncherController] 委托调用；方法体与原实现一致。 */
internal abstract class LauncherQueryActions(
    core: LauncherControllerCore,
) : LauncherDatapackActions(core),
    LauncherQueryApi {
    override suspend fun modIconBytes(jar: Path): ByteArray? =
        withContext(Dispatchers.IO) {
            readModIcon(jar)
                .onFailure {
                    store.log(I18n.t("miscA1.queryActions.modIconReadFailed", jar, it.message), true)
                }.getOrNull()
        }

    override suspend fun serverStatus(address: String): ServerStatus? =
        withContext(Dispatchers.IO) {
            pingAddress(address)
        }

    override suspend fun instanceDirectorySize(id: String): Long? =
        withContext(Dispatchers.IO) {
            // 实例可能刚被删除、界面还带着旧 id 调用一次。查询按「查不到就是没有」处理，
            // 不抛异常：调用方在 LaunchedEffect 里，异常会变成 Compose 的 Error 对话框。
            val instance = store.instanceOrNull(id) ?: return@withContext null
            val taskContext = kotlinx.coroutines.currentCoroutineContext()
            dev.molifox.core.platform.files
                .directoryByteSize(
                    store.versionGamePath(instance),
                ) {
                    taskContext.ensureActive()
                }.onFailure { store.log(I18n.t("miscA1.queryActions.directorySizeFailed", it.message), true) }
                .getOrNull()
        }
}
