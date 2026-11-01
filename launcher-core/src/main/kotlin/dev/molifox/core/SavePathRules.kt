// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责存档相关的路径解析：saves/、backups/ 目录、安全子路径与链接判定。

package dev.molifox.core

import dev.molifox.core.i18n.I18n
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
        I18n.t("diagA2.savePathRules.gameRunning")
    }
}

/** 解析 `saves/` 下的直接子项；只接受目录或 `.zip`，并拒绝越界与符号链接。 */
internal fun saveSourcePath(
    saves: Path,
    saveName: String,
): Path {
    require(saveName.isNotBlank() && saveName.length < MAX_SAVE_ID_LENGTH) {
        I18n.t("diagA2.savePathRules.invalidSaveName")
    }
    require('/' !in saveName && '\\' !in saveName && ':' !in saveName) {
        I18n.t("diagA2.savePathRules.invalidSaveNameDetail", saveName)
    }
    val path =
        runCatching {
            safePath(saves, saveName)
        }.getOrElse { error(I18n.t("diagA2.savePathRules.unsafeSaveName", saveName)) }
    require(path.parent?.toAbsolutePath()?.normalize() == absoluteSavePath(saves)) {
        I18n.t("diagA2.savePathRules.outsideSaves", saveName)
    }
    require(!isLinkPath(path)) { I18n.t("diagA2.savePathRules.linkedSave", saveName) }
    require(path.exists(LinkOption.NOFOLLOW_LINKS)) {
        I18n.t("diagA2.savePathRules.saveMissing", saveName)
    }
    require(
        path.isDirectory(LinkOption.NOFOLLOW_LINKS) ||
            (path.isRegularFile(LinkOption.NOFOLLOW_LINKS) && path.name.endsWith(".zip", true)),
    ) {
        I18n.t("diagA2.savePathRules.unrecognizedSave", saveName)
    }
    return path
}

/** 恢复目标路径：`saves/<名称>`，并保证它就是 `saves/` 的直接子项。 */
internal fun saveTargetPath(
    saves: Path,
    name: String,
): Path {
    require(isPlainSaveName(name)) { I18n.t("diagA2.savePathRules.invalidBackupSaveName", name) }
    val target =
        runCatching {
            safePath(saves, name)
        }.getOrElse { error(I18n.t("diagA2.savePathRules.unsafeBackupSaveName", name)) }
    require(target.parent.toAbsolutePath().normalize() == absoluteSavePath(saves)) {
        I18n.t("diagA2.savePathRules.backupSaveOutsideSaves", name)
    }
    return target
}

/** 由备份标识解析备份压缩包；标识就是去掉 `.zip` 的文件名，越界与符号链接一律拒绝。 */
internal fun resolveBackupArchive(
    directory: Path,
    backupId: String,
): Path {
    require(backupId.isNotBlank() && backupId.length < MAX_SAVE_ID_LENGTH) {
        I18n.t("diagA2.savePathRules.invalidBackupId")
    }
    require('/' !in backupId && '\\' !in backupId && ':' !in backupId) {
        I18n.t("diagA2.savePathRules.invalidBackupId")
    }
    val archive =
        runCatching {
            safePath(directory, "$backupId.zip")
        }.getOrElse { error(I18n.t("diagA2.savePathRules.invalidBackupIdDetail", backupId)) }
    require(archive.parent.toAbsolutePath().normalize() == absoluteSavePath(directory)) {
        I18n.t("diagA2.savePathRules.invalidBackupIdDetail", backupId)
    }
    require(archive.isRegularFile(LinkOption.NOFOLLOW_LINKS) && !isLinkPath(archive)) {
        I18n.t("diagA2.savePathRules.backupMissing", backupId)
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
        I18n.t("diagA2.savePathRules.notOrdinaryDirectory", path)
    }
}

internal fun absoluteSavePath(path: Path): Path = path.toAbsolutePath().normalize()
