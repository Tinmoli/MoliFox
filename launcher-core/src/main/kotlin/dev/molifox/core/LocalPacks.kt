// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责资源包与光影目录的列出、导入（zip / 文件夹）与删除。

package dev.molifox.core

import dev.molifox.core.i18n.I18n
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID
import java.util.zip.ZipFile
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

/** `resourcepacks/` 或 `shaderpacks/` 下的一个直接子项：`.zip` 或文件夹。 */
data class LocalPackEntry(
    val filename: String,
    val path: Path,
    val isDirectory: Boolean,
    val size: Long,
    val modified: Long,
)

/** 导入临时前缀：先写进同目录的隐藏项，成功后一次改名；失败不留残留。 */
private const val PACK_STAGING_PREFIX = ".molifox-import-"

private const val MAX_PACK_BYTES = 512L * 1024 * 1024

/** 重名后缀上限：`Pack (999).zip` 之后改用其他名称。 */
private const val MAX_NAME_SUFFIX = 999

/** 生成的文件名长度上限，避免超出常见文件系统的单段名称限制。 */
private const val MAX_NAME_LENGTH = 120

/** 已校验的导入来源：原始路径、安全化后的目标名与是否为文件夹。 */
private class PreparedPackSource(
    val source: Path,
    val name: String,
    val isDirectory: Boolean,
)

/** 一次导入的进度：已写入磁盘的目标（失败时删除）与已生成的条目。 */
private class PackImportBatch(
    val created: MutableList<Path> = mutableListOf(),
    val imported: MutableList<LocalPackEntry> = mutableListOf(),
)

/**
 * 资源包与光影目录的文件管理。
 *
 * 只接受 `.zip` 文件或文件夹；重名时在扩展名前加序号（`Pack.zip` → `Pack (2).zip`），从不覆盖已有内容。
 * 复制先落同目录的隐藏临时项，成功后再改名；任何一步失败都删除临时项与本次已导入的内容。
 */
