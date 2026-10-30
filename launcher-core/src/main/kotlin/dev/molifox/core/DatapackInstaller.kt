// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责数据包向指定存档的校验与安装。

package dev.molifox.core

import com.google.gson.JsonObject
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID
import java.util.zip.ZipFile
import kotlin.io.path.createDirectories
import kotlin.io.path.createDirectory
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.isSymbolicLink
import kotlin.io.path.name
import kotlin.io.path.readAttributes

private const val MAX_DATAPACK_DOWNLOAD_BYTES = 200L * 1024 * 1024
private const val MAX_DATAPACK_METADATA_BYTES = 65536
private const val MAX_WORLD_METADATA_BYTES = 128L * 1024 * 1024

/** A data pack belongs to one existing world, never the instance's top-level directory. */
internal class DatapackInstaller(
    private val store: LauncherStore,
    private val network: NetworkClient,
    private val backend: ContentBackend,
    private val api: String,
) {
    suspend fun install(
        hit: ContentHit,
        versionId: String,
        instanceId: String,
        worldName: String,
        context: JobContext,
    ) {
        require(hit.type == "datapack")
        val instance = store.instance(instanceId)
        val signature = contentSignature(store, instance)
        val world = worldDirectory(instanceId, worldName)
        val identity = worldIdentity(world)
        val destination = DatapackDestination(instanceId, worldName, signature, world, identity)
        val file = resolveFile(hit, versionId, instance)
        downloadAndInstall(file, destination, context)
    }

    private suspend fun resolveFile(
        hit: ContentHit,
        versionId: String,
        instance: GameInstance,
    ): DatapackFile {
        val info = backend.version(versionId)
        val project = backend.project(hit.id)
        validateVersion(hit, versionId, instance, info, project)
        val file =
            info
                .array("files")
                .map { it.asJsonObject }
                .let { files ->
                    files.firstOrNull { it.boolean("primary") } ?: files.firstOrNull()
                } ?: error("数据包没有可用文件")
        val filename = safeFilename(file.string("filename"))
        require(filename.endsWith(".zip", true)) { "数据包必须为 ZIP" }
        if (info.boolean("manual_download")) {
            throw ManualContentRequired(
                listOf(ManualContentFile(hit.id, versionId, filename, info.string("website"))),
            )
        }
        val size = file.get("size")?.asLong ?: -1
        val hash512 = file.obj("hashes").string("sha512")
        val hash1 = file.obj("hashes").string("sha1")
        require(
            size in 1..(MAX_DATAPACK_DOWNLOAD_BYTES) &&
                (hash512.isBlank() || hash512.matches(Regex("[a-fA-F0-9]{128}"))) &&
                (hash1.isBlank() || hash1.matches(Regex("[a-fA-F0-9]{40}"))) &&
                (hash512.isNotBlank() || hash1.isNotBlank()),
        ) {
            "数据包缺少有效大小或校验信息，或文件超过 200 MB"
        }
        return DatapackFile(file, filename, size, hash512, hash1)
    }

    private fun validateVersion(
        hit: ContentHit,
        versionId: String,
        instance: GameInstance,
        info: JsonObject,
        project: JsonObject,
    ) {
        require(
            project.string("project_type") == "datapack" &&
                info.string("project_id") == hit.id &&
                info.string("id") == versionId &&
                info.array("game_versions").any { it.asString == instance.gameVersion },
        ) {
            "数据包版本不属于此项目或不兼容目标游戏版本"
        }
        require(
            info.array("dependencies").none {
                it.asJsonObject.string("dependency_type") == "required"
            },
        ) {
            "此数据包有必需前置，请先从项目页面确认并手动安装完整组合"
        }
    }

    private suspend fun downloadAndInstall(
        selectedFile: DatapackFile,
        destination: DatapackDestination,
        context: JobContext,
    ) {
        val cache =
            store.temporaryDirectory.resolve("datapack-staging").apply { createDirectories() }
        val download = cache.resolve("${UUID.randomUUID()}.zip")
        try {
            network.downloadTracked(
                context,
                DownloadArtifact(
                    contentFileUrl(api, selectedFile.file.string("url")),
                    download,
                    selectedFile.hash512.ifBlank { selectedFile.hash1 },
                    selectedFile.size,
                    if (selectedFile.hash512.isNotBlank()) "SHA-512" else "SHA-1",
                ),
            ) { bytes, total ->
                context.progress(
                    if (total > 0) bytes.toFloat() / total else 0f,
                    "下载数据包 · ${selectedFile.filename}",
                )
            }
            validateArchive(download)
            currentCoroutineContext().ensureActive()
            verifyDestinationUnchanged(destination)
            publishDatapack(download, selectedFile.filename, destination)
            context.progress(1f, "已安装至 ${destination.worldName}/datapacks；进入世界后启用或重载")
        } finally {
            Files.deleteIfExists(download)
        }
    }

    /** 下载期间实例或存档可能被替换，写盘前复核实例签名、存档路径与身份。 */
    private fun verifyDestinationUnchanged(destination: DatapackDestination) {
        require(
            destination.signature ==
                contentSignature(store, store.instance(destination.instanceId)) &&
                worldDirectory(destination.instanceId, destination.worldName) ==
                destination.world &&
                worldIdentity(destination.world) == destination.identity,
        ) {
            "目标实例或存档已经改变，请重新选择"
        }
    }

    private fun validateArchive(download: Path) {
        ZipFile(download.toFile()).use { zip ->
            val manifest = zip.getEntry("pack.mcmeta") ?: error("ZIP 根目录缺少 pack.mcmeta")
            require(
                !manifest.isDirectory && manifest.size in 1..MAX_DATAPACK_METADATA_BYTES,
            ) {
                "数据包说明文件无效"
            }
            val text = zip.getInputStream(manifest).use { it.readNBytes(65537) }
            require(
                text.size <= MAX_DATAPACK_METADATA_BYTES &&
                    com.google.gson.JsonParser
                        .parseString(text.toString(Charsets.UTF_8))
                        .asJsonObject
                        .has("pack"),
            ) {
                "数据包说明格式无效"
            }
        }
    }

    private suspend fun publishDatapack(
        download: Path,
        filename: String,
        destination: DatapackDestination,
    ) {
        val world = destination.world
        val directory = world.resolve("datapacks")
        if (directory.exists(NOFOLLOW_LINKS)) {
            requireOrdinaryDirectory(directory)
        } else {
            directory.createDirectory()
        }
        val target = safePath(directory, filename)
        require(!target.exists(NOFOLLOW_LINKS)) { "存档中已有同名数据包，请先在存档目录整理：$filename" }
        val staging = directory.resolve(".molifox-${UUID.randomUUID()}.tmp")
        try {
            Files.copy(download, staging)
            currentCoroutineContext().ensureActive()
            requireOrdinaryDirectory(directory)
            require(
                worldDirectory(destination.instanceId, destination.worldName) == world &&
                    worldIdentity(world) == destination.identity,
            ) {
                "目标存档已经改变"
            }
            Files.move(staging, target)
        } finally {
            Files.deleteIfExists(staging)
        }
    }

    private fun worldDirectory(
        instanceId: String,
        name: String,
    ): Path {
        val root = store.instancePath(instanceId).toAbsolutePath().normalize()
        val saves = root.resolve("saves")
        val world = safePath(saves, safeFilename(name))
        requireOrdinaryDirectory(saves)
        requireOrdinaryDirectory(world)
        require(world.resolve("level.dat").isRegularFile(NOFOLLOW_LINKS)) { "请选择已有的世界存档" }
        return world
    }

    private fun requireOrdinaryDirectory(path: Path) {
        val attrs = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        require(attrs.isDirectory && !attrs.isSymbolicLink && !attrs.isOther) { "存档路径不是普通目录" }
    }

    /** Windows providers may return a null fileKey, so it cannot identify a world alone. */
    private fun worldIdentity(world: Path): List<String> {
        val directory = Files.readAttributes(world, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        val level = world.resolve("level.dat")
        val file = Files.readAttributes(level, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        require(
            file.isRegularFile &&
                !file.isSymbolicLink &&
                file.size() <= MAX_WORLD_METADATA_BYTES,
        ) {
            "存档元数据文件无效"
        }
        return listOf(
            directory.fileKey()?.toString().orEmpty(),
            directory.creationTime().toString(),
            file.creationTime().toString(),
            digest(level, "SHA-256"),
        )
    }
}

private data class DatapackDestination(
    val instanceId: String,
    val worldName: String,
    val signature: String,
    val world: Path,
    val identity: List<String>,
)

private data class DatapackFile(
    val file: JsonObject,
    val filename: String,
    val size: Long,
    val hash512: String,
    val hash1: String,
)
