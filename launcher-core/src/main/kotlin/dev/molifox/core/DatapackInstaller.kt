// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责数据包向指定存档的校验与安装。

package dev.molifox.core

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
        val info = backend.version(versionId)
        val project = backend.project(hit.id)
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
            size in 1..(200L * 1024 * 1024) &&
                (hash512.isBlank() || hash512.matches(Regex("[a-fA-F0-9]{128}"))) &&
                (hash1.isBlank() || hash1.matches(Regex("[a-fA-F0-9]{40}"))) &&
                (hash512.isNotBlank() || hash1.isNotBlank()),
        ) {
            "数据包缺少有效大小或校验信息，或文件超过 200 MB"
        }
        val cache =
            store.temporaryDirectory.resolve("datapack-staging").apply { createDirectories() }
        val download = cache.resolve("${UUID.randomUUID()}.zip")
        try {
            network.downloadTracked(
                context,
                DownloadArtifact(
                    contentFileUrl(api, file.string("url")),
                    download,
                    hash512.ifBlank { hash1 },
                    size,
                    if (hash512.isNotBlank()) "SHA-512" else "SHA-1",
                ),
            ) { bytes, total ->
                context.progress(
                    if (total > 0) bytes.toFloat() / total else 0f,
                    "下载数据包 · $filename",
                )
            }
            ZipFile(download.toFile()).use { zip ->
                val manifest = zip.getEntry("pack.mcmeta") ?: error("ZIP 根目录缺少 pack.mcmeta")
                require(!manifest.isDirectory && manifest.size in 1..65536) { "数据包说明文件无效" }
                val text = zip.getInputStream(manifest).use { it.readNBytes(65537) }
                require(
                    text.size <= 65536 &&
                        com.google.gson.JsonParser
                            .parseString(text.toString(Charsets.UTF_8))
                            .asJsonObject
                            .has("pack"),
                ) {
                    "数据包说明格式无效"
                }
            }
            currentCoroutineContext().ensureActive()
            require(
                signature == contentSignature(store, store.instance(instanceId)) &&
                    worldDirectory(instanceId, worldName) == world &&
                    worldIdentity(world) == identity,
            ) {
                "目标实例或存档已经改变，请重新选择"
            }
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
                    worldDirectory(instanceId, worldName) == world &&
                        worldIdentity(world) == identity,
                ) {
                    "目标存档已经改变"
                }
                Files.move(staging, target)
            } finally {
                Files.deleteIfExists(staging)
            }
            context.progress(1f, "已安装至 $worldName/datapacks；进入世界后启用或重载")
        } finally {
            Files.deleteIfExists(download)
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
        require(file.isRegularFile && !file.isSymbolicLink && file.size() <= 128L * 1024 * 1024) {
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
