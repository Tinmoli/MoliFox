// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 定义只读文件系统接口，调用方不依赖操作系统或 NIO 实现。

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
}
