// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 直接 `java -jar MoliFox.jar` 时，macOS 的 AWT/Skiko 要求 JVM 以 -XstartOnFirstThread 启动。
// 原生外壳会带参数，但 macOS 只发 JAR，因此由启动器自己检测并重启一次。

package dev.molifox.bootstrap

import java.nio.file.Path

internal const val MAC_FIRST_THREAD_ARGUMENT = "-XstartOnFirstThread"

/**
 * 返回 macOS 需要的重启命令；不需要重启时返回 null。
 *
 * [codeSource] 是当前 JAR 或开发期类目录；JAR 模式用 `-jar`，类目录模式沿用 classpath。
 */
internal fun macRelaunchCommand(
    osName: String,
    jvmArguments: List<String>,
    javaExecutable: Path,
    codeSource: Path,
    classPath: String,
    dataDirectory: Path,
): List<String>? {
    if (!osName.startsWith("Mac", ignoreCase = true)) return null
    if (MAC_FIRST_THREAD_ARGUMENT in jvmArguments) return null
    val launch =
        if (codeSource.fileName.toString().endsWith(".jar", ignoreCase = true)) {
            listOf("-jar", codeSource.toString())
        } else {
            listOf("-cp", classPath.ifBlank { codeSource.toString() }, "dev.molifox.bootstrap.BootstrapMainKt")
        }
    return listOf(
        javaExecutable.toString(),
        MAC_FIRST_THREAD_ARGUMENT,
        "-Xdock:name=MoliFox",
        "-Dfile.encoding=UTF-8",
        "-Dmolifox.data.dir=$dataDirectory",
    ) + launch
}
