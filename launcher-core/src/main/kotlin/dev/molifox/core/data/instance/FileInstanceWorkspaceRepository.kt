// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 在 IO 调度器按需读取实例目录，通过平台接口取得文件快照。

package dev.molifox.core.data.instance

import dev.molifox.core.i18n.I18n
import dev.molifox.core.platform.files.FileSystemAccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.nio.file.Path

class FileInstanceWorkspaceRepository(
    private val instanceRoot: (String) -> Path,
    private val files: FileSystemAccess,
    private val reportFailure: (Path, Throwable) -> Unit,
) : InstanceWorkspaceRepository {
    override suspend fun existingDirectories(
        instanceId: String,
        relativePaths: List<String>,
    ): Result<List<String>> =
        withContext(Dispatchers.IO) {
            val root =
                resolve(instanceId, "", emptyList()).getOrElse {
                    return@withContext Result.failure(it)
                }
            Result.success(relativePaths.filter { existingDirectory(root.resolve(it)) })
        }

    override suspend fun ensureDirectory(
        instanceId: String,
        relativePath: String,
    ): Result<Path> =
        withContext(Dispatchers.IO) {
            val root =
                resolve(instanceId, "", emptyList()).getOrElse {
                    return@withContext Result.failure(it)
                }
            val directory = root.resolve(relativePath).normalize()
            if (!directory.startsWith(root)) {
                return@withContext Result.failure(
                    IllegalArgumentException(
                        I18n.t("miscA2.instanceWorkspace.relativePathWithinInstance"),
                    ),
                )
            }
            files.createDirectory(directory).onFailure { reportFailure(directory, it) }
        }

    private suspend fun existingDirectory(path: Path): Boolean {
        currentCoroutineContext().ensureActive()
        return files
            .metadata(path)
            .onFailure { reportFailure(path, it) }
            .getOrNull()
            ?.directory ==
            true
    }

    override suspend fun listDirectory(
        instanceId: String,
        folder: String,
        segments: List<String>,
    ): Result<List<InstanceDirectoryEntry>> =
        withContext(Dispatchers.IO) {
            val directory =
                resolve(instanceId, folder, segments).getOrElse {
                    return@withContext Result.failure(it)
                }
            files.children(directory).map { children ->
                children
                    .map { child ->
                        currentCoroutineContext().ensureActive()
                        entry(child)
                    }.sortedWith(
                        compareBy({ !it.isDirectory }, {
                            it.path.fileName
                                .toString()
                                .lowercase()
                        }),
                    )
            }
        }

    private fun entry(path: Path): InstanceDirectoryEntry {
        val metadata = files.metadata(path).onFailure { reportFailure(path, it) }.getOrNull()
        return InstanceDirectoryEntry(
            path,
            metadata?.directory == true,
            metadata?.bytes ?: 0L,
            metadata?.modified,
        )
    }

    private fun resolve(
        instanceId: String,
        folder: String,
        segments: List<String>,
    ): Result<Path> =
        runCatching {
            val root = instanceRoot(instanceId).toAbsolutePath().normalize()
            require((listOf(folder) + segments).none { Path.of(it).isAbsolute }) {
                I18n.t("miscA2.instanceWorkspace.relativePath")
            }
            val directory =
                segments
                    .fold(root.resolve(folder)) { parent, segment -> parent.resolve(segment) }
                    .normalize()
            require(directory.startsWith(root)) {
                I18n.t("miscA2.instanceWorkspace.pathOutsideInstance")
            }
            directory
        }
}
