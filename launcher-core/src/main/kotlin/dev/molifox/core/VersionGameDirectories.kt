// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 管理 versions 下的具名游戏目录与必要的版本配置。

package dev.molifox.core

import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import kotlin.io.path.exists

internal class VersionGameDirectories(
    private val store: LauncherStore,
) {
    fun availableName(
        root: Path,
        preferred: String,
    ): String {
        val base =
            preferred
                .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "-")
                .trim()
                .trimEnd('.')
                .take(70)
                .ifBlank { "Minecraft" }
        var candidate = base
        var index = 2
        while (
            root.resolve("versions").resolve(candidate).exists(NOFOLLOW_LINKS) ||
            store.data.instances.any {
                taskPathKey(store.minecraftRoot(it)) == taskPathKey(root) &&
                    it.gameDirectoryName
                        .ifBlank { it.versionId }
                        .equals(candidate, store.platform.osName == "windows")
            }
        ) {
            candidate = "$base-${index++}"
        }
        safeFilename(candidate)
        return candidate
    }

    fun writeProfile(instance: GameInstance) {
        val folder = store.versionGamePath(instance)
        if (folder.fileName.toString() == instance.versionId) return
        // 官方基础配置不能反向继承依赖它的加载器配置，否则会产生循环。
        if (folder.fileName.toString() == instance.gameVersion) return
        val file = folder.resolve("${folder.fileName}.json")
        val profile =
            mapOf(
                "id" to folder.fileName.toString(),
                "inheritsFrom" to instance.versionId,
                "_minecraftVersion" to instance.gameVersion,
            )
        writeJson(file, profile)
    }
}
