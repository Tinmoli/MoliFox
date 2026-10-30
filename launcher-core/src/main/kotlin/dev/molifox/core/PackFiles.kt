// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责整合包文件条目的归一化与校验。

package dev.molifox.core

import com.google.gson.JsonObject
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.isSymbolicLink
import kotlin.io.path.name
import kotlin.io.path.walk

data class PackPreview(
    val archive: String,
    val title: String,
    val optionalFiles: List<String>,
    val hash: String,
    val gameVersion: String = "",
    val loader: LoaderType = LoaderType.Vanilla,
    val loaderVersion: String = "",
    val version: String = "",
    val summary: String = "",
    val requiredFiles: Int = 0,
)

internal fun packDownload(
    file: JsonObject,
    target: Path,
    url: String,
): DownloadArtifact {
    val env = file.obj("env")
    for (side in listOf("client", "server")) {
        require(
            env.string(side, "required") in setOf("required", "optional", "unsupported"),
        ) {
            "整合包环境标记无效"
        }
    }
    val hashes = file.obj("hashes")
    val sha512 = hashes.string("sha512")
    val sha1 = hashes.string("sha1")
    require(sha512.matches(Regex("[a-fA-F0-9]{128}")) && sha1.matches(Regex("[a-fA-F0-9]{40}"))) {
        "整合包文件需要有效的 SHA-1 和 SHA-512：${file.string("path")}"
    }
    val sizeValue = file.get("fileSize")
    require(
        sizeValue != null &&
            sizeValue.isJsonPrimitive &&
            sizeValue.asJsonPrimitive.isNumber &&
            sizeValue.asString.matches(Regex("\\d+")),
    ) {
        "整合包文件大小无效：${file.string("path")}"
    }
    val size = sizeValue.asString.toLongOrNull()
    require(size != null && size >= 0) { "整合包文件大小无效" }
    return DownloadArtifact(validatePackUrl(url), target, sha512, size, "SHA-512")
}

internal fun packPath(
    root: Path,
    relative: String,
): Path {
    require(
        !relative
            .replace('\\', '/')
            .substringBefore('/')
            .lowercase()
            .startsWith(".molifox-"),
    ) {
        "整合包不能覆盖启动器维护文件"
    }
    return safePath(root, relative)
}

/** Stages all pack content before replacing any existing user file. */
internal class PackFileTransaction(
    private val store: LauncherStore,
    private val root: Path,
) {
    val directory =
        safePath(root, ".molifox-pack-${UUID.randomUUID()}").apply { createDirectories() }
    val content = directory.resolve("files").apply { createDirectories() }
    private var retain = false

    suspend fun commit(context: JobContext) {
        val backups = linkedMapOf<Path, Path>()
        val installed = mutableListOf<Path>()
        runCatching {
            installContent(context, backups, installed)
        }.onFailure { error ->
            // 安装中途失败（含协程取消）必须回滚已落盘文件，故此处兜底而不收窄具体类型。
            throw rollback(error, backups, installed)
        }
        discardBackups(backups)
    }

    /** 把暂存内容逐个移动到目标位置：已有文件先移到备份目录，移动完成才算安装成功。 */
    private suspend fun installContent(
        context: JobContext,
        backups: MutableMap<Path, Path>,
        installed: MutableList<Path>,
    ) {
        val files =
            Files.walk(content).use { paths ->
                paths
                    .filter { it.isRegularFile() && !Files.isSymbolicLink(it) }
                    .sorted()
                    .toList()
            }
        for (file in files) {
            context.checkCancelled()
            val target = packPath(root, content.relativize(file).joinToString("/"))
            if (target.exists()) {
                require(target.isRegularFile() && !Files.isSymbolicLink(target)) {
                    "整合包目标不是普通文件：$target"
                }
                val backup = safePath(directory, "backup/${backups.size}/${target.fileName}")
                backup.parent.createDirectories()
                Files.move(target, backup)
                backups[target] = backup
            }
            target.parent.createDirectories()
            Files.move(file, target)
            installed.add(target)
        }
    }

    /** 回滚已安装文件并还原备份；回滚本身失败时保留目录并返回带原始原因的异常。 */
    private fun rollback(
        error: Throwable,
        backups: Map<Path, Path>,
        installed: List<Path>,
    ): Throwable {
        val result =
            runCatching {
                installed.asReversed().forEach { Files.deleteIfExists(it) }
                for ((target, backup) in backups.entries.toList().asReversed()) {
                    Files.move(
                        backup,
                        target,
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                }
            }
        if (result.isFailure) {
            retain = true
            return IOException("整合包安装未完成，原文件备份保留在 $directory", error).also {
                result.exceptionOrNull()?.let(it::addSuppressed)
            }
        }
        return error
    }

    /** 安装成功后把备份移入回收站；移除失败时保留目录，避免用户旧文件被无声删除。 */
    private fun discardBackups(backups: Map<Path, Path>) {
        backups.values.forEach { backup ->
            runCatching { store.platform.moveToTrash(backup) }
                .onFailure {
                    retain = true
                    store.log("旧整合包文件保留在 $directory", true)
                }
        }
    }

    fun cleanup() {
        if (retain) return
        check(
            directory.parent.toAbsolutePath().normalize() == root.toAbsolutePath().normalize() &&
                directory.name.startsWith(".molifox-pack-") &&
                !Files.isSymbolicLink(directory),
        )
        Files.walk(directory).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }
}
