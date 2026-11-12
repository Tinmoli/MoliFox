// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责 JobContext 的产物写入登记、下载文件归属判断与实例资源绑定。

package dev.molifox.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.nio.file.Path

internal suspend fun JobContext.cleanFailure(error: Throwable) {
    for (key in failureActions.keys) {
        val cleanup = failureActions.remove(key) ?: continue
        runCatching { cleanup() }.onFailure {
            if (it !== error) error.addSuppressed(it)
        }
    }
}

internal fun JobContext.disarmCleanup(key: Any) {
    cancellationActions.remove(key)
    failureActions.remove(key)
}

/** 直接调用安装服务时也执行失败清理，取消继续由任务队列处理。 */
internal suspend fun <T> JobContext.cleanOnFailure(action: suspend () -> T): T =
    runCatching { action() }.getOrElse { error ->
        if (error !is CancellationException) {
            withContext(NonCancellable + Dispatchers.IO) { cleanFailure(error) }
        }
        throw error
    }

internal fun JobContext.beforeDirectoryCreation(path: Path) = outputs.beforeDirectoryCreation(path)

internal fun JobContext.writeOutput(
    path: Path,
    action: () -> Unit,
) = outputs.write(path, action)

internal fun JobContext.trackOutput(path: Path) = outputs.track(path)

/** 下载文件只有不在任何实例的引用范围内、且不属于运行时目录时才可回收。 */
internal fun JobContext.canRemoveDownload(
    path: Path,
    ownedInstanceId: String? = null,
): Boolean {
    val resources =
        store.state.value.jobs
            .find { it.id == id }
            ?.resources
            .orEmpty()
    val ownedIds =
        resources
            .filter { it.startsWith("instance:") }
            .map { it.removePrefix("instance:") }
            .toSet()
    val normalized = path.toAbsolutePath().normalize()
    val instances = store.data.instances
    if (ownedInstanceId == null && references?.first != instances) {
        references = instances to TaskFileReferences(store, ownedIds)
    }
    val fileReferences =
        if (ownedInstanceId ==
            null
        ) {
            requireNotNull(references).second
        } else {
            TaskFileReferences(store, ownedIds + ownedInstanceId)
        }
    return !fileReferences.protects(normalized) &&
        !normalized.startsWith(
            store.dataDirectory
                .resolve("runtimes")
                .toAbsolutePath()
                .normalize(),
        )
}

internal fun JobContext.bindInstance(instanceId: String) {
    bindResources?.invoke(taskResourcesForInstances(store, instanceId))
}

/** 在任务已绑定资源的基础上追加实例：新实例由任务运行中创建，不能像整合包导入那样整体替换。 */
internal fun JobContext.bindInstances(vararg instanceIds: String) {
    if (instanceIds.isEmpty()) return
    val current =
        store.state.value.jobs
            .find { it.id == id }
            ?.resources
            .orEmpty()
    val added = instanceIds.flatMap { taskResourcesForInstances(store, it).toList() }
    bindResources?.invoke(current + added)
}
