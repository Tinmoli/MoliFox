// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责模组与资源包/光影的更新任务：单行更新与一键批量更新。

package dev.molifox.core

import dev.molifox.core.i18n.I18n

/** 模组/资源包/光影的更新任务入口；由 [LauncherContentActions] 继承。 */
internal interface LauncherContentUpdateApi {
    /** 更新单个模组：下载校验通过后才替换旧文件；任务中心可取消，失败时旧文件保持原样。 */
    fun updateMod(
        instanceId: String,
        item: ModUpdateItem,
    ): String

    /** 一键更新多个模组：顺序执行，单项失败不影响其余，任务结束时汇总失败原因。 */
    fun updateMods(
        instanceId: String,
        items: List<ModUpdateItem>,
    ): String

    /** 更新单个资源包/光影：下载校验通过后才替换旧文件；任务中心可取消，失败时旧文件保持原样。 */
    fun updatePack(
        instanceId: String,
        item: PackUpdateItem,
    ): String

    /** 一键更新多个资源包/光影：语义与 [updateMods] 一致，可能匹配项不自动应用。 */
    fun updatePacks(
        instanceId: String,
        items: List<PackUpdateItem>,
    ): String
}

/** 更新任务的实现；与内容安装分离，避免单一 Actions 类职责过宽。 */
internal abstract class LauncherContentUpdateActions(
    core: LauncherControllerCore,
) : LauncherDeletionActions(core),
    LauncherContentUpdateApi {
    override fun updateMod(
        instanceId: String,
        item: ModUpdateItem,
    ): String {
        requireIdle(instanceId)
        return jobs.enqueue(
            I18n.t("coreD.update.mod", item.title),
            taskResourcesForInstances(store, instanceId),
            kind = LauncherTaskKind.UpdateContent,
        ) {
            content.modUpdates.apply(instanceId, item, this)
        }
    }

    override fun updateMods(
        instanceId: String,
        items: List<ModUpdateItem>,
    ): String {
        val targets =
            items.filter {
                it.status == ModUpdateStatus.Updatable && it.applicable && !it.possibleMatch
            }
        return enqueueUpdateBatch(
            instanceId,
            I18n.t("coreD.update.mods", targets.size),
            targets.map { item ->
                ContentUpdateTask(item.title.ifBlank { item.filename }) { context ->
                    content.modUpdates.apply(instanceId, item, context)
                }
            },
            I18n.t("coreD.update.noMods"),
        )
    }

    override fun updatePack(
        instanceId: String,
        item: PackUpdateItem,
    ): String {
        requireIdle(instanceId)
        return jobs.enqueue(
            I18n.t("coreD.update.pack", item.folder.title, item.filename),
            taskResourcesForInstances(store, instanceId),
            kind = LauncherTaskKind.UpdateContent,
        ) {
            content.packUpdates.apply(instanceId, item, this)
        }
    }

    override fun updatePacks(
        instanceId: String,
        items: List<PackUpdateItem>,
    ): String {
        val targets =
            items.filter {
                it.status == PackUpdateStatus.Updatable &&
                    it.applicable &&
                    !it.possibleMatch
            }
        return enqueueUpdateBatch(
            instanceId,
            packBatchTitle(targets),
            targets.map { item ->
                ContentUpdateTask(item.filename) { context ->
                    content.packUpdates.apply(instanceId, item, context)
                }
            },
            I18n.t("coreD.update.noPacks"),
        )
    }

    /** 批量更新共用的入队逻辑：顺序执行，末了把所有失败原因合成任务错误。 */
    private fun enqueueUpdateBatch(
        instanceId: String,
        title: String,
        tasks: List<ContentUpdateTask>,
        emptyMessage: String,
    ): String {
        require(tasks.isNotEmpty()) { emptyMessage }
        requireIdle(instanceId)
        return jobs.enqueue(
            title,
            taskResourcesForInstances(store, instanceId),
            kind = LauncherTaskKind.UpdateContent,
        ) {
            val result = applyContentUpdateBatch(this, tasks)
            if (result.failures.isNotEmpty()) throw IllegalStateException(result.summary())
        }
    }
}

/** 一键更新的任务标题：同一目录时写目录名，混合目录时用「内容」。 */
private fun packBatchTitle(items: List<PackUpdateItem>): String {
    val title = items.map { it.folder.title }.distinct().singleOrNull() ?: I18n.t("coreD.update.mixedFolder")
    return I18n.t("coreD.update.batch", items.size, title)
}
