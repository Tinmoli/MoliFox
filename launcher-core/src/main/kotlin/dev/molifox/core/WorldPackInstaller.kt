// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责世界包下载、校验与安装到实例的 saves/。

package dev.molifox.core

import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.UUID
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.name

/** 世界包下载体积上限（8 GiB）；超过按异常输入拒绝，避免误装超大文件。 */
private const val MAX_WORLD_DOWNLOAD_BYTES = 8L * 1024 * 1024 * 1024

/** 字节与兆字节的换算，用于下载进度文案。 */
private const val BYTES_PER_MEGABYTE = 1024 * 1024

/** 世界包的结构校验、命名与安装落点。 */
internal class WorldPackInstaller(
    private val store: LauncherStore,
    private val network: NetworkClient,
    private val backend: ContentBackend,
    private val api: String,
) {
    suspend fun install(
        hit: ContentHit,
        versionId: String,
        instanceId: String,
        context: JobContext,
    ) = withContext(Dispatchers.IO) {
        require(hit.type == "world") { "只能安装世界项目" }
        val instance = store.instance(instanceId)
        require(instance.installed) { "请先完成游戏实例安装" }
        require(versionId.isNotBlank()) { "请选择要安装的世界版本" }
        context.progress(0f, "读取世界包版本…")
        val version = backend.version(versionId)
        val project = backend.project(hit.id)
        requireWorldVersion(hit, versionId, instance, version, project)
        val file = resolveFile(hit, versionId, version)
        downloadAndInstall(hit, file, instance, context)
    }

    /** 版本必须属于当前世界项目，且支持目标实例的游戏版本。 */
    private fun requireWorldVersion(
        hit: ContentHit,
        versionId: String,
        instance: GameInstance,
        version: JsonObject,
        project: JsonObject,
    ) {
        require(
            project.string("project_type") == "world" &&
                version.string("project_id") == hit.id &&
                version.string("id") == versionId,
        ) {
            "世界包版本不属于此项目"
        }
        val gameVersions = version.array("game_versions").map { it.asString }
        require(gameVersions.isEmpty() || instance.gameVersion in gameVersions) {
            "世界包不支持实例的游戏版本 ${instance.gameVersion}"
        }
    }

    /** 世界包只接受 ZIP，并沿用现有的大小与摘要校验规则。 */
    private fun resolveFile(
        hit: ContentHit,
        versionId: String,
        version: JsonObject,
    ): WorldPackFile {
        val file =
            version
                .array("files")
                .map { it.asJsonObject }
                .let { files -> files.firstOrNull { it.boolean("primary") } ?: files.firstOrNull() }
                ?: error("世界包没有可下载文件")
        val filename = safeFilename(file.string("filename"))
        require(filename.endsWith(".zip", true)) { "世界包必须为 ZIP 压缩包：$filename" }
        if (version.boolean("manual_download")) {
            throw ManualContentRequired(
                listOf(ManualContentFile(hit.id, versionId, filename, version.string("website"))),
            )
        }
        val size = file.get("size")?.asLong ?: -1
        val hash512 = file.obj("hashes").string("sha512")
        val hash1 = file.obj("hashes").string("sha1")
        require(
            size in 1..MAX_WORLD_DOWNLOAD_BYTES &&
                (hash512.isBlank() || hash512.matches(Regex("[a-fA-F0-9]{128}"))) &&
                (hash1.isBlank() || hash1.matches(Regex("[a-fA-F0-9]{40}"))) &&
                (hash512.isNotBlank() || hash1.isNotBlank()),
        ) {
            "世界包缺少有效大小或校验信息，或文件超过 8 GB"
        }
        return WorldPackFile(file, filename, size, hash512, hash1)
    }

    private suspend fun downloadAndInstall(
        hit: ContentHit,
        selectedFile: WorldPackFile,
        instance: GameInstance,
        context: JobContext,
    ) {
        val cache = store.temporaryDirectory.resolve("world-staging").apply { createDirectories() }
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
                    if (total > 0) {
                        WORLD_DOWNLOAD_PROGRESS_END * bytes.toFloat() / total
                    } else {
                        0f
                    },
                    "下载世界包 · ${bytes / BYTES_PER_MEGABYTE} MB",
                )
            }
            context.checkCancelled()
            installArchive(download, hit, instance, context)
        } finally {
            Files.deleteIfExists(download)
        }
    }

    /** 校验压缩包结构后解压到 `saves/` 的临时目录，成功才改名就位；失败删除临时目录。 */
    private suspend fun installArchive(
        archive: Path,
        hit: ContentHit,
        instance: GameInstance,
        context: JobContext,
    ) = withContext(Dispatchers.IO) {
        // 下载期间游戏可能被启动：写存档目录前必须再确认一次。
        requireSaveGameStopped(store, instance.id)
        val layout = SaveArchive.inspect(archive)
        val saves = savesDirectoryOf(store, instance.id)
        require(!isLinkPath(saves)) { "实例的存档目录是符号链接或目录联接，无法安装世界" }
        saves.createDirectories()
        val staging = saveStagingPath(saves)
        withSaveCleanup({ runCatching { deleteSaveTree(staging) } }) {
            extractWorldPack(archive, staging, layout.prefix, context)
            requireWorldLevelDat(staging)
            val name = worldPackName(layout, staging, hit.title, archive.name)
            val target = saveTargetPath(saves, name)
            require(!target.exists(LinkOption.NOFOLLOW_LINKS)) {
                "存档里已有同名世界「$name」，未安装；请先删除或重命名该世界"
            }
            requireSaveGameStopped(store, instance.id)
            commitSavePath(staging, target)
            context.progress(1f, "世界已安装到 saves/$name")
            store.log("世界安装完成：${instance.name} / $name ← ${hit.title}")
        }
    }
}

/** 世界包下载文件：规范化后的文件记录与校验信息。 */
private data class WorldPackFile(
    val file: JsonObject,
    val filename: String,
    val size: Long,
    val hash512: String,
    val hash1: String,
)
