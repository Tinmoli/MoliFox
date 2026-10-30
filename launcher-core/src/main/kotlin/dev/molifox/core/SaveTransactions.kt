// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责用备份替换世界的回滚边界，以及跨实例复制/导入的落点规划。

package dev.molifox.core

import java.io.IOException
import java.nio.file.LinkOption
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

/** 替换时保留的旧世界后缀：`<世界名>（替换前 HH-mm）`，同样避开冒号。 */
private val replaceSuffixFormat =
    DateTimeFormatter.ofPattern("HH-mm").withZone(ZoneId.systemDefault())

/**
 * 存档写入的回滚边界：中途任何失败都先执行 [cleanup]，再抛出原错误。
 *
 * 解压、校验与改名都可能失败，失败时临时目录必须删掉、已改名的旧世界必须放回，因此这里只能兜底
 * `Throwable`（含 `IllegalArgumentException` 与取消）。
 */
internal inline fun <T> withSaveCleanup(
    cleanup: (Throwable) -> Unit,
    block: () -> T,
): T =
    runCatching {
        block()
    }.getOrElse { error ->
        // 回滚边界：解压、校验、改名都可能失败，任何失败都必须先清理临时目录再抛出，只能兜底 Throwable。
        cleanup(error)
        throw error
    }

/** 用 [archive] 的内容替换 `saves/<name>`，返回改名保留的旧世界目录。 */
internal suspend fun replaceSaveWorld(
    saves: Path,
    name: String,
    archive: Path,
    context: JobContext?,
    backupArchiveName: String,
): Path {
    val target = saveTargetPath(saves, name)
    val staging = saveStagingPath(saves)
    var kept: Path? = null
    return withSaveCleanup({ error ->
        rollbackReplaceWorld(saves, name, target, staging, kept, backupArchiveName, error)
    }) {
        extractArchive(archive, staging, context, skipSessionLock = true)
        requireReplaceableWorld(staging)
        // 当前世界改名保留，名字带「替换前 HH-mm」，不删除。
        val keptName =
            uniqueSaveName(
                saves,
                "$name（替换前 ${replaceSuffixFormat.format(Instant.now())}）",
                isZip = false,
            )
        val keptPath = saveTargetPath(saves, keptName)
        commitSavePath(target, keptPath)
        kept = keptPath
        // 临时目录改名就位。
        commitSavePath(staging, target)
        keptPath
    }
}

/**
 * 替换失败后的回滚：删掉临时目录，把已改名的旧世界放回原位。
 *
 * 回滚自身失败时，把全部失败记为 suppressed，换成一个带中文说明的 [IOException] 抛出。
 */
private fun rollbackReplaceWorld(
    saves: Path,
    name: String,
    target: Path,
    staging: Path,
    kept: Path?,
    backupArchiveName: String,
    error: Throwable,
): Nothing {
    val failures = mutableListOf<Throwable>()
    runCatching { deleteSaveTree(staging) }.onFailure { failures += it }
    val previousWorld = kept
    if (previousWorld != null) {
        runCatching {
            if (target.exists(LinkOption.NOFOLLOW_LINKS)) deleteSaveTree(target)
            commitSavePath(previousWorld, target)
        }.onFailure { failure ->
            failures += failure
            throwReplaceFailure(
                "替换存档失败，且旧世界没有放回原位：请手动把 ${previousWorld.fileName} 改回 $name，" +
                    "备份在 $backupArchiveName",
                error,
                failures,
            )
        }
    }
    if (failures.isEmpty()) throw error
    throwReplaceFailure("替换存档失败，清理未完成：请检查 $saves", error, failures)
}

private fun throwReplaceFailure(
    message: String,
    error: Throwable,
    failures: List<Throwable>,
): Nothing {
    val failure = IOException(message, error)
    failures.forEach { failure.addSuppressed(it) }
    throw failure
}

