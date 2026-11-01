// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责游戏版本清单与加载器版本列表的读取和缓存。

package dev.molifox.core

import dev.molifox.core.i18n.I18n
import kotlinx.coroutines.CancellationException
import java.io.IOException
import kotlin.io.path.exists

/** OptiFine 官方列表由 BMCLAPI 镜像。 */
private const val OPTIFINE_VERSION_LIST = "https://bmclapi2.bangbang93.com/optifine/versionList"

/** LiteLoader 的版本/库清单。 */
private const val LITELOADER_VERSIONS = "https://dl.liteloader.com/versions/versions.json"

/** Cleanroom 发布索引（HMCL 维护）。 */
private const val CLEANROOM_INDEX = "https://hmcl.glavo.site/metadata/cleanroom/index.json"

/** 网络层会把状态码异常包在“无法连接”里，这里沿 cause 链找回来。 */
private fun Throwable.hasHttpStatus(vararg codes: Int): Boolean {
    var current: Throwable? = this
    while (current != null) {
        if (current is HttpStatusException && current.statusCode in codes) return true
        current = current.cause
    }
    return false
}

/** 版本元数据来源：游戏版本清单与各加载器的版本列表，只读并缓存最近一次结果。 */
internal class MinecraftVersionCatalog(
    private val store: LauncherStore,
    private val network: NetworkClient,
    private val manifestUrl: String,
    private val fabricMetaBase: String,
    private val quiltMetaBase: String,
    private val legacyFabricMetaBase: String,
    private val forgeMavenBase: String,
    private val neoforgeMavenBase: String,
) {
    @Volatile private var versionsCache: List<ReleaseVersion> = emptyList()

    suspend fun versions(refresh: Boolean = false): List<ReleaseVersion> {
        if (!refresh && versionsCache.isNotEmpty()) return versionsCache
        val cache = store.metadataDirectory.resolve("versions.json")
        val listSource = store.data.settings.effectiveVersionListSource
        val json =
            try {
                network
                    .json(
                        if (listSource == "bmclapi") versionManifestUrl("bmclapi") else manifestUrl,
                        sourceOverride = listSource,
                    ).asJsonObject
                    .also { writeJson(cache, it) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: IOException) {
                if (cache.exists()) {
                    store.log(I18n.t("networkB2.minecraftVersionCatalog.useCachedVersionList"))
                    readObject(cache)
                } else {
                    throw error
                }
            }
        return json
            .array("versions")
            .map { gson.fromJson(it, ReleaseVersion::class.java) }
            .also { versionsCache = it }
    }

    /**
     * 某些加载器只支持固定的游戏版本：返回这些版本供左侧列表过滤。
     * 主流加载器返回 null，表示不限制（仍由各自元数据决定具体加载器版本）。
     */
    suspend fun supportedGameVersions(loader: LoaderType): Set<String>? =
        when (loader) {
            LoaderType.LegacyFabric ->
                network
                    .json("$legacyFabricMetaBase/v2/versions/game")
                    .asJsonArray
                    .mapNotNull { it.asJsonObject.string("version").takeIf(String::isNotBlank) }
                    .toSet()
            LoaderType.OptiFine ->
                network
                    .json(OPTIFINE_VERSION_LIST)
                    .asJsonArray
                    .mapNotNull { it.asJsonObject.string("mcversion").takeIf(String::isNotBlank) }
                    .toSet()
            LoaderType.LiteLoader ->
                network
                    .json(LITELOADER_VERSIONS)
                    .asJsonObject
                    .obj("versions")
                    .keySet()
            LoaderType.Cleanroom -> setOf("1.12.2")
            else -> null
        }

    suspend fun loaderVersions(
        loader: LoaderType,
        gameVersion: String,
    ): List<LoaderVersion> {
        safeFilename(gameVersion)
        return when (loader) {
            LoaderType.Vanilla -> emptyList()
            LoaderType.Fabric,
            LoaderType.Quilt,
            LoaderType.LegacyFabric,
            -> fabricLoaderVersions(loader, gameVersion)
            LoaderType.Forge -> forgeLoaderVersions(gameVersion)
            LoaderType.NeoForge -> neoforgeLoaderVersions(gameVersion)
            LoaderType.OptiFine -> optiFineLoaderVersions(gameVersion)
            LoaderType.LiteLoader -> liteLoaderLoaderVersions(gameVersion)
            LoaderType.Cleanroom -> cleanroomLoaderVersions(gameVersion)
        }
    }

    /** OptiFine 版本列表来自 BMCLAPI；版本号用 `<type>_<patch>`，安装器再按游戏版本/类型/补丁拼下载地址。 */
    private suspend fun optiFineLoaderVersions(gameVersion: String): List<LoaderVersion> {
        val raw =
            network
                .json(OPTIFINE_VERSION_LIST)
                .asJsonArray
                .mapNotNull { item ->
                    val json = item.asJsonObject
                    if (json.string("mcversion") != gameVersion) {
                        null
                    } else {
                        val type = json.string("type")
                        val patch = json.string("patch")
                        if (type.isBlank() || patch.isBlank()) {
                            null
                        } else {
                            LoaderVersion(
                                "${type}_$patch",
                                !patch.startsWith("pre", true) && !patch.startsWith("alpha", true),
                            )
                        }
                    }
                }
        return newestFirst(raw.distinctBy { it.version })
    }

    /** LiteLoader 只有一个 versions.json：按游戏版本取所有构建，版本号就是库版本。 */
    private suspend fun liteLoaderLoaderVersions(gameVersion: String): List<LoaderVersion> {
        val root =
            network
                .json(LITELOADER_VERSIONS)
                .asJsonObject
                .obj("versions")
                .obj(gameVersion)
        val versions = linkedMapOf<String, LoaderVersion>()
        listOf("snapshots", "artefacts").forEach { section ->
            root
                .obj(section)
                .obj("com.mumfrey:liteloader")
                .entrySet()
                .forEach { (_, value) ->
                    if (!value.isJsonObject) return@forEach
                    val version = value.asJsonObject.string("version")
                    if (version.isNotBlank()) {
                        versions[version] =
                            LoaderVersion(version, !version.contains("SNAPSHOT", ignoreCase = true))
                    }
                }
        }
        return newestFirst(versions.values.toList())
    }

    /** Cleanroom 只支持 1.12.2，元数据是 HMCL 维护的发布索引。 */
    private suspend fun cleanroomLoaderVersions(gameVersion: String): List<LoaderVersion> {
        if (gameVersion != "1.12.2") return emptyList()
        val versions =
            network
                .json(CLEANROOM_INDEX)
                .asJsonArray
                .mapNotNull { item ->
                    item.asJsonObject
                        .string("name")
                        .takeIf { it.isNotBlank() }
                        ?.let { LoaderVersion(it, !it.contains("alpha", true)) }
                }
        return newestFirst(versions)
    }

    /** Fabric 与 Quilt 共用同一套元数据形状，只有主机与 API 版本不同。 */
    private suspend fun fabricLoaderVersions(
        loader: LoaderType,
        gameVersion: String,
    ): List<LoaderVersion> {
        val host =
            when (loader) {
                LoaderType.Fabric -> fabricMetaBase
                LoaderType.LegacyFabric -> legacyFabricMetaBase
                else -> quiltMetaBase
            }
        val apiVersion = if (loader == LoaderType.Quilt) "v3" else "v2"
        val versions =
            try {
                network
                    .json("$host/$apiVersion/versions/loader/${encoded(gameVersion)}")
                    .asJsonArray
                    .map { item ->
                        val info = item.asJsonObject.obj("loader")
                        LoaderVersion(
                            info.string("version"),
                            info.boolean(
                                "stable",
                                !Regex("(?i)alpha|beta|rc|snapshot")
                                    .containsMatchIn(info.string("version")),
                            ),
                        )
                    }
            } catch (error: IOException) {
                // LegacyFabric 对不支持的正式版返回 400；这表示该版本没有 Legacy 分支，不是网络故障。
                if (loader == LoaderType.LegacyFabric && error.hasHttpStatus(400, 404)) {
                    return emptyList()
                }
                throw error
            }
        return newestFirst(versions)
    }

    /** Forge 只提供 maven-metadata.xml，版本号形如 `<游戏版本>-<加载器版本>`。 */
    private suspend fun forgeLoaderVersions(gameVersion: String): List<LoaderVersion> {
        val xml =
            network.text(
                "$forgeMavenBase/net/minecraftforge/forge/maven-metadata.xml",
            )
        val versions =
            Regex("<version>([^<]+)</version>")
                .findAll(xml)
                .map { it.groupValues[1] }
                .filter { it.startsWith("$gameVersion-") }
                .map { LoaderVersion(it.removePrefix("$gameVersion-")) }
                .toList()
        return newestFirst(versions)
    }

    /** NeoForge 的版本前缀按游戏版本推导：1.20.1 走旧的 forge 仓库，其余按主/次版本号。 */
    private suspend fun neoforgeLoaderVersions(gameVersion: String): List<LoaderVersion> {
        val legacy = gameVersion == "1.20.1"
        val project = if (legacy) "forge" else "neoforge"
        val prefix = neoforgeVersionPrefix(gameVersion, legacy)
        val xml =
            network.text(
                "$neoforgeMavenBase/releases/net/neoforged/$project/maven-metadata.xml",
            )
        val versions =
            Regex("<version>([^<]+)</version>")
                .findAll(xml)
                .map { it.groupValues[1] }
                .filter { it.startsWith(prefix) }
                .map { LoaderVersion(it, !it.contains("beta") && !it.contains("alpha")) }
                .toList()
        return newestFirst(versions)
    }

    private fun neoforgeVersionPrefix(
        gameVersion: String,
        legacy: Boolean,
    ): String {
        if (legacy) return "1.20.1-"
        val parts = gameVersion.split('.')
        return if (parts.first() == "1") {
            "${parts.getOrElse(1) { "0" }}.${parts.getOrElse(2) { "0" }}."
        } else {
            "${parts[0]}.${parts.getOrElse(1) { "0" }}."
        }
    }

    /** 列表统一按版本号从新到旧排序，界面默认展示最新版本。 */
    private fun newestFirst(versions: List<LoaderVersion>): List<LoaderVersion> =
        versions.sortedWith { left, right ->
            compareLoaderVersions(right.version, left.version)
        }
}
