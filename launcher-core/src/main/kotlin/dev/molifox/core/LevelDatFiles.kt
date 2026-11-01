// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责 level.dat 写回共用的滚动备份与原子替换。

package dev.molifox.core

import dev.molifox.core.i18n.I18n
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.UUID
import kotlin.io.path.createDirectories

/**
 * 原始 `level.dat` 的滚动备份：同一个世界只保留一份，覆盖写。
 *
 * 文件名带世界路径短哈希，避免不同实例的同名世界互相覆盖；备份失败直接抛出，
 * 调用方必须中止写入，不能在没有备份的情况下覆盖原文件。
 */
internal fun backupLevelDat(
    store: LauncherStore,
    world: Path,
    bytes: ByteArray,
) {
    val directory = store.dataDirectory.resolve("backups/level-dat")
    try {
        directory.createDirectories()
        Files.write(
            directory.resolve(backupLabel(world)),
            bytes,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE,
        )
    } catch (failure: IOException) {
        store.log(
            I18n.t("saveA1.levelDatFiles.backupLogFailed", failure.message ?: world.fileName),
            true,
        )
        throw IOException(
            I18n.t(
                "saveA1.levelDatFiles.backupFailed",
                failure.message ?: I18n.t("saveA1.levelDatFiles.backupDirectoryUnwritable"),
            ),
            failure,
        )
    }
}

/** `<世界名>-<路径哈希>.dat`；哈希只用于区分同名世界，不暴露完整路径。 */
private fun backupLabel(world: Path): String {
    val name = world.fileName.toString().replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]"), "_")
    val hash =
        world
            .toAbsolutePath()
            .normalize()
            .toString()
            .hashCode()
            .toUInt()
            .toString(16)
    return "$name-$hash.dat"
}

/**
 * 先把新内容写到同目录的临时文件，再确认原文件未被其他程序改动，最后原子替换。
 *
 * 原文件在写入期间被游戏或编辑器改过时放弃替换并抛出中文错误；临时文件总会清理。
 */
internal fun replaceLevelDat(
    level: Path,
    before: ByteArray,
    bytes: ByteArray,
) {
    val temporary = level.resolveSibling(".level-${UUID.randomUUID()}.part")
    try {
        Files.write(temporary, bytes, StandardOpenOption.CREATE_NEW)
        require(Files.readAllBytes(level).contentEquals(before)) {
            I18n.t("saveA1.levelDatFiles.changed")
        }
        replaceFile(temporary, level)
    } finally {
        Files.deleteIfExists(temporary)
    }
}
