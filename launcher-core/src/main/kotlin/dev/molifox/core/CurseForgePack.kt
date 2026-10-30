// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责CurseForge 整合包清单解析与安装。

package dev.molifox.core

import com.google.gson.JsonObject
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
        info.string("author").let { if (it.isBlank()) "CurseForge 整合包" else "作者：$it" },
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
        "不支持的 CurseForge 整合包清单版本"
    }
    require(
        info.string("name").isNotBlank() &&
            info.obj("minecraft").string("version").isNotBlank() &&
            info.get("files")?.isJsonArray == true,
    ) {
        "CurseForge 整合包缺少名称、游戏版本或文件清单"
    }
    val loaders = info.obj("minecraft").array("modLoaders").map { it.asJsonObject }
    require(loaders.size <= 1) { "CurseForge 整合包包含多个加载器，暂不支持此组合" }
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
        "整合包使用了不支持的加载器：$loader"
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
        "CurseForge overrides 目录无效"
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
    require(paths.distinct().size == paths.size) { "整合包覆盖目录包含重复路径" }
    val pathSet = paths.toSet()
    for (path in paths) {
        var parent = path.substringBeforeLast('/', "")
        while (parent.isNotBlank()) {
            require(parent !in pathSet) { "整合包覆盖目录存在文件和目录冲突" }
            parent = parent.substringBeforeLast('/', "")
        }
    }
}

private fun validatePackFiles(info: JsonObject): List<JsonObject> {
    val files = info.array("files").map { it.asJsonObject }
    require(files.size <= MAX_CURSEFORGE_FILE_COUNT) { "整合包文件过多" }
    for (file in files) {
        curseForgeVersionId(curseForgePackKey(file))
        require(
            file.get("required")?.isJsonPrimitive == true &&
                file.getAsJsonPrimitive("required").isBoolean,
        ) {
            "CurseForge 文件缺少 required 布尔标记"
        }
    }
    require(files.map { it.string("projectID") }.distinct().size == files.size) {
        "整合包包含重复项目或多个文件版本"
    }
    return files
}

private fun curseForgePackKey(file: JsonObject) =
    "curseforge:${file.string("projectID")}:${file.string("fileID")}"

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
    require(kind in setOf("mod", "resourcepack", "shader")) { "整合包包含不支持的项目类型：$kind" }
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
        "整合包中的文件与游戏版本或加载器不兼容：$versionId"
    }
}

private fun resolvePackTarget(
    kind: String,
    info: JsonObject,
): CurseForgePackTarget {
    val file = info.array("files").first().asJsonObject
    val filename = safeFilename(file.string("filename"))
    require(filename.endsWith(if (kind == "mod") ".jar" else ".zip", true)) {
        "整合包文件扩展名无效：$filename"
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
    context.progress(0f, "批量读取 CurseForge 文件清单…")
    val metadata = backend.packMetadata(files.map(::curseForgePackKey))
    for ((position, entry) in files.withIndex()) {
        context.checkCancelled()
        context.progress(0f, "读取 CurseForge 清单 ${position + 1}/${files.size}")
        val file =
            resolvePackEntry(manifest, entry, metadata, manualDirectory, existingRoot, missing)
        if (file != null) resolved += file
    }
    require(resolved.map { it.path.lowercase() }.distinct().size == resolved.size) {
        "整合包中多个项目使用同名文件"
    }
    if (missing.isNotEmpty()) {
        throw ManualContentRequired(missing, "请将文件按原文件名放到 $manualDirectory 后重试导入。")
    }
    return resolved
}

internal suspend fun stageCurseForgePackFiles(
    files: List<CurseForgePackFile>,
    transaction: PackFileTransaction,
    context: JobContext,
    download: suspend (DownloadArtifact) -> Unit,
) {
    for ((position, file) in files.withIndex()) {
        context.checkCancelled()
        context.progress(
            position.toFloat() / maxOf(files.size, 1),
            "整合包文件 ${position + 1}/${files.size} · ${file.path}",
        )
        val target = packPath(transaction.content, file.path)
        if (file.local != null) {
            require(file.local.isRegularFile() && !Files.isSymbolicLink(file.local)) {
                "手动下载文件已改变：${file.local.name}"
            }
            target.parent.createDirectories()
            Files.copy(file.local, target, StandardCopyOption.REPLACE_EXISTING)
        } else {
            download(requireNotNull(file.artifact).copy(target = target))
        }
        require(target.fileSize() == file.size && digest(target).equals(file.hash, true)) {
            "整合包文件大小或 SHA-1 校验失败：${file.path}"
        }
    }
}
