// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责模组元数据与依赖声明的读取。

package dev.molifox.core

import com.google.gson.JsonParser
import dev.molifox.core.i18n.I18n
import java.nio.file.Path
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
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
    /**
     * 嵌套 JAR（META-INF/jars）里声明的 mod id：打包者内嵌的库与 Fabric API 模块。
     * 只取 id 用于依赖补齐判定「已存在」，不进 entries，避免污染列表显示与更新匹配。
     */
    val nestedIds: List<String> = emptyList(),
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

    /** 依赖补齐用：自身声明与内嵌 jar 提供的 id 都算「已存在」。 */
    val knownIds
        get() = (ids + nestedIds).distinct()

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
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Path, Cached>?) = size > 1024
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
                ModMetadata(warning = I18n.t("packsC.modMetadata.unreadable", it.message.orEmpty().take(180)))
            }
        cache[key] = Cached(size, modified, metadata)
        return metadata
    }

    /** 格式探测顺序固定：Quilt → Fabric → NeoForge TOML → Forge TOML → 旧版 mcmod.info。 */
    private fun parse(path: Path): ModMetadata =
        ZipFile(path.toFile()).use { zip ->
            val base =
                ModMetadataJsonFormats.parseQuilt(zip)
                    ?: ModMetadataJsonFormats.parseFabric(zip)
                    ?: ModMetadataTomlFormats.parseNeoForge(zip)
                    ?: ModMetadataTomlFormats.parseForge(zip)
                    ?: ModMetadataJsonFormats.parseLegacy(zip)
                    ?: return ModMetadata(warning = I18n.t("packsC.modMetadata.unsupported"))
            base.copy(nestedIds = nestedJarIds(zip))
        }
}

private const val MAX_NESTED_JAR_BYTES = 64L * 1024 * 1024

private const val MAX_NESTED_DECLARATION_BYTES = 512L * 1024

private const val NESTED_JAR_PREFIX = "META-INF/jars/"

/**
 * 读取嵌套 jar 声明的 mod id：c2me-base、fabric-resource-loader-v0 这类 id 是打包者内嵌的
 * 库与 Fabric API 模块，不读它们就会被依赖补齐当成缺失项目逐个联网查找，失败还刷一屏错误。
 * 路径来自四种内嵌清单（fabric/quilt 的 jars 数组、JarJar、Manifest），未声明的嵌套 jar
 * 运行时不会被加载，不能算「已存在」。
 */
private fun nestedJarIds(zip: ZipFile): List<String> =
    runCatching {
        JarInJarDeclarations
            .read(zip, JarInJarLoader.Unknown)
            .embedded
            .map { it.path }
            .filter { it.startsWith(NESTED_JAR_PREFIX) && it.endsWith(".jar") }
            .flatMap { path -> nestedDeclarationIds(zip, path) }
            .distinct()
            .toList()
    }.getOrElse { emptyList() }

private fun nestedDeclarationIds(
    zip: ZipFile,
    path: String,
): List<String> {
    val entry =
        zip
            .getEntry(path)
            ?.takeIf { !it.isDirectory && it.size in 1..MAX_NESTED_JAR_BYTES }
            ?: return emptyList()
    return runCatching {
        zip.getInputStream(entry).use { outer ->
            ZipInputStream(outer).use { nested ->
                generateSequence { nested.nextEntry }
                    .mapNotNull { child ->
                        val name = child.name.substringAfterLast('/')
                        if (name != FABRIC_MOD && name != QUILT_MOD) return@mapNotNull null
                        // 流式条目的 size 常为 -1，按读取字节数限界防超大声明。
                        val bytes = nested.readNBytes(MAX_NESTED_DECLARATION_BYTES.toInt() + 1)
                        if (bytes.size > MAX_NESTED_DECLARATION_BYTES.toInt()) return@mapNotNull null
                        declarationId(bytes.decodeToString(), name)
                    }.firstOrNull()
            }
        }
    }.getOrNull()?.let(::listOf).orEmpty()
}

private fun declarationId(
    text: String,
    name: String,
): String? =
    runCatching {
        val json = JsonParser.parseString(text).asJsonObject
        if (name == "quilt.mod.json") {
            json.obj("quilt_loader").string("id")
        } else {
            json.string("id")
        }
    }.getOrNull()?.takeIf { it.isNotBlank() }
