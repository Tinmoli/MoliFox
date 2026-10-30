// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责本地内容文件的更新检查：识别来源、查询新版本并生成更新报告。

package dev.molifox.core

import com.google.gson.JsonObject
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

private const val REGISTRY_FILE_NAME = ".molifox-content.json"
private const val FINGERPRINT_LOOKUP_BATCH_SIZE = 100

private val MOD_CONTENT_PATTERN = Regex("(?i).*\\.jar(?:\\.disabled)?")
private val PACK_CONTENT_PATTERN = Regex("(?i).*\\.zip")

/** 待检查的本地内容文件；folder 决定内容类型与来源查询方式。 */
private class LocalContentFile(
    val folder: String,
    val filename: String,
    val hash: String,
)

/** 指纹识别得到的当前版本与可更新版本（键均为文件 SHA-512）。 */
private class ContentRecognition(
    val recognized: JsonObject,
    val latest: JsonObject,
)

/** 只读检查本地内容更新；识别不落盘，锁定与安装仍由用户确认。 */
internal class ContentUpdateChecker(
    private val store: LauncherStore,
    private val network: NetworkClient,
    private val api: String,
    private val backend: ContentBackend,
    private val curseForge: CurseForgeBackend?,
) {
    suspend fun check(
        instanceId: String,
        includePrerelease: Boolean,
    ): ContentUpdateReport {
        val instance = store.instance(instanceId)
        val root = store.instancePath(instanceId)
        val registryFile = root.resolve(REGISTRY_FILE_NAME)
        val registryHash = if (registryFile.exists()) digest(registryFile) else ""
        val registry = if (registryFile.exists()) readObject(registryFile) else JsonObject()
        val local = collectLocal(root)
        val useCurseForge = store.data.settings.contentDownloadSource == "curseforge"
        val recognition =
            if (useCurseForge) {
                recognizeCurseForge(local, root, instance, includePrerelease)
            } else {
                recognizeModrinth(local, instance, includePrerelease)
            }
        val items =
            buildItems(local, recognition, registry, instance, useCurseForge, includePrerelease)
        require(
            contentSignature(store, store.instance(instanceId)) ==
                contentSignature(store, instance) &&
                (if (registryFile.exists()) digest(registryFile) else "") == registryHash,
        ) {
            "检查期间实例或资源列表已改变，请重试"
        }
        return ContentUpdateReport(
            instanceId,
            contentSignature(store, instance),
            registryHash,
            items,
        )
    }

    private suspend fun collectLocal(root: Path): List<LocalContentFile> {
        val local = mutableListOf<LocalContentFile>()
        for (folder in listOf("mods", "resourcepacks", "shaderpacks")) {
            val directory = root.resolve(folder)
            if (!directory.exists()) continue
            for (file in directory.listDirectoryEntries().sortedBy { it.name }) {
                currentCoroutineContext().ensureActive()
                if (!isContentFile(file, folder)) continue
                local += LocalContentFile(folder, file.name, digest(file, "SHA-512"))
            }
        }
        return local
    }

    /** 只接受与内容目录匹配的 jar / zip；符号链接与普通文件之外的条目一律忽略。 */
    private fun isContentFile(
        file: Path,
        folder: String,
    ): Boolean {
        if (!file.isRegularFile() || Files.isSymbolicLink(file)) return false
        val pattern = if (folder == "mods") MOD_CONTENT_PATTERN else PACK_CONTENT_PATTERN
        return pattern.matches(file.name)
    }

    private suspend fun recognizeCurseForge(
        local: List<LocalContentFile>,
        root: Path,
        instance: GameInstance,
        includePrerelease: Boolean,
    ): ContentRecognition {
        val provider = requireNotNull(curseForge) { "CurseForge 内容源未初始化" }
        val recognized = JsonObject()
        val latest = JsonObject()
        val matches =
            provider.fingerprints(
                local.map { safePath(root.resolve(it.folder), it.filename) },
            )
        val available = mutableMapOf<String, List<JsonObject>>()
        for (file in local) {
            val matched =
                matches[safePath(root.resolve(file.folder), file.filename)] ?: continue
            val projectId = "curseforge:${matched.string("modId")}"
            val current = backend.version("$projectId:${matched.string("id")}")
            recognized.add(file.hash, current)
            val kind = contentKind(file.folder)
            val versions =
                available[projectId]
                    ?: backend.versions(projectId, instance, kind).also {
                        available[projectId] = it
                    }
            versions
                .firstOrNull {
                    contentCompatible(it, instance, kind) &&
                        (includePrerelease || it.string("version_type") == "release")
                }?.let { latest.add(file.hash, it) }
        }
        return ContentRecognition(recognized, latest)
    }

    /** 限制单次请求的指纹数量；某一批失败不能被当成「没有更新」。 */
    private suspend fun recognizeModrinth(
        local: List<LocalContentFile>,
        instance: GameInstance,
        includePrerelease: Boolean,
    ): ContentRecognition {
        val recognized = JsonObject()
        val latest = JsonObject()
        for (batch in local.map { it.hash }.distinct().chunked(FINGERPRINT_LOOKUP_BATCH_SIZE)) {
            val body =
                JsonObject().apply {
                    add("hashes", gson.toJsonTree(batch))
                    addProperty("algorithm", "sha512")
                }
            network.post("$api/version_files", body).entrySet().forEach { (key, value) ->
                recognized.add(key, value)
            }
            body.add(
                "loaders",
                gson.toJsonTree(
                    listOf(instance.loader.name.lowercase(), "minecraft", "iris", "optifine"),
                ),
            )
            body.add("game_versions", gson.toJsonTree(listOf(instance.gameVersion)))
            body.add("version_types", gson.toJsonTree(versionTypes(includePrerelease)))
            network.post("$api/version_files/update", body).entrySet().forEach { (key, value) ->
                latest.add(key, value)
            }
        }
        return ContentRecognition(recognized, latest)
    }

    private fun buildItems(
        local: List<LocalContentFile>,
        recognition: ContentRecognition,
        registry: JsonObject,
        instance: GameInstance,
        useCurseForge: Boolean,
        includePrerelease: Boolean,
    ): List<ContentUpdate> =
        local.map { file ->
            val current =
                recognition.recognized
                    .get(file.hash)
                    ?.takeIf { it.isJsonObject }
                    ?.asJsonObject
            val registered = registeredSource(registry, file)
            when {
                registered != null && registered.startsWith("curseforge:") != useCurseForge ->
                    ContentUpdate(
                        file.filename,
                        file.folder,
                        warning = "此文件登记在另一内容源，请切换下载来源后检查",
                    )
                current == null ->
                    ContentUpdate(file.filename, file.folder, warning = "未识别来源，无法确认更新")
                else ->
                    recognizedItem(
                        file,
                        current,
                        recognition,
                        registry,
                        instance,
                        includePrerelease,
                    )
            }
        }

    /** 注册表里登记该文件的来源键；同一目录与文件名只认一条记录。 */
    private fun registeredSource(
        registry: JsonObject,
        file: LocalContentFile,
    ): String? =
        registry
            .entrySet()
            .find {
                it.value.isJsonObject &&
                    it.value.asJsonObject.string("folder") == file.folder &&
                    it.value.asJsonObject.string("file") == contentBaseFilename(file.filename)
            }?.key

    private fun recognizedItem(
        file: LocalContentFile,
        current: JsonObject,
        recognition: ContentRecognition,
        registry: JsonObject,
        instance: GameInstance,
        includePrerelease: Boolean,
    ): ContentUpdate {
        val projectId = current.string("project_id")
        val record = registry.obj(projectId)
        val kind = contentKind(file.folder)
        val locked = isLocked(record, file, current)
        val source =
            ContentSource(
                projectId,
                current.string("id"),
                file.folder,
                file.filename,
                file.hash,
                current.string("name", projectId),
                kind,
                locked,
            )
        val candidate =
            recognition.latest
                .get(file.hash)
                ?.takeIf { it.isJsonObject }
                ?.asJsonObject
        val newer = newerVersion(candidate, current, projectId, instance, kind, includePrerelease)
        return ContentUpdate(
            file.filename,
            file.folder,
            source,
            current.string("version_number", current.string("name")),
            if (locked) null else newer?.let(::contentVersionOf),
            if (locked) "版本已锁定" else "",
        )
    }

    private fun isLocked(
        record: JsonObject,
        file: LocalContentFile,
        current: JsonObject,
    ): Boolean =
        record.boolean("locked") &&
            record.string("folder") == file.folder &&
            record.string("file") == contentBaseFilename(file.filename) &&
            record.string("versionId") == current.string("id")

    private fun newerVersion(
        candidate: JsonObject?,
        current: JsonObject,
        projectId: String,
        instance: GameInstance,
        kind: String,
        includePrerelease: Boolean,
    ): JsonObject? =
        candidate?.takeIf {
            it.string("project_id") == projectId &&
                it.string("id") != current.string("id") &&
                contentCompatible(it, instance, kind) &&
                (includePrerelease || it.string("version_type") == "release") &&
                runCatching {
                    Instant.parse(it.string("date_published")) >
                        Instant.parse(current.string("date_published"))
                }.getOrDefault(false)
        }
}

/** 更新查询接受的版本通道：默认只要正式版。 */
private fun versionTypes(includePrerelease: Boolean): List<String> =
    if (includePrerelease) {
        listOf(
            "release",
            "beta",
            "alpha",
        )
    } else {
        listOf("release")
    }

/** 内容目录对应的内容类型。 */
private fun contentKind(folder: String): String =
    when (folder) {
        "mods" -> "mod"
        "resourcepacks" -> "resourcepack"
        else -> "shader"
    }
