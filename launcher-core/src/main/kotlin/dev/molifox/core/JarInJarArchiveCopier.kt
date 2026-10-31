// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责把内嵌条目有界地复制到临时文件：按实际字节扣预算、可取消，并统计条目数防压缩炸弹。

package dev.molifox.core

import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import kotlin.io.path.createDirectories

private const val TEMPORARY_DIRECTORY_NAME = "jar-in-jar"
private const val TEMPORARY_FILE_PREFIX = "embedded-"
private const val TEMPORARY_FILE_SUFFIX = ".jar"
private const val COPY_BUFFER_BYTES = 64 * 1024

/** 临时目录只放本次扫描的中间文件，调用方负责在解析后删除。 */
internal class JarInJarArchiveCopier(
    temporaryDirectory: Path,
    private val limits: JarInJarLimits,
) {
    private val temporaryRoot: Path = temporaryDirectory.resolve(TEMPORARY_DIRECTORY_NAME)

    /** 创建失败让 [java.io.IOException] 冒泡到扫描器，由那里统一标记为不完整。 */
    fun createTemporaryFile(): Path {
        temporaryRoot.createDirectories()
        return Files.createTempFile(temporaryRoot, TEMPORARY_FILE_PREFIX, TEMPORARY_FILE_SUFFIX)
    }

    fun copyToTemporary(
        parent: ZipFile,
        entry: ZipEntry,
        target: Path,
        isCancelled: () -> Boolean,
        budget: JarInJarScanBudget,
    ): Boolean {
        val source = parent.getInputStream(entry)
        return source.use { input -> copyStream(input, target, isCancelled, budget) }
    }

    /** 只数到上限就停，避免为统计条目数完整遍历恶意压缩包。 */
    fun countEntries(
        zip: ZipFile,
        stopAt: Int,
    ): Int {
        var count = 0
        val entries = zip.entries()
        while (entries.hasMoreElements()) {
            entries.nextElement()
            count++
            if (count >= stopAt) break
        }
        return count
    }

    private fun copyStream(
        input: InputStream,
        target: Path,
        isCancelled: () -> Boolean,
        budget: JarInJarScanBudget,
    ): Boolean {
        val buffer = ByteArray(COPY_BUFFER_BYTES)
        var complete = true
        Files.newOutputStream(target).use { output ->
            while (complete) {
                val count = input.read(buffer)
                if (count < 0) break
                complete = copyChunk(buffer, count, output, isCancelled, budget)
            }
        }
        return complete
    }

    private fun copyChunk(
        buffer: ByteArray,
        count: Int,
        output: OutputStream,
        isCancelled: () -> Boolean,
        budget: JarInJarScanBudget,
    ): Boolean {
        if (isCancelled()) {
            budget.hostFailure("扫描已取消")
            return false
        }
        // 按实际读出的字节扣预算：压缩包声明的 size 可能被伪造。
        if (!budget.addBytes(count.toLong())) {
            budget.hostFailure("内嵌 JAR 解压体积超过限制")
            return false
        }
        output.write(buffer, 0, count)
        return true
    }
}
