// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责为每个游戏进程准备独立原生库和参数目录，并回收已退出进程的缓存。

package dev.molifox.core

import dev.molifox.core.i18n.I18n
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

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

    /** 每局游戏独占的系统临时目录，随该局运行目录一起回收。 */
    fun temporary(directory: Path): Path {
        require(directory.toAbsolutePath().normalize().parent == root)
        return directory.resolve("tmp").apply { createStorageDirectory(this) }
    }

    fun attach(
        directory: Path,
        instanceId: String,
        process: Process,
    ) {
        writeJson(
            directory.resolve("process.json"),
            mapOf(
                "instanceId" to instanceId,
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

    /**
     * 启动器重启后仍存活的游戏进程，按实例 id 汇总。游戏可以比启动器活得久，
     * 而「运行中」判定原本只在本会话内存里——重启后靠运行目录遗留的 process.json 恢复判断：
     * pid + 启动时间双重确认，避免 PID 复用误判；没有 instanceId 的旧记录跳过。
     */
    fun orphanRuns(): Map<String, ProcessHandle> {
        if (!root.isDirectory() || Files.isSymbolicLink(root)) return emptyMap()
        val found = mutableMapOf<String, ProcessHandle>()
        for (directory in root.listDirectoryEntries()) {
            runCatching {
                UUID.fromString(directory.name)
                val marker = directory.resolve("process.json")
                if (!marker.isRegularFile() || Files.isSymbolicLink(marker)) return@runCatching
                val data = readObject(marker)
                val instanceId = data.string("instanceId")
                if (instanceId.isBlank()) return@runCatching
                val process = ProcessHandle.of(data.get("pid")?.asLong ?: return@runCatching).orElse(null)
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
                if (alive && process != null) found[instanceId] = process
            }
        }
        return found
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
