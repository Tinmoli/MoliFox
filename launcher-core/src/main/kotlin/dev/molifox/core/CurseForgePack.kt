// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责CurseForge 整合包清单解析与安装。

package dev.molifox.core

import com.google.gson.JsonObject
import dev.molifox.core.i18n.I18n
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.zip.ZipFile
import kotlin.io.path.createDirectories
import kotlin.io.path.fileSize
import kotlin.io.path.isRegularFile
import kotlin.io.path.isSymbolicLink
import kotlin.io.path.name

/** CurseForge 整合包允许的文件条目上限。 */
private const val MAX_CURSEFORGE_FILE_COUNT = 20000

internal fun readCurseForgeManifest(
    info: JsonObject,
    zip: ZipFile,
    root: Path,
    os: String,
): PackManifest {
    val header = readCurseForgeHeader(info)
    requireValidOverrideEntries(zip, info, root, os)
    val files = validatePackFiles(info)
    return PackManifest(
        null,
        InstallRequest(
            info.string("name"),
            info.obj("minecraft").string("version"),
            header.loaderType,
            header.loaderVersion,
        ),
        files.filter { !it.boolean("required") }.map(::curseForgePackKey),
        files.count { it.boolean("required") },
        info.string("version"),
        info.string("author").let {
            if (it.isBlank()) {
                I18n.t("packsB1.curseForgePack.defaultAuthor")
            } else {
                I18n.t("packsB1.curseForgePack.author", it)
            }
        },
        info,
    )
}

/** 清单头部校验得到的加载器信息。 */
private data class CurseForgeHeader(
    val loaderType: LoaderType,
    val loaderVersion: String,
)

private fun readCurseForgeHeader(info: JsonObject): CurseForgeHeader {
    require(
        info.string("manifestType") == "minecraftModpack" && info.string("manifestVersion") == "1",
    ) {
        I18n.t("packsB1.curseForgePack.unsupportedManifestVersion")
    }
    require(
        info.string("name").isNotBlank() &&
            info.obj("minecraft").string("version").isNotBlank() &&
            info.get("files")?.isJsonArray == true,
    ) {
        I18n.t("packsB1.curseForgePack.missingManifestFields")
    }
    val loaders = info.obj("minecraft").array("modLoaders").map { it.asJsonObject }
    require(loaders.size <= 1) { I18n.t("packsB1.curseForgePack.multipleLoaders") }
    val loader = loaders.firstOrNull()?.string("id").orEmpty()
    val types =
        mapOf(
            "forge" to LoaderType.Forge,
            "fabric" to LoaderType.Fabric,
            "quilt" to LoaderType.Quilt,
            "neoforge" to LoaderType.NeoForge,
        )
    require(
        loader.isBlank() ||
            loader.substringBefore('-') in types && loader.substringAfter('-', "").isNotBlank(),
    ) {
        I18n.t("packsB1.curseForgePack.unsupportedLoader", loader)
    }
    return CurseForgeHeader(
        types[loader.substringBefore('-')] ?: LoaderType.Vanilla,
        loader.substringAfter('-', ""),
    )
}

/** 校验 overrides 目录并检查覆盖文件之间没有重复路径或文件/目录冲突。 */
private fun requireValidOverrideEntries(
    zip: ZipFile,
    info: JsonObject,
    root: Path,
    os: String,
) {
    val overrides = info.string("overrides")
    require(
        overrides.isNotBlank() &&
            !overrides.endsWith('/') &&
            !overrides.contains('\\') &&
            overrides.split('/').none { it.isBlank() || it == "." || it == ".." },
    ) {
        I18n.t("packsB1.curseForgePack.invalidOverrides")
    }
    safePath(root, overrides)
    val paths =
        validateArchive(zip, "$overrides/", root).map { (_, path) ->
            val relative =
                root
                    .toAbsolutePath()
                    .normalize()
                    .relativize(path)
                    .joinToString("/")
            packPath(root, relative)
            if (os == "windows") relative.lowercase() else relative
        }
    require(paths.distinct().size == paths.size) { I18n.t("packsB1.curseForgePack.duplicateOverridePaths") }
    val pathSet = paths.toSet()
    for (path in paths) {
        var parent = path.substringBeforeLast('/', "")
        while (parent.isNotBlank()) {
            require(parent !in pathSet) { I18n.t("packsB1.curseForgePack.overridePathConflict") }
            parent = parent.substringBeforeLast('/', "")
        }
    }
}

