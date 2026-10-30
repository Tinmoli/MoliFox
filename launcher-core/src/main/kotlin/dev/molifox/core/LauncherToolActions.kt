// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责实例整理、复制、迁移与资源包。

package dev.molifox.core

import java.nio.file.Path
import kotlin.io.path.name

/** 实例整理、复制、迁移以及资源包/光影。 */
internal interface LauncherToolApi {
    /** 在后台任务里复制实例；进度与取消沿用任务队列。 */
    fun copyInstance(
        sourceId: String,
        name: String? = null,
        copySaves: Boolean = true,
        copyMods: Boolean = true,
    ): String

    /** 整理实例目录（默认删除空目录）；重复模组等只报告。 */
    fun tidyInstance(
        id: String,
        removeEmptyDirs: Boolean = true,
    ): TidyReport

    fun verifyInstance(id: String): VerifyReport

    fun toggleFavorite(id: String)

    /** 列出实例的 `resourcepacks/` 或 `shaderpacks/` 内容。 */
    fun listPacks(
        instanceId: String,
        folder: PackFolder,
    ): List<LocalPackEntry>

    /** 在后台任务里导入资源包/光影（`.zip` 或文件夹）；重名加序号，失败不留残留。 */
    fun importPacks(
        instanceId: String,
        folder: PackFolder,
        sources: List<Path>,
    ): String

    /** 删除一个资源包/光影（移入回收站）。 */
    fun deletePack(
        instanceId: String,
        folder: PackFolder,
        filename: String,
    ): Boolean

    /** 预览把实例整体移动到另一个游戏目录；只读。 */
    fun planInstanceMove(
        instanceId: String,
        destinationGameDirectory: Path,
    ): IsolationPlan

    /** 在后台任务里把实例整体移动到另一个游戏目录（复制、校验、切记录、删源）。 */
    fun moveInstance(
        instanceId: String,
        destinationGameDirectory: Path,
    ): String
}

/** 实现由 [LauncherController] 委托调用；方法体与原实现一致。 */
internal abstract class LauncherToolActions(
    core: LauncherControllerCore,
) : LauncherContentActions(core),
    LauncherToolApi {
    /** 在后台任务里复制实例；进度与取消沿用任务队列。 */
    override fun copyInstance(
        sourceId: String,
        name: String?,
        copySaves: Boolean,
        copyMods: Boolean,
    ): String {
        requireIdle(sourceId)
        return jobs.enqueue(
            "复制 ${store.instance(sourceId).name}",
            taskResourcesForInstances(store, sourceId),
        ) {
            tools.copyInstance(sourceId, name, copySaves, copyMods, this)
        }
    }

    /** 整理实例目录（默认删除空目录）；重复模组等只报告。 */
    override fun tidyInstance(
        id: String,
        removeEmptyDirs: Boolean,
    ): TidyReport {
        requireIdle(id)
        return tools.tidyInstance(id, removeEmptyDirs)
    }

    override fun verifyInstance(id: String): VerifyReport = tools.verifyInstance(id)

    override fun toggleFavorite(id: String) {
        store.updateInstance(id) { it.copy(favorite = !it.favorite) }
    }

    /** 列出实例的 `resourcepacks/` 或 `shaderpacks/` 内容。 */
    override fun listPacks(
        instanceId: String,
        folder: PackFolder,
    ): List<LocalPackEntry> = content.listPacks(instanceId, folder)

    /** 在后台任务里导入资源包/光影（`.zip` 或文件夹）；重名加序号，失败不留残留。 */
    override fun importPacks(
        instanceId: String,
        folder: PackFolder,
        sources: List<Path>,
    ): String {
        requireIdle(instanceId)
        return jobs.enqueue("导入${folder.title}", taskResourcesForInstances(store, instanceId)) {
            content.importPacks(instanceId, folder, sources, this)
        }
    }

    /** 删除一个资源包/光影（移入回收站）。 */
    override fun deletePack(
        instanceId: String,
        folder: PackFolder,
        filename: String,
    ): Boolean {
        requireIdle(instanceId)
        return content.deletePack(instanceId, folder, filename)
    }

    /** 预览把实例整体移动到另一个游戏目录；只读。 */
    override fun planInstanceMove(
        instanceId: String,
        destinationGameDirectory: Path,
    ): IsolationPlan = migration.planInstanceMove(instanceId, destinationGameDirectory)

    /** 在后台任务里把实例整体移动到另一个游戏目录（复制、校验、切记录、删源）。 */
    override fun moveInstance(
        instanceId: String,
        destinationGameDirectory: Path,
    ): String {
        requireIdle()
        val plan = migration.planInstanceMove(instanceId, destinationGameDirectory)
        require(plan.blockers.isEmpty()) { plan.blockers.first() }
        return jobs.enqueue("移动 ${store.instance(instanceId).name}") {
            migration.moveInstance(instanceId, destinationGameDirectory, this)
        }
    }
}
