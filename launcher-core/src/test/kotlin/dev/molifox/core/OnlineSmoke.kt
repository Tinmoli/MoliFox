// 负责验证游戏清单、加载器和内容接口的真实连通性。

package dev.molifox.core

import java.nio.file.Path
import kotlinx.coroutines.runBlocking

/** Explicit opt-in: no accounts, credentials, game launches, or large downloads. */
fun main(arguments: Array<String>) = runBlocking {
    LauncherController(dataDirectory = Path.of(arguments.single())).use { controller ->
        val versions = controller.installer.versions(true)
        val report =
            linkedMapOf<String, Any>(
                "minecraftVersions" to versions.size,
                "latestRelease" to versions.first { it.type == "release" }.id,
            )
        for ((loader, game) in
            listOf(
                LoaderType.Fabric to "1.20.1",
                LoaderType.Quilt to "1.20.1",
                LoaderType.Forge to "1.20.1",
                LoaderType.NeoForge to "1.21.1",
            )) {
            println("Checking ${loader.title} / $game")
            try {
                val available = controller.installer.loaderVersions(loader, game)
                check(available.isNotEmpty()) { "${loader.title} returned no versions for $game" }
                report[loader.title] =
                    mapOf(
                        "minecraft" to game,
                        "count" to available.size,
                        "first" to available.first().version,
                    )
                if (loader == LoaderType.Fabric || loader == LoaderType.Quilt) {
                    val host =
                        if (loader == LoaderType.Fabric) "https://meta.fabricmc.net/v2"
                        else "https://meta.quiltmc.org/v3"
                    val profile =
                        controller.network
                            .json(
                                "$host/versions/loader/$game/${encoded(available.first().version)}/profile/json"
                            )
                            .asJsonObject
                    check(profile.string("mainClass").isNotBlank())
                }
            } catch (error: Exception) {
                report[loader.title] = mapOf("error" to (error.message ?: "unknown"))
                println("${loader.title}: ${error.message}")
            }
        }
        val mods = controller.content.search("Sodium", "mod")
        check(mods.hits.isNotEmpty()) { "Modrinth search returned no results" }
        report["modrinth"] = mapOf("total" to mods.total, "first" to mods.hits.first().title)
        writeJson(Path.of(arguments.single()).resolve("online-smoke.json"), report)
        println(gson.toJson(report))
        check(report.values.none { it is Map<*, *> && it.containsKey("error") }) {
            "Some online endpoints could not be verified; see online-smoke.json"
        }
    }
}
