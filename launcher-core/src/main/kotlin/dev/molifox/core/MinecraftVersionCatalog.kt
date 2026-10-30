// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责游戏版本清单与加载器版本列表的读取和缓存。

package dev.molifox.core

import kotlinx.coroutines.CancellationException
import java.io.IOException
import kotlin.io.path.exists

/** 版本元数据来源：游戏版本清单与各加载器的版本列表，只读并缓存最近一次结果。 */
internal class MinecraftVersionCatalog(
    private val store: LauncherStore,
    private val network: NetworkClient,
    private val manifestUrl: String,
    private val fabricMetaBase: String,
    private val quiltMetaBase: String,
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
                    store.log("网络不可用，使用缓存的游戏版本列表")
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

    suspend fun loaderVersions(
        loader: LoaderType,
        gameVersion: String,
    ): List<LoaderVersion> {
        safeFilename(gameVersion)
        return when (loader) {
            LoaderType.Vanilla -> emptyList()
            LoaderType.Fabric,
            LoaderType.Quilt,
            -> fabricLoaderVersions(loader, gameVersion)
            LoaderType.Forge -> forgeLoaderVersions(gameVersion)
            LoaderType.NeoForge -> neoforgeLoaderVersions(gameVersion)
        }
    }

    /** Fabric 与 Quilt 共用同一套元数据形状，只有主机与 API 版本不同。 */
    private suspend fun fabricLoaderVersions(
        loader: LoaderType,
        gameVersion: String,
    ): List<LoaderVersion> {
        val host = if (loader == LoaderType.Fabric) fabricMetaBase else quiltMetaBase
        val apiVersion = if (loader == LoaderType.Fabric) "v2" else "v3"
        val versions =
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
        val versions =
            network
                .json(
                    "$neoforgeMavenBase/api/maven/versions/releases/net/neoforged/$project",
                ).asJsonObject
                .array("versions")
                .map { it.asString }
                .filter { it.startsWith(prefix) }
                .map { LoaderVersion(it, !it.contains("beta") && !it.contains("alpha")) }
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
