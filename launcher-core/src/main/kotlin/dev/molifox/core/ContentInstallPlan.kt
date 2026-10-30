// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责内容安装前置依赖和目标文件计划。

package dev.molifox.core

import com.google.gson.JsonObject
import java.net.URI
import kotlin.io.path.exists
import kotlin.io.path.name

data class OptionalContent(
    val id: String,
    val title: String,
    val selected: Boolean,
)

data class PlannedContentFile(
    val projectId: String,
    val title: String,
    val versionId: String,
    val filename: String,
    val folder: String,
    val url: String,
    val hash: String,
    val algorithm: String,
    val size: Long,
    val iconUrl: String = "",
)

data class ContentInstallPlan(
    val instanceId: String,
    val title: String,
    val signature: String,
    val registryHash: String,
    val files: List<PlannedContentFile>,
    val optional: List<OptionalContent>,
    val sources: List<ContentSource> = emptyList(),
)

internal data class ContentRequest(
    val hit: ContentHit,
    val versionId: String = "",
)

internal fun contentSignature(
    store: LauncherStore,
    instance: GameInstance,
) = "${instance.gameVersion}|${instance.loader}|${instance.versionId}|${store.instancePath(
    instance.id,
).toAbsolutePath().normalize()}"

internal fun contentFileUrl(
    api: String,
    url: String,
): String {
    val source = URI(api)
    val target = URI(url)
    // The configured CurseForge mirror/proxy may serve files itself; official endpoints still
    // fall through to the CDN allow-list. Explicit local test servers are accepted as well.
    val configuredService = source.host !in setOf("api.modrinth.com", "api.curseforge.com")
    if (
        target.scheme == source.scheme &&
        target.host == source.host &&
        target.port == source.port &&
        (configuredService || source.host == "127.0.0.1")
    ) {
        return url
    }
    return validatePackUrl(url)
}

