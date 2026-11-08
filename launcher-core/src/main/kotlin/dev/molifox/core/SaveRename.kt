// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

package dev.molifox.core

import dev.molifox.core.i18n.I18n
import java.nio.file.Files
import java.nio.file.LinkOption
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile

/** 同一 saves 目录内重命名，不跟随链接、不改变世界内容、不覆盖目标。 */
internal fun renameStoredSave(
    store: LauncherStore,
    instanceId: String,
    saveName: String,
    requestedName: String,
): String {
    val instance = store.instance(instanceId)
    requireSaveGameStopped(store, instanceId)
    val saves = savesDirectoryOf(store, instanceId)
    requireSaveDirectory(saves, I18n.t("core.saveManager.1"))
    val source = saveSourcePath(saves, saveName)
    val isZip = source.isRegularFile(LinkOption.NOFOLLOW_LINKS)
    val base = requestedName.trim().removeSuffixIgnoreCase(".zip")
    val targetName = if (isZip) "$base.zip" else base
    require(isPlainSaveName(targetName)) { I18n.t("diagA2.savePathRules.invalidSaveName") }
    if (targetName == saveName) return saveName
    val target = saveTargetPath(saves, targetName)
    require(!target.exists(LinkOption.NOFOLLOW_LINKS)) { I18n.t("core.saveManager.7", targetName) }
    Files.move(source, target)
    store.log(I18n.t("saveA2.manager.logSaveRenamed", instance.name, saveName, targetName))
    return targetName
}
