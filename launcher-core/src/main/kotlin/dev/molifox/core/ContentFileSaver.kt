// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责“另存为”主文件下载：只写用户指定路径，不进入实例安装事务。

package dev.molifox.core

import dev.molifox.core.i18n.I18n
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.name

private const val BYTES_PER_MEGABYTE = 1024 * 1024

internal class ContentFileSaver(
    private val core: ContentServiceCore,
) {
    suspend fun save(
        hit: ContentHit,
        versionId: String,
        destination: Path,
        context: JobContext,
    ) {
        require(versionId.isNotBlank()) { I18n.t("contentA2.contentPackDownloadActions.noVersion") }
        context.progress(0f, I18n.t("contentA2.contentPackDownloadActions.readingVersion"))
        val version = core.backend.version(versionId)
        requireVersion(hit, version, versionId)
        val file = primaryFile(version)
        requireManualContent(hit, version, file)
        val sha512 = file.obj("hashes").string("sha512")
        val sha1 = file.obj("hashes").string("sha1")
        require(
            sha512.matches(Regex("[a-fA-F0-9]{128}")) ||
                sha1.matches(Regex("[a-fA-F0-9]{40}")),
        ) {
            I18n.t("contentA2.contentPackDownloadActions.checksumMissing")
        }
        val target = destination.toAbsolutePath().normalize()
        target.parent?.createDirectories()
        val source = if (hit.id.startsWith("curseforge:")) core.curseForge.api else core.api
        core.network.downloadTracked(
            context,
            DownloadArtifact(
                contentFileUrl(source, file.string("url")),
                target,
                sha512.ifBlank { sha1 },
                file.get("size")?.asLong ?: 0,
                if (sha512.isNotBlank()) "SHA-512" else "SHA-1",
            ),
        ) { bytes, total ->
            context.progress(
                if (total > 0) bytes.toFloat() / total else 0f,
                I18n.t("contentA2.contentPackDownloadActions.saving", bytes / BYTES_PER_MEGABYTE),
            )
        }
        context.progress(1f, I18n.t("contentA2.contentPackDownloadActions.saved", target.name))
    }

    private fun primaryFile(version: com.google.gson.JsonObject) =
        version
            .array("files")
            .map { it.asJsonObject }
            .firstOrNull { it.boolean("primary") }
            ?: version.array("files").firstOrNull()?.asJsonObject
            ?: error(I18n.t("contentA2.contentPackDownloadActions.fileMissing"))

    private fun requireVersion(
        hit: ContentHit,
        version: com.google.gson.JsonObject,
        versionId: String,
    ) {
        require(
            version.string("project_id") == hit.id &&
                (versionId.isBlank() || version.string("id") == versionId),
        ) {
            I18n.t("contentA2.contentPackDownloadActions.versionMismatch")
        }
    }

    private fun requireManualContent(
        hit: ContentHit,
        version: com.google.gson.JsonObject,
        file: com.google.gson.JsonObject,
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
}
