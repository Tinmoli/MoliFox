// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责启动目录与便携存储的迁移初始化。

package dev.molifox.core

import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.io.path.*

/** Validate the portable directory; never silently create a second profile elsewhere. */
class StartupStorageResolver(private val probe: (Path) -> Unit = ::checkStartupStorage) {
    fun resolve(directory: Path): Path {
        val target = directory.toAbsolutePath().normalize()
        try {
            probe(target)
        } catch (error: IOException) {
            throw IOException("无法写入启动器数据目录：$target\n${error.message.orEmpty()}", error)
        } catch (error: SecurityException) {
            throw IOException("没有启动器数据目录的写入权限：$target", error)
        }
        return target
    }
}

private fun checkStartupStorage(directory: Path) {
    directory.createDirectories()
    val temporary = Files.createTempFile(directory, ".molifox-write-check-", ".tmp")
    try {
        Files.writeString(temporary, "MoliFox storage check")
    } finally {
        Files.deleteIfExists(temporary)
    }
    // A directory can be writable while an existing lock file has a restrictive ACL.
    // Opening must succeed, but do not acquire or modify another process's lock.
    FileChannel.open(
            directory.resolve("instance.lock"),
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
        )
        .use {}
}
