// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

package dev.molifox.core

import com.google.gson.JsonParser
import dev.molifox.core.i18n.I18n
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.nio.file.Files
import java.security.MessageDigest

/** 仅使用服务器明确声明的标准皮肤上传能力，不猜测站点私有上传地址。 */
internal class AuthlibSkinUploads(
    private val store: LauncherStore,
    private val network: NetworkClient,
    private val credentials: suspend (String) -> GameCredentials,
    private val library: SkinLibrary,
) {
    private fun account(id: String): Account =
        store.data.accounts
            .find { it.id == id }
            ?.also { require(it.type == AccountType.Authlib) { I18n.t("dialogs.skin.site.accountRequired") } }
            ?: error(I18n.t("core.skinService.2"))

    private fun root(account: Account): String =
        requireNotNull(normalizeAuthlibServerUrl(account.authlibServer)) {
            I18n.t("core.authlibClient.4")
        }

    private fun uuid(account: Account): String =
        account.uuid.replace("-", "").lowercase().also {
            require(Regex("[a-f0-9]{32}").matches(it)) { I18n.t("core.skinService.3") }
        }

    suspend fun supported(id: String): Boolean =
        withContext(Dispatchers.IO) {
            val account = account(id)
            val request =
                network.actions
                    .builder(
                        "${root(account)}/sessionserver/session/minecraft/profile/${uuid(account)}?unsigned=false",
                        allowInsecureHttp = true,
                    ).build()
            network.actions.execute(request, redirects = false, attempts = 1).use { response ->
                if (!response.isSuccessful) throw ServiceHttpException(response.code, request.url.host)
                val bytes =
                    response.body?.byteStream()?.readNBytes(SKIN_SERVICE_MAX_BYTES + 1)
                        ?: error(I18n.t("core.authlibAccounts.9"))
                require(bytes.size <= SKIN_SERVICE_MAX_BYTES) { I18n.t("core.authlibAccounts.9") }
                val profile = JsonParser.parseString(bytes.toString(Charsets.UTF_8)).asJsonObject
                require(
                    profile.string("id").replace("-", "").equals(uuid(account), true),
                ) { I18n.t("core.skinService.3") }
                profile.array("properties").any { entry ->
                    if (!entry.isJsonObject) {
                        false
                    } else {
                        entry.asJsonObject.let { property ->
                            property.string("name") == "uploadableTextures" &&
                                property.string("value").split(',').any { it.trim() == "skin" }
                        }
                    }
                }
            }
        }

    suspend fun upload(
        id: String,
        skinId: String,
    ) = withContext(Dispatchers.IO) {
        val account = account(id)
        require(supported(id)) { I18n.t("dialogs.skin.site.unsupported") }
        val entry = library.list().find { it.id == skinId } ?: error(I18n.t("core.skinLibrary.13"))
        val file = library.texture(skinId)
        validateUploadSkin(file, entry.variant)
        val bytes = Files.newInputStream(file).use { it.readNBytes(SKIN_SERVICE_MAX_BYTES + 1) }
        require(
            bytes.size <= SKIN_SERVICE_MAX_BYTES &&
                MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) } ==
                entry.sha256,
        ) {
            I18n.t("core.skinService.5")
        }
        val credential = credentials(id)
        require(
            credential.uuid.replace("-", "").equals(uuid(account), true) &&
                normalizeAuthlibServerUrl(credential.authlibServer) == root(account),
        ) { I18n.t("core.skinService.3") }
        val body =
            MultipartBody
                .Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("model", if (entry.variant == SkinVariant.Slim) "slim" else "")
                .addFormDataPart("file", "skin.png", bytes.toRequestBody("image/png".toMediaType()))
                .build()
        val request =
            network.actions
                .builder("${root(account)}/api/user/profile/${uuid(account)}/skin", allowInsecureHttp = true)
                .header("Authorization", "Bearer ${credential.accessToken}")
                .put(body)
                .build()
        network.actions.execute(request, redirects = false, attempts = 1).use { response ->
            if (!response.isSuccessful) throw ServiceHttpException(response.code, request.url.host)
        }
    }
}
