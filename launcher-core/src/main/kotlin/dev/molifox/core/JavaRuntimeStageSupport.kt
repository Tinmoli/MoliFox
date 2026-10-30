// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责 Java 运行时安装的目录准备与校验辅助：从 JavaRuntimeInstaller 拆出，保持原行为。

package dev.molifox.core

import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile

/** 可执行文件搜索深度：Zulu/Temurin 解压后最多套几层目录。 */
internal const val EXECUTABLE_SEARCH_DEPTH = 4

/** 版本与架构必须与计划一致，否则不登记为已安装。 */
internal fun verifyStagedRuntime(
    plan: RuntimeInstallPlan,
    found: JavaRuntime,
    platform: PlatformServices,
) {
    require(found.majorVersion == plan.major) {
        "Java 版本校验失败：期望 ${plan.major}，实际 ${found.majorVersion}"
    }
    require(
        normalizedJavaArchitecture(found.architecture) ==
            normalizedJavaArchitecture(platform.architecture),
    ) { "Java 架构校验失败：${found.architecture}" }
}

/** 计算最终安装目录，并在存在旧目录时先做安全校验再删除。 */
internal fun prepareDestination(
    plan: RuntimeInstallPlan,
    removeExisting: (Path) -> Unit,
): Path {
    val destination =
        plan.runtimes
            .resolve(plan.directoryName)
            .toAbsolutePath()
            .normalize()
    require(destination.parent == plan.runtimes.toAbsolutePath().normalize()) {
        "Java 安装目录异常"
    }
    if (destination.exists()) {
        require(!Files.isSymbolicLink(destination)) { "Java 安装目录是符号链接，已拒绝替换" }
        removeExisting(destination)
    }
    return destination
}

/** 递归删除临时目录树；符号链接按文件删除，不跟随。 */
internal fun deleteTree(root: Path) {
    if (!root.exists()) return
    Files.walk(root).use { stream ->
        stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
    }
}

/** 目录内普通文件的总字节数；不可读项按 0 计。 */
internal fun directorySize(directory: Path): Long =
    runCatching {
        Files.walk(directory).use { stream ->
            stream
                .filter { Files.isRegularFile(it) }
                .mapToLong { runCatching { it.fileSize() }.getOrDefault(0) }
                .sum()
        }
    }.getOrDefault(0)

/** 在解压目录里找 Java 可执行文件；只认 `bin/` 下的同名文件。 */
internal fun findExecutable(
    root: Path,
    javaExecutable: String,
): Path? =
    runCatching {
        Files.walk(root, EXECUTABLE_SEARCH_DEPTH).use { stream ->
            stream
                .filter {
                    it.isRegularFile() &&
                        it.fileName.toString() == javaExecutable &&
                        it.parent?.fileName?.toString() == "bin"
                }.findFirst()
                .orElse(null)
        }
    }.getOrNull()

/** 解压目录下直接是 bin/ 时取该目录，否则用原目录。 */
internal fun resolveInstallRoot(
    root: Path,
    javaExecutable: String,
): Path =
    Files
        .list(root)
        .use { it.toList() }
        .singleOrNull()
        ?.takeIf { it.isDirectory() && findExecutable(it, javaExecutable) != null }
        ?: root
