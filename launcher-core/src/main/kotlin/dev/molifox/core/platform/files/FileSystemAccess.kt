// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 定义文件系统接口，调用方不依赖操作系统或 NIO 实现；读写错误统一以 Result 返回。

package dev.molifox.core.platform.files

import java.nio.file.Path
import java.time.Instant

data class FileMetadata(
    val directory: Boolean,
    val bytes: Long,
    val modified: Instant,
)

interface FileSystemAccess {
    fun metadata(path: Path): Result<FileMetadata>

    fun children(directory: Path): Result<List<Path>>

    /** 目录存在时直接返回；不存在时逐级创建。 */
    fun createDirectory(directory: Path): Result<Path>
}
