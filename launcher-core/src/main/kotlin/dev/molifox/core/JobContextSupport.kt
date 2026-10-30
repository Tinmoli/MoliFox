// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责 JobContext 的产物写入登记、下载文件归属判断与实例资源绑定。

package dev.molifox.core

import java.nio.file.Path

internal fun JobContext.beforeDirectoryCreation(path: Path) = outputs.beforeDirectoryCreation(path)

internal fun JobContext.writeOutput(
    path: Path,
    action: () -> Unit,
) = outputs.write(path, action)

internal fun JobContext.trackOutput(path: Path) = outputs.track(path)

/** 下载文件只有不在任何实例的引用范围内、且不属于运行时目录时才可回收。 */
internal fun JobContext.canRemoveDownload(path: Path): Boolean {
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
    if (references?.first != instances) {
        references = instances to TaskFileReferences(store, ownedIds)
    }
    return !requireNotNull(references).second.protects(normalized) &&
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
