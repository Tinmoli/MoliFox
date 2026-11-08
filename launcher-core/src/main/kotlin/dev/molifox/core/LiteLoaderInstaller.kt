// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 生成 LiteLoader 的 LaunchWrapper 配置：版本清单自带 tweakClass 与依赖库。

package dev.molifox.core

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import dev.molifox.core.i18n.I18n

/** LiteLoader 的版本清单与库仓库。 */
private const val LITELOADER_VERSIONS = "https://dl.liteloader.com/versions/versions.json"
private const val LITELOADER_REPO = "https://dl.liteloader.com/versions/"

/** 从 LiteLoader 的 versions.json 合成可直接启动的加载器配置。 */
internal class LiteLoaderInstaller(
    private val network: NetworkClient,
) {
    internal suspend fun fetchProfile(
        instance: GameInstance,
        context: JobContext,
        from: Float = 0f,
        to: Float = 1f,
    ): JsonObject {
        context.progress(
            from,
            I18n.t(
                "networkB2.loaderProfileFetcher.loadingProfile",
                InstallPhase.LOADER,
                instance.loader.title,
            ),
        )
        val versions =
            network
                .json(LITELOADER_VERSIONS)
                .asJsonObject
                .obj("versions")
                .obj(instance.gameVersion)
        val entry = findLiteLoaderEntry(versions, instance.loaderVersion)
        val tweakClass = entry.string("tweakClass")
        require(tweakClass.isNotBlank()) {
            I18n.t("networkB2.loaderProfileFetcher.liteLoaderVersionMissing")
        }
        val libraries = buildLibraries(entry, instance.loaderVersion)
        context.checkCancelled()
        context.progress(
            to,
            I18n.t(
                "networkB2.loaderProfileFetcher.profileReady",
                InstallPhase.LOADER,
                instance.loader.title,
            ),
        )
        return launchProfile(libraries, tweakClass)
    }

    private fun buildLibraries(
        entry: JsonObject,
        loaderVersion: String,
    ): JsonArray {
        val libraries = JsonArray()
        entry.array("libraries").forEach { element ->
            val library = element.asJsonObject.deepCopy()
            ensureLibraryDownload(library)
            libraries.add(library)
        }
        libraries.add(
            JsonObject().apply {
                addProperty("name", "com.mumfrey:liteloader:$loaderVersion")
                addProperty("url", LITELOADER_REPO)
            },
        )
        return libraries
    }

    private fun launchProfile(
        libraries: JsonArray,
        tweakClass: String,
    ): JsonObject =
        JsonObject().apply {
            addProperty("mainClass", "net.minecraft.launchwrapper.Launch")
            add("libraries", libraries)
            add(
                "arguments",
                JsonObject().apply {
                    add(
                        "game",
                        JsonArray().apply {
                            add("--tweakClass")
                            add(tweakClass)
                        },
                    )
                },
            )
        }
}

/** 在 snapshots/artefacts 两段里按库版本找 LiteLoader 启动信息。 */
private fun findLiteLoaderEntry(
    versions: JsonObject,
    loaderVersion: String,
): JsonObject {
    for (section in listOf("snapshots", "artefacts")) {
        val entries = versions.obj(section).obj("com.mumfrey:liteloader")
        for ((_, value) in entries.entrySet()) {
            if (!value.isJsonObject) continue
            val entry = value.asJsonObject
            if (entry.string("version") == loaderVersion) return entry
        }
    }
    error(I18n.t("networkB2.loaderProfileFetcher.liteLoaderVersionMissing"))
}

/** 老版本启动器库没有 downloads 段，按 Maven 坐标补一个显式地址，避免被跳过。 */
private fun ensureLibraryDownload(library: JsonObject) {
    val name = library.string("name")
    if (!name.startsWith("net.minecraft:") || library.obj("downloads").has("artifact")) return
    val relative = mavenPath(name)
    library.add(
        "downloads",
        JsonObject().apply {
            add(
                "artifact",
                JsonObject().apply {
                    addProperty("path", relative)
                    addProperty("url", "https://libraries.minecraft.net/$relative")
                },
            )
        },
    )
}