private fun validatePackFiles(info: JsonObject): List<JsonObject> {
    val files = info.array("files").map { it.asJsonObject }
    require(files.size <= MAX_CURSEFORGE_FILE_COUNT) { I18n.t("packsB1.curseForgePack.tooManyFiles") }
    for (file in files) {
        curseForgeVersionId(curseForgePackKey(file))
        require(
            file.get("required")?.isJsonPrimitive == true &&
                file.getAsJsonPrimitive("required").isBoolean,
        ) {
            I18n.t("packsB1.curseForgePack.missingRequiredFlag")
        }
    }
    require(files.map { it.string("projectID") }.distinct().size == files.size) {
        I18n.t("packsB1.curseForgePack.duplicateProjects")
    }
    return files
}

private fun curseForgePackKey(file: JsonObject) = "curseforge:${file.string("projectID")}:${file.string("fileID")}"

internal data class CurseForgePackFile(
    val path: String,
    val artifact: DownloadArtifact?,
    val local: Path?,
    val hash: String,
    val size: Long,
)

/** 清单条目解析后的安装目标：文件名、实例内相对路径与校验信息。 */
private data class CurseForgePackTarget(
    val filename: String,
    val relative: String,
    val url: String,
    val hash: String,
    val size: Long,
)

private fun requireCompatiblePackEntry(
    manifest: PackManifest,
    versionId: String,
    info: JsonObject,
    kind: String,
) {
    require(kind in setOf("mod", "resourcepack", "shader")) {
        I18n.t("packsB1.curseForgePack.unsupportedKind", kind)
    }
    require(
        info.array("game_versions").any { it.asString == manifest.request.gameVersion } &&
            (
                kind != "mod" ||
                    manifest.request.loader != LoaderType.Vanilla &&
                    info.array("loaders").any {
                        it.asString.equals(manifest.request.loader.name, true)
                    }
            ),
    ) {
        I18n.t("packsB1.curseForgePack.incompatibleEntry", versionId)
    }
}

private fun resolvePackTarget(
    kind: String,
    info: JsonObject,
): CurseForgePackTarget {
    val file = info.array("files").first().asJsonObject
    val filename = safeFilename(file.string("filename"))
    require(filename.endsWith(if (kind == "mod") ".jar" else ".zip", true)) {
        I18n.t("packsB1.curseForgePack.invalidExtension", filename)
    }
    val folder =
        when (kind) {
            "mod" -> "mods"
            "resourcepack" -> "resourcepacks"
            else -> "shaderpacks"
        }
    return CurseForgePackTarget(
        filename,
        "$folder/$filename",
        file.string("url"),
        file.obj("hashes").string("sha1"),
        file.get("size").asLong,
    )
}

/**
 * 解析单个清单条目；需要手动下载且本地未命中时记入 [missing] 并返回 null。
 */
private fun resolvePackEntry(
    manifest: PackManifest,
    entry: JsonObject,
    metadata: Map<String, Pair<JsonObject, String>>,
    manualDirectory: Path,
    existingRoot: Path?,
    missing: MutableList<ManualContentFile>,
): CurseForgePackFile? {
    val versionId = curseForgePackKey(entry)
    val (info, kind) = metadata.getValue(versionId)
    requireCompatiblePackEntry(manifest, versionId, info, kind)
    val target = resolvePackTarget(kind, info)
    if (!info.boolean("manual_download")) {
        val artifact =
            DownloadArtifact(target.url, Path.of(target.relative), target.hash, target.size)
        return CurseForgePackFile(target.relative, artifact, null, target.hash, target.size)
    }
    return resolveManualPackEntry(
        entry,
        target,
        info,
        versionId,
        manualDirectory,
        existingRoot,
        missing,
    )
}

