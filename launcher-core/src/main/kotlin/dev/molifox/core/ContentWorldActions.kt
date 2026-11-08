// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责世界包下载与安装入口。

package dev.molifox.core

import java.nio.file.Path

/** 世界分类的安装入口：下载校验后解压到目标实例的 `saves/`，不走模组安装事务。 */
internal abstract class ContentWorldActions(
    core: ContentServiceCore,
) : ContentInstallFlowActions(core) {
    internal suspend fun installWorld(
        hit: ContentHit,
        versionId: String,
        instanceId: String,
        context: JobContext,
        destination: Path? = null,
    ) = WorldPackInstaller(
        store,
        network,
        backend,
        if (hit.id.startsWith("curseforge:")) curseForge.api else api,
    ).install(hit, versionId, instanceId, context, destination)
}
