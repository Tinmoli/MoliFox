// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

package dev.molifox.core

import com.google.gson.JsonObject
import dev.molifox.core.i18n.I18n
import dev.molifox.core.i18n.Strings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URI
import java.util.Locale

data class MinecraftCape(
    val id: String,
    val name: String,
    val textureUrl: String,
    val active: Boolean,
)

/** 只本地化服务端别名，实际切换仍使用披风 ID。新别名在词库补齐前保留原文。 */
fun MinecraftCape.localizedName(strings: Strings): String {
    val alias = name.filter(Char::isLetterOrDigit).lowercase(Locale.ROOT)
    val key = "dialogs.skin.cape.name.$alias"
    return if (strings.has(key)) strings.t(key) else name
}

internal fun parseMinecraftCapes(json: JsonObject): List<MinecraftCape> =
    json.array("capes").mapNotNull { entry ->
        if (!entry.isJsonObject) return@mapNotNull null
        val cape = entry.asJsonObject
        val id = cape.string("id")
        val url = cape.string("url")
        if (id.isBlank() || url.isBlank()) return@mapNotNull null
        val texture =
            try {
                normalizeSkinTextureUrl(url)
            } catch (_: IllegalArgumentException) {
                java.util.logging.Logger
                    .getLogger("MoliFox.skin")
                    .warning("Ignoring cape with an invalid texture URL")
                return@mapNotNull null
            } catch (_: java.net.URISyntaxException) {
                java.util.logging.Logger
                    .getLogger("MoliFox.skin")
                    .warning("Ignoring cape with a malformed texture URL")
                return@mapNotNull null
            }
        MinecraftCape(id, cape.string("alias", id), texture, cape.string("state") == "ACTIVE")
    }

/** 凭据只发送到官方端点；禁止跳转，避免披风写入携带令牌跨域。 */
internal suspend fun NetworkClient.changeMinecraftCape(
    api: String,
    token: String,
    capeId: String?,
) = withContext(Dispatchers.IO) {
    val uri = URI(api)
    val officialEndpoint = uri.scheme == "https" && uri.host == "api.minecraftservices.com" && uri.port == -1
    val localEndpoint = uri.scheme == "http" && uri.host == "127.0.0.1"
    require(
        (officialEndpoint || localEndpoint) &&
            uri.path.isEmpty() &&
            uri.userInfo == null &&
            uri.query == null &&
            uri.fragment == null,
    ) {
        I18n.t("core.networkEndpointActions.16")
    }
    val request = actions.builder("$api/minecraft/profile/capes/active").header("Authorization", "Bearer $token")
    if (capeId == null) {
        request.delete()
    } else {
        request.put(
            JsonObject()
                .apply {
                    addProperty("capeId", capeId)
                }.toString()
                .toRequestBody("application/json".toMediaType()),
        )
    }
    actions.execute(request.build(), redirects = false, attempts = 1).use { response ->
        if (!response.isSuccessful) throw ServiceHttpException(response.code, uri.host)
    }
}
