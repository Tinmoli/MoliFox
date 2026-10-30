// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责创建测试用目录链接，在 Windows 普通账户下使用目录联接。

package dev.molifox.core

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** 实际建立链接后才返回；不修改系统权限，不把链接创建失败当成测试通过。 */
internal fun createTestDirectoryLink(
    link: Path,
    target: Path,
) {
    val symbolicFailure = runCatching { Files.createSymbolicLink(link, target) }.exceptionOrNull()
    if (symbolicFailure == null) return
    if (!System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
        throw IOException("无法建立测试目录符号链接：$link", symbolicFailure)
    }
    createJunction(link, target, symbolicFailure)
}

/** 建立 Windows 目录联接并核对结果；失败原因与超时沿用原提示。 */
private fun createJunction(
    link: Path,
    target: Path,
    symbolicFailure: Throwable,
) {
    val process =
        ProcessBuilder("cmd", "/c", "mklink", "/J", link.toString(), target.toString())
            .redirectErrorStream(true)
            .start()
    if (!process.waitFor(10, TimeUnit.SECONDS)) {
        process.destroyForcibly()
        throw IOException("创建测试目录联接超时：$link", symbolicFailure)
    }
    val result = process.inputStream.bufferedReader().use { it.readText() }
    if (process.exitValue() != 0 || link.toRealPath() != target.toRealPath()) {
        throw IOException("无法建立测试目录联接：$link；$result", symbolicFailure)
    }
}
