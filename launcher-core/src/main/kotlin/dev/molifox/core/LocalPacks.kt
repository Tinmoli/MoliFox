// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责资源包与光影目录的列出、导入（zip / 文件夹）与删除。

package dev.molifox.core

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
import kotlin.io.path.*
import kotlinx.coroutines.*

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

/**
 * 资源包与光影目录的文件管理。
 *
 * 只接受 `.zip` 文件或文件夹；重名时在扩展名前加序号（`Pack.zip` → `Pack (2).zip`），从不覆盖已有内容。 复制先落同目录的隐藏临时项，成功后再改名；任何一步失败都删除临时项与本次已导入的内容。
 */
class LocalPackStore internal constructor(private val store: LauncherStore) {

    fun list(instanceId: String, folder: PackFolder): List<LocalPackEntry> {
        val directory = store.instancePath(instanceId).resolve(folder.directory)
        if (!directory.isDirectory(LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(directory))
            return emptyList()
        return runCatching {
                directory
                    .listDirectoryEntries()
                    .filter {
                        !it.name.startsWith(".") &&
                            !Files.isSymbolicLink(it) &&
                            (it.isRegularFile(LinkOption.NOFOLLOW_LINKS) ||
                                it.isDirectory(LinkOption.NOFOLLOW_LINKS))
                    }
                    .filter {
                        it.isDirectory(LinkOption.NOFOLLOW_LINKS) || it.name.endsWith(".zip", true)
                    }
                    .map { entry ->
                        val isDirectory = entry.isDirectory(LinkOption.NOFOLLOW_LINKS)
                        LocalPackEntry(
                            filename = entry.name,
                            path = entry,
                            isDirectory = isDirectory,
                            size =
                                if (isDirectory) 0L
                                else runCatching { Files.size(entry) }.getOrDefault(0L),
                            modified =
                                runCatching { Files.getLastModifiedTime(entry).toMillis() }
                                    .getOrDefault(0L),
                        )
                    }
                    .sortedWith(compareBy({ !it.isDirectory }, { it.filename.lowercase() }))
            }
            .getOrElse { emptyList() }
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
            require(sources.isNotEmpty()) { "请选择要导入的文件" }
            val directory = store.instancePath(instanceId).resolve(folder.directory)
            require(!Files.isSymbolicLink(directory)) { "${folder.title}目录是符号链接，未导入" }
            directory.createDirectories()
            data class Prepared(val source: Path, val name: String, val isDirectory: Boolean)

            val prepared =
                sources.map { raw ->
                    currentCoroutineContext().ensureActive()
                    val source = raw.toAbsolutePath().normalize()
                    require(source.exists(LinkOption.NOFOLLOW_LINKS)) { "找不到文件：${raw.fileName}" }
                    require(!Files.isSymbolicLink(source)) { "不支持符号链接：${raw.fileName}" }
                    val isDirectory = source.isDirectory(LinkOption.NOFOLLOW_LINKS)
                    if (!isDirectory) {
                        require(
                            source.isRegularFile(LinkOption.NOFOLLOW_LINKS) &&
                                source.name.endsWith(".zip", true)
                        ) {
                            "只能导入 .zip 或文件夹：${raw.fileName}"
                        }
                        require(runCatching { Files.size(source) }.getOrDefault(0L) <= MAX_PACK_BYTES) {
                            "文件过大（上限 512 MB）：${raw.fileName}"
                        }
                        require(
                            runCatching { ZipFile(source.toFile()).use { true } }.getOrDefault(false)
                        ) {
                            "不是有效的 ZIP：${raw.fileName}"
                        }
                    }
                    Prepared(source, safePackFilename(source.name), isDirectory)
                }
            val created = mutableListOf<Path>()
            val imported = mutableListOf<LocalPackEntry>()
            try {
                prepared.forEachIndexed { index, item ->
                    currentCoroutineContext().ensureActive()
                    val name = uniqueChildName(directory, item.name)
                    val target = directory.resolve(name)
                    require(!target.exists(LinkOption.NOFOLLOW_LINKS)) { "目标已存在：$name" }
                    val staging = directory.resolve("$PACK_STAGING_PREFIX${UUID.randomUUID()}")
                    try {
                        if (item.isDirectory) copyTree(item.source, staging)
                        else Files.copy(item.source, staging)
                        context?.checkCancelled()
                        Files.move(staging, target)
                    } finally {
                        runCatching { deleteTreeOrFile(staging) }
                    }
                    created.add(target)
                    imported.add(
                        LocalPackEntry(
                            filename = name,
                            path = target,
                            isDirectory = item.isDirectory,
                            size =
                                if (item.isDirectory) 0L
                                else runCatching { Files.size(target) }.getOrDefault(0L),
                            modified =
                                runCatching { Files.getLastModifiedTime(target).toMillis() }
                                    .getOrDefault(0L),
                        )
                    )
                    context?.progress(
                        (index + 1).toFloat() / prepared.size,
                        "导入 ${folder.title} · $name",
                    )
                }
            } catch (error: Throwable) {
                created.forEach { runCatching { deleteTreeOrFile(it) } }
                throw error
            }
            store.log("已导入 ${folder.title}：${imported.joinToString("、") { it.filename }}")
            imported
        }

    /** 删除一个包：移入回收站，不硬删；找不到或回收站失败返回 false。 */
    fun delete(instanceId: String, folder: PackFolder, filename: String): Boolean {
        val directory = store.instancePath(instanceId).resolve(folder.directory)
        val target =
            runCatching { safePath(directory, safePackFilename(filename)) }.getOrNull() ?: return false
        require(
            target.parent.toAbsolutePath().normalize() == directory.toAbsolutePath().normalize()
        ) {
            "只能删除${folder.title}目录下的内容：$filename"
        }
        if (!target.exists(LinkOption.NOFOLLOW_LINKS)) return false
        return runCatching {
                store.platform.moveToTrash(target)
                store.log("${folder.title}已移入回收站：$filename")
                true
            }
            .getOrElse {
                store.log("删除${folder.title}失败：${it.message ?: filename}", true)
                false
            }
    }

    /** 递归复制文件夹：跳过符号链接/目录联接与越界路径，空目录也会建出来。 */
    private fun copyTree(source: Path, target: Path) {
        val root = source.toAbsolutePath().normalize()
        val realRoot = runCatching { root.toRealPath() }.getOrElse { root }
        Files.walkFileTree(
            root,
            object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(
                    dir: Path,
                    attrs: BasicFileAttributes,
                ): FileVisitResult {
                    if (dir != root && (attrs.isOther || attrs.isSymbolicLink))
                        return FileVisitResult.SKIP_SUBTREE
                    target.resolve(root.relativize(dir).joinToString("/")).createDirectories()
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
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

                override fun visitFileFailed(file: Path, error: IOException): FileVisitResult =
                    FileVisitResult.CONTINUE
            },
        )
    }
}

/**
 * 同目录内不冲突的名称：重名时在扩展名前加序号（`Pack.zip` → `Pack (2).zip`、`World` → `World (2)`）。
 *
 * 只返回名字，不创建任何东西；目录里已有同名项时继续往后找。
 */
internal fun uniqueChildName(directory: Path, name: String): String {
    val trimmed = name.trim()
    require(trimmed.isNotBlank()) { "名称不能为空" }
    val dot = trimmed.lastIndexOf('.')
    val hasExtension = dot > 0 && dot < trimmed.length - 1
    val base = if (hasExtension) trimmed.substring(0, dot) else trimmed
    val extension = if (hasExtension) trimmed.substring(dot) else ""
    fun taken(candidate: String) = directory.resolve(candidate).exists(LinkOption.NOFOLLOW_LINKS)
    if (!taken(trimmed)) return trimmed
    for (index in 2..999) {
        val candidate = "$base ($index)$extension"
        if (candidate.length <= 120 && !taken(candidate)) return candidate
    }
    error("同名文件过多，请改用其他名称")
}

/** 文件名安全化：去掉各平台的非法字符与结尾的点/空格，限制长度，空名称回退为 pack。 */
internal fun safePackFilename(name: String): String {
    val cleaned =
        name.trim()
            .replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]"), "_")
            .trimEnd('.', ' ')
            .trim()
    val limited = cleaned.take(100).trimEnd('.', ' ')
    return limited.ifBlank { "pack" }
}

/** 删除文件或目录树；链接与目录联接只删链接本身，不进入目标目录。 */
internal fun deleteTreeOrFile(path: Path) {
    if (!path.exists(LinkOption.NOFOLLOW_LINKS)) return
    if (Files.isSymbolicLink(path) || !path.isDirectory(LinkOption.NOFOLLOW_LINKS)) {
        Files.deleteIfExists(path)
        return
    }
    Files.walkFileTree(
        path,
        object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(
                dir: Path,
                attrs: BasicFileAttributes,
            ): FileVisitResult =
                if (dir != path && (attrs.isOther || attrs.isSymbolicLink))
                    FileVisitResult.SKIP_SUBTREE
                else FileVisitResult.CONTINUE

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.deleteIfExists(file)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: Path, error: IOException?): FileVisitResult {
                Files.deleteIfExists(dir)
                return FileVisitResult.CONTINUE
            }
        },
    )
}