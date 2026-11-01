// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责内容安装前置依赖和目标文件计划。

package dev.molifox.core

import com.google.gson.JsonObject
import dev.molifox.core.i18n.I18n
import java.net.URI
import kotlin.io.path.exists

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
    val sameOrigin =
        target.scheme == source.scheme && target.host == source.host && target.port == source.port
    if (sameOrigin && (configuredService || source.host == "127.0.0.1")) {
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
    ): ContentInstallPlan = planMany(listOf(ContentRequest(hit, exactVersion)), instanceId, selectedOptional, sources)

    suspend fun planMany(
        requests: List<ContentRequest>,
        instanceId: String,
        selectedOptional: Set<String>,
        sources: List<ContentSource> = emptyList(),
    ): ContentInstallPlan {
        requireUniquePlanItems(requests, sources)
        val instance = store.instance(instanceId)
        require(instance.installed) { I18n.t("contentA1.install.instanceNotInstalled") }
        val registryFile = store.instancePath(instanceId).resolve(".molifox-content.json")
        val registry = if (registryFile.exists()) readObject(registryFile) else JsonObject()
        val registryHash = if (registryFile.exists()) digest(registryFile) else ""
        registerContentSources(instanceId, sources, registry)
        val batch =
            ContentPlanBatch(
                requests,
                instanceId,
                instance,
                registry,
                registryHash,
                selectedOptional,
                sources,
            )
        return ContentDependencyResolver(batch, store, api, backend).resolve()
    }

    private fun requireUniquePlanItems(
        requests: List<ContentRequest>,
        sources: List<ContentSource>,
    ) {
        require(
            requests.isNotEmpty() && requests.map { it.hit.id }.distinct().size == requests.size,
        ) {
            I18n.t("contentA1.installPlan.duplicateProjectVersions")
        }
        require(sources.map { it.projectId }.distinct().size == sources.size) {
            I18n.t("contentA1.installPlan.duplicateLocalFiles")
        }
    }

    /** 校验本地内容文件并与安装登记表合并；登记表只在内存中更新，供后续版本收敛读取。 */
    private fun registerContentSources(
        instanceId: String,
        sources: List<ContentSource>,
        registry: JsonObject,
    ) {
        for (source in sources) {
            verifyContentSource(store, instanceId, source)
            require(
                !registry.obj(source.projectId).boolean("locked") ||
                    registry.obj(source.projectId).string("versionId") == source.versionId,
            ) {
                I18n.t("contentA1.installPlan.lockedVersionMismatch")
            }
            require(
                registry.obj(source.projectId).string("file").let {
                    it.isBlank() || it == contentBaseFilename(source.filename)
                },
            ) {
                I18n.t("contentA1.installPlan.duplicateProjectFiles")
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
    }
}
