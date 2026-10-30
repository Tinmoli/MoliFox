// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 提供实例文件夹查询用例，只依赖数据接口，不直接读文件或访问系统。

package dev.molifox.core.domain.instance

import dev.molifox.core.data.instance.InstanceDirectoryEntry
import dev.molifox.core.data.instance.InstanceWorkspaceRepository

enum class GameFolder(
    val relativePath: String,
) {
    Root(""),
    Mods("mods"),
    ResourcePacks("resourcepacks"),
    ShaderPacks("shaderpacks"),
    Saves("saves"),
    Schematics("schematics"),
    Config("config"),
    Logs("logs"),
    CrashReports("crash-reports"),
    Screenshots("screenshots"),
}

class InstanceWorkspaceQueries(
    private val repository: InstanceWorkspaceRepository,
) {
    suspend fun existingFolders(instanceId: String): Result<List<GameFolder>> =
        repository
            .existingDirectories(instanceId, GameFolder.entries.map { it.relativePath })
            .map { paths ->
                GameFolder.entries.filter { it.relativePath in paths }
            }

    suspend fun listDirectory(
        instanceId: String,
        folder: String,
        segments: List<String>,
    ): Result<List<InstanceDirectoryEntry>> = repository.listDirectory(instanceId, folder, segments)
}
