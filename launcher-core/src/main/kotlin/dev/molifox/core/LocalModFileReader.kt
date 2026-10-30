// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 集中本地模组文件读取，保持查询与替换事务的职责分离。

package dev.molifox.core

import java.nio.file.Path
import kotlin.io.path.fileSize
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.name

internal class LocalModFileReader(
    private val metadataReader: ModMetadataReader,
    private val hasher: ModFileHasher,
) {
    private data class Cached(
        val size: Long,
        val modified: Long,
        val file: LocalModFile,
    )

    private val cache =
        object : LinkedHashMap<Path, Cached>(64, .75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Path, Cached>?) =
                size > 1024
        }

    @Synchronized
    fun read(path: Path): LocalModFile {
        val normalized = path.toAbsolutePath().normalize()
        val size = path.fileSize()
        val modified = path.getLastModifiedTime().toMillis()
        cache[normalized]
            ?.takeIf { it.size == size && it.modified == modified }
            ?.let {
                return it.file
            }
        val file =
            LocalModFile(
                normalized,
                path.name,
                size,
                modified,
                !path.name.endsWith(".disabled", true),
                hasher(path, "SHA-512"),
                metadataReader.read(path),
            )
        cache[normalized] = Cached(size, modified, file)
        return file
    }
}
