// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责内容依赖图的版本收敛与安装计划组装。

package dev.molifox.core

import com.google.gson.JsonObject
import dev.molifox.core.i18n.I18n

private const val MAX_DEPENDENCY_RESOLUTION_PASSES = 40
private const val MAX_CONTENT_DEPENDENCIES = 100
private const val SHA512_HEX_PATTERN = "[a-fA-F0-9]{128}"
private const val SHA1_HEX_PATTERN = "[a-fA-F0-9]{40}"

/**
 * 一批内容安装请求的不可变输入。
 *
 * 各项都在 [ContentInstallPlanner.planMany] 中校验通过后才装入本对象，收敛过程不再重复校验。
 */
internal data class ContentPlanBatch(
    val requests: List<ContentRequest>,
    val instanceId: String,
    val instance: GameInstance,
    val registry: JsonObject,
    val registryHash: String,
    val selectedOptional: Set<String>,
    val sources: List<ContentSource>,
)

/**
 * 解析内容依赖并收敛出版本稳定的安装计划。
 *
 * 每轮从 [MAX_DEPENDENCY_RESOLUTION_PASSES] 次迭代中重新开始依赖遍历，直到所需版本与本轮猜测一致；
 * 不兼容、缺失或被锁定的依赖通过 require / 异常报出，与调用方原有的失败语义一致。
 */
