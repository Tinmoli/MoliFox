// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责存档相关的路径解析：saves/、backups/ 目录、安全子路径与链接判定。

package dev.molifox.core

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

/** 原版备份目录名：`<游戏目录>/backups/`，与原版游戏共用同一个目录。 */
private const val SAVE_BACKUP_DIRECTORY = "backups"

/** 存档名与备份标识的长度上限；超长按非法输入拒绝。 */
private const val MAX_SAVE_ID_LENGTH = 200

internal fun savesDirectoryOf(
    store: LauncherStore,
    instanceId: String,
): Path = store.instancePath(instanceId).resolve("saves")

internal fun backupDirectoryOf(
    store: LauncherStore,
    instanceId: String,
): Path = store.instancePath(instanceId).resolve(SAVE_BACKUP_DIRECTORY)

/** 存档目录必须是真实目录：不能是文件、符号链接或目录联接。 */
internal fun requireSaveDirectory(
    saves: Path,
    message: String,
) {
    require(saves.isDirectory(LinkOption.NOFOLLOW_LINKS) && !isLinkPath(saves)) { message }
}

/** 游戏正在使用这些实例的存档目录时拒绝写盘。 */
internal fun requireSaveGameStopped(
    store: LauncherStore,
    vararg ids: String,
) {
    val directories = ids.map { taskPathKey(store.instancePath(it)) }.toSet()
    require(
        store.state.value.runningGames.none {
            taskPathKey(store.instancePath(it.instanceId)) in directories
        },
    ) {
        "游戏正在使用此存档目录，请先退出游戏"
    }
}

/** 解析 `saves/` 下的直接子项；只接受目录或 `.zip`，并拒绝越界与符号链接。 */
internal fun saveSourcePath(
    saves: Path,
    saveName: String,
): Path {
    require(saveName.isNotBlank() && saveName.length < MAX_SAVE_ID_LENGTH) { "存档名称无效" }
    require('/' !in saveName && '\\' !in saveName && ':' !in saveName) { "存档名称无效：$saveName" }
    val path =
        runCatching {
            safePath(saves, saveName)
        }.getOrElse { error("存档名称不安全：$saveName") }
    require(path.parent?.toAbsolutePath()?.normalize() == absoluteSavePath(saves)) {
        "只能操作 saves 目录下的存档：$saveName"
    }
    require(!isLinkPath(path)) { "存档是符号链接或目录联接，未处理：$saveName" }
    require(path.exists(LinkOption.NOFOLLOW_LINKS)) { "找不到存档：$saveName" }
    require(
        path.isDirectory(LinkOption.NOFOLLOW_LINKS) ||
            (path.isRegularFile(LinkOption.NOFOLLOW_LINKS) && path.name.endsWith(".zip", true)),
    ) {
        "不是可识别的存档：$saveName"
    }
    return path
}

/** 恢复目标路径：`saves/<名称>`，并保证它就是 `saves/` 的直接子项。 */
internal fun saveTargetPath(
    saves: Path,
    name: String,
): Path {
    require(isPlainSaveName(name)) { "备份里的存档名称无效：$name" }
    val target =
        runCatching {
            safePath(saves, name)
        }.getOrElse { error("备份里的存档名称不安全：$name") }
    require(target.parent.toAbsolutePath().normalize() == absoluteSavePath(saves)) {
        "备份里的存档名称超出 saves 目录：$name"
    }
    return target
}

/** 由备份标识解析备份压缩包；标识就是去掉 `.zip` 的文件名，越界与符号链接一律拒绝。 */
internal fun resolveBackupArchive(
    directory: Path,
    backupId: String,
): Path {
    require(backupId.isNotBlank() && backupId.length < MAX_SAVE_ID_LENGTH) { "备份标识无效" }
    require('/' !in backupId && '\\' !in backupId && ':' !in backupId) { "备份标识无效" }
    val archive =
        runCatching {
            safePath(directory, "$backupId.zip")
        }.getOrElse { error("备份标识无效：$backupId") }
    require(archive.parent.toAbsolutePath().normalize() == absoluteSavePath(directory)) {
        "备份标识无效：$backupId"
    }
    require(archive.isRegularFile(LinkOption.NOFOLLOW_LINKS) && !isLinkPath(archive)) {
        "找不到备份文件：$backupId"
    }
    return archive
}

/**
 * 符号链接与 Windows 目录联接（junction）都算链接。
 *
 * Java 把 junction 的 `isSymbolicLink` 报成 false、`isOther` 报成 true，因此只看 `isSymbolicLink`
 * 会漏掉联接，`Files.walk` 甚至会递归进联接指向的目录。
 */
internal fun isLinkPath(path: Path): Boolean =
    runCatching {
        Files.readAttributes(
            path,
            BasicFileAttributes::class.java,
            LinkOption.NOFOLLOW_LINKS,
        )
    }.map { it.isSymbolicLink || it.isOther }
        .getOrDefault(false)

/** 备份目录必须是普通目录：不能是文件、符号链接或目录联接。 */
internal fun requireOrdinaryDirectory(path: Path) {
    val attributes =
        Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
    require(attributes.isDirectory && !attributes.isSymbolicLink && !attributes.isOther) {
        "备份路径不是普通目录：$path"
    }
}

internal fun absoluteSavePath(path: Path): Path = path.toAbsolutePath().normalize()
