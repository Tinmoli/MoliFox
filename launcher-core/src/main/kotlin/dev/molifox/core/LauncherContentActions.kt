// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责内容安装、整合包与导出任务。

package dev.molifox.core

import java.nio.file.Path
import java.util.UUID
import kotlin.io.path.name

/** 内容、整合包与导出的后台任务入口。 */
internal interface LauncherContentApi {
    fun installContent(
        hit: ContentHit,
        id: String?,
    ): String

    fun installContent(plan: ContentInstallPlan): String

    /** 更新单个模组：下载校验通过后才替换旧文件；任务中心可取消，失败时旧文件保持原样。 */
    fun updateMod(
        instanceId: String,
        item: ModUpdateItem,
    ): String

    fun preparePack(
        hit: ContentHit,
        versionId: String = "",
        onReady: (PackPreview) -> Unit,
    ): String

    fun installDatapack(
        hit: ContentHit,
        versionId: String,
        instanceId: String,
        worldName: String,
    ): String

    fun importPack(
        file: Path,
        optionalFiles: Set<String>? = null,
        expectedHash: String? = null,
    ): String

    fun exportMrpack(
        plan: MrpackExportPlan,
        destination: Path,
        selected: Set<String> = plan.defaultSelection,
        permittedLocalFiles: Set<String> = emptySet(),
        format: PackExportFormat = PackExportFormat.Modrinth,
        options: PackExportOptions = PackExportOptions(),
    ): String

    fun exportInstance(
        id: String,
        destination: Path,
    ): String

    fun upgradePack(
        instanceId: String,
        archive: Path,
        expectedArchiveSha256: String,
        allowComponentChange: Boolean = false,
        policy: PackUpgradePolicy = PackUpgradePolicy(restoreUserDeleted = true),
    ): String
}

/** 实现由 [LauncherController] 委托调用；方法体与原实现一致。 */
internal abstract class LauncherContentActions(
    core: LauncherControllerCore,
) : LauncherDeletionActions(core),
    LauncherContentApi {
    override fun installContent(
        hit: ContentHit,
        id: String?,
    ): String {
        if (hit.type != "modpack") requireIdle(requireNotNull(id) { "请选择实例" })
        val resources =
            if (hit.type == "modpack") {
                setOf("new-pack:${UUID.randomUUID()}")
            } else {
                taskResourcesForInstances(store, requireNotNull(id) { "请选择实例" })
            }
        val directoryId = store.data.activeGameDirectoryId
        return jobs.enqueue("安装 ${hit.title}", resources) {
            content.installContent(hit, id, this, directoryId)
        }
    }

    override fun installContent(plan: ContentInstallPlan): String {
        requireIdle(plan.instanceId)
        return jobs.enqueue(
            "安装 ${plan.title}",
            taskResourcesForInstances(store, plan.instanceId),
        ) {
            content.installPlan(plan, this)
        }
    }

    /** 更新单个模组：下载校验通过后才替换旧文件；任务中心可取消，失败时旧文件保持原样。 */
    override fun updateMod(
        instanceId: String,
        item: ModUpdateItem,
    ): String {
        requireIdle(instanceId)
        return jobs.enqueue(
            "更新模组 ${item.title}",
            taskResourcesForInstances(store, instanceId),
        ) {
            content.modUpdates.apply(instanceId, item, this)
        }
    }

    override fun preparePack(
        hit: ContentHit,
        versionId: String,
        onReady: (PackPreview) -> Unit,
    ): String =
        jobs.enqueue("准备 ${hit.title}", setOf("pack-download:${hit.id}:$versionId")) {
            val archive = content.downloadPack(hit, this, versionId)
            onReady(content.previewPack(archive))
        }

    override fun installDatapack(
        hit: ContentHit,
        versionId: String,
        instanceId: String,
        worldName: String,
    ): String {
        requireIdle(instanceId)
        return jobs.enqueue(
            "安装数据包 ${hit.title}",
            taskResourcesForInstances(store, instanceId),
        ) {
            content.installDatapack(hit, versionId, instanceId, worldName, this)
        }
    }

    override fun importPack(
        file: Path,
        optionalFiles: Set<String>?,
        expectedHash: String?,
    ): String {
        val directoryId = store.data.activeGameDirectoryId
        return jobs.enqueue("导入 ${file.fileName}", setOf("new-pack:${UUID.randomUUID()}")) {
            content.importPack(
                file,
                this,
                optionalFiles = optionalFiles,
                expectedHash = expectedHash,
                gameDirectoryId = directoryId,
            )
        }
    }

    override fun exportMrpack(
        plan: MrpackExportPlan,
        destination: Path,
        selected: Set<String>,
        permittedLocalFiles: Set<String>,
        format: PackExportFormat,
        options: PackExportOptions,
    ): String {
        requireIdle(plan.instanceId)
        return jobs.enqueue(
            "导出${if (format.importable) "整合包" else "完整备份"} ${plan.metadata.name}",
            taskResourcesForInstances(store, plan.instanceId),
        ) {
            content.exportMrpack(
                plan,
                destination,
                this,
                selected,
                permittedLocalFiles,
                format,
                options,
            )
        }
    }

    override fun exportInstance(
        id: String,
        destination: Path,
    ): String {
        requireIdle(id)
        return jobs.enqueue(
            "导出 ${store.instance(id).name}",
            taskResourcesForInstances(store, id),
        ) {
            content.exportInstance(id, destination)
        }
    }

    /**
     * 按预览计划原地升级整合包；[expectedArchiveSha256] 防止预览后归档被替换。 需改游戏版本或加载器时由调用方确认后置
     * [allowComponentChange]，任务队列里执行并可取消。
     */

    override fun upgradePack(
        instanceId: String,
        archive: Path,
        expectedArchiveSha256: String,
        allowComponentChange: Boolean,
        policy: PackUpgradePolicy,
    ): String {
        requireIdle(instanceId)
        return jobs.enqueue(
            "升级整合包 ${store.instance(instanceId).name}",
            taskResourcesForInstances(store, instanceId),
        ) {
            content.upgradePack(
                instanceId,
                archive,
                expectedArchiveSha256,
                allowComponentChange,
                this,
                policy,
            )
        }
    }
}
