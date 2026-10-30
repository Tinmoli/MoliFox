// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责内容安装入口。

package dev.molifox.core

/** 实现由 [ContentService] 委托调用；方法体与原实现一致。 */
internal abstract class ContentInstallFlowActions(
    core: ContentServiceCore,
) : ContentExportActions(core) {
    internal suspend fun installContent(
        hit: ContentHit,
        instanceId: String?,
        context: JobContext,
        gameDirectoryId: String?,
    ) {
        if (hit.type == "modpack") {
            importPack(downloadPack(hit, context, ""), context, gameDirectoryId = gameDirectoryId)
        } else {
            val instance = instanceId?.let { store.instance(it) } ?: error("请先选择一个游戏实例")
            require(instance.installed) { "请先完成游戏实例安装" }
            require(hit.type != "mod" || instance.loader != LoaderType.Vanilla) {
                "原版无法加载模组，请选择带加载器的实例"
            }
            installPlan(planContent(hit, instance.id), context)
        }
    }
}
