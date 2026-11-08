// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责启动器附近已有游戏目录的只读发现。

package dev.molifox.core

import java.nio.file.Files
import java.nio.file.Path

/** 启动时发现的一个游戏根：真实路径，以及 `versions/<id>/<id>.json` 的数量。 */
data class StartupRoot(
    val path: Path,
    val versions: Int,
)

/**
 * 按启动器自身位置推断现有 Minecraft 游戏根目录。只读探测：不创建、移动或写入任何游戏文件， 也不会因为扫描而在用户的 `.minecraft` 里留下任何东西。
 *
 * 支持三种摆放（可以同时命中，按下面的顺序去重）：
 * 1. 启动器目录本身就是游戏根（含 `versions/`、`libraries/`、`assets/`）；
 * 2. 启动器目录就是 `.minecraft`，或位于 `.minecraft` 内（向上最多两层，按目录名或完整游戏根判断）；
 * 3. 启动器目录旁边有 `.minecraft/`，允许它只有 `versions/`（残缺或便携副本）。
 *
 * 只有确实包含至少一个 `versions/<id>/<id>.json` 的目录才会返回；路径一律取真实路径， 这样注册记录和后续比较都不受符号链接、大小写或 `..` 影响。
 */
object StartupGameRoots {
    fun resolve(launcherDirectory: Path): List<StartupRoot> {
        val start = launcherDirectory.toAbsolutePath().normalize()
        val candidates = linkedSetOf<Path>()
        // 1. 启动器目录本身：就是 .minecraft，或是 versions/libraries/assets 齐全的游戏根。
        if (isMinecraftDirectory(start) || isFullGameRoot(start)) candidates.add(start)
        // 2. 启动器旁边的 .minecraft：即使缺少 libraries/assets，只要有版本清单也算。
        candidates.add(start.resolve(".minecraft"))
        // 3. 启动器位于 .minecraft 之内：检查上一级与上两级，只接受像游戏根的那一层。
        start.parent?.takeIf { isGameRootCandidate(it) }?.let { candidates.add(it) }
        start.parent
            ?.parent
            ?.takeIf { isGameRootCandidate(it) }
            ?.let { candidates.add(it) }
        val found = linkedMapOf<Path, Int>()
        for (candidate in candidates) {
            val real = realDirectory(candidate) ?: continue
            val versions = versionProfileCount(real)
            if (versions > 0) found[real] = versions
        }
        return found.map { (path, versions) -> StartupRoot(path, versions) }
    }

    private fun isMinecraftDirectory(path: Path): Boolean =
        path.fileName?.toString().equals(".minecraft", ignoreCase = true)

    /** 完整游戏根：versions/libraries/assets 三者齐全，用于识别被改了名字的游戏目录。 */
    private fun isFullGameRoot(path: Path): Boolean =
        runCatching {
            Files.isDirectory(path.resolve("versions")) &&
                Files.isDirectory(path.resolve("libraries")) &&
                Files.isDirectory(path.resolve("assets"))
        }.getOrDefault(false)

    private fun isGameRootCandidate(path: Path): Boolean = isMinecraftDirectory(path) || isFullGameRoot(path)

    private fun realDirectory(path: Path): Path? =
        runCatching {
            path.toRealPath()
        }.getOrNull()
            ?.takeIf { Files.isDirectory(it) }

    /** 数出 `versions/<id>/<id>.json` 的个数；读取失败按 0 处理，不抛异常。 */
    private fun versionProfileCount(root: Path): Int {
        val versions = root.resolve("versions")
        if (!Files.isDirectory(versions)) return 0
        return runCatching {
            Files.newDirectoryStream(versions).use { entries ->
                entries.count { folder ->
                    Files.isDirectory(folder) &&
                        Files.isRegularFile(
                            folder.resolve(folder.fileName.toString() + ".json"),
                        )
                }
            }
        }.getOrDefault(0)
    }
}
