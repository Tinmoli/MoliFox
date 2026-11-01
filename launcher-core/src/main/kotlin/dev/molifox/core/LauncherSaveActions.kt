// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责存档的备份、恢复与导入导出。

package dev.molifox.core

import dev.molifox.core.i18n.I18n
import java.nio.file.Path

/** 存档列表、备份、恢复与跨实例复制。 */
internal interface LauncherSaveApi {
    /** 列出实例 `saves/` 下的存档；不跟随符号链接，损坏或不可读的子项由实现跳过。 */
    fun listSaves(id: String): List<SaveEntry>

    /** 列出实例的存档备份，按创建时间从新到旧。 */
    fun listSaveBackups(id: String): List<SaveBackupInfo>

    /** 在后台任务里备份一个存档；备份成功后原存档保留，进度在日志页。 */
    fun backupSave(
        instanceId: String,
        saveName: String,
    ): String

    /**
     * 在后台任务里恢复备份。默认按非破坏性复制恢复成同级新世界；实例开启「恢复时替换原世界」时
     * 先备份当前世界再替换，旧世界改名为「（替换前 HH-mm）」保留。
     */
    fun restoreSave(
        instanceId: String,
        backupId: String,
    ): String

    /** 在后台任务里把存档复制到另一个实例；同名目标自动加序号，从不覆盖。 */
    fun copySaveToInstance(
        sourceInstanceId: String,
        saveName: String,
        targetInstanceId: String,
        rename: String? = null,
    ): String

    /** 删除一个存档备份：移入回收站，不做不可恢复的硬删除。 */
    fun deleteSaveBackup(
        instanceId: String,
        backupId: String,
    ): Boolean

    /** 删除一个存档：移入回收站，不做不可恢复的硬删除。 */
    fun deleteSave(
        instanceId: String,
        saveName: String,
    ): Boolean

    /** 在后台任务里导入外部存档（`.zip` 或世界文件夹）；重名加序号，无效输入不写盘。 */
    fun importSave(
        instanceId: String,
        source: Path,
    ): String
}

/** 实现由 [LauncherController] 委托调用；方法体与原实现一致。 */
internal abstract class LauncherSaveActions(
    core: LauncherControllerCore,
) : LauncherToolActions(core),
    LauncherSaveApi {
    /** 列出实例 `saves/` 下的存档；不跟随符号链接，损坏或不可读的子项由实现跳过。 */
    override fun listSaves(id: String): List<SaveEntry> = saves.listSaves(id)

    /** 列出实例的存档备份，按创建时间从新到旧。 */
    override fun listSaveBackups(id: String): List<SaveBackupInfo> = saves.listBackups(id)

    /** 在后台任务里备份一个存档；备份成功后原存档保留，进度在日志页。 */
    override fun backupSave(
        instanceId: String,
        saveName: String,
    ): String {
        requireIdle(instanceId)
        return jobs.enqueue(
            I18n.t("miscA1.task.backupSave", saveName),
            taskResourcesForInstances(store, instanceId),
            kind = LauncherTaskKind.SaveOperation,
        ) {
            saves.backupSave(instanceId, saveName, this)
        }
    }

    /**
     * 在后台任务里恢复备份。默认按非破坏性复制恢复成同级新世界；实例开启「恢复时替换原世界」时 先备份当前世界再替换，旧世界改名为「（替换前 HH-mm）」保留。
     */

    override fun restoreSave(
        instanceId: String,
        backupId: String,
    ): String {
        requireIdle(instanceId)
        val replace = store.instance(instanceId).replaceSaveOnRestore
        return jobs.enqueue(
            I18n.t("miscA1.task.restoreSave", backupId),
            taskResourcesForInstances(store, instanceId),
            kind = LauncherTaskKind.SaveOperation,
        ) {
            if (replace) {
                saves.replaceWorldWithBackup(instanceId, backupId, this)
            } else {
                saves.restoreSave(instanceId, backupId, this)
            }
        }
    }

    /** 在后台任务里把存档复制到另一个实例；同名目标自动加序号，从不覆盖。 */
    override fun copySaveToInstance(
        sourceInstanceId: String,
        saveName: String,
        targetInstanceId: String,
        rename: String?,
    ): String {
        requireIdle(sourceInstanceId, targetInstanceId)
        return jobs.enqueue(
            I18n.t("miscA1.task.copySave", saveName),
            taskResourcesForInstances(store, sourceInstanceId, targetInstanceId),
            kind = LauncherTaskKind.SaveOperation,
        ) {
            saves.copySaveToInstance(sourceInstanceId, saveName, targetInstanceId, rename, this)
        }
    }

    /** 删除一个存档备份：移入回收站，不做不可恢复的硬删除。 */
    override fun deleteSaveBackup(
        instanceId: String,
        backupId: String,
    ): Boolean {
        requireIdle(instanceId)
        return saves.deleteBackup(instanceId, backupId)
    }

    /** 删除一个存档：移入回收站，不做不可恢复的硬删除。 */
    override fun deleteSave(
        instanceId: String,
        saveName: String,
    ): Boolean {
        requireIdle(instanceId)
        return saves.deleteSave(instanceId, saveName)
    }

    /** 在后台任务里导入外部存档（`.zip` 或世界文件夹）；重名加序号，无效输入不写盘。 */
    override fun importSave(
        instanceId: String,
        source: Path,
    ): String {
        requireIdle(instanceId)
        return jobs.enqueue(
            I18n.t("miscA1.task.importSave", source.fileName),
            taskResourcesForInstances(store, instanceId),
            kind = LauncherTaskKind.SaveOperation,
        ) {
            saves.importSave(instanceId, source, this)
        }
    }
}
