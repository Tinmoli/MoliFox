// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责世界包下载、校验与安装到实例的 saves/。

package dev.molifox.core

import com.google.gson.JsonObject
import dev.molifox.core.i18n.I18n
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
        require(hit.type == "world") { I18n.t("packsB2.worldPack.notWorldProject") }
        val instance = store.instance(instanceId)
        require(instance.installed) { I18n.t("packsB2.worldPack.instanceNotInstalled") }
        require(versionId.isNotBlank()) { I18n.t("packsB2.worldPack.versionRequired") }
        context.progress(0f, I18n.t("packsB2.worldPack.readingVersion"))
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
            I18n.t("packsB2.worldPack.versionNotInProject")
        }
        val gameVersions = version.array("game_versions").map { it.asString }
        require(gameVersions.isEmpty() || instance.gameVersion in gameVersions) {
            I18n.t("packsB2.worldPack.versionUnsupported", instance.gameVersion)
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
                ?: error(I18n.t("packsB2.worldPack.noDownloadableFile"))
        val filename = safeFilename(file.string("filename"))
        require(filename.endsWith(".zip", true)) {
            I18n.t("packsB2.worldPack.notZip", filename)
        }
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
            I18n.t("packsB2.worldPack.invalidSizeOrHash")
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
                    I18n.t("packsB2.worldPack.downloadProgress", bytes / BYTES_PER_MEGABYTE),
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
        require(!isLinkPath(saves)) { I18n.t("packsB2.worldPack.savesIsLink") }
        saves.createDirectories()
        val staging = saveStagingPath(saves)
        withSaveCleanup({ runCatching { deleteSaveTree(staging) } }) {
            extractWorldPack(archive, staging, layout.prefix, context)
            requireWorldLevelDat(staging)
            val name = worldPackName(layout, staging, hit.title, archive.name)
            val target = saveTargetPath(saves, name)
            require(!target.exists(LinkOption.NOFOLLOW_LINKS)) {
                I18n.t("packsB2.worldPack.duplicateSave", name)
            }
            requireSaveGameStopped(store, instance.id)
            commitSavePath(staging, target)
            context.progress(1f, I18n.t("packsB2.worldPack.installedAt", name))
            store.log(I18n.t("packsB2.worldPack.installLogged", instance.name, name, hit.title))
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
