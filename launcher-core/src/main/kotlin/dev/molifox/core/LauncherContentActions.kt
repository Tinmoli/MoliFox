// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责内容安装、整合包与导出任务。

package dev.molifox.core

import dev.molifox.core.i18n.I18n
import java.nio.file.Path
import java.util.UUID

/** 内容、整合包与导出的后台任务入口。 */
internal interface LauncherContentApi : LauncherContentUpdateApi {
    fun exportMrpack(request: PackExportRequest): String

    fun installContent(
        hit: ContentHit,
        id: String?,
    ): String

    fun installContent(plan: ContentInstallPlan): String

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

    /** 只下载选中版本的主文件到本地，不安装、不写实例登记表。 */
    fun saveContentFile(
        hit: ContentHit,
        versionId: String,
        destination: Path,
    ): String

    /** 在后台任务里下载世界包并安装到指定目录（默认实例 `saves/`）；重名会被拒绝。 */
    fun installWorld(
        hit: ContentHit,
        versionId: String,
        instanceId: String,
        destination: Path? = null,
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
) : LauncherContentUpdateActions(core),
    LauncherContentApi {
    override fun exportMrpack(request: PackExportRequest): String {
        requireIdle(request.instanceId)
        val fixed =
            request.copy(
                selectedEntries = request.selectedEntries.toSet(),
                excludedPaths = request.excludedPaths.toSet(),
            )
        return jobs.enqueue(
            I18n.t(
                if (fixed.format.importable) "coreC.tasks.exportPack" else "coreC.tasks.exportFullBackup",
                fixed.metadata.name,
            ),
            taskResourcesForInstances(store, fixed.instanceId),
            kind = LauncherTaskKind.ExportContent,
        ) {
            val plan = content.prepareRequestedExport(fixed, this)
            content.exportMrpack(
                plan,
                fixed.destination,
                this,
                plan.files.map { it.path }.toSet(),
                exportLocalResourcePaths(plan, fixed.format),
                fixed.format,
                fixed.options,
            )
        }
    }

    override fun installContent(
        hit: ContentHit,
        id: String?,
    ): String {
        val resources =
            if (hit.type == "modpack") {
                setOf("new-pack:${UUID.randomUUID()}")
            } else {
                taskResourcesForInstances(store, requireNotNull(id) { I18n.t("coreC.content.chooseInstance") })
            }
        val directoryId = store.data.activeGameDirectoryId
        return jobs.enqueue(
            I18n.t("coreC.tasks.install", hit.title),
            resources,
            kind = LauncherTaskKind.InstallContent,
        ) {
            if (hit.type == "modpack") enableInstallationTransfers()
            content.installContent(hit, id, this, directoryId)
        }
    }

    override fun installContent(plan: ContentInstallPlan): String =
        jobs.enqueue(
            I18n.t("coreC.tasks.install", plan.title),
            taskResourcesForInstances(store, plan.instanceId),
            kind = LauncherTaskKind.InstallContent,
        ) {
            content.installPlan(plan, this)
        }

    override fun preparePack(
        hit: ContentHit,
        versionId: String,
        onReady: (PackPreview) -> Unit,
    ): String =
        jobs.enqueue(
            I18n.t("coreC.tasks.prepare", hit.title),
            setOf("pack-download:${hit.id}:$versionId"),
            kind = LauncherTaskKind.PrepareContent,
        ) {
            val archive = content.downloadPack(hit, this, versionId)
            onReady(content.previewPack(archive))
        }

    override fun installDatapack(
        hit: ContentHit,
        versionId: String,
        instanceId: String,
        worldName: String,
    ): String =
        jobs.enqueue(
            I18n.t("coreC.tasks.installDatapack", hit.title),
            taskResourcesForInstances(store, instanceId),
            kind = LauncherTaskKind.InstallContent,
        ) {
            content.installDatapack(hit, versionId, instanceId, worldName, this)
        }

    override fun saveContentFile(
        hit: ContentHit,
        versionId: String,
        destination: Path,
    ): String =
        jobs.enqueue(
            I18n.t("coreC.tasks.saveContent", hit.title),
            emptySet(),
            kind = LauncherTaskKind.PrepareContent,
        ) {
            content.saveContentFile(hit, versionId, destination, this)
        }

    override fun installWorld(
        hit: ContentHit,
        versionId: String,
        instanceId: String,
        destination: Path?,
    ): String {
        require(hit.type == "world") { I18n.t("coreC.world.notAWorld") }
        require(versionId.isNotBlank()) { I18n.t("coreC.world.chooseVersion") }
        return jobs.enqueue(
            I18n.t("coreC.tasks.installWorld", hit.title),
            taskResourcesForInstances(store, instanceId),
            kind = LauncherTaskKind.InstallContent,
        ) {
            content.installWorld(hit, versionId, instanceId, this, destination)
        }
    }

    override fun importPack(
        file: Path,
        optionalFiles: Set<String>?,
        expectedHash: String?,
    ): String {
        val directoryId = store.data.activeGameDirectoryId
        return jobs.enqueue(
            I18n.t("coreC.tasks.import", file.fileName),
            setOf("new-pack:${UUID.randomUUID()}"),
            kind = LauncherTaskKind.ImportContent,
        ) {
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
            if (format.importable) {
                I18n.t("coreC.tasks.exportPack", plan.metadata.name)
            } else {
                I18n.t("coreC.tasks.exportFullBackup", plan.metadata.name)
            },
            taskResourcesForInstances(store, plan.instanceId),
            kind = LauncherTaskKind.ExportContent,
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
            I18n.t("coreC.tasks.exportInstance", store.instance(id).name),
            taskResourcesForInstances(store, id),
            kind = LauncherTaskKind.ExportContent,
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
            I18n.t("coreC.tasks.upgradePack", store.instance(instanceId).name),
            taskResourcesForInstances(store, instanceId),
            kind = LauncherTaskKind.UpdateContent,
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