class LocalPackStore internal constructor(
    private val store: LauncherStore,
) {
    fun list(
        instanceId: String,
        folder: PackFolder,
    ): List<LocalPackEntry> {
        val directory = store.instancePath(instanceId).resolve(folder.directory)
        if (!directory.isDirectory(LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(directory)) {
            return emptyList()
        }
        return runCatching {
            directory
                .listDirectoryEntries()
                .filter {
                    !it.name.startsWith(".") &&
                        !Files.isSymbolicLink(it) &&
                        (
                            it.isRegularFile(LinkOption.NOFOLLOW_LINKS) ||
                                it.isDirectory(LinkOption.NOFOLLOW_LINKS)
                        )
                }.filter {
                    it.isDirectory(LinkOption.NOFOLLOW_LINKS) || it.name.endsWith(".zip", true)
                }.map { entry ->
                    val isDirectory = entry.isDirectory(LinkOption.NOFOLLOW_LINKS)
                    LocalPackEntry(
                        filename = entry.name,
                        path = entry,
                        isDirectory = isDirectory,
                        size =
                            if (isDirectory) {
                                0L
                            } else {
                                runCatching { Files.size(entry) }.getOrDefault(0L)
                            },
                        modified =
                            runCatching { Files.getLastModifiedTime(entry).toMillis() }
                                .getOrDefault(0L),
                    )
                }.sortedWith(compareBy({ !it.isDirectory }, { it.filename.lowercase() }))
        }.getOrElse { emptyList() }
    }

    /**
     * 把拖入或选择的 `.zip` / 文件夹复制到目标目录，返回导入后的条目。
     *
     * 先校验全部来源再开始复制：不存在的、符号链接、非 zip、损坏 zip 都直接报错，磁盘上不会留下半份内容。
     */
    suspend fun import(
        instanceId: String,
        folder: PackFolder,
        sources: List<Path>,
        context: JobContext? = null,
    ): List<LocalPackEntry> =
        withContext(Dispatchers.IO) {
            require(sources.isNotEmpty()) { I18n.t("packsB1.localPacks.noSources") }
            val directory = store.instancePath(instanceId).resolve(folder.directory)
            require(!Files.isSymbolicLink(directory)) {
                I18n.t("packsB1.localPacks.directorySymlink", folder.title)
            }
            directory.createDirectories()

            val prepared = prepareSources(sources)
            val batch = PackImportBatch()
            try {
                copyPrepared(directory, prepared, folder, context, batch)
            } catch (cancelled: CancellationException) {
                rollbackImported(batch.created)
                throw cancelled
            } catch (failure: IllegalArgumentException) {
                rollbackImported(batch.created)
                throw failure
            } catch (failure: IllegalStateException) {
                // 名称耗尽等校验失败发生在复制途中，同样要清掉本次已导入的内容。
                rollbackImported(batch.created)
                throw failure
            } catch (failure: IOException) {
                rollbackImported(batch.created)
                throw failure
            }
            store.log(
                I18n.t(
                    "packsB1.localPacks.imported",
                    folder.title,
                    batch.imported.joinToString(
                        I18n.t("packsB1.localPacks.nameSeparator"),
                    ) { it.filename },
                ),
            )
            batch.imported
        }

    /** 复制前逐个校验来源；任何一项不合格都在写入磁盘前失败。 */
    private suspend fun prepareSources(sources: List<Path>): List<PreparedPackSource> =
        sources.map { raw ->
            currentCoroutineContext().ensureActive()
            val source = raw.toAbsolutePath().normalize()
            require(source.exists(LinkOption.NOFOLLOW_LINKS)) {
                I18n.t("packsB1.localPacks.sourceMissing", raw.fileName)
            }
            require(!Files.isSymbolicLink(source)) {
                I18n.t("packsB1.localPacks.sourceSymlink", raw.fileName)
            }
            val isDirectory = source.isDirectory(LinkOption.NOFOLLOW_LINKS)
            if (!isDirectory) {
                require(
                    source.isRegularFile(LinkOption.NOFOLLOW_LINKS) &&
                        source.name.endsWith(".zip", true),
                ) {
                    I18n.t("packsB1.localPacks.unsupportedSource", raw.fileName)
                }
                require(
                    runCatching { Files.size(source) }.getOrDefault(0L) <= MAX_PACK_BYTES,
                ) {
                    I18n.t("packsB1.localPacks.fileTooLarge", raw.fileName)
                }
                require(
                    runCatching {
                        ZipFile(
                            source.toFile(),
                        ).use { true }
                    }.getOrDefault(false),
                ) {
                    I18n.t("packsB1.localPacks.invalidZip", raw.fileName)
                }
            }
            PreparedPackSource(source, safePackFilename(source.name), isDirectory)
        }

    /** 逐个复制已校验来源并登记结果；失败时由调用方删除本次已写入的目标。 */
    private suspend fun copyPrepared(
        directory: Path,
        prepared: List<PreparedPackSource>,
        folder: PackFolder,
        context: JobContext?,
        batch: PackImportBatch,
    ) {
        prepared.forEachIndexed { index, item ->
            currentCoroutineContext().ensureActive()
            val name = uniqueChildName(directory, item.name)
            val target = directory.resolve(name)
            require(!target.exists(LinkOption.NOFOLLOW_LINKS)) { I18n.t("packsB1.localPacks.targetExists", name) }
            copyOne(directory, item, target, context)
            batch.created.add(target)
            batch.imported.add(
                LocalPackEntry(
                    filename = name,
                    path = target,
                    isDirectory = item.isDirectory,
                    size =
                        if (item.isDirectory) {
                            0L
                        } else {
                            runCatching { Files.size(target) }.getOrDefault(0L)
                        },
                    modified =
                        runCatching { Files.getLastModifiedTime(target).toMillis() }
                            .getOrDefault(0L),
                ),
            )
            context?.progress(
                (index + 1).toFloat() / prepared.size,
                I18n.t("packsB1.localPacks.importProgress", folder.title, name),
            )
        }
    }

    /** 单项复制：先写同目录的隐藏临时项，成功后改名；任何结果都清理临时项。 */
    private suspend fun copyOne(
        directory: Path,
        item: PreparedPackSource,
        target: Path,
        context: JobContext?,
    ) {
        val staging = directory.resolve("$PACK_STAGING_PREFIX${UUID.randomUUID()}")
        try {
            if (item.isDirectory) {
                copyTree(item.source, staging)
            } else {
                Files.copy(item.source, staging)
            }
            context?.checkCancelled()
            Files.move(staging, target)
        } finally {
            runCatching { deleteTreeOrFile(staging) }
        }
    }

    /** 失败或取消时删除本次已导入的目标；单个删除失败不掩盖原始失败。 */
    private fun rollbackImported(created: List<Path>) {
        created.forEach { runCatching { deleteTreeOrFile(it) } }
    }

    /** 删除一个包：移入回收站，不硬删；找不到或回收站失败返回 false。 */
    fun delete(
        instanceId: String,
        folder: PackFolder,
        filename: String,
    ): Boolean {
        val directory = store.instancePath(instanceId).resolve(folder.directory)
        val target =
            runCatching { safePath(directory, safePackFilename(filename)) }.getOrNull()
                ?: return false
        require(
            target.parent.toAbsolutePath().normalize() == directory.toAbsolutePath().normalize(),
        ) {
            I18n.t("packsB1.localPacks.deleteOutsideFolder", folder.title, filename)
        }
        if (!target.exists(LinkOption.NOFOLLOW_LINKS)) return false
        return runCatching {
            store.platform.moveToTrash(target)
            store.log(I18n.t("packsB1.localPacks.movedToTrash", folder.title, filename))
            true
        }.getOrElse {
            store.log(
                I18n.t("packsB1.localPacks.deleteFailed", folder.title, it.message ?: filename),
                true,
            )
            false
        }
    }

    /** 同目录重命名；zip 保留扩展名，文件夹不追加扩展名，来源登记与文件移动保持一致。 */
    fun rename(
        instanceId: String,
        folder: PackFolder,
        filename: String,
        newName: String,
    ): String {
        val source = safePath(store.instancePath(instanceId).resolve(folder.directory), safeFilename(filename))
        val directory = source.isDirectory(LinkOption.NOFOLLOW_LINKS)
        return renameInstalledContent(
            store,
            instanceId,
            folder.directory,
            filename,
            newName,
            if (directory) RenameContentKind.DirectoryPack else RenameContentKind.ZipPack,
        )
    }

    /** 递归复制文件夹：跳过符号链接/目录联接与越界路径，空目录也会建出来。 */
    private fun copyTree(
        source: Path,
        target: Path,
    ) {
        val root = source.toAbsolutePath().normalize()
        val realRoot = runCatching { root.toRealPath() }.getOrElse { root }
        Files.walkFileTree(
            root,
            object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(
                    dir: Path,
                    attrs: BasicFileAttributes,
                ): FileVisitResult {
                    if (dir != root && (attrs.isOther || attrs.isSymbolicLink)) {
                        return FileVisitResult.SKIP_SUBTREE
                    }
                    target.resolve(root.relativize(dir).joinToString("/")).createDirectories()
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(
                    file: Path,
                    attrs: BasicFileAttributes,
                ): FileVisitResult {
                    if (!attrs.isRegularFile || attrs.isOther) return FileVisitResult.CONTINUE
                    val real =
                        runCatching { file.toRealPath() }.getOrNull()
                            ?: return FileVisitResult.CONTINUE
                    if (!real.startsWith(realRoot)) return FileVisitResult.CONTINUE
                    val destination = target.resolve(root.relativize(file).joinToString("/"))
                    destination.parent.createDirectories()
                    Files.copy(file, destination, StandardCopyOption.REPLACE_EXISTING)
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(
                    file: Path,
                    error: IOException,
                ): FileVisitResult = FileVisitResult.CONTINUE
            },
        )
    }
}

/**
 * 同目录内不冲突的名称：重名时在扩展名前加序号（`Pack.zip` → `Pack (2).zip`、`World` → `World (2)`）。
 *
 * 只返回名字，不创建任何东西；目录里已有同名项时继续往后找。
 */
internal fun uniqueChildName(
    directory: Path,
    name: String,
): String {
    val trimmed = name.trim()
    require(trimmed.isNotBlank()) { I18n.t("packsB1.localPacks.blankName") }
    val dot = trimmed.lastIndexOf('.')
    val hasExtension = dot > 0 && dot < trimmed.length - 1
    val base = if (hasExtension) trimmed.substring(0, dot) else trimmed
    val extension = if (hasExtension) trimmed.substring(dot) else ""

    fun taken(candidate: String) = directory.resolve(candidate).exists(LinkOption.NOFOLLOW_LINKS)
    if (!taken(trimmed)) return trimmed
    for (index in 2..MAX_NAME_SUFFIX) {
        val candidate = "$base ($index)$extension"
        if (candidate.length <= MAX_NAME_LENGTH && !taken(candidate)) return candidate
    }
    error(I18n.t("packsB1.localPacks.tooManyNames"))
}

/** 文件名安全化：去掉各平台的非法字符与结尾的点/空格，限制长度，空名称回退为 pack。 */
internal fun safePackFilename(name: String): String {
    val cleaned =
        name
            .trim()
            .replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]"), "_")
            .trimEnd('.', ' ')
            .trim()
    val limited = cleaned.take(100).trimEnd('.', ' ')
    return limited.ifBlank { "pack" }
}

/**
 * 删除文件或目录树；符号链接与目录联接只删链接本身，不进入目标目录。
 *
 * 转发到 [deletionDeleteTree]，与实例删除共用同一套链接判据：裸 `Files.walk` / `walkFileTree`
 * 会进入 Windows 目录联接指向的真实目录，误删用户放在别处的内容。
 */
internal fun deleteTreeOrFile(path: Path) {
    deletionDeleteTree(path)
}
