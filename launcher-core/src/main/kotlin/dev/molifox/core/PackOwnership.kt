// 负责整合包文件归属账本的读写。

package dev.molifox.core

import com.google.gson.JsonParser
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.UUID
import java.util.zip.ZipFile
import kotlin.io.path.*

/** 每个实例一份的文件归属账本；以 .molifox- 开头，packPath 已禁止整合包覆盖该前缀。 */
internal const val PACK_OWNERSHIP_FILE = ".molifox-pack-ownership.json"

internal enum class PackFileOrigin {
    Download,
    Override,
}

internal data class PackOwnedFile(
    val path: String,
    val sha1: String,
    val size: Long = 0,
    val optional: Boolean = false,
    val origin: PackFileOrigin = PackFileOrigin.Download,
)

internal data class PackOwnership(
    val formatVersion: Int = 1,
    val source: String = "",
    val versionId: String = "",
    val name: String = "",
    val gameVersion: String = "",
    val loader: String = "",
    val loaderVersion: String = "",
    val archiveSha256: String = "",
    val installedAt: String = "",
    val optionalFiles: List<String> = emptyList(),
    val files: List<PackOwnedFile> = emptyList(),
)

/** 账本路径统一使用正斜杠，规划器用同一套键比较。 */
internal fun packRelative(path: String): String = path.replace('\\', '/')

private val packSha1Pattern = Regex("[a-fA-F0-9]{40}")

/**
 * 读写归属账本，并按整合包清单生成账本。
 *
 * 账本只描述「整合包安装/升级时写入的文件及其期望哈希」；读取失败一律视为没有账本， 由调用方回退到迁移基线（所有现存文件按用户文件保护）。
 */
internal object PackOwnershipStore {
    const val FORMAT_VERSION = 1