internal class ContentDependencyResolver(
    private val batch: ContentPlanBatch,
    private val store: LauncherStore,
    private val api: String,
    backend: ContentBackend,
) {
    private val initial =
        batch.requests.filter { it.versionId.isNotBlank() }.associate { it.hit.id to it.versionId }
    private val cache = ContentLookupCache(backend)
    private var guesses: Map<String, String> = initial

    suspend fun resolve(): ContentInstallPlan {
        repeat(MAX_DEPENDENCY_RESOLUTION_PASSES) {
            val plan = runPass()
            if (plan != null) return plan
        }
        error(I18n.t("core.contentDependencyResolver.1"))
    }

    /** 跑一轮依赖遍历；版本仍在变化时返回 null，由 [resolve] 进入下一轮。 */
    private suspend fun runPass(): ContentInstallPlan? {
        val state = ContentPassState(initial.toMutableMap())
        val visitor = PassVisitor(state)
        for (request in batch.requests) {
            visitor.visit(request.hit.id, request.hit.type, request.versionId)
        }
        if (state.changed || state.required != guesses) {
            guesses = state.required.toMap()
            return null
        }
        return settlePass(state)
    }

    /** 依赖版本不再变化后校验冲突并组装最终计划。 */
    private fun settlePass(state: ContentPassState): ContentInstallPlan {
        verifyIncompatible(state)
        requireUniqueFilenames(state)
        if (state.manual.isNotEmpty()) throw ManualContentRequired(state.manual.values.toList())
        return buildPlan(state)
    }

    private fun verifyIncompatible(state: ContentPassState) {
        for ((id, versionId) in state.incompatible) {
            val planned = state.chosen[id]?.string("id")
            val installed =
                batch.registry
                    .obj(id)
                    .string("versionId")
                    .takeIf { it.isNotBlank() }
            // 与不兼容版本相同才算冲突：计划里选的或已安装的任一版本等于它就要拦下。
            val conflict =
                listOfNotNull(planned, installed).any { versionId.isBlank() || it == versionId }
            require(!conflict) {
                I18n.t(
                    "contentA1.dependency.conflict",
                    if (versionId.isBlank()) id else "$id / $versionId",
                )
            }
        }
    }

    private fun requireUniqueFilenames(state: ContentPassState) {
        val names = state.files.values.map { "${it.folder}/${it.filename}" }
        require(names.distinct().size == state.files.size) { I18n.t("core.contentDependencyResolver.3") }
    }

    private fun buildPlan(state: ContentPassState): ContentInstallPlan {
        val title =
            if (batch.requests.size == 1) {
                batch.requests
                    .first()
                    .hit.title
            } else {
                I18n.t("core.contentDependencyResolver.4", batch.requests.size)
            }
        return ContentInstallPlan(
            batch.instanceId,
            title,
            contentSignature(store, batch.instance),
            batch.registryHash,
            state.files.values.toList(),
            state.optional.values.toList(),
            batch.sources.filter { it.projectId in state.files },
            dependencies = planDependencies(state.files.values),
        )
    }

    /** 除主项目外的文件视为依赖；登记表里的版本用于界面区分已安装与待更新。 */
    private fun planDependencies(files: Collection<PlannedContentFile>): List<PlannedDependency> {
        val roots = batch.requests.mapTo(mutableSetOf()) { it.hit.id }
        return files
            .filter { it.projectId !in roots }
            .map { file ->
                PlannedDependency(
                    file.projectId,
                    file.title,
                    file.versionId,
                    batch.registry.obj(file.projectId).string("versionId"),
                    file.filename,
                    file.folder,
                )
            }
    }

    /** 一轮依赖遍历的全部可变状态；每轮重建，依赖缓存留在 [cache] 里跨轮复用。 */
    private class ContentPassState(
        initial: MutableMap<String, String>,
    ) {
        val required: MutableMap<String, String> = initial
        val chosen: MutableMap<String, JsonObject> = linkedMapOf()
        val files: MutableMap<String, PlannedContentFile> = linkedMapOf()
        val optional: MutableMap<String, OptionalContent> = linkedMapOf()
        val incompatible: MutableList<Pair<String, String>> = mutableListOf()
        val manual: MutableMap<String, ManualContentFile> = linkedMapOf()
        var changed: Boolean = false
    }

    /** 按需拉取并缓存项目、版本与最新兼容版本，避免同一轮里重复请求。 */
    private inner class PassVisitor(
        private val state: ContentPassState,
    ) {
        suspend fun visit(
            id: String,
            kind: String,
            requested: String = "",
        ) {
            if (beginVisit(id, requested)) return
            requireSupported(kind)
            val info = resolveVersion(id, kind)
            state.chosen[id] = info
            collectDependencies(info)
            planFiles(id, kind, info)
        }

        /** 登记本轮要求并判断是否已经处理过；返回 true 表示本次访问可直接结束。 */
        private fun beginVisit(
            id: String,
            requested: String,
        ): Boolean {
            require(state.chosen.size < MAX_CONTENT_DEPENDENCIES) { I18n.t("core.contentDependencyResolver.5") }
            if (requested.isNotBlank()) {
                val previous = state.required.putIfAbsent(id, requested)
                require(previous == null || previous == requested) {
                    I18n.t("core.contentDependencyResolver.6", id, previous, requested)
                }
            }
            val chosen = state.chosen[id] ?: return false
            if (requested.isNotBlank() && chosen.string("id") != requested) state.changed = true
            return true
        }

        private fun requireSupported(kind: String) {
            require(kind in setOf("mod", "resourcepack", "shader")) {
                I18n.t("core.contentDependencyResolver.7", kind)
            }
            require(kind != "mod" || batch.instance.loader != LoaderType.Vanilla) {
                I18n.t("core.contentDependencyResolver.8")
            }
        }

        /** 取锁定版本或已选版本；都没有时取当前游戏与加载器兼容的最新版本。 */
        private suspend fun resolveVersion(
            id: String,
            kind: String,
        ): JsonObject {
            val lockedVersion =
                batch.registry
                    .obj(id)
                    .takeIf { it.boolean("locked") }
                    ?.string("versionId")
                    ?.takeIf { it.isNotBlank() }
            val pinned = state.required[id] ?: guesses[id] ?: lockedVersion
            require(lockedVersion == null || pinned == lockedVersion) {
                I18n.t("core.contentDependencyResolver.9", id)
            }
            val info =
                if (pinned != null) {
                    cache.version(pinned)
                } else {
                    cache.latestCompatible(id, kind, batch.instance)
                }
            require(info.string("project_id") == id) { I18n.t("core.contentDependencyResolver.10", id) }
            require(isCompatibleVersion(info, batch.instance, kind)) {
                I18n.t("core.contentDependencyResolver.11", info.string("name"))
            }
            return info
        }

        private suspend fun collectDependencies(info: JsonObject) {
            for (entry in info.array("dependencies")) {
                val dependency = entry.asJsonObject
                val relation = dependency.string("dependency_type")
                if (relation == "embedded") continue
                val dependencyVersion = dependency.string("version_id")
                val dependencyId = dependencyProjectId(dependency, dependencyVersion)
                if (relation == "incompatible") {
                    if (dependencyId.isNotBlank()) {
                        state.incompatible += dependencyId to dependencyVersion
                    }
                } else {
                    addDependency(dependency, relation, dependencyId, dependencyVersion)
                }
            }
        }

        private suspend fun dependencyProjectId(
            dependency: JsonObject,
            dependencyVersion: String,
        ): String =
            dependency.string("project_id").ifBlank {
                if (dependencyVersion.isNotBlank()) {
                    cache.version(dependencyVersion).string("project_id")
                } else {
                    ""
                }
            }

        private suspend fun addDependency(
            dependency: JsonObject,
            relation: String,
            dependencyId: String,
            dependencyVersion: String,
        ) {
            if (dependencyId.isBlank()) {
                require(relation != "required") {
                    I18n.t("core.contentDependencyResolver.12", dependency.string("file_name"))
                }
                return
            }
            val details = cache.project(dependencyId)
            // 选装依赖只登记、只有被勾选时才继续安装；其余关系仅 required 需要落地。
            val proceed =
                if (relation == "optional") {
                    state.optional[dependencyId] =
                        OptionalContent(
                            dependencyId,
                            details.string("title", dependencyId),
                            dependencyId in batch.selectedOptional,
                        )
                    dependencyId in batch.selectedOptional
                } else {
                    relation == "required"
                }
            if (proceed) visit(dependencyId, details.string("project_type"), dependencyVersion)
        }

        private fun planFiles(
            id: String,
            kind: String,
            info: JsonObject,
        ) {
            val availableFiles = info.array("files").map { it.asJsonObject }
            val file =
                availableFiles.firstOrNull { it.boolean("primary") }
                    ?: availableFiles.firstOrNull()
                    ?: error(I18n.t("core.contentDependencyResolver.13"))
            val filename = safeFilename(file.string("filename"))
            if (info.boolean("manual_download")) {
                state.manual[id] =
                    ManualContentFile(id, info.string("id"), filename, info.string("website"))
                return
            }
            require(kind != "mod" || filename.endsWith(".jar", true)) {
                I18n.t("contentA1.dependency.modFileNotJar")
            }
            state.files[id] = plannedFile(id, kind, info, file, filename)
        }

        private fun plannedFile(
            id: String,
            kind: String,
            info: JsonObject,
            file: JsonObject,
            filename: String,
        ): PlannedContentFile {
            val hashes = file.obj("hashes")
            val sha512 = hashes.string("sha512")
            val hash = sha512.ifBlank { hashes.string("sha1") }
            val pattern = if (sha512.isNotBlank()) SHA512_HEX_PATTERN else SHA1_HEX_PATTERN
            require(hash.matches(Regex(pattern))) { I18n.t("contentA1.dependency.missingHash") }
            return PlannedContentFile(
                id,
                info.string("name"),
                info.string("id"),
                filename,
                contentFolderFor(kind),
                contentFileUrl(api, file.string("url")),
                hash,
                if (sha512.isNotBlank()) "SHA-512" else "SHA-1",
                file.get("size")?.asLong ?: 0,
                batch.requests
                    .firstOrNull { it.hit.id == id }
                    ?.hit
                    ?.iconUrl
                    ?: cache.cachedProject(id)?.string("icon_url").orEmpty(),
            )
        }
    }
}

