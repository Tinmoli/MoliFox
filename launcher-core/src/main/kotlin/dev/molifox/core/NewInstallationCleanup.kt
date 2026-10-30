// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 在新实例安装取消后回收其专属版本目录、整合包副本和实例记录。

package dev.molifox.core

import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import kotlin.io.path.Path
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.isSymbolicLink
import kotlin.io.path.walk

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
        context.onFinished(
            "new-installation:${instance.id}",
            cancel = {
                if (folder.exists(NOFOLLOW_LINKS)) {
                    require(context.canRemoveDownload(folder)) { "新版本目录已被其他实例引用，未删除" }
                    require(folder.toRealPath() == folder) { "新版本目录已变为链接，未删除" }
                    val paths = Files.walk(folder).use { it.toList() }
                    require(paths.all { !Files.isSymbolicLink(it) && it.toRealPath() == it }) {
                        "新版本目录包含链接，未删除"
                    }
                    paths.sortedByDescending { it.nameCount }.forEach(Files::deleteIfExists)
                }
                val current = store.data.instances.find { it.id == instance.id }
                if (current?.packFile?.isNotBlank() == true) {
                    val archive =
                        java.nio.file.Path
                            .of(current.packFile)
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
            },
            complete = {},
        )
    }
}
