// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责为每个游戏进程准备独立原生库和参数目录，并回收已退出进程的缓存。

package dev.molifox.core

import dev.molifox.core.i18n.I18n
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.isSymbolicLink
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.walk

internal class GameRuntimeDirectories(
    private val store: LauncherStore,
) {
    private val root =
        store.temporaryDirectory
            .resolve("games")
            .toAbsolutePath()
            .normalize()

    fun create(id: String): Path {
        UUID.fromString(id)
        createStorageDirectory(root)
        return root.resolve(id).apply { createStorageDirectory(this) }
    }

    fun attach(
        directory: Path,
        process: Process,
    ) {
        writeJson(
            directory.resolve("process.json"),
            mapOf(
                "pid" to process.pid(),
                "startedAt" to
                    process
                        .info()
                        .startInstant()
                        .map { it.toString() }
                        .orElse(""),
            ),
        )
    }

    fun release(directory: Path) {
        require(directory.toAbsolutePath().normalize().parent == root)
        UUID.fromString(directory.fileName.toString())
        if (!directory.exists()) return
        require(
            !Files.isSymbolicLink(directory) && directory.toRealPath().parent == root.toRealPath(),
        )
        Files.walk(directory).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }

    /** 无法确认归属或进程状态的缓存保留；只处理有有效进程记录且已退出的会话。 */
    fun prune() {
        if (!root.isDirectory() || Files.isSymbolicLink(root)) return
        for (directory in root.listDirectoryEntries()) {
            runCatching {
                UUID.fromString(directory.name)
                val marker = directory.resolve("process.json")
                if (!marker.isRegularFile() || Files.isSymbolicLink(marker)) return@runCatching
                val data = readObject(marker)
                val pid = data.get("pid")?.asLong ?: return@runCatching
                val process = ProcessHandle.of(pid).orElse(null)
                val alive =
                    process?.isAlive == true &&
                        (
                            data.string("startedAt").isBlank() ||
                                process
                                    .info()
                                    .startInstant()
                                    .map { it.toString() }
                                    .orElse("") ==
                                data.string("startedAt")
                        )
                if (!alive) release(directory)
            }.onFailure {
                store.log(I18n.t("instA1.runtimeDirectories.keepUnconfirmed", directory.fileName))
            }
        }
    }
}
