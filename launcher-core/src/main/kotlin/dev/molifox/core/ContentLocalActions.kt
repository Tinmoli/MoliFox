// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责实例内的模组、资源包与原理图。

package dev.molifox.core

import java.nio.file.Files
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

    fun deleteSchematic(
        id: String,
        filename: String,
    )
}

private const val SCHEMATIC_MAX_BYTES = 256L * 1024 * 1024

/** 实现由 [ContentService] 委托调用；方法体与原实现一致。 */
internal abstract class ContentLocalActions(
    core: ContentServiceCore,
) : ContentPackDownloadActions(core),
    ContentLocalApi {
    override suspend fun packIcon(
        id: String,
        path: Path,
        folder: PackFolder,
    ): ByteArray? =
        installedIcons
            .read(id, path, folder)
            .onFailure {
                store.log("无法读取${folder.title}头像 ${path.fileName}：${it.message}", true)
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
        require(Regex("(?i).*\\.jar(?:\\.disabled)?").matches(filename)) { "无效的模组文件" }
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
        require(!target.exists()) { "目标文件已存在，请处理重复模组" }
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
        require(store.instance(id).loader != LoaderType.Vanilla) { "请先选择带加载器的实例" }
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
        require(files.isNotEmpty()) { "请选择原理图文件" }
        val destination = store.instancePath(id).resolve("schematics").apply { createDirectories() }
        for (file in files) {
            require(Regex("(?i).*\\.(litematic|schem|schematic|nbt)").matches(file.name)) {
                "仅支持 .litematic / .schem / .schematic / .nbt 原理图"
            }
            require(runCatching { file.fileSize() }.getOrDefault(0L) <= SCHEMATIC_MAX_BYTES) {
                "原理图文件过大（上限 256 MB）"
            }
            Files.copy(file, safePath(destination, safeFilename(file.name)))
        }
    }

    override fun deleteSchematic(
        id: String,
        filename: String,
    ) {
        require(Regex("(?i).*\\.(litematic|schem|schematic|nbt)").matches(filename)) { "无效的原理图文件" }
        store.platform.moveToTrash(
            safePath(store.instancePath(id).resolve("schematics"), safeFilename(filename)),
        )
    }
}
