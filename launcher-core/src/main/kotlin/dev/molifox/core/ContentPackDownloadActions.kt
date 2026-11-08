// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责整合包与数据包下载。

package dev.molifox.core

import com.google.gson.JsonObject
import dev.molifox.core.i18n.I18n
import java.nio.file.Path
import kotlin.io.path.name

/** 整合包归档与数据包的下载入口。 */
internal interface ContentPackDownloadApi {
    suspend fun downloadPack(
        hit: ContentHit,
        context: JobContext,
        versionId: String = "",
    ): Path

    suspend fun installDatapack(
        hit: ContentHit,
        versionId: String,
        instanceId: String,
        worldName: String,
        context: JobContext,
    )

    /** 把项目版本的主文件下载到用户指定路径；不创建实例、不写资源登记表。 */
    suspend fun saveContentFile(
        hit: ContentHit,
        versionId: String,
        destination: Path,
        context: JobContext,
    )
}

private const val BYTES_PER_MEGABYTE = 1024 * 1024

private fun packDownloadProgress(
    bytes: Long,
    total: Long,
): Float = if (total > 0) bytes.toFloat() / total else 0f

/** 实现由 [ContentService] 委托调用；方法体与原实现一致。 */
internal abstract class ContentPackDownloadActions(
    core: ContentServiceCore,
) : ContentSearchActions(core),
    ContentPackDownloadApi {
    override suspend fun downloadPack(
        hit: ContentHit,
        context: JobContext,
        versionId: String,
    ): Path {
        require(hit.type == "modpack")
        context.progress(0f, I18n.t("contentA2.contentPackDownloadActions.readingVersion"))
        return if (hit.id.startsWith("curseforge:")) {
            downloadCurseForgePack(hit, context, versionId)
        } else {
            downloadModrinthPack(hit, context, versionId)
        }
    }

    private suspend fun downloadCurseForgePack(
        hit: ContentHit,
        context: JobContext,
        versionId: String,
    ): Path {
        val version = curseForgePackVersion(hit, versionId)
        requirePackVersion(hit, version, versionId)
        val file = version.array("files").first().asJsonObject
        requireManualContent(hit, version, file)
        val archive =
            safePath(
                store.downloadDirectory,
                "packs/curseforge-${curseForgeProjectId(
                    hit.id,
                )}/${safeFilename(file.string("filename"))}",
            )
        require(archive.name.endsWith(".zip", true)) {
            I18n.t("contentA2.contentPackDownloadActions.zipRequired")
        }
        downloadPackArchive(
            context,
            DownloadArtifact(
                contentFileUrl(curseForge.api, file.string("url")),
                archive,
                file.obj("hashes").string("sha1"),
                file.get("size").asLong,
            ),
        )
        return archive
    }

    private suspend fun curseForgePackVersion(
        hit: ContentHit,
        versionId: String,
    ): JsonObject {
        val available =
            if (versionId.isBlank()) {
                curseForge.available(hit.id)
            } else {
                listOf(backend.version(versionId))
            }
        return available.firstOrNull { it.string("version_type") == "release" }
            ?: available.firstOrNull()
            ?: error(I18n.t("contentA2.contentPackDownloadActions.noVersion"))
    }

    private suspend fun downloadModrinthPack(
        hit: ContentHit,
        context: JobContext,
        versionId: String,
    ): Path {
        val version = modrinthPackVersion(hit, versionId)
        requirePackVersion(hit, version, versionId)
        val file =
            version
                .array("files")
                .map { it.asJsonObject }
                .find { it.string("filename").endsWith(".mrpack") }
                ?: error(I18n.t("contentA2.contentPackDownloadActions.mrpackMissing"))
        val archive =
            safePath(
                safePath(store.downloadDirectory, "packs/${safeFilename(hit.id)}"),
                safeFilename(file.string("filename")),
            )
        val (sha512, sha1) = packFileDigest(file)
        downloadPackArchive(
            context,
            DownloadArtifact(
                contentFileUrl(api, file.string("url")),
                archive,
                sha512.ifBlank { sha1 },
                file.get("size")?.asLong ?: 0,
                if (sha512.isNotBlank()) "SHA-512" else "SHA-1",
            ),
        )
        return archive
    }

    private suspend fun modrinthPackVersion(
        hit: ContentHit,
        versionId: String,
    ): JsonObject {
        val available =
            if (versionId.isNotBlank()) {
                listOf(backend.version(versionId))
            } else {
                network.json("$api/project/${encoded(hit.id)}/version").asJsonArray.map {
                    it.asJsonObject
                }
            }
        return available
            .sortedByDescending { it.string("date_published") }
            .let { all ->
                all.firstOrNull { it.string("version_type") == "release" } ?: all.firstOrNull()
            } ?: error(I18n.t("contentA2.contentPackDownloadActions.noVersion"))
    }

    /** 优先使用 SHA-512，缺失时退回 SHA-1；两者都无效则拒绝下载。 */
    private fun packFileDigest(file: JsonObject): Pair<String, String> {
        val hashes = file.obj("hashes")
        val sha512 = hashes.string("sha512")
        val sha1 = hashes.string("sha1")
        require(
            (sha512.isBlank() || sha512.matches(Regex("[a-fA-F0-9]{128}"))) &&
                (sha1.isBlank() || sha1.matches(Regex("[a-fA-F0-9]{40}"))) &&
                (sha512.isNotBlank() || sha1.isNotBlank()),
        ) {
            I18n.t("contentA2.contentPackDownloadActions.checksumMissing")
        }
        return sha512 to sha1
    }

    private fun requireManualContent(
        hit: ContentHit,
        version: JsonObject,
        file: JsonObject,
    ) {
        if (!version.boolean("manual_download")) return
        throw ManualContentRequired(
            listOf(
                ManualContentFile(
                    hit.id,
                    version.string("id"),
                    file.string("filename"),
                    version.string("website"),
                ),
            ),
        )
    }

    private suspend fun downloadPackArchive(
        context: JobContext,
        artifact: DownloadArtifact,
    ) {
        network.downloadTracked(context, artifact) { bytes, total ->
            context.progress(
                packDownloadProgress(bytes, total),
                I18n.t("contentA2.contentPackDownloadActions.downloading", bytes / BYTES_PER_MEGABYTE),
            )
        }
    }

    private fun requirePackVersion(
        hit: ContentHit,
        version: JsonObject,
        versionId: String,
    ) {
        require(
            version.string("project_id") == hit.id &&
                (versionId.isBlank() || version.string("id") == versionId),
        ) {
            I18n.t("contentA2.contentPackDownloadActions.versionMismatch")
        }
    }

    override suspend fun saveContentFile(
        hit: ContentHit,
        versionId: String,
        destination: Path,
        context: JobContext,
    ) = ContentFileSaver(core).save(hit, versionId, destination, context)

    override suspend fun installDatapack(
        hit: ContentHit,
        versionId: String,
        instanceId: String,
        worldName: String,
        context: JobContext,
    ) = DatapackInstaller(
        store,
        network,
        backend,
        if (hit.id.startsWith("curseforge:")) curseForge.api else api,
    ).install(hit, versionId, instanceId, worldName, context)
}
