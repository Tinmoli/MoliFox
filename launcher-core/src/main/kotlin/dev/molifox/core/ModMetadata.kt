// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责模组元数据与依赖声明的读取。

package dev.molifox.core

import com.google.gson.JsonElement
import com.google.gson.JsonParser
import org.tomlj.Toml
import java.nio.file.Path
import java.util.jar.Manifest
import java.util.zip.ZipFile
import kotlin.io.path.fileSize
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.name

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

    private fun parse(path: Path): ModMetadata =
        ZipFile(path.toFile()).use { zip ->
            fun text(name: String): String? {
                val entry = zip.getEntry(name) ?: return null
                require(entry.size <= 2 * 1024 * 1024) { "声明文件过大" }
                return zip.getInputStream(entry).use {
                    val bytes = it.readNBytes(2 * 1024 * 1024 + 1)
                    require(bytes.size <= 2 * 1024 * 1024) { "声明文件过大" }
                    bytes.toString(Charsets.UTF_8)
                }
            }
            // Prefer Quilt's richer declaration when a universal JAR also includes fabric.mod.json.
            text("quilt.mod.json")?.let { source ->
                val root = JsonParser.parseString(source).asJsonObject
                val loader = root.obj("quilt_loader")
                val info = loader.obj("metadata")
                val minecraft =
                    loader
                        .array("depends")
                        .mapNotNull { it.takeIf(JsonElement::isJsonObject)?.asJsonObject }
                        .firstOrNull { it.string("id") == "minecraft" }
                return@use ModMetadata(
                    "Quilt",
                    listOf(
                        ModEntry(
                            loader.string("id"),
                            info.string("name", loader.string("id")),
                            loader.string("version"),
                            info.obj("contributors").keySet().joinToString(),
                            info.string("description"),
                            loader.array("depends").mapNotNull {
                                if (it.isJsonObject) {
                                    it.asJsonObject.string("id").takeIf(String::isNotBlank)
                                } else {
                                    null
                                }
                            },
                            minecraft
                                ?.get("versions")
                                ?.let { jsonStrings(it).joinToString(" || ") }
                                .orEmpty(),
                            projectRefs(
                                jsonStrings(info.get("contact")) +
                                    jsonStrings(info.get("homepage")),
                            ),
                        ),
                    ),
                )
            }
            text("fabric.mod.json")?.let { source ->
                val info = JsonParser.parseString(source).asJsonObject
                val minecraftConstraint =
                    jsonStrings(info.obj("depends").get("minecraft")).joinToString(" || ")
                return@use ModMetadata(
                    "Fabric",
                    listOf(
                        ModEntry(
                            info.string("id"),
                            info.string("name", info.string("id")),
                            info.string("version"),
                            info.array("authors").joinToString { authorName(it) },
                            info.string("description"),
                            info.obj("depends").entrySet().map { (id, value) -> "$id $value" },
                            minecraftConstraint,
                            projectRefs(
                                jsonStrings(info.get("contact")) +
                                    jsonStrings(info.get("homepage")) +
                                    jsonStrings(info.get("sources")),
                            ),
                        ),
                    ),
                )
            }
            for (
            (filename, loader) in
            listOf(
                "META-INF/neoforge.mods.toml" to "NeoForge",
                "META-INF/mods.toml" to "Forge",
            )
            ) {
                val source = text(filename) ?: continue
                val parsed = Toml.parse(source)
                require(!parsed.hasErrors()) { "TOML 声明格式错误" }
                val version =
                    text("META-INF/MANIFEST.MF")
                        ?.let {
                            Manifest(it.byteInputStream())
                                .mainAttributes
                                .getValue("Implementation-Version")
                        }.orEmpty()
                val mods =
                    parsed.getArray("mods")
                        ?: return@use ModMetadata(loader, warning = "声明缺少 mods 列表")
                return@use ModMetadata(
                    loader,
                    (0 until mods.size()).map { index ->
                        val mod = mods.getTable(index)
                        val id = mod.getString("modId").orEmpty()
                        val dependencies = parsed.getTable("dependencies")?.getArray(listOf(id))
                        val dependencyList =
                            if (dependencies == null) {
                                emptyList()
                            } else {
                                (0 until dependencies.size()).map { item ->
                                    dependencies.getTable(item)
                                }
                            }
                        val minecraftConstraint =
                            dependencyList
                                .firstOrNull { it.getString("modId") == "minecraft" }
                                ?.getString("versionRange")
                                .orEmpty()
                        ModEntry(
                            id,
                            mod.getString("displayName") ?: id,
                            mod
                                .getString("version")
                                .orEmpty()
                                .replace("\${file.jarVersion}", version),
                            mod.getString("authors").orEmpty(),
                            mod.getString("description").orEmpty(),
                            dependencyList.map { dependency ->
                                "${dependency.getString(
                                    "modId",
                                ).orEmpty()} ${dependency.getString("versionRange").orEmpty()}"
                            },
                            minecraftConstraint,
                            projectRefs(
                                listOfNotNull(
                                    mod.getString("displayURL"),
                                    mod.getString("displayUrl"),
                                    mod.getString("homepage"),
                                ),
                            ),
                        )
                    },
                )
            }
            text("mcmod.info")?.let { source ->
                val json = JsonParser.parseString(source)
                val mods =
                    if (json.isJsonArray) json.asJsonArray else json.asJsonObject.array("modList")
                return@use ModMetadata(
                    "Forge (legacy)",
                    mods.map { value ->
                        val info = value.asJsonObject
                        val minecraftConstraint =
                            (
                                listOf(info.string("mcversion"), info.string("gameVersion")) +
                                    info.array("acceptableMinecraftVersions").map { it.asString }
                            ).filter { it.isNotBlank() }
                                .distinct()
                                .joinToString(" || ")
                        ModEntry(
                            info.string("modid"),
                            info.string("name", info.string("modid")),
                            info.string("version"),
                            info.array("authorList").joinToString { authorName(it) },
                            info.string("description"),
                            emptyList(),
                            minecraftConstraint,
                            projectRefs(listOf(info.string("url"))),
                        )
                    },
                )
            }
            ModMetadata(warning = "未找到已支持的模组声明，仍可启用或禁用此文件")
        }

    private fun jsonStrings(value: JsonElement?): List<String> =
        when {
            value == null || value.isJsonNull -> emptyList()
            value.isJsonPrimitive ->
                if (value.asJsonPrimitive.isString) listOf(value.asString) else emptyList()
            value.isJsonArray -> value.asJsonArray.flatMap { jsonStrings(it) }
            value.isJsonObject ->
                value.asJsonObject.entrySet().flatMap { (key, child) ->
                    // 只收集可能是链接或版本号的文本字段，跳过颜色、文件名等无关值。
                    if (key.equals("logoFile", true) || key.equals("icon", true)) {
                        emptyList()
                    } else {
                        jsonStrings(child)
                    }
                }
            else -> emptyList()
        }

    /** 从主页/源码链接里提取 Modrinth 与 CurseForge 项目 slug，其它域名忽略。 */
    private fun projectRefs(urls: Iterable<String>): List<ModProjectRef> {
        val refs = mutableListOf<ModProjectRef>()
        for (url in urls) {
            modrinthProjectUrl.find(url)?.groupValues?.getOrNull(1)?.let {
                refs += ModProjectRef("modrinth", it)
            }
            curseForgeProjectUrl.find(url)?.groupValues?.getOrNull(1)?.let {
                refs += ModProjectRef("curseforge", it)
            }
        }
        return refs.distinct()
    }

    private fun authorName(value: JsonElement): String =
        if (value.isJsonObject) {
            value.asJsonObject.string("name")
        } else if (value.isJsonPrimitive) {
            value.asString
        } else {
            ""
        }
}

private val modrinthProjectUrl =
    Regex("(?i)https?://(?:www\\.)?modrinth\\.com/(?:mod|project)/([a-z0-9_-]+)")

private val curseForgeProjectUrl =
    Regex("(?i)https?://(?:www\\.)?curseforge\\.com/minecraft/mc-mods/([a-z0-9_-]+)")
