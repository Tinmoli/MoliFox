// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 使用系统文件属性和目录流提供只读访问，错误通过 Result 返回。

package dev.molifox.core.platform.files

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

class NioFileSystemAccess : FileSystemAccess {
    override fun metadata(path: Path): Result<FileMetadata> =
        read {
            val attributes = Files.readAttributes(path, BasicFileAttributes::class.java)
            FileMetadata(
                directory = attributes.isDirectory,
                bytes = if (attributes.isRegularFile) attributes.size() else 0L,
                modified = attributes.lastModifiedTime().toInstant(),
            )
        }

    override fun children(directory: Path): Result<List<Path>> =
        read {
            if (!Files.isDirectory(directory)) return@read emptyList()
            Files.newDirectoryStream(directory).use { entries -> entries.toList() }
        }

    private inline fun <T> read(operation: () -> T): Result<T> =
        try {
            Result.success(operation())
        } catch (failure: IOException) {
            Result.failure(failure)
        } catch (failure: SecurityException) {
            Result.failure(failure)
        }
}
