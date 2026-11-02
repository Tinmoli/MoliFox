// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责实例内的模组、资源包与原理图。

package dev.molifox.core

import dev.molifox.core.i18n.I18n
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.isRegularFile
import kotlin.io.path.isSymbolicLink
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

/** 实例目录里的模组、资源包、光影与原理图。 */
internal interface ContentLocalApi {
    suspend fun packIcon(
        id: String,
        path: Path,
        folder: PackFolder,
    ): ByteArray?

    /** 列出 `resourcepacks/` / `shaderpacks/` 的直接子项（zip 或文件夹）。 */
    fun listPacks(
        id: String,
        folder: PackFolder,
    ): List<LocalPackEntry>

    /** 把 `.zip` 或文件夹导入资源包/光影目录；重名加序号，不覆盖、失败不留残留。 */
    suspend fun importPacks(
        id: String,
        folder: PackFolder,
        sources: List<Path>,
        context: JobContext? = null,
    ): List<LocalPackEntry>

    /** 删除一个资源包/光影（移入回收站）。 */
    fun deletePack(
        id: String,
        folder: PackFolder,
        filename: String,
    ): Boolean

    fun listMods(id: String): List<ModFile>

    fun toggleMod(
        id: String,
        filename: String,
    )

    fun deleteMod(
        id: String,
        filename: String,
    )

    fun importMods(
        id: String,
        files: List<Path>,
    )

    /** 原理图（世界编辑 litematic/schem 等）导入到实例的 schematics 目录。 */
    fun importSchematics(
        id: String,
        files: List<Path>,
    )

    /** 重命名原理图目录下的文件或文件夹；[relativePath] 相对于 schematics 根目录。 */
    fun renameSchematicEntry(
        id: String,
        relativePath: String,
        newName: String,
    )

    /** 把原理图目录下的文件或文件夹移入回收站；[relativePath] 相对于 schematics 根目录。 */
    fun deleteSchematicEntry(
        id: String,
        relativePath: String,
    )
}

private const val SCHEMATIC_MAX_BYTES = 256L * 1024 * 1024

