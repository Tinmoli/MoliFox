// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责整合包文件归属账本的读写。

package dev.molifox.core

import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import dev.molifox.core.i18n.I18n
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.UUID
import java.util.zip.ZipFile
import kotlin.io.path.absolute
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.isSymbolicLink
import kotlin.io.path.name
import kotlin.io.path.writeText

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

    fun write(
        root: Path,
        ownership: PackOwnership,
    ) {
        require(ownership.formatVersion == FORMAT_VERSION) {
            I18n.t("packsA2.packOwnership.formatVersionInvalid")
        }
        ownership.files.forEach {
            require(it.path.isNotBlank()) { I18n.t("packsA2.packOwnership.emptyPath") }
        }
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

    fun read(
        root: Path,
        store: LauncherStore? = null,
    ): PackOwnership? {
        val file =
            try {
                safePath(root, PACK_OWNERSHIP_FILE)
            } catch (error: IllegalArgumentException) {
                store?.log(
                    I18n.t("packsA2.packOwnership.pathInvalid", error.message),
                    true,
                )
                return null
            }
        if (!file.exists()) return null
        return readOwnership(file, root, store)
    }

    /**
     * 账本是用户可编辑的磁盘文件：读不出来（IO、JSON 语法、节点类型或校验失败）就按「没有账本」处理，
     * 由调用方回退到迁移基线，因此这里按实际可能抛出的具体异常逐个兜底。
     */
    private fun readOwnership(
        file: Path,
        root: Path,
        store: LauncherStore?,
    ): PackOwnership? =
        try {
            parseOwnership(readObject(file), root)
        } catch (error: IOException) {
            corruptOwnership(store, error)
        } catch (error: JsonParseException) {
            corruptOwnership(store, error)
        } catch (error: IllegalStateException) {
            corruptOwnership(store, error)
        } catch (error: IllegalArgumentException) {
            corruptOwnership(store, error)
        } catch (error: UnsupportedOperationException) {
            corruptOwnership(store, error)
        }

    private fun corruptOwnership(
        store: LauncherStore?,
        error: Exception,
    ): PackOwnership? {
        store?.log(
            I18n.t("packsA2.packOwnership.corruptTreatedAsMissing", error.message),
            true,
        )
        return null
    }

    private fun parseOwnership(
        json: JsonObject,
        root: Path,
    ): PackOwnership {
        require(json.int("formatVersion") == FORMAT_VERSION) {
            I18n.t("packsA2.packOwnership.versionUnsupported")
        }
        require(json["files"]?.isJsonArray == true) {
            I18n.t("packsA2.packOwnership.missingFileList")
        }
        val files = json.array("files").map { element -> readOwnedFile(element.asJsonObject, root) }
        return PackOwnership(
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
        collectIndexedFiles(manifest, selection, root, entries)
        collectCheckedFiles(manifest, root, entries)
        collectOverrideFiles(manifest, zip, root, entries)
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

    /** 清单 `files` 里的下载条目；标记为客户端不支持的条目不进账本。 */
    private fun collectIndexedFiles(
        manifest: PackManifest,
        selection: Set<String>?,
        root: Path,
        entries: MutableMap<String, PackOwnedFile>,
    ) {
        for (file in manifest.index
            ?.array("files")
            ?.map { it.asJsonObject }
            .orEmpty()) {
            val client = file.obj("env").string("client", "required")
            val optional = client == "optional"
            // 客户端不支持的条目、未勾选的选装条目都不进账本（不写盘、不校验）。
            val included =
                client != "unsupported" &&
                    (!optional || selection == null || file.string("path") in selection)
            if (included) {
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
        }
    }

    /** 清单直接列出的已校验文件（MCBBS 等格式）。 */
    private fun collectCheckedFiles(
        manifest: PackManifest,
        root: Path,
        entries: MutableMap<String, PackOwnedFile>,
    ) {
        for (file in manifest.checkedFiles) {
            val path = ownedPath(root, file.path)
            entries[path] =
                PackOwnedFile(path, file.hash.lowercase(), 0, false, PackFileOrigin.Download)
        }
    }

    /** 归档 overrides 目录展开后的覆盖文件。 */
    private fun collectOverrideFiles(
        manifest: PackManifest,
        zip: ZipFile,
        root: Path,
        entries: MutableMap<String, PackOwnedFile>,
    ) {
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
private fun ownedPath(
    root: Path,
    path: String,
): String {
    val absolute = root.toAbsolutePath().normalize()
    val target = packPath(root, path).toAbsolutePath().normalize()
    return absolute.relativize(target).joinToString("/")
}

/** 读取账本里的一条文件记录；路径与哈希不合法时抛出参数异常，由 [PackOwnershipStore.read] 兜底。 */
private fun readOwnedFile(
    item: JsonObject,
    root: Path,
): PackOwnedFile {
    val path = packRelative(item.string("path"))
    val sha1 = item.string("sha1")
    require(path.isNotBlank() && sha1.matches(packSha1Pattern)) {
        I18n.t("packsA2.packOwnership.entryInvalid")
    }
    safePath(root, path)
    return PackOwnedFile(
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
