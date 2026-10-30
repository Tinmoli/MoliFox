// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责外置认证账户的登录、启动凭据续期与档案同步。

package dev.molifox.core

import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import java.io.IOException
import java.util.UUID

private const val MAX_PLAYER_NAME_LENGTH = 320

internal class AuthlibAccounts(
    private val store: LauncherStore,
    private val vault: TokenVault,
    private val client: AuthlibClient,
) {
    private data class StoredAuthlibSession(
        val accessToken: String,
        val clientToken: String,
        val username: String,
    )

    /**
     * 用外置认证服务器（LittleSkin 等）登录并保存账户。
     *
     * 返回的 [Result] 失败时异常一定是 [AuthlibException]，界面可通过 `error.error` 区分 密码错误 / 无法连接 / 不是 Yggdrasil
     * 元数据 / 需要重新登录等情况。
     */
    suspend fun addAccount(
        serverUrl: String,
        username: String,
        password: String,
    ): Result<Account> {
        val playerName = username.trim()
        if (playerName.isBlank() || playerName.length > MAX_PLAYER_NAME_LENGTH) {
            return Result.failure(
                AuthlibException(AuthlibError.InvalidCredentials, "请输入认证服务器上的用户名或邮箱"),
            )
        }
        if (password.isEmpty()) {
            return Result.failure(AuthlibException(AuthlibError.InvalidCredentials, "请输入密码"))
        }
        return try {
            Result.success(login(serverUrl, playerName, password))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: AuthlibException) {
            Result.failure(error)
        } catch (error: IOException) {
            Result.failure(loginFailure(error))
        } catch (error: IllegalArgumentException) {
            Result.failure(loginFailure(error))
        } catch (error: IllegalStateException) {
            Result.failure(loginFailure(error))
        } catch (error: JsonParseException) {
            Result.failure(loginFailure(error))
        }
    }

    /** 校验服务器元数据、用密码换令牌，并保存账户与凭据。 */
    private suspend fun login(
        serverUrl: String,
        playerName: String,
        password: String,
    ): Account {
        // 地址先过元数据校验，既确认它是 Yggdrasil 服务器，也顺便记入服务器列表。
        val metadata = client.metadata(serverUrl)
        val server = persistAuthlibServer(store, metadata)
        val session = client.authenticate(server.url, playerName, password)
        val profile =
            session.selectedProfile
                ?: session.availableProfiles.firstOrNull()
                ?: throw AuthlibException(
                    AuthlibError.NoProfile,
                    "该账户在认证服务器上没有可用的游戏角色",
                )
        val account = accountFor(server, profile)
        vault.save(account.id, encodeSession(session, server.url, playerName))
        store.mutate {
            it.copy(
                accounts = it.accounts.filter { item -> item.id != account.id } + account,
                activeAccountId = account.id,
            )
        }
        store.notify("已登录外置账户：${account.name}")
        store.log("外置账户登录成功：${account.name}（${server.name}）")
        return account
    }

    /** 复用同一服务器上同一角色的账户 id 与本地皮肤设置。 */
    private fun accountFor(
        server: AuthlibServer,
        profile: AuthlibProfile,
    ): Account {
        val existing =
            store.data.accounts.find {
                it.type == AccountType.Authlib &&
                    it.authlibServer == server.url &&
                    it.uuid.equals(profile.id, ignoreCase = true)
            }
        return Account(
            id = existing?.id ?: UUID.randomUUID().toString(),
            name = profile.name,
            uuid = profile.id,
            type = AccountType.Authlib,
            localSkinId = existing?.localSkinId.orEmpty(),
            skinVariant = existing?.skinVariant ?: SkinVariant.Classic,
            authlibServer = server.url,
        )
    }

    /** 登录阶段的未预期失败统一归为无效响应，界面只看到可操作的提示。 */
    private fun loginFailure(error: Exception): AuthlibException =
        AuthlibException(AuthlibError.InvalidResponse, error.message ?: "外置账户登录失败")

    /**
     * 启动凭据：校验令牌，过期则续期；续期失败抛出 [AuthlibError.SessionExpired]， 网络失败抛出
     * [AuthlibError.Network]，界面据此决定是提示重登还是检查网络。
     */
    suspend fun credentials(account: Account): GameCredentials {
        val (server, stored) = credentialsSource(account)
        val session = refreshSession(account, server, stored)
        val profile = session.selectedProfile
        val name = profile?.name ?: account.name
        val uuid = profile?.id ?: account.uuid
        syncAccount(account, name, uuid)
        return GameCredentials(
            name = name,
            uuid = uuid,
            accessToken = session.accessToken,
            userType = "mojang",
            skinUrl = account.skinUrl,
            skinVariant = account.skinVariant,
            authlibServer = server,
        )
    }

    /** 令牌仍有效就沿用，否则续期并写回；取消与认证失败原样向上传递。 */
    private suspend fun refreshSession(
        account: Account,
        server: String,
        stored: StoredAuthlibSession,
    ): AuthlibSession =
        try {
            if (client.validate(server, stored.accessToken, stored.clientToken)) {
                AuthlibSession(
                    server,
                    stored.accessToken,
                    stored.clientToken,
                    stored.username,
                    null,
                )
            } else {
                val refreshed =
                    client.refresh(server, stored.accessToken, stored.clientToken)
                vault.save(
                    account.id,
                    encodeSession(refreshed, server, stored.username),
                )
                refreshed
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: AuthlibException) {
            throw error
        } catch (_: IOException) {
            throw invalidSessionFailure()
        } catch (_: IllegalArgumentException) {
            throw invalidSessionFailure()
        } catch (_: IllegalStateException) {
            throw invalidSessionFailure()
        } catch (_: JsonParseException) {
            throw invalidSessionFailure()
        }

    /** 档案名字或 UUID 变化时写回账户列表。 */
    private fun syncAccount(
        account: Account,
        name: String,
        uuid: String,
    ) {
        if (name == account.name && uuid.equals(account.uuid, ignoreCase = true)) return
        store.mutate {
            it.copy(
                accounts =
                    it.accounts.map { item ->
                        if (item.id == account.id) item.copy(name = name, uuid = uuid) else item
                    },
            )
        }
    }

    /** 读取并校验外置账户的服务器地址与已保存会话；缺少任一项都按原提示拒绝。 */
    private fun credentialsSource(account: Account): Pair<String, StoredAuthlibSession> {
        val server =
            normalizeAuthlibServerUrl(account.authlibServer)
                ?: throw AuthlibException(
                    AuthlibError.InvalidServer,
                    "此账户缺少有效的认证服务器地址，请重新添加账户",
                )
        val stored =
            readSession(account.id)
                ?: throw AuthlibException(
                    AuthlibError.SessionExpired,
                    "外置账户登录已过期，请重新登录",
                )
        return server to stored
    }

    private fun invalidSessionFailure(): AuthlibException =
        AuthlibException(AuthlibError.InvalidResponse, "认证服务器返回的数据无效")

    private fun readSession(accountId: String): StoredAuthlibSession? {
        val raw = vault.read(accountId) ?: return null
        val json =
            runCatching { JsonParser.parseString(raw).asJsonObject }.getOrNull() ?: return null
        val accessToken = json.string("accessToken").trim()
        val clientToken = json.string("clientToken").trim()
        if (accessToken.isBlank() || clientToken.isBlank()) return null
        return StoredAuthlibSession(accessToken, clientToken, json.string("username"))
    }

    /** 令牌按现有凭据库格式（加密的单个 JSON 字符串）保存，绝不写入 launcher.json。 */
    private fun encodeSession(
        session: AuthlibSession,
        server: String,
        username: String,
    ): String =
        JsonObject()
            .apply {
                addProperty("server", server)
                addProperty("accessToken", session.accessToken)
                addProperty("clientToken", session.clientToken)
                addProperty("username", username.ifBlank { session.username })
            }.toString()
}