/** 实现由 [ContentService] 委托调用；方法体与原实现一致。 */
internal abstract class ContentLocalActions(
    core: ContentServiceCore,
) : ContentPackDownloadActions(core),
    ContentLocalApi,
    ContentRenameApi by ContentRenameActions(core) {
    override suspend fun packIcon(
        id: String,
        path: Path,
        folder: PackFolder,
    ): ByteArray? =
        installedIcons
            .read(id, path, folder)
            .onFailure {
                store.log(
                    I18n.t(
                        "contentA1.local.packIconReadFailed",
                        folder.title,
                        path.fileName,
                        it.message.toString(),
                    ),
                    true,
                )
            }.getOrNull()

    /** 列出 `resourcepacks/` / `shaderpacks/` 的直接子项（zip 或文件夹）。 */
    override fun listPacks(
        id: String,
        folder: PackFolder,
    ): List<LocalPackEntry> = localPacks.list(id, folder)

    /** 把 `.zip` 或文件夹导入资源包/光影目录；重名加序号，不覆盖、失败不留残留。 */
    override suspend fun importPacks(
        id: String,
        folder: PackFolder,
        sources: List<Path>,
        context: JobContext?,
    ): List<LocalPackEntry> = localPacks.import(id, folder, sources, context)

    /** 删除一个资源包/光影（移入回收站）。 */
    override fun deletePack(
        id: String,
        folder: PackFolder,
        filename: String,
    ): Boolean = localPacks.delete(id, folder, filename)

    override fun listMods(id: String): List<ModFile> {
        val directory = store.instancePath(id).resolve("mods")
        if (!directory.exists()) return emptyList()
        return directory
            .listDirectoryEntries()
            .filter {
                it.isRegularFile() &&
                    !Files.isSymbolicLink(it) &&
                    !it.name.startsWith(".molifox-") &&
                    Regex("(?i).*\\.jar(?:\\.disabled)?").matches(it.name)
            }.map {
                ModFile(
                    it.name,
                    it.fileSize(),
                    !it.name.endsWith(".disabled", true),
                    metadataReader.read(it),
                    it.getLastModifiedTime().toMillis(),
                )
            }.sortedBy { it.filename.lowercase() }
    }

    override fun toggleMod(
        id: String,
        filename: String,
    ) {
        require(Regex("(?i).*\\.jar(?:\\.disabled)?").matches(filename)) {
            I18n.t("contentA1.local.invalidModFile")
        }
        val directory = store.instancePath(id).resolve("mods")
        val source = safePath(directory, safeFilename(filename))
        val target =
            safePath(
                directory,
                if (filename.endsWith(".disabled", true)) {
                    contentBaseFilename(filename)
                } else {
                    "$filename.disabled"
                },
            )
        require(!target.exists()) { I18n.t("contentA1.local.modTargetExists") }
        Files.move(source, target)
    }

    override fun deleteMod(
        id: String,
        filename: String,
    ) {
        require(Regex("(?i).*\\.jar(?:\\.disabled)?").matches(filename))
        store.platform.moveToTrash(
            safePath(store.instancePath(id).resolve("mods"), safeFilename(filename)),
        )
    }

    override fun importMods(
        id: String,
        files: List<Path>,
    ) {
        require(store.instance(id).loader != LoaderType.Vanilla) {
            I18n.t("contentA1.local.loaderInstanceRequired")
        }
        val destination = store.instancePath(id).resolve("mods").apply { createDirectories() }
        for (file in files) {
            require(file.name.endsWith(".jar", true))
            Files.copy(file, safePath(destination, safeFilename(file.name)))
        }
    }

    /** 原理图（世界编辑 litematic/schem 等）导入到实例的 schematics 目录。 */
    override fun importSchematics(
        id: String,
        files: List<Path>,
    ) {
        require(files.isNotEmpty()) { I18n.t("contentA1.local.selectSchematics") }
        val destination = store.instancePath(id).resolve("schematics").apply { createDirectories() }
        for (file in files) {
            require(Regex("(?i).*\\.(litematic|schem|schematic|nbt)").matches(file.name)) {
                I18n.t("contentA1.local.schematicFormats")
            }
            require(runCatching { file.fileSize() }.getOrDefault(0L) <= SCHEMATIC_MAX_BYTES) {
                I18n.t("contentA1.local.schematicTooLarge")
            }
            Files.copy(file, safePath(destination, safeFilename(file.name)))
        }
    }

    /** 目录内重命名：只允许改当前层名字，路径与符号链接由 safePath 兜底。 */
    override fun renameSchematicEntry(
        id: String,
        relativePath: String,
        newName: String,
    ) {
        val root = store.instancePath(id).resolve("schematics")
        val source = safePath(root, relativePath)
        require(Files.exists(source, LinkOption.NOFOLLOW_LINKS)) {
            I18n.t("contentA1.local.renameSourceMissing")
        }
        val trimmed = newName.trim()
        safeFilename(trimmed)
        val parent = relativePath.replace('\\', '/').substringBeforeLast('/', "")
        val targetRelative = if (parent.isBlank()) trimmed else "$parent/$trimmed"
        val target = safePath(root, targetRelative)
        require(!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            I18n.t("contentA1.local.renameTargetExists", trimmed)
        }
        Files.move(source, target)
        store.notify(I18n.t("contentA1.local.renamed", source.fileName, trimmed))
    }

    override fun deleteSchematicEntry(
        id: String,
        relativePath: String,
    ) {
        val target = safePath(store.instancePath(id).resolve("schematics"), relativePath)
        require(Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            I18n.t("contentA1.local.deleteSourceMissing")
        }
        store.platform.moveToTrash(target)
        store.notify(I18n.t("contentA1.local.movedToTrash", target.fileName))
    }
}