/** 按需拉取并缓存项目与版本信息，供一轮或多轮依赖遍历复用。 */
private class ContentLookupCache(
    private val backend: ContentBackend,
) {
    private val projects = mutableMapOf<String, JsonObject>()
    private val versions = mutableMapOf<String, JsonObject>()
    private val latest = mutableMapOf<String, JsonObject>()

    /** 只读已缓存的项目，不触发请求（用于读取项目图标等装饰信息）。 */
    fun cachedProject(id: String): JsonObject? = projects[id]

    suspend fun project(id: String): JsonObject =
        projects.getOrPutSuspend(id) {
            backend.project(id)
        }

    suspend fun version(id: String): JsonObject =
        versions.getOrPutSuspend(id) {
            backend.version(id)
        }

    suspend fun latestCompatible(
        id: String,
        kind: String,
        instance: GameInstance,
    ): JsonObject =
        latest.getOrPutSuspend(id) {
            val available =
                backend.versions(id, instance, kind).filter {
                    isCompatibleVersion(it, instance, kind)
                }
            available.firstOrNull { it.string("version_type") == "release" }
                ?: available.firstOrNull()
                ?: error(I18n.t("core.contentDependencyResolver.14", id))
        }
}

/** 版本是否同时满足当前游戏版本与（模组时的）加载器要求。 */
private fun isCompatibleVersion(
    version: JsonObject,
    instance: GameInstance,
    kind: String,
): Boolean =
    version.array("game_versions").any { it.asString == instance.gameVersion } &&
        (
            kind != "mod" ||
                version.array("loaders").any {
                    it.asString.equals(instance.loader.name, true)
                }
        )

private fun contentFolderFor(kind: String): String =
    when (kind) {
        "shader" -> "shaderpacks"
        "resourcepack" -> "resourcepacks"
        else -> "mods"
    }

private suspend fun <K, V> MutableMap<K, V>.getOrPutSuspend(
    key: K,
    value: suspend () -> V,
): V = get(key) ?: value().also { put(key, it) }
