// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责整合包与实例导出。

package dev.molifox.core

import dev.molifox.core.i18n.I18n
import kotlinx.coroutines.flow.Flow
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.isRegularFile

/** 导出对话框与整合包/实例导出。 */
internal interface ContentExportApi {
    /**
     * 导出对话框的顶层内容扫描：列出实例目录的直接子项并按实际文件统计数量与体积。
     * 边扫描边发快照，界面可先显示 `…` 再逐步更新；取消收集即停止扫描。
     */
    fun exportEntries(id: String): Flow<PackExportScan>

    suspend fun previewMrpackExport(
        id: String,
        metadata: MrpackExportMetadata,
        resolveDownloads: Boolean = true,
        resolveCurseForge: Boolean = false,
        includeLogs: Boolean = false,
    ): MrpackExportPlan

    suspend fun exportMrpack(
        plan: MrpackExportPlan,
        destination: Path,
        context: JobContext,
        selected: Set<String> = plan.defaultSelection,
        permittedLocalFiles: Set<String> = emptySet(),
        format: PackExportFormat = PackExportFormat.Modrinth,
        options: PackExportOptions = PackExportOptions(),
    )

    fun exportInstance(
        id: String,
        destination: Path,
    )
}

/** 导出实例时按目录首段保留的内容；其余运行文件不进入整合包。 */
private val EXPORT_CONTENT_ROOTS =
    setOf(
        "mods",
        "saves",
        "config",
        "resourcepacks",
        "shaderpacks",
        "screenshots",
        "defaultconfigs",
        "kubejs",
        "scripts",
        "options.txt",
        "optionsof.txt",
        "servers.dat",
    )

/** 任意层级命中即跳过的运行产物。 */
private val EXPORT_SKIPPED_NAMES =
    setOf(
        "natives",
        "logs",
        "crash-reports",
        ".molifox-classpath.jar",
        ".molifox-content.json",
    )

/** 实现由 [ContentService] 委托调用；方法体与原实现一致。 */
internal abstract class ContentExportActions(
    core: ContentServiceCore,
) : ContentPackUpgradeActions(core),
    ContentExportApi {
    override fun exportEntries(id: String): Flow<PackExportScan> =
        MrpackExporter(store, network, api, curseForge).entries(id)

    override suspend fun previewMrpackExport(
        id: String,
        metadata: MrpackExportMetadata,
        resolveDownloads: Boolean,
        resolveCurseForge: Boolean,
        includeLogs: Boolean,
    ): MrpackExportPlan =
        MrpackExporter(store, network, api, curseForge)
            .preview(id, metadata, resolveDownloads, resolveCurseForge, includeLogs)

    override suspend fun exportMrpack(
        plan: MrpackExportPlan,
        destination: Path,
        context: JobContext,
        selected: Set<String>,
        permittedLocalFiles: Set<String>,
        format: PackExportFormat,
        options: PackExportOptions,
    ) = MrpackExporter(store, network, api, curseForge)
        .export(plan, destination, selected, permittedLocalFiles, context, format, options)

    override fun exportInstance(
        id: String,
        destination: Path,
    ) {
        val instance = store.instance(id)
        require(
            instance.installed &&
                (!instance.externalProfile || instance.loader == LoaderType.Vanilla),
        ) {
            I18n.t("contentA1.export.externalLoaderUnsupported")
        }
        val root = store.instancePath(id)
        destination.parent?.createDirectories()
        require(
            !destination.toAbsolutePath().normalize().startsWith(root.toAbsolutePath().normalize()),
        ) {
            I18n.t("contentA1.export.destinationInsideInstance")
        }
        val temporary = destination.resolveSibling("${destination.fileName}.part")
        try {
            ZipOutputStream(Files.newOutputStream(temporary)).use { zip ->
                writeInstanceManifest(zip, instance)
                writeInstanceEntries(zip, root, instance)
            }
            Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            temporary.deleteIfExists()
        }
        store.log(I18n.t("contentA1.export.instanceExported", destination))
    }

    /** 把实例目录里的普通文件（跳过符号链接）按导出规则逐个写进压缩包。 */
    private fun writeInstanceEntries(
        zip: ZipOutputStream,
        root: Path,
        instance: GameInstance,
    ) {
        Files.walk(root).use { paths ->
            paths
                .filter { it.isRegularFile() && !Files.isSymbolicLink(it) }
                .forEach { file -> addExportEntry(zip, root, instance, file) }
        }
    }

    private fun writeInstanceManifest(
        zip: ZipOutputStream,
        instance: GameInstance,
    ) {
        zip.putNextEntry(ZipEntry("molifox-instance.json"))
        zip.write(
            gson
                .toJson(
                    mapOf(
                        "formatVersion" to 1,
                        "instance" to
                            InstallRequest(
                                instance.name,
                                instance.gameVersion,
                                instance.loader,
                                instance.loaderVersion,
                            ),
                    ),
                ).toByteArray(Charsets.UTF_8),
        )
        zip.closeEntry()
    }

    private fun addExportEntry(
        zip: ZipOutputStream,
        root: Path,
        instance: GameInstance,
        file: Path,
    ) {
        val relative = root.relativize(file)
        if (!shouldExportEntry(instance, relative)) return
        zip.putNextEntry(ZipEntry("files/${relative.joinToString("/")}"))
        Files.copy(file, zip)
        zip.closeEntry()
    }

    /** 隔离实例只导出用户内容根目录；运行产物在任意层级都跳过。 */
    private fun shouldExportEntry(
        instance: GameInstance,
        relative: Path,
    ): Boolean {
        val includeAll = !store.isolated(instance) || instance.gameDirectoryId != null
        if (includeAll && relative.first().toString() !in EXPORT_CONTENT_ROOTS) return false
        return relative.none { it.toString() in EXPORT_SKIPPED_NAMES }
    }
}