/** 跨实例复制的前置校验：两个实例不能共用同一个存档目录。 */
internal fun requireDistinctSaveDirectories(
    store: LauncherStore,
    source: GameInstance,
    destination: GameInstance,
) {
    require(
        taskPathKey(store.gamePath(source)) != taskPathKey(store.gamePath(destination)),
    ) {
        "源和目标使用同一个存档目录，无需复制"
    }
}

/** 一次跨实例复制的落点：源路径、是否整包、目标目录与最终名称。 */
internal data class SaveCopyPlan(
    val sourceTree: Path,
    val isZip: Boolean,
    val targetDirectory: Path,
    val target: Path,
    val finalName: String,
)

/** 校验源存档并选定目标路径；目标已有同名存档时加序号，从不覆盖。 */
internal fun prepareSaveCopy(
    store: LauncherStore,
    sourceInstanceId: String,
    targetInstanceId: String,
    saveName: String,
    rename: String?,
): SaveCopyPlan {
    val sourceSaves = savesDirectoryOf(store, sourceInstanceId)
    requireSaveDirectory(sourceSaves, "源实例的存档目录不存在，无法复制")
    val sourcePath = saveSourcePath(sourceSaves, saveName)
    val targetSaves = savesDirectoryOf(store, targetInstanceId)
    require(!isLinkPath(targetSaves)) { "目标实例的存档目录是符号链接或目录联接，无法复制" }
    targetSaves.createDirectories()
    val isZip = !sourcePath.isDirectory(LinkOption.NOFOLLOW_LINKS)
    // 未指定新名称时沿用存档名：目录名，或去掉 `.zip` 的文件名（`uniqueSaveName` 会补回后缀）。
    val defaultName = sourcePath.name.removeSuffixIgnoreCase(".zip")
    val requested = rename?.trim().orEmpty().ifBlank { defaultName }
    require(requested.length <= MAX_SAVE_NAME_LENGTH) { "存档名称需要 1–80 个字符" }
    val base = if (isZip) requested.removeSuffixIgnoreCase(".zip") else requested
    val finalName = uniqueSaveName(targetSaves, base, isZip)
    val target =
        safePath(targetSaves, finalName).also {
            require(it.parent.toAbsolutePath().normalize() == absoluteSavePath(targetSaves)) {
                "存档名称无效：$finalName"
            }
        }
    require(!target.exists(LinkOption.NOFOLLOW_LINKS)) { "目标存档已存在：$finalName" }
    return SaveCopyPlan(sourcePath, isZip, targetSaves, target, finalName)
}

/** 一次导入的来源：规范化后的路径、文件夹或 `.zip`，以及要剥掉的包装目录前缀与目标名称。 */
internal data class SaveImportPlan(
    val source: Path,
    val isDirectory: Boolean,
    val prefix: String,
    val base: String,
)

/** 校验要导入的外部存档：只接受带 `level.dat` 的 `.zip` 或世界文件夹。 */
internal fun prepareSaveImport(source: Path): SaveImportPlan {
    val incoming = source.toAbsolutePath().normalize()
    require(incoming.exists(LinkOption.NOFOLLOW_LINKS) && !isLinkPath(incoming)) {
        "找不到要导入的存档：${source.fileName}"
    }
    val isDirectory = incoming.isDirectory(LinkOption.NOFOLLOW_LINKS)
    if (isDirectory) {
        val level = incoming.resolve("level.dat")
        require(level.isRegularFile(LinkOption.NOFOLLOW_LINKS) && !isLinkPath(level)) {
            "文件夹里没有 level.dat，不是有效的世界：${incoming.fileName}"
        }
        return SaveImportPlan(incoming, true, "", safeSaveLabel(incoming.name))
    }
    require(
        incoming.isRegularFile(LinkOption.NOFOLLOW_LINKS) &&
            incoming.name.endsWith(".zip", true),
    ) {
        "存档只能导入 .zip 或世界文件夹：${incoming.fileName}"
    }
    return SaveImportPlan(
        incoming,
        false,
        SaveArchive.inspect(incoming).prefix,
        safeSaveLabel(incoming.name.removeSuffixIgnoreCase(".zip")),
    )
}
