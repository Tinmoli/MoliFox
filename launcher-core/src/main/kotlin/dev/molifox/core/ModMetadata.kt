package dev.molifox.core

import com.google.gson.JsonElement
import com.google.gson.JsonParser
import java.nio.file.Path
import java.util.jar.Manifest
import java.util.zip.ZipFile
import kotlin.io.path.*
import org.tomlj.Toml

data class ModEntry(
    val id: String,
    val name: String,
    val version: String,
    val authors: String = "",
    val description: String = "",
    val dependencies: List<String> = emptyList(),
)

data class ModMetadata(
    val loader: String = "",
    val entries: List<ModEntry> = emptyList(),
    val warning: String = "",
) {
    val title
        get() = entries.joinToString(" / ") { it.name.ifBlank { it.id } }

    val versions
        get() = entries.map { it.version }.filter { it.isNotBlank() }.distinct().joinToString(" / ")
}

/** Reads declarations only. Never loads classes, extracts files, or evaluates build expressions. */
internal class ModMetadataReader {
    private data class Cached(val size: Long, val modified: Long, val metadata: ModMetadata)

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
        val metadata = runCatching {
            parse(path)
        }
            .getOrElse {
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
                val loader = JsonParser.parseString(source).asJsonObject.obj("quilt_loader")
                val info = loader.obj("metadata")
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
                                if (it.isJsonObject)
                                    it.asJsonObject.string("id").takeIf(String::isNotBlank)
                                else null
                            },
                        )
                    ),
                )
            }
            text("fabric.mod.json")?.let { source ->
                val info = JsonParser.parseString(source).asJsonObject
                return@use ModMetadata(
                    "Fabric",
                    listOf(
                        ModEntry(
                            info.string("id"),
                            info.string("name", info.string("id")),
                            info.string("version"),
                            info.array("authors").joinToString { authorName(it) },
                            info.string("description"),
                            info.obj("depends").entrySet().map { (id, value) -> "$id ${value}" },
                        )
                    ),
                )
            }
            for ((filename, loader) in
                listOf(
                    "META-INF/neoforge.mods.toml" to "NeoForge",
                    "META-INF/mods.toml" to "Forge",
                )) {
                val source = text(filename) ?: continue
                val parsed = Toml.parse(source)
                require(!parsed.hasErrors()) { "TOML 声明格式错误" }
                val version =
                    text("META-INF/MANIFEST.MF")
                        ?.let {
                            Manifest(it.byteInputStream())
                                .mainAttributes
                                .getValue("Implementation-Version")
                        }
                        .orEmpty()
                val mods =
                    parsed.getArray("mods")
                        ?: return@use ModMetadata(loader, warning = "声明缺少 mods 列表")
                return@use ModMetadata(
                    loader,
                    (0 until mods.size()).map { index ->
                        val mod = mods.getTable(index)
                        val id = mod.getString("modId").orEmpty()
                        val dependencies = parsed.getTable("dependencies")?.getArray(listOf(id))
                        ModEntry(
                            id,
                            mod.getString("displayName") ?: id,
                            mod.getString("version")
                                .orEmpty()
                                .replace("\${file.jarVersion}", version),
                            mod.getString("authors").orEmpty(),
                            mod.getString("description").orEmpty(),
                            if (dependencies == null) emptyList()
                            else
                                (0 until dependencies.size()).map { item ->
                                    val dependency = dependencies.getTable(item)
                                    "${dependency.getString("modId").orEmpty()} ${dependency.getString("versionRange").orEmpty()}"
                                },
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
                        ModEntry(
                            info.string("modid"),
                            info.string("name", info.string("modid")),
                            info.string("version"),
                            info.array("authorList").joinToString { authorName(it) },
                            info.string("description"),
                        )
                    },
                )
            }
            ModMetadata(warning = "未找到已支持的模组声明，仍可启用或禁用此文件")
        }

    private fun authorName(value: JsonElement): String =
        if (value.isJsonObject) value.asJsonObject.string("name")
        else if (value.isJsonPrimitive) value.asString else ""
}
