// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责创建并回收测试专用临时目录。

package dev.molifox.core

import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import java.util.UUID
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.walk

/** 在系统临时目录下建一个测试目录，块结束后无论成败都递归删除。 */
internal fun <T> withTempDirectory(
    prefix: String,
    block: (Path) -> T,
): T {
    val directory = Files.createTempDirectory(prefix)
    try {
        return block(directory)
    } finally {
        removeTestTree(directory)
    }
}

/** 项目根目录：本机测试数据统一放在项目内 `.work/` 下，不允许落到 C 盘用户临时目录。 */
internal fun projectRoot(): Path {
    var root = Path.of(System.getProperty("user.dir")).toAbsolutePath()
    while (root.parent != null && !root.resolve("settings.gradle.kts").isRegularFile()) {
        root = root.parent
    }
    return root
}

/** 在项目 `.work/tests/<prefix>/<uuid>` 下建一个独立测试目录；调用方负责用 [removeTestTree] 清理。 */
internal fun workTestDirectory(prefix: String): Path {
    val directory =
        projectRoot()
            .resolve(
                ".work/tests",
            ).resolve(prefix)
            .resolve(UUID.randomUUID().toString())
    directory.createDirectories()
    return directory
}

/** 递归删除测试目录；读不出或已被占用时静默跳过，不掩盖测试本身的失败。 */
internal fun removeTestTree(root: Path) {
    if (!root.exists()) return
    runCatching {
        Files.walk(root).use { paths ->
            paths
                .sorted(
                    Comparator.reverseOrder(),
                ).forEach { runCatching { Files.deleteIfExists(it) } }
        }
    }
}
