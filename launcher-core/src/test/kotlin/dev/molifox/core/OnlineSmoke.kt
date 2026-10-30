// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责验证游戏清单、加载器和内容接口的真实连通性。

package dev.molifox.core

import kotlinx.coroutines.runBlocking
import java.nio.file.Path

private val ONLINE_SMOKE_LOADERS =
    listOf(
        LoaderType.Fabric to "1.20.1",
        LoaderType.Quilt to "1.20.1",
        LoaderType.Forge to "1.20.1",
        LoaderType.NeoForge to "1.21.1",
    )

/** Explicit opt-in: no accounts, credentials, game launches, or large downloads. */
fun main(arguments: Array<String>) =
    runBlocking {
        LauncherController(dataDirectory = Path.of(arguments.single())).use { controller ->
            val report = versionReport(controller)
            for ((loader, game) in ONLINE_SMOKE_LOADERS) {
                checkOnlineLoader(controller, loader, game, report)
            }
            checkModrinthSearch(controller, report)
            writeJson(Path.of(arguments.single()).resolve("online-smoke.json"), report)
            println(gson.toJson(report))
            check(report.values.none { it is Map<*, *> && it.containsKey("error") }) {
                "Some online endpoints could not be verified; see online-smoke.json"
            }
        }
    }

/** 版本清单总数与最新正式版标识，作为后续加载器与搜索检查的基线。 */
private suspend fun versionReport(controller: LauncherController): MutableMap<String, Any> {
    val versions = controller.installer.versions(true)
    return linkedMapOf<String, Any>(
        "minecraftVersions" to versions.size,
        "latestRelease" to versions.first { it.type == "release" }.id,
    )
}

/** Modrinth 搜索一次，确认内容接口可用并把首条结果写入报告。 */
private suspend fun checkModrinthSearch(
    controller: LauncherController,
    report: MutableMap<String, Any>,
) {
    val mods = controller.content.search("Sodium", "mod")
    check(mods.hits.isNotEmpty()) { "Modrinth search returned no results" }
    report["modrinth"] = mapOf("total" to mods.total, "first" to mods.hits.first().title)
}

private suspend fun checkOnlineLoader(
    controller: LauncherController,
    loader: LoaderType,
    game: String,
    report: MutableMap<String, Any>,
) {
    println("Checking ${loader.title} / $game")
    try {
        val available = controller.installer.loaderVersions(loader, game)
        check(
            available.isNotEmpty(),
        ) { "${loader.title} returned no versions for $game" }
        report[loader.title] =
            mapOf(
                "minecraft" to game,
                "count" to available.size,
                "first" to available.first().version,
            )
        if (loader == LoaderType.Fabric || loader == LoaderType.Quilt) {
            checkOnlineLoaderProfile(controller, loader, game, available.first().version)
        }
    } catch (error: Exception) {
        report[loader.title] = mapOf("error" to (error.message ?: "unknown"))
        println("${loader.title}: ${error.message}")
    }
}

private suspend fun checkOnlineLoaderProfile(
    controller: LauncherController,
    loader: LoaderType,
    game: String,
    version: String,
) {
    val host =
        if (loader == LoaderType.Fabric) {
            "https://meta.fabricmc.net/v2"
        } else {
            "https://meta.quiltmc.org/v3"
        }
    val profile =
        controller.network
            .json(
                "$host/versions/loader/$game/${encoded(
                    version,
                )}/profile/json",
            ).asJsonObject
    check(profile.string("mainClass").isNotBlank())
}
