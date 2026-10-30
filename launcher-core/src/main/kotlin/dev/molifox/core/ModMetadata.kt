// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责模组元数据与依赖声明的读取。

package dev.molifox.core

import java.nio.file.Path
import java.util.zip.ZipFile
import kotlin.io.path.fileSize
import kotlin.io.path.getLastModifiedTime

/** 声明中指向项目主页的引用；`provider` 只使用 `modrinth` 或 `curseforge`。 */
data class ModProjectRef(
    val provider: String,
    val slug: String,
)

data class ModEntry(
    val id: String,
    val name: String,
    val version: String,
    val authors: String = "",
    val description: String = "",
    val dependencies: List<String> = emptyList(),
    /** 声明的 Minecraft 版本约束，例如 Fabric 的 `>=1.20 <1.21` 或 Forge 的 `[1.20,1.21)`。 */
    val minecraftConstraint: String = "",
    /** 声明里的 Modrinth / CurseForge 项目链接；更新检查优先按项目标识匹配。 */
    val projectRefs: List<ModProjectRef> = emptyList(),
)

data class ModMetadata(
    val loader: String = "",
    val entries: List<ModEntry> = emptyList(),
    val warning: String = "",
) {
    val title
        get() = entries.joinToString(" / ") { it.name.ifBlank { it.id } }

    val versions
        get() =
            entries
                .map { it.version }
                .filter { it.isNotBlank() }
                .distinct()
                .joinToString(" / ")

    val ids
        get() = entries.map { it.id }.filter { it.isNotBlank() }.distinct()

    val minecraftConstraints
        get() = entries.map { it.minecraftConstraint }.filter { it.isNotBlank() }.distinct()

    /** 加载器键（小写）用于与内容源返回的 `loaders` 比较。 */
    val loaderKeys: List<String>
        get() =
            when {
                loader.startsWith("Fabric") -> listOf("fabric")
                loader.startsWith("Quilt") -> listOf("quilt")
                loader.startsWith("NeoForge") -> listOf("neoforge")
                loader.startsWith("Forge") -> listOf("forge")
                else -> emptyList()
            }

    val projectRefs
        get() = entries.flatMap { it.projectRefs }.distinct()
}

/** Reads declarations only. Never loads classes, extracts files, or evaluates build expressions. */
internal class ModMetadataReader {
    private data class Cached(
        val size: Long,
        val modified: Long,
        val metadata: ModMetadata,
    )

    private val cache =
        object : LinkedHashMap<Path, Cached>(64, .75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Path, Cached>?) =
                size > 1024
        }

    @Synchronized
    fun read(path: Path): ModMetadata {
        val key = path.toAbsolutePath().normalize()
        val size = path.fileSize()
        val modified = path.getLastModifiedTime().toMillis()
        cache[key]
            ?.takeIf { it.size == size && it.modified == modified }
            ?.let {
                return it.metadata
            }
        val metadata =
            runCatching {
                parse(path)
            }.getOrElse {
                ModMetadata(warning = "无法读取模组声明：${it.message.orEmpty().take(180)}")
            }
        cache[key] = Cached(size, modified, metadata)
        return metadata
    }

    /** 格式探测顺序固定：Quilt → Fabric → NeoForge TOML → Forge TOML → 旧版 mcmod.info。 */
    private fun parse(path: Path): ModMetadata =
        ZipFile(path.toFile()).use { zip ->
            ModMetadataJsonFormats.parseQuilt(zip)
                ?: ModMetadataJsonFormats.parseFabric(zip)
                ?: ModMetadataTomlFormats.parseNeoForge(zip)
                ?: ModMetadataTomlFormats.parseForge(zip)
                ?: ModMetadataJsonFormats.parseLegacy(zip)
                ?: ModMetadata(warning = "未找到已支持的模组声明，仍可启用或禁用此文件")
        }
}