    fun write(root: Path, ownership: PackOwnership) {
        require(ownership.formatVersion == FORMAT_VERSION) { "文件归属账本版本无效" }
        ownership.files.forEach { require(it.path.isNotBlank()) { "文件归属账本包含空路径" } }
        root.createDirectories()
        val target = safePath(root, PACK_OWNERSHIP_FILE)
        val temporary = safePath(root, "$PACK_OWNERSHIP_FILE.${UUID.randomUUID()}.part")
        try {
            temporary.writeText(gson.toJson(ownership), Charsets.UTF_8)
            try {
                Files.move(
                    temporary,
                    target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            temporary.deleteIfExists()
        }
    }

    fun read(root: Path, store: LauncherStore? = null): PackOwnership? {
        val file =
            try {
                safePath(root, PACK_OWNERSHIP_FILE)
            } catch (error: Exception) {
                store?.log("整合包文件归属账本路径无效：${error.message}", true)
                return null
            }
        if (!file.exists()) return null
        return try {
            val json = Files.newBufferedReader(file).use { JsonParser.parseReader(it) }.asJsonObject
            require(json.int("formatVersion") == FORMAT_VERSION) { "文件归属账本版本不受支持" }
            require(json["files"]?.isJsonArray == true) { "文件归属账本缺少文件列表" }
            val files =
                json.array("files").map { element ->
                    val item = element.asJsonObject
                    val path = packRelative(item.string("path"))
                    val sha1 = item.string("sha1")
                    require(path.isNotBlank() && sha1.matches(packSha1Pattern)) {
                        "文件归属账本条目无效"
                    }
                    safePath(root, path)
                    PackOwnedFile(
                        path = path,
                        sha1 = sha1.lowercase(),
                        size = item.get("size")?.takeUnless { it.isJsonNull }?.asLong ?: 0,
                        optional = item.boolean("optional"),
                        origin =
                            PackFileOrigin.entries.find {
                                it.name.equals(item.string("origin"), true)
                            } ?: PackFileOrigin.Download,
                    )
                }
            PackOwnership(
                formatVersion = FORMAT_VERSION,
                source = json.string("source"),
                versionId = json.string("versionId"),
                name = json.string("name"),
                gameVersion = json.string("gameVersion"),
                loader = json.string("loader"),
                loaderVersion = json.string("loaderVersion"),
                archiveSha256 = json.string("archiveSha256"),
                installedAt = json.string("installedAt"),
                optionalFiles =
                    json.array("optionalFiles").mapNotNull {
                        it.takeIf { value -> value.isJsonPrimitive }?.asString
                    },
                files = files,
            )
        } catch (error: Exception) {
            store?.log("整合包文件归属账本损坏，已按无账本模式处理：${error.message}", true)
            null
        }
    }

    /** 按清单生成账本；哈希取自清单与压缩包条目，不扫描磁盘。 */
    fun buildOwnership(
        manifest: PackManifest,
        archiveSha256: String,
        selection: Set<String>?,
        zip: ZipFile,
        root: Path,
    ): PackOwnership {
        val entries = linkedMapOf<String, PackOwnedFile>()
        for (file in manifest.index?.array("files")?.map { it.asJsonObject }.orEmpty()) {
            val client = file.obj("env").string("client", "required")
            if (client == "unsupported") continue
            val optional = client == "optional"
            if (optional && selection != null && file.string("path") !in selection) continue
            val path = ownedPath(root, file.string("path"))
            entries[path] =
                PackOwnedFile(
                    path = path,
                    sha1 = file.obj("hashes").string("sha1").lowercase(),
                    size = file.get("fileSize")?.takeUnless { it.isJsonNull }?.asLong ?: 0,
                    optional = optional,
                    origin = PackFileOrigin.Download,
                )
        }
        for (file in manifest.checkedFiles) {
            val path = ownedPath(root, file.path)
            entries[path] =
                PackOwnedFile(path, file.hash.lowercase(), 0, false, PackFileOrigin.Download)
        }
        for (prefix in manifest.overridePrefixes) {
            for ((entry, target) in validateArchive(zip, prefix, root)) {
                val path =
                    root
                        .toAbsolutePath()
                        .normalize()
                        .relativize(target.toAbsolutePath().normalize())
                        .joinToString("/")
                if (path.substringBefore('/').lowercase() in manifest.excludedRoots) continue
                entries[path] =
                    PackOwnedFile(
                        path = path,
                        sha1 = zipEntryHash(zip, entry),
                        size = entry.size,
                        origin = PackFileOrigin.Override,
                    )
            }
        }
        return PackOwnership(
            source =
                when {
                    manifest.index != null -> "modrinth"
                    manifest.curseForge != null -> "curseforge"
                    else -> "archive"
                },
            versionId = manifest.version,
            name = manifest.request.name,
            gameVersion = manifest.request.gameVersion,
            loader = manifest.request.loader.name,
            loaderVersion = manifest.request.loaderVersion,
            archiveSha256 = archiveSha256.lowercase(),
            installedAt = Instant.now().toString(),
            optionalFiles = manifest.optionalFiles,
            files = entries.values.sortedBy { it.path },
        )
    }

    /** 没有账本的老实例：能用旧整合包归档解析出清单时以它为基线，否则返回空账本， 让规划器把现存文件全部当作受保护的用户文件。 */
    fun migrationBaseline(
        root: Path,
        archive: Path?,
        manifest: PackManifest? = null,
        selection: Set<String>? = null,
        os: String = "windows",
    ): PackOwnership {
        val source =
            archive?.takeIf { it.isRegularFile() && !Files.isSymbolicLink(it) }
                ?: return PackOwnership()
        return try {
            ZipFile(source.toFile()).use { zip ->
                val resolved = manifest ?: readPackManifest(zip, root, os)
                buildOwnership(resolved, digest(source, "SHA-256"), selection, zip, root)
            }
        } catch (_: Exception) {
            PackOwnership()
        }
    }
}

/** 把清单里的相对路径规范化到 root 之下，同时拒绝越界、符号链接和 .molifox- 前缀。 */
private fun ownedPath(root: Path, path: String): String {
    val absolute = root.toAbsolutePath().normalize()
    val target = packPath(root, path).toAbsolutePath().normalize()
    return absolute.relativize(target).joinToString("/")
}
