// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 在新实例安装失败或取消后回收其专属版本目录、整合包副本和实例记录。

package dev.molifox.core

import dev.molifox.core.i18n.I18n
import java.nio.file.DirectoryNotEmptyException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import kotlin.io.path.exists

internal class NewInstallationCleanup(
    private val store: LauncherStore,
) {
    fun register(
        instance: GameInstance,
        context: JobContext,
    ) {
        val root = store.minecraftRoot(instance).toAbsolutePath().normalize()
        val folder = store.versionGamePath(instance).toAbsolutePath().normalize()
        require(folder.parent == root.resolve("versions"))
        context.reservedInstallDirectory = folder
        val discard: suspend () -> Unit = {
            try {
                if (context.freshInstallDirectory == folder) {
                    removeVersionFolder(context, folder, instance.id)
                } else {
                    removeEmptyReservation(folder)
                }
            } finally {
                try {
                    removePackArchive(instance)
                } finally {
                    store.endPendingInstall(instance.instanceFolderName())
                    removeInstanceRecord(instance)
                }
            }
        }
        context.onFinished(
            cleanupKey(instance.id),
            cancel = discard,
            complete = {},
            failure = discard,
        )
    }

    fun disarm(
        instance: GameInstance,
        context: JobContext,
    ) {
        context.disarmCleanup(cleanupKey(instance.id))
    }

    private fun cleanupKey(instanceId: String): String = "new-installation:$instanceId"

    private fun removeEmptyReservation(folder: Path) {
        if (!Files.isDirectory(folder, NOFOLLOW_LINKS) || folder.toRealPath() != folder) return
        try {
            Files.deleteIfExists(folder)
        } catch (_: DirectoryNotEmptyException) {
            // 安装未取得写入权时，保留其他来源写入的文件。
        }
    }

    /** 删除专属版本目录；目录本身或其中任一条目变成链接时拒绝删除。版本升级回滚复用同一套守卫。 */
    internal fun removeVersionFolder(
        context: JobContext,
        folder: Path,
        ownedInstanceId: String? = null,
    ) {
        if (!folder.exists(NOFOLLOW_LINKS)) return
        require(context.canRemoveDownload(folder, ownedInstanceId)) {
            I18n.t("diagA2.newInstallationCleanup.versionReferenced")
        }
        require(folder.toRealPath() == folder) {
            I18n.t("diagA2.newInstallationCleanup.versionLinked")
        }
        val paths = Files.walk(folder).use { it.toList() }
        require(paths.all { !Files.isSymbolicLink(it) && it.toRealPath() == it }) {
            I18n.t("diagA2.newInstallationCleanup.versionContainsLink")
        }
        paths.sortedByDescending { it.nameCount }.forEach(Files::deleteIfExists)
    }

    /** 删除该实例的整合包副本；文件必须仍在 packArchives 目录内并以实例 id 命名。 */
    private fun removePackArchive(instance: GameInstance) {
        val packFile =
            store.data.instances
                .find { it.id == instance.id }
                ?.packFile
        if (packFile.isNullOrBlank()) return
        val archive =
            java.nio.file.Path
                .of(packFile)
                .toAbsolutePath()
                .normalize()
        require(
            archive.parent ==
                store.directories.packArchives
                    .toAbsolutePath()
                    .normalize(),
        )
        require(archive.fileName.toString().substringBefore('.') == instance.id)
        Files.deleteIfExists(archive)
    }

    /** 移除实例记录；被移除的是当前选中实例时改选同一游戏目录下的其它实例。版本升级回滚复用。 */
    internal fun removeInstanceRecord(instance: GameInstance) {
        store.mutate { data ->
            val remaining = data.instances.filter { it.id != instance.id }
            data.copy(
                instances = remaining,
                selectedInstanceId =
                    if (data.selectedInstanceId == instance.id) {
                        remaining
                            .firstOrNull {
                                it.gameDirectoryId == data.activeGameDirectoryId
                            }?.id
                    } else {
                        data.selectedInstanceId
                    },
            )
        }
    }
}
