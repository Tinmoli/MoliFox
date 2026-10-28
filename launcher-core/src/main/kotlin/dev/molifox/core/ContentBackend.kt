// 负责内容来源接口和跨来源数据类型。

package dev.molifox.core

import com.google.gson.JsonObject

/** Provider adapters return one internal metadata shape. No provider credentials enter a plan. */
internal interface ContentBackend {
    suspend fun project(id: String): JsonObject

    suspend fun version(id: String): JsonObject

    suspend fun versions(id: String, instance: GameInstance, kind: String): List<JsonObject>

    suspend fun browseVersions(id: String): List<JsonObject>
}

internal class ModrinthBackend(private val network: NetworkClient, private val api: String) :
    ContentBackend {
    override suspend fun project(id: String) =
        network.json("$api/project/${encoded(id)}").asJsonObject

    override suspend fun version(id: String) =
        network.json("$api/version/${encoded(id)}").asJsonObject

    override suspend fun browseVersions(id: String) =
        network
            .json("$api/project/${encoded(id)}/version?include_changelog=false")
            .asJsonArray
            .map { it.asJsonObject }

    override suspend fun versions(
        id: String,
        instance: GameInstance,
        kind: String,
    ): List<JsonObject> {
        val filters =
            "game_versions=${encoded(gson.toJson(listOf(instance.gameVersion)))}" +
                if (kind == "mod")
                    "&loaders=${encoded(gson.toJson(listOf(instance.loader.name.lowercase())))}"
                else ""
        return network.json("$api/project/${encoded(id)}/version?$filters").asJsonArray.map {
            it.asJsonObject
        }
    }
}

internal class ContentBackends(
    private val modrinth: ContentBackend,
    private val curseForge: ContentBackend,
) : ContentBackend {
    private fun backend(id: String) = if (id.startsWith("curseforge:")) curseForge else modrinth

    override suspend fun project(id: String) = backend(id).project(id)

    override suspend fun version(id: String) = backend(id).version(id)

    override suspend fun versions(id: String, instance: GameInstance, kind: String) =
        backend(id).versions(id, instance, kind)

    override suspend fun browseVersions(id: String) = backend(id).browseVersions(id)
}

data class ManualContentFile(
    val projectId: String,
    val versionId: String,
    val filename: String,
    val website: String,
)

class ManualContentRequired(
    val files: List<ManualContentFile>,
    instruction: String = "请从以上官方页面下载；普通模组可使用导入模组，整合包 ZIP 可从本地导入。",
) :
    IllegalStateException(
        "以下文件需要手动从项目页面下载，未猜测或绕过下载地址：\n" +
            files.joinToString("\n") { "${it.filename} · ${it.website}" } +
            "\n$instruction"
    )

/** Stable website routing for the UI; IDs remain independent of the currently selected source. */
fun contentWebsite(hit: ContentHit): String {
    val path =
        when (hit.type) {
            "modpack" -> "modpacks"
            "resourcepack" -> "texture-packs"
            "shader" -> "shaders"
            "datapack" -> "data-packs"
            else -> "mc-mods"
        }
    return if (hit.id.startsWith("curseforge:"))
        "https://www.curseforge.com/minecraft/$path/${encoded(hit.slug)}"
    else "https://modrinth.com/${hit.type}/${encoded(hit.slug)}"
}
