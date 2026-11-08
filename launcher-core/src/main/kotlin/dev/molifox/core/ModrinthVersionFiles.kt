// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

package dev.molifox.core

import com.google.gson.JsonElement
import com.google.gson.JsonObject

private const val MODRINTH_UPDATE_BATCH_SIZE = 100

/** 文件名与 SHA-512 按原顺序传入；筛选条件由模组或资源包调用方决定。 */
internal suspend fun NetworkClient.latestModrinthFiles(
    api: String,
    files: List<Pair<String, String>>,
    gameVersion: String,
    loader: String,
    allowPrerelease: Boolean,
): Map<String, RemoteModVersion> {
    val result = mutableMapOf<String, RemoteModVersion>()
    val hashes = files.map { it.second }.filter { it.isNotBlank() }.distinct()
    for (batch in hashes.chunked(MODRINTH_UPDATE_BATCH_SIZE)) {
        val body =
            JsonObject().apply {
                add("hashes", gson.toJsonTree(batch))
                addProperty("algorithm", "sha512")
                if (gameVersion.isNotBlank()) {
                    add("game_versions", gson.toJsonTree(listOf(gameVersion)))
                }
                if (loader.isNotBlank()) add("loaders", gson.toJsonTree(listOf(loader)))
                add(
                    "version_types",
                    gson.toJsonTree(if (allowPrerelease) listOf("release", "beta", "alpha") else listOf("release")),
                )
            }
        post("$api/version_files/update", body).entrySet().forEach { (hash, value) ->
            val info = value.takeIf(JsonElement::isJsonObject)?.asJsonObject ?: return@forEach
            val projectId = info.string("project_id")
            if (projectId.isBlank()) return@forEach
            val version =
                remoteVersion(info, RemoteModProject(projectId, "", "", "modrinth"))
                    ?: return@forEach
            files.filter { it.second.equals(hash, true) }.forEach { (filename, _) ->
                result[filename] = version
            }
        }
    }
    return result
}
