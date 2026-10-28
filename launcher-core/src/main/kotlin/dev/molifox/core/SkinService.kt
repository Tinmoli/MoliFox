// 负责正版皮肤档案刷新和皮肤上传。

package dev.molifox.core

import com.google.gson.JsonObject
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class MinecraftSkinProfile(
    val name: String,
    val uuid: String,
    val textureUrl: String,
    val variant: SkinVariant,
)

class SkinService
internal constructor(
    private val store: LauncherStore,
    private val network: NetworkClient,
    private val credentials: suspend (String) -> GameCredentials,
    private val api: String = "https://api.minecraftservices.com",
) {
    val library = SkinLibrary(store)

    private fun microsoftAccount(id: String): Account =
        store.data.accounts
            .find { it.id == id }
            ?.also { require(it.type == AccountType.Microsoft) { "正版皮肤上传需要微软账户" } }
            ?: error("账户已移除")

    suspend fun refreshProfile(id: String): MinecraftSkinProfile {
        val account = microsoftAccount(id)
        val credential = credentials(id)
        require(credential.uuid == account.uuid) { "授权档案与所选账户不匹配" }
        return saveProfile(
            account,
            network.authorizedJson("$api/minecraft/profile", credential.accessToken),
        )
    }

    private fun saveProfile(account: Account, json: JsonObject): MinecraftSkinProfile {
        require(json.string("id").replace("-", "").equals(account.uuid.replace("-", ""), true)) {
            "服务器返回的角色与所选账户不匹配"
        }
        val active =
            json
                .array("skins")
                .firstOrNull { it.asJsonObject.string("state") == "ACTIVE" }
                ?.asJsonObject
        val url =
            active?.string("url").orEmpty().let {
                if (it.isBlank()) "" else normalizeSkinTextureUrl(it)
            }
        val variant =
            if (active?.string("variant").equals("SLIM", true)) SkinVariant.Slim
            else SkinVariant.Classic
        val name = json.string("name", account.name)
        store.mutate { data ->
            data.copy(
                accounts =
                    data.accounts.map {
                        if (it.id == account.id)
                            it.copy(name = name, skinUrl = url, skinVariant = variant)
                        else it
                    }
            )
        }
        return MinecraftSkinProfile(name, account.uuid, url, variant)
    }

    /**
     * Called only after an explicit upload action; authentication uses this account, never global
     * selection.
     */
    suspend fun upload(id: String, skinId: String): MinecraftSkinProfile =
        withContext(Dispatchers.IO) {
            val account = microsoftAccount(id)
            val entry = library.list().find { it.id == skinId } ?: error("皮肤已移除")
            val file = library.texture(skinId)
            validateUploadSkin(file, entry.variant)
            val bytes = Files.newInputStream(file).use { it.readNBytes(1024 * 1024 + 1) }
            require(
                bytes.size <= 1024 * 1024 &&
                    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
                        "%02x".format(it)
                    } == entry.sha256
            ) {
                "皮肤已改变，请重新选择"
            }
            val credential = credentials(id)
            require(credential.uuid == account.uuid) { "授权档案与所选账户不匹配" }
            network.uploadMinecraftSkin(
                "$api/minecraft/profile/skins",
                credential.accessToken,
                entry.variant.apiValue,
                bytes,
            )
            // A successful write followed by a failed profile refresh must not claim the upload
            // itself failed.
            try {
                saveProfile(
                    account,
                    network.authorizedJson("$api/minecraft/profile", credential.accessToken),
                )
            } catch (failure: Exception) {
                if (failure is kotlinx.coroutines.CancellationException) throw failure
                store.log("皮肤已上传，但档案刷新失败，请稍后刷新头像：${failure.message}")
                MinecraftSkinProfile(account.name, account.uuid, "", entry.variant)
            }
        }

    suspend fun useOfflinePreview(accountId: String, skinId: String?) {
        val account = store.data.accounts.find { it.id == accountId } ?: error("账户已移除")
        require(account.type == AccountType.Offline) { "此选项仅设置离线角色的本地预览" }
        val variant =
            if (skinId != null) {
                library.texture(skinId)
                library.list().first { it.id == skinId }.variant
            } else SkinVariant.Classic
        store.mutate { data ->
            data.copy(
                accounts =
                    data.accounts.map {
                        if (it.id == accountId)
                            it.copy(localSkinId = skinId.orEmpty(), skinVariant = variant)
                        else it
                    }
            )
        }
    }

    suspend fun texture(account: Account, refresh: Boolean = false): Path? =
        if (account.type == AccountType.Offline) {
            try {
                account.localSkinId.takeIf { it.isNotBlank() }?.let { library.texture(it) }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                store.log("离线皮肤预览不可用：${failure.message}")
                null
            }
        } else AccountSkinCache(store, network).load(account, refresh)

    suspend fun exportCurrent(accountId: String, destination: Path) {
        val account = store.data.accounts.find { it.id == accountId } ?: error("账户已移除")
        val file = texture(account) ?: error("当前账户没有可导出的皮肤纹理，请先刷新档案或导入本地皮肤")
        exportSkinTexture(file, destination)
    }
}
