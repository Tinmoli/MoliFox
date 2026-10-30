// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 定义实例目录查询的数据接口与快照；不持有界面状态或缓存。

package dev.molifox.core.data.instance

import java.nio.file.Path
import java.time.Instant

data class InstanceDirectoryEntry(
    val path: Path,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val modified: Instant?,
)

interface InstanceWorkspaceRepository {
    suspend fun existingDirectories(
        instanceId: String,
        relativePaths: List<String>,
    ): Result<List<String>>

    suspend fun listDirectory(
        instanceId: String,
        folder: String,
        segments: List<String>,
    ): Result<List<InstanceDirectoryEntry>>
}