/** 手动下载条目：本地已有且校验通过就复用，否则记入缺失清单。 */
private fun resolveManualPackEntry(
    entry: JsonObject,
    target: CurseForgePackTarget,
    info: JsonObject,
    versionId: String,
    manualDirectory: Path,
    existingRoot: Path?,
    missing: MutableList<ManualContentFile>,
): CurseForgePackFile? {
    val local =
        listOfNotNull(
            safePath(manualDirectory, target.filename),
            existingRoot?.let { packPath(it, target.relative) },
        ).firstOrNull {
            it.isRegularFile() &&
                !Files.isSymbolicLink(it) &&
                it.fileSize() == target.size &&
                digest(it).equals(target.hash, true)
        }
    if (local == null) {
        missing +=
            ManualContentFile(
                "curseforge:${entry.string("projectID")}",
                versionId,
                target.filename,
                info.string("website"),
            )
    }
    return local?.let { CurseForgePackFile(target.relative, null, it, target.hash, target.size) }
}

/**
 * Resolve the entire file list, including manual files, before creating or modifying an instance.
 */
internal suspend fun prepareCurseForgePack(
    manifest: PackManifest,
    backend: CurseForgeBackend,
    selected: Set<String>?,
    manualDirectory: Path,
    context: JobContext,
    existingRoot: Path? = null,
): List<CurseForgePackFile> {
    val files =
        requireNotNull(manifest.curseForge)
            .array("files")
            .map { it.asJsonObject }
            .filter {
                it.boolean("required") || selected == null || curseForgePackKey(it) in selected
            }
    val missing = mutableListOf<ManualContentFile>()
    val resolved = mutableListOf<CurseForgePackFile>()
    context.progress(0f, I18n.t("packsB1.curseForgePack.readingFileList"))
    val metadata = backend.packMetadata(files.map(::curseForgePackKey))
    for ((position, entry) in files.withIndex()) {
        context.checkCancelled()
        context.progress(
            0f,
            I18n.t("packsB1.curseForgePack.readingManifest", position + 1, files.size),
        )
        val file =
            resolvePackEntry(manifest, entry, metadata, manualDirectory, existingRoot, missing)
        if (file != null) resolved += file
    }
    require(resolved.map { it.path.lowercase() }.distinct().size == resolved.size) {
        I18n.t("packsB1.curseForgePack.duplicateFilenames")
    }
    if (missing.isNotEmpty()) {
        throw ManualContentRequired(
            missing,
            I18n.t("packsB1.curseForgePack.manualFilesRequired", manualDirectory),
        )
    }
    return resolved
}

internal suspend fun stageCurseForgePackFiles(
    files: List<CurseForgePackFile>,
    transaction: PackFileTransaction,
    context: JobContext,
    progressStart: Float = 0f,
    progressEnd: Float = 1f,
    download: suspend (DownloadArtifact) -> Unit,
) {
    context.beginTransfers(files.size)
    for ((position, file) in files.withIndex()) {
        context.checkCancelled()
        val span = progressEnd - progressStart
        context.progress(
            progressStart + span * position.toFloat() / maxOf(files.size, 1),
            I18n.t("packsB1.curseForgePack.stagingFile", position + 1, files.size, file.path),
        )
        val target = packPath(transaction.content, file.path)
        if (file.local != null) {
            require(file.local.isRegularFile() && !Files.isSymbolicLink(file.local)) {
                I18n.t("packsB1.curseForgePack.manualFileChanged", file.local.name)
            }
            target.parent.createDirectories()
            Files.copy(file.local, target, StandardCopyOption.REPLACE_EXISTING)
        } else {
            download(requireNotNull(file.artifact).copy(target = target))
        }
        require(target.fileSize() == file.size && digest(target).equals(file.hash, true)) {
            I18n.t("packsB1.curseForgePack.hashMismatch", file.path)
        }
    }
}
