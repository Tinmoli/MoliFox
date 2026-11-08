// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 按需遍历目录统计普通文件大小，不保留扫描结果；支持调用方中断遍历。

package dev.molifox.core.platform.files

import java.io.IOException
import java.io.UncheckedIOException
import java.nio.file.Files
import java.nio.file.Path

fun directoryByteSize(
    path: Path,
    checkActive: () -> Unit,
): Result<Long?> =
    try {
        val bytes =
            if (!Files.isDirectory(path)) {
                null
            } else {
                Files.walk(path).use { files ->
                    files
                        .filter {
                            checkActive()
                            Files.isRegularFile(it)
                        }.mapToLong { file ->
                            runCatching { Files.size(file) }.getOrDefault(0L)
                        }.sum()
                }
            }
        Result.success(bytes)
    } catch (failure: UncheckedIOException) {
        Result.failure(failure)
    } catch (failure: IOException) {
        Result.failure(failure)
    } catch (failure: SecurityException) {
        Result.failure(failure)
    }
