// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责正版皮肤档案刷新和皮肤上传。

package dev.molifox.core

import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import dev.molifox.core.i18n.I18n
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlin.io.path.createTempFile
import kotlin.io.path.deleteIfExists
import kotlin.io.path.isDirectory
import kotlin.io.path.isSymbolicLink
import kotlin.io.path.name

private const val MAX_SKIN_DOWNLOAD_BYTES = 1024 * 1024

data class MinecraftSkinProfile(
    val name: String,
    val uuid: String,
    val textureUrl: String,
    val variant: SkinVariant,
)

/**
 * 离线皮肤的实际落盘位置。
 *
 * [library] 是启动器皮肤库里的 `MoliFox/skins/<id>.png`（离线角色一直用它做预览）；
 * [customSkinLoader] 只在当前选中实例下已经存在 CustomSkinLoader 的本地皮肤目录时才非空， 启动器绝不主动创建模组目录。
 */
data class OfflineSkinPlacement(
    val library: Path,
    val customSkinLoader: Path?,
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
                ?.also { require(it.type == AccountType.Microsoft) { I18n.t("core.skinService.1") } }
                ?: error(I18n.t("core.skinService.2"))

        suspend fun refreshProfile(id: String): MinecraftSkinProfile {
            val account = microsoftAccount(id)
            val credential = credentials(id)
            require(credential.uuid == account.uuid) { I18n.t("core.skinService.3") }
            return saveProfile(
                account,
                network.authorizedJson("$api/minecraft/profile", credential.accessToken),
            )
        }

        private fun saveProfile(
            account: Account,
            json: JsonObject,
        ): MinecraftSkinProfile {
            require(
                json.string("id").replace("-", "").equals(account.uuid.replace("-", ""), true),
            ) {
                I18n.t("core.skinService.4")
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
                if (active?.string("variant").equals("SLIM", true)) {
                    SkinVariant.Slim
                } else {
                    SkinVariant.Classic
                }
            val name = json.string("name", account.name)
            store.mutate { data ->
                data.copy(
                    accounts =
                        data.accounts.map {
                            if (it.id == account.id) {
                                it.copy(name = name, skinUrl = url, skinVariant = variant)
                            } else {
                                it
                            }
                        },
                )
            }
            return MinecraftSkinProfile(name, account.uuid, url, variant)
        }

        /**
         * Called only after an explicit upload action; authentication uses this account, never
         * global selection.
         */
        suspend fun upload(
            id: String,
            skinId: String,
        ): MinecraftSkinProfile =
            withContext(Dispatchers.IO) {
                val account = microsoftAccount(id)
                val entry = library.list().find { it.id == skinId } ?: error(I18n.t("core.skinLibrary.13"))
                val file = library.texture(skinId)
                validateUploadSkin(file, entry.variant)
                val bytes = Files.newInputStream(file).use { it.readNBytes(1024 * 1024 + 1) }
                require(
                    bytes.size <= MAX_SKIN_DOWNLOAD_BYTES &&
                        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
                            "%02x".format(it)
                        } == entry.sha256,
                ) {
                    I18n.t("core.skinService.5")
                }
                val credential = credentials(id)
                require(credential.uuid == account.uuid) { I18n.t("core.skinService.3") }
                network.uploadMinecraftSkin(
                    "$api/minecraft/profile/skins",
                    credential.accessToken,
                    entry.variant.apiValue,
                    bytes,
                )
                // A successful write followed by a failed profile refresh must not claim the upload
                // itself failed.
                refreshProfileAfterUpload(account, entry.variant, credential)
            }

        /** 上传已经成功，档案刷新失败只降级为日志与空纹理，绝不把上传判为失败。 */
        private suspend fun refreshProfileAfterUpload(
            account: Account,
            variant: SkinVariant,
            credential: GameCredentials,
        ): MinecraftSkinProfile =
            try {
                saveProfile(
                    account,
                    network.authorizedJson("$api/minecraft/profile", credential.accessToken),
                )
            } catch (failure: IOException) {
                uploadedWithoutProfile(account, variant, failure)
            } catch (failure: IllegalStateException) {
                uploadedWithoutProfile(account, variant, failure)
            } catch (failure: IllegalArgumentException) {
                uploadedWithoutProfile(account, variant, failure)
            } catch (failure: JsonParseException) {
                uploadedWithoutProfile(account, variant, failure)
            }

        private fun uploadedWithoutProfile(
            account: Account,
            variant: SkinVariant,
            failure: Throwable,
        ): MinecraftSkinProfile {
            store.log(
                I18n.t(
                    "miscA2.skinService.uploadedProfileRefreshFailed",
                    failure.message,
                ),
            )
            return MinecraftSkinProfile(account.name, account.uuid, "", variant)
        }

        /**
         * 把皮肤库中的一项设为离线角色的皮肤，并在存在 CustomSkinLoader 本地皮肤目录时同步一份。
         *
         * 离线模式没有皮肤服务器：文件必须由本机目录提供给皮肤模组，启动器不会伪造上传。
         * 返回实际写入的位置；[skinId] 为 null（恢复默认）时返回 null。
         */
        suspend fun useOfflinePreview(
            accountId: String,
            skinId: String?,
        ): OfflineSkinPlacement? {
            val account = store.data.accounts.find { it.id == accountId } ?: error(I18n.t("core.skinService.2"))
            require(account.type == AccountType.Offline) { I18n.t("core.skinService.6") }
            if (skinId == null) {
                store.mutate { data ->
                    data.copy(
                        accounts =
                            data.accounts.map {
                                if (it.id == accountId) {
                                    it.copy(localSkinId = "", skinVariant = SkinVariant.Classic)
                                } else {
                                    it
                                }
                            },
                    )
                }
                return null
            }
            val entry =
                library.list().find { it.id == skinId } ?: error(I18n.t("core.skinLibrary.11"))
            val file = library.texture(skinId)
            store.mutate { data ->
                data.copy(
                    accounts =
                        data.accounts.map {
                            if (it.id == accountId) {
                                it.copy(localSkinId = skinId, skinVariant = entry.variant)
                            } else {
                                it
                            }
                        },
                )
            }
            return OfflineSkinPlacement(
                file,
                publishCustomSkinLoaderSkin(account.name, file, entry.variant),
            )
        }

        /** 当前选中实例下真实存在的 CustomSkinLoader 本地皮肤目录；目录不存在时返回 null，不创建。 */
        fun customSkinLoaderDirectory(): Path? {
            val instance =
                store.data.selectedInstanceId?.let { id ->
                    store.data.instances.find { it.id == id }
                } ?: return null
            val game =
                runCatching { store.gamePath(instance) }.getOrNull()?.toAbsolutePath()?.normalize()
                    ?: return null
            val directory =
                game
                    .resolve("CustomSkinLoader")
                    .resolve("LocalSkin")
                    .resolve("skins")
                    .normalize()
            return directory.takeIf {
                it.startsWith(game) && it.isDirectory() && !Files.isSymbolicLink(it)
            }
        }

        /**
         * 仅在模组目录已存在时写 `<game>/CustomSkinLoader/LocalSkin/skins/<玩家名>.png`。
         * 写不进去只记录日志，不影响启动器内的离线预览。
         */
        private suspend fun publishCustomSkinLoaderSkin(
            playerName: String,
            source: Path,
            variant: SkinVariant,
        ): Path? =
            withContext(Dispatchers.IO) {
                val directory = customSkinLoaderDirectory() ?: return@withContext null
                val target = directory.resolve("$playerName.png")
                if (Files.isSymbolicLink(target)) {
                    store.log(I18n.t("miscA2.skinService.customSkinLoaderSymlink"))
                    return@withContext null
                }
                try {
                    validateLocalSkinFile(source, variant)
                    val staging = Files.createTempFile(directory, ".molifox-skin-", ".tmp")
                    try {
                        Files.copy(source, staging, StandardCopyOption.REPLACE_EXISTING)
                        validateLocalSkinFile(staging, variant)
                        Files.move(staging, target, StandardCopyOption.REPLACE_EXISTING)
                    } finally {
                        staging.deleteIfExists()
                    }
                    store.log(I18n.t("miscA2.skinService.customSkinLoaderWritten", target))
                    target
                } catch (failure: IOException) {
                    store.log(I18n.t("miscA2.skinService.customSkinLoaderWriteFailed", failure.message))
                    null
                } catch (failure: IllegalArgumentException) {
                    store.log(I18n.t("miscA2.skinService.customSkinLoaderWriteFailed", failure.message))
                    null
                } catch (failure: IllegalStateException) {
                    store.log(I18n.t("miscA2.skinService.customSkinLoaderWriteFailed", failure.message))
                    null
                }
            }

        suspend fun texture(
            account: Account,
            refresh: Boolean = false,
        ): Path? =
            if (account.type == AccountType.Offline) {
                try {
                    account.localSkinId.takeIf { it.isNotBlank() }?.let { library.texture(it) }
                } catch (failure: IOException) {
                    store.log(I18n.t("miscA2.skinService.offlinePreviewUnavailable", failure.message))
                    null
                } catch (failure: IllegalArgumentException) {
                    store.log(I18n.t("miscA2.skinService.offlinePreviewUnavailable", failure.message))
                    null
                } catch (failure: IllegalStateException) {
                    store.log(I18n.t("miscA2.skinService.offlinePreviewUnavailable", failure.message))
                    null
                }
            } else {
                AccountSkinCache(store, network).load(account, refresh)
            }

        suspend fun exportCurrent(
            accountId: String,
            destination: Path,
        ) {
            val account = store.data.accounts.find { it.id == accountId } ?: error(I18n.t("core.skinService.2"))
            val file = texture(account) ?: error(I18n.t("core.skinService.7"))
            exportSkinTexture(file, destination)
        }
    }