internal class ContentInstallPlanner(
    private val store: LauncherStore,
    private val network: NetworkClient,
    private val api: String,
    private val backend: ContentBackend = ModrinthBackend(network, api),
) {
    suspend fun plan(
        hit: ContentHit,
        instanceId: String,
        selectedOptional: Set<String>,
        exactVersion: String = "",
        sources: List<ContentSource> = emptyList(),
    ): ContentInstallPlan =
        planMany(listOf(ContentRequest(hit, exactVersion)), instanceId, selectedOptional, sources)

    suspend fun planMany(
        requests: List<ContentRequest>,
        instanceId: String,
        selectedOptional: Set<String>,
        sources: List<ContentSource> = emptyList(),
    ): ContentInstallPlan {
        require(
            requests.isNotEmpty() && requests.map { it.hit.id }.distinct().size == requests.size,
        ) {
            "同一项目有多个本地版本，请先整理"
        }
        require(sources.map { it.projectId }.distinct().size == sources.size) { "同一项目有多个本地文件，请先整理" }
        val instance = store.instance(instanceId)
        require(instance.installed) { "请先完成游戏实例安装" }
        val root = store.instancePath(instanceId)
        val registryFile = root.resolve(".molifox-content.json")
        val registry = if (registryFile.exists()) readObject(registryFile) else JsonObject()
        val registryHash = if (registryFile.exists()) digest(registryFile) else ""
        for (source in sources) {
            verifyContentSource(store, instanceId, source)
            require(
                !registry.obj(source.projectId).boolean("locked") ||
                    registry.obj(source.projectId).string("versionId") == source.versionId,
            ) {
                "锁定版本与本地文件不一致，请先整理或解锁"
            }
            require(
                registry.obj(source.projectId).string("file").let {
                    it.isBlank() || it == contentBaseFilename(source.filename)
                },
            ) {
                "同一项目有多个文件，请先整理"
            }
            registry.add(
                source.projectId,
                contentSourceRecord(
                    source.copy(
                        locked = source.locked || registry.obj(source.projectId).boolean("locked"),
                    ),
                ),
            )
        }
        val projects = mutableMapOf<String, JsonObject>()
        val versions = mutableMapOf<String, JsonObject>()
        val latest = mutableMapOf<String, JsonObject>()

        suspend fun project(id: String) =
            projects.getOrPutSuspend(id) {
                backend.project(id)
            }

        suspend fun version(id: String) =
            versions.getOrPutSuspend(id) {
                backend.version(id)
            }
        val initial =
            requests.filter { it.versionId.isNotBlank() }.associate { it.hit.id to it.versionId }
        var guesses = initial
        repeat(40) {
            val required = initial.toMutableMap()
            val chosen = linkedMapOf<String, JsonObject>()
            val files = linkedMapOf<String, PlannedContentFile>()
            val optional = linkedMapOf<String, OptionalContent>()
            val incompatible = mutableListOf<Pair<String, String>>()
            val manual = linkedMapOf<String, ManualContentFile>()
            var changed = false

            suspend fun visit(
                id: String,
                kind: String,
                requested: String = "",
            ) {
                require(chosen.size < 100) { "资源依赖过多" }
                if (requested.isNotBlank()) {
                    val previous = required.putIfAbsent(id, requested)
                    require(previous == null || previous == requested) {
                        "同一依赖要求不同版本：$id ($previous / $requested)，请更换资源版本"
                    }
                }
                val previous = chosen[id]
                if (previous != null) {
                    if (requested.isNotBlank() && previous.string("id") != requested) changed = true
                    return
                }
                require(kind in setOf("mod", "resourcepack", "shader")) { "不支持作为前置安装的资源类型：$kind" }
                require(kind != "mod" || instance.loader != LoaderType.Vanilla) {
                    "此资源需要模组加载器，请先选择带加载器的实例"
                }
                val lockedVersion =
                    registry
                        .obj(id)
                        .takeIf { it.boolean("locked") }
                        ?.string("versionId")
                        ?.takeIf { it.isNotBlank() }
                val pinned = required[id] ?: guesses[id] ?: lockedVersion
                require(lockedVersion == null || pinned == lockedVersion) {
                    "依赖或项目版本已锁定：$id，请先解锁再更换"
                }
                val info =
                    if (pinned != null) {
                        version(pinned)
                    } else {
                        latest.getOrPutSuspend(id) {
                            val available =
                                backend.versions(id, instance, kind).filter {
                                    it.array("game_versions").any {
                                        it.asString == instance.gameVersion
                                    } &&
                                        (
                                            kind != "mod" ||
                                                it.array("loaders").any {
                                                    it.asString.equals(instance.loader.name, true)
                                                }
                                        )
                                }
                            available.firstOrNull { it.string("version_type") == "release" }
                                ?: available.firstOrNull()
                                ?: error("资源 $id 没有兼容当前游戏的版本")
                        }
                    }
                require(info.string("project_id") == id) { "资源版本与项目不匹配：$id" }
                require(
                    info.array("game_versions").any { it.asString == instance.gameVersion } &&
                        (
                            kind != "mod" ||
                                info.array("loaders").any {
                                    it.asString.equals(instance.loader.name, true)
                                }
                        ),
                ) {
                    "${info.string("name")} 与当前游戏版本或加载器不兼容"
                }
                chosen[id] = info
                for (entry in info.array("dependencies")) {
                    val dependency = entry.asJsonObject
                    val relation = dependency.string("dependency_type")
                    if (relation == "embedded") continue
                    val dependencyVersion = dependency.string("version_id")
                    val dependencyId =
                        dependency.string("project_id").ifBlank {
                            if (dependencyVersion.isNotBlank()) {
                                version(dependencyVersion).string("project_id")
                            } else {
                                ""
                            }
                        }
                    if (relation == "incompatible") {
                        if (dependencyId.isNotBlank()) {
                            incompatible += dependencyId to dependencyVersion
                        }
                        continue
                    }
                    if (dependencyId.isBlank()) {
                        require(relation != "required") {
                            "此资源需要手动提供外部依赖：${dependency.string("file_name")}"
                        }
                        continue
                    }
                    val details = project(dependencyId)
                    if (relation == "optional") {
                        optional[dependencyId] =
                            OptionalContent(
                                dependencyId,
                                details.string("title", dependencyId),
                                dependencyId in selectedOptional,
                            )
                        if (dependencyId !in selectedOptional) continue
                    } else if (relation != "required") {
                        continue
                    }
                    visit(dependencyId, details.string("project_type"), dependencyVersion)
                }
                val availableFiles = info.array("files").map { it.asJsonObject }
                val file =
                    availableFiles.firstOrNull { it.boolean("primary") }
                        ?: availableFiles.firstOrNull()
                        ?: error("资源没有可下载文件")
                val filename = safeFilename(file.string("filename"))
                if (info.boolean("manual_download")) {
                    manual[id] =
                        ManualContentFile(id, info.string("id"), filename, info.string("website"))
                    return
                }
                require(kind != "mod" || filename.endsWith(".jar", true)) { "模组文件必须是 JAR" }
                val hashes = file.obj("hashes")
                val sha512 = hashes.string("sha512")
                val hash = sha512.ifBlank { hashes.string("sha1") }
                require(
                    hash.matches(
                        Regex(if (sha512.isNotBlank()) "[a-fA-F0-9]{128}" else "[a-fA-F0-9]{40}"),
                    ),
                ) {
                    "资源文件缺少有效校验信息"
                }
                val folder =
                    when (kind) {
                        "shader" -> "shaderpacks"
                        "resourcepack" -> "resourcepacks"
                        else -> "mods"
                    }
                files[id] =
                    PlannedContentFile(
                        id,
                        info.string("name"),
                        info.string("id"),
                        filename,
                        folder,
                        contentFileUrl(api, file.string("url")),
                        hash,
                        if (sha512.isNotBlank()) "SHA-512" else "SHA-1",
                        file.get("size")?.asLong ?: 0,
                        requests.firstOrNull { it.hit.id == id }?.hit?.iconUrl
                            ?: projects[id]?.string("icon_url").orEmpty(),
                    )
            }
            for (request in requests) visit(request.hit.id, request.hit.type, request.versionId)
            if (changed || required != guesses) {
                guesses = required.toMap()
                return@repeat
            }
            for ((id, versionId) in incompatible) {
                val planned = chosen[id]?.string("id")
                val installed = registry.obj(id).string("versionId").takeIf { it.isNotBlank() }
                require(
                    listOfNotNull(planned, installed).none {
                        versionId.isBlank() || it == versionId
                    },
                ) {
                    "资源与已安装或待安装项目冲突：$id${if (versionId.isBlank()) "" else " / $versionId"}，请先处理冲突"
                }
            }
            require(
                files.values
                    .map { "${it.folder}/${it.filename}" }
                    .distinct()
                    .size == files.size,
            ) {
                "不同项目包含同名文件，无法安全安装"
            }
            if (manual.isNotEmpty()) throw ManualContentRequired(manual.values.toList())
            return ContentInstallPlan(
                instanceId,
                if (requests.size == 1) requests.first().hit.title else "更新 ${requests.size} 项资源",
                contentSignature(store, instance),
                registryHash,
                files.values.toList(),
                optional.values.toList(),
                sources.filter { it.projectId in files },
            )
        }
        error("依赖版本无法收敛，请选择其他资源版本")
    }
}

private suspend fun <K, V> MutableMap<K, V>.getOrPutSuspend(
    key: K,
    value: suspend () -> V,
): V = get(key) ?: value().also { put(key, it) }
