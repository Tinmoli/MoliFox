// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责账户创建、登录、启动凭据刷新和本地退出。

package dev.molifox.core

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import java.time.Instant
import java.util.UUID
import kotlin.io.path.name

class AccountService(
    private val store: LauncherStore,
    private val network: NetworkClient,
    private val scope: CoroutineScope,
) {
    val vault = TokenVault(store)
    internal val authlibClient = AuthlibClient(network)
    internal val authlibInjector = AuthlibInjector(network, authlibClient)
    private var loginJob: Job? = null

    fun addOffline(name: String) {
        require(Regex("[A-Za-z0-9_]{3,16}").matches(name)) { "玩家名称需要 3–16 位字母、数字或下划线" }
        val existing =
            store.data.accounts.find { it.type == AccountType.Offline && it.name == name }
        val account = existing ?: Account(name = name, uuid = offlineUuid(name))
        store.mutate {
            it.copy(
                accounts = if (existing == null) it.accounts + account else it.accounts,
                activeAccountId = account.id,
            )
        }
    }

    fun select(id: String) {
        require(store.data.accounts.any { it.id == id })
        store.mutate { it.copy(activeAccountId = id) }
    }

    fun remove(id: String) {
        vault.remove(id)
        store.mutate { current ->
            val accounts = current.accounts.filter { it.id != id }
            current.copy(
                accounts = accounts,
                activeAccountId =
                    if (current.activeAccountId == id) {
                        accounts.firstOrNull()?.id
                    } else {
                        current.activeAccountId
                    },
            )
        }
    }

    /** 已保存的外置认证服务器（供界面展示名称、选择与删除，不含任何令牌）。 */
    fun authlibServers(): List<AuthlibServer> = store.data.authlibServers.toList()

    /** 校验并保存外置认证服务器：先请求 Yggdrasil 元数据，成功后按规范化 URL 去重保存。 同一 URL 的重复添加会更新显示名，不会产生重复条目。 */
    suspend fun addAuthlibServer(serverUrl: String): Result<AuthlibServer> =
        try {
            Result.success(persistAuthlibServer(authlibClient.metadata(serverUrl)))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: AuthlibException) {
            Result.failure(error)
        } catch (error: Exception) {
            Result.failure(
                AuthlibException(
                    AuthlibError.InvalidServer,
                    error.message ?: "不是有效的认证服务器地址",
                ),
            )
        }

    /** 非挂起显示名：账户列表/卡片可直接调用，不发起网络请求。 已保存的服务器取缓存名称，未保存时退回主机名（仍无法识别则为空字符串）。 */
    fun authlibServerName(serverUrl: String): String {
        val normalized = normalizeAuthlibServerUrl(serverUrl) ?: return ""
        return store.data.authlibServers
            .find { matchesAuthlibServer(it.url, normalized) }
            ?.name
            ?: runCatching { java.net.URI(normalized).host }.getOrNull().orEmpty()
    }

    /**
     * 是否为内置默认服务器（[DEFAULT_AUTHLIB_SERVER]，当前是 LittleSkin）。
     *
     * 内置项由 [LauncherData] 默认值播种、不可删除；比对走 [matchesAuthlibServer]，因此站点根地址、
     * `/api/yggdrasil` 形式与末尾多一个 `/` 的写法都算同一台。界面用它隐藏 / 禁用删除入口，
     * [removeAuthlibServer] 再用它兜底拒绝一次。
     */
    fun isBuiltinAuthlibServer(serverUrl: String): Boolean {
        val normalized = normalizeAuthlibServerUrl(serverUrl) ?: return false
        return matchesAuthlibServer(DEFAULT_AUTHLIB_SERVER.url, normalized)
    }

    /**
     * 删除已保存的服务器；账户本身保留，仍可用已存的令牌继续登录。
     * 内置默认服务器（LittleSkin）不可删除，返回 false 且不改动任何数据。
     */
    fun removeAuthlibServer(serverUrl: String): Boolean {
        if (isBuiltinAuthlibServer(serverUrl)) return false
        val normalized = normalizeAuthlibServerUrl(serverUrl) ?: serverUrl.trim()
        val target =
            store.data.authlibServers.find { matchesAuthlibServer(it.url, normalized) }
                ?: return false
        store.mutate { data ->
            data.copy(authlibServers = data.authlibServers.filterNot { it.url == target.url })
        }
        return true
    }

    /** 服务器显示名（账户卡片副标题）。已保存的服务器直接取缓存名称，不发起请求； 未保存的地址会先校验元数据，失败时抛出 [AuthlibException]。 */
    suspend fun authlibMetadata(serverUrl: String): String {
        val normalized =
            normalizeAuthlibServerUrl(serverUrl)
                ?: throw AuthlibException(AuthlibError.InvalidServer, "不是有效的认证服务器地址")
        store.data.authlibServers
            .find { matchesAuthlibServer(it.url, normalized) }
            ?.let {
                return it.name
            }
        return authlibClient.metadata(normalized).name
    }

    /**
     * 用外置认证服务器（LittleSkin 等）登录并保存账户。
     *
     * 返回的 [Result] 失败时异常一定是 [AuthlibException]，界面可通过 `error.error` 区分 密码错误 / 无法连接 / 不是 Yggdrasil
     * 元数据 / 需要重新登录等情况。
     */
    suspend fun addAuthlibAccount(
        serverUrl: String,
        username: String,
        password: String,
    ): Result<Account> {
        val playerName = username.trim()
        if (playerName.isBlank() || playerName.length > 320) {
            return Result.failure(
                AuthlibException(AuthlibError.InvalidCredentials, "请输入认证服务器上的用户名或邮箱"),
            )
        }
        if (password.isEmpty()) {
            return Result.failure(AuthlibException(AuthlibError.InvalidCredentials, "请输入密码"))
        }
        return try {
            // 地址先过元数据校验，既确认它是 Yggdrasil 服务器，也顺便记入服务器列表。
            val metadata = authlibClient.metadata(serverUrl)
            val server = persistAuthlibServer(metadata)
            val session = authlibClient.authenticate(server.url, playerName, password)
            val profile =
                session.selectedProfile
                    ?: session.availableProfiles.firstOrNull()
                    ?: throw AuthlibException(
                        AuthlibError.NoProfile,
                        "该账户在认证服务器上没有可用的游戏角色",
                    )
            val existing =
                store.data.accounts.find {
                    it.type == AccountType.Authlib &&
                        it.authlibServer == server.url &&
                        it.uuid.equals(profile.id, ignoreCase = true)
                }
            val account =
                Account(
                    id = existing?.id ?: UUID.randomUUID().toString(),
                    name = profile.name,
                    uuid = profile.id,
                    type = AccountType.Authlib,
                    localSkinId = existing?.localSkinId.orEmpty(),
                    skinVariant = existing?.skinVariant ?: SkinVariant.Classic,
                    authlibServer = server.url,
                )
            vault.save(account.id, encodeAuthlibSession(session, server.url, playerName))
            store.mutate {
                it.copy(
                    accounts = it.accounts.filter { item -> item.id != existing?.id } + account,
                    activeAccountId = account.id,
                )
            }
            store.notify("已登录外置账户：${account.name}")
            store.log("外置账户登录成功：${account.name}（${server.name}）")
            Result.success(account)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: AuthlibException) {
            Result.failure(error)
        } catch (error: Exception) {
            Result.failure(
                AuthlibException(
                    AuthlibError.InvalidResponse,
                    error.message ?: "外置账户登录失败",
                ),
            )
        }
    }

    suspend fun beginMicrosoftLogin(): DeviceCode {
        val clientId =
            store.data.settings.microsoftClientId
                .trim()
        require(runCatching { UUID.fromString(clientId) }.isSuccess) {
            "请先在设置中填写 Microsoft 应用客户端 ID，并启用公共客户端设备登录"
        }
        cancelLogin()
        val data =
            network.form(
                "https://login.microsoftonline.com/consumers/oauth2/v2.0/devicecode",
                mapOf("client_id" to clientId, "scope" to "XboxLive.signin offline_access"),
            )
        check(!data.has("error")) {
            "无法开始微软登录：${data.string("error_description", data.string("error"))}"
        }
        val deviceCode = data.string("device_code")
        val expiresAt = System.currentTimeMillis() + data.int("expires_in", 900) * 1000L
        loginJob =
            scope.launch(Dispatchers.IO) {
                try {
                    var interval = maxOf(5, data.int("interval", 5))
                    while (System.currentTimeMillis() < expiresAt) {
                        delay(interval * 1000L)
                        val tokens =
                            network.form(
                                "https://login.microsoftonline.com/consumers/oauth2/v2.0/token",
                                mapOf(
                                    "client_id" to clientId,
                                    "grant_type" to "urn:ietf:params:oauth:grant-type:device_code",
                                    "device_code" to deviceCode,
                                ),
                            )
                        when (tokens.string("error")) {
                            "authorization_pending" -> continue
                            "slow_down" -> {
                                interval += 5
                                continue
                            }
                            "" -> {
                                val credentials = exchangeMinecraft(tokens.string("access_token"))
                                ensureActive()
                                val existing =
                                    store.data.accounts.find {
                                        it.type == AccountType.Microsoft &&
                                            it.uuid == credentials.uuid
                                    }
                                val account =
                                    Account(
                                        existing?.id ?: UUID.randomUUID().toString(),
                                        credentials.name,
                                        credentials.uuid,
                                        AccountType.Microsoft,
                                        credentials.skinUrl,
                                        skinVariant = credentials.skinVariant,
                                    )
                                vault.save(account.id, tokens.string("refresh_token"))
                                store.mutate {
                                    it.copy(
                                        accounts =
                                            it.accounts.filter { account ->
                                                account.id != existing?.id
                                            } + account,
                                        activeAccountId = account.id,
                                    )
                                }
                                store.notify("已登录：${account.name}")
                                store.log("微软账户登录成功：${account.name}")
                                return@launch
                            }
                            else -> error("登录已过期、被拒绝或取消，请重新登录")
                        }
                    }
                    error("登录验证码已过期")
                } catch (_: CancellationException) {
                    store.log("微软登录已取消")
                } catch (error: Exception) {
                    store.log(error.message ?: "微软登录失败", true)
                    store.notify(error.message ?: "微软登录失败")
                }
            }
        return DeviceCode(data.string("user_code"), data.string("verification_uri"), expiresAt)
    }

    fun cancelLogin() {
        loginJob?.cancel()
        loginJob = null
    }

    suspend fun credentials(accountId: String? = store.data.activeAccountId): GameCredentials {
        val account = store.data.accounts.find { it.id == accountId } ?: error("请先添加并选择游戏账户")
        if (account.type == AccountType.Offline) return GameCredentials(account.name, account.uuid)
        if (account.type == AccountType.Authlib) return authlibCredentials(account)
        val refresh = vault.read(account.id) ?: error("此账户的登录凭据不可用，请重新登录微软账户")
        val tokens =
            network.form(
                "https://login.microsoftonline.com/consumers/oauth2/v2.0/token",
                mapOf(
                    "client_id" to store.data.settings.microsoftClientId,
                    "grant_type" to "refresh_token",
                    "refresh_token" to refresh,
                    "scope" to "XboxLive.signin offline_access",
                ),
            )
        check(!tokens.has("error")) { "微软登录已失效，请重新登录" }
        vault.save(account.id, tokens.string("refresh_token", refresh))
        val credentials = exchangeMinecraft(tokens.string("access_token"))
        require(credentials.uuid == account.uuid) { "登录账户与保存的游戏档案不匹配" }
        if (
            account.name != credentials.name ||
            account.skinUrl != credentials.skinUrl ||
            account.skinVariant != credentials.skinVariant
        ) {
            store.mutate {
                it.copy(
                    accounts =
                        it.accounts.map { item ->
                            if (item.id == account.id) {
                                item.copy(
                                    name = credentials.name,
                                    skinUrl = credentials.skinUrl,
                                    skinVariant = credentials.skinVariant,
                                )
                            } else {
                                item
                            }
                        },
                )
            }
        }
        return credentials
    }

    /**
     * 外置认证账户的启动凭据：校验令牌，过期则续期；续期失败抛出 [AuthlibError.SessionExpired]， 网络失败抛出
     * [AuthlibError.Network]，界面据此决定是提示重登还是检查网络。
     */
    private suspend fun authlibCredentials(account: Account): GameCredentials {
        val server =
            normalizeAuthlibServerUrl(account.authlibServer)
                ?: throw AuthlibException(
                    AuthlibError.InvalidServer,
                    "此账户缺少有效的认证服务器地址，请重新添加账户",
                )
        val stored =
            readAuthlibSession(account.id)
                ?: throw AuthlibException(
                    AuthlibError.SessionExpired,
                    "外置账户登录已过期，请重新登录",
                )
        val session =
            try {
                if (authlibClient.validate(server, stored.accessToken, stored.clientToken)) {
                    AuthlibSession(
                        server,
                        stored.accessToken,
                        stored.clientToken,
                        stored.username,
                        null,
                    )
                } else {
                    val refreshed =
                        authlibClient.refresh(server, stored.accessToken, stored.clientToken)
                    vault.save(
                        account.id,
                        encodeAuthlibSession(refreshed, server, stored.username),
                    )
                    refreshed
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: AuthlibException) {
                throw error
            } catch (_: Exception) {
                throw AuthlibException(AuthlibError.InvalidResponse, "认证服务器返回的数据无效")
            }
        val profile = session.selectedProfile
        val name = profile?.name ?: account.name
        val uuid = profile?.id ?: account.uuid
        if (name != account.name || !uuid.equals(account.uuid, ignoreCase = true)) {
            store.mutate {
                it.copy(
                    accounts =
                        it.accounts.map { item ->
                            if (item.id == account.id) item.copy(name = name, uuid = uuid) else item
                        },
                )
            }
        }
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

    /**
     * 保存已验证的服务器；同一规范化 URL 只保留一条，重复添加时更新显示名。
     *
     * 去重按 [matchesAuthlibServer] 判断，与删除 / 显示名查找同一套口径：内置默认项、 带或不带 `/api/yggdrasil`、以及末尾多一个 `/`
     * 的写法都算同一个服务器， 不会因为站点根地址与 API 根地址两种写法并存而多出一条 LittleSkin。
     */
    private fun persistAuthlibServer(metadata: AuthlibMetadata): AuthlibServer {
        val existing =
            store.data.authlibServers.find { matchesAuthlibServer(it.url, metadata.apiRoot) }
        if (existing != null && existing.name == metadata.name) return existing
        val server =
            AuthlibServer(
                url = metadata.apiRoot,
                name = metadata.name,
                addedAt = existing?.addedAt ?: Instant.now().toString(),
            )
        store.mutate { data ->
            val servers = data.authlibServers
            data.copy(
                authlibServers =
                    if (existing != null) {
                        servers.map { if (it.url == existing.url) server else it }
                    } else {
                        servers + server
                    },
            )
        }
        return server
    }

    /** 兼容用户输入站点根地址、而保存的是 `<根地址>/api/yggdrasil` 的情况。 */
    private fun matchesAuthlibServer(
        savedUrl: String,
        normalized: String,
    ): Boolean =
        savedUrl == normalized ||
            savedUrl == "$normalized/api/yggdrasil" ||
            (
                savedUrl.endsWith("/api/yggdrasil") &&
                    savedUrl.removeSuffix("/api/yggdrasil") == normalized
            )

    private data class StoredAuthlibSession(
        val accessToken: String,
        val clientToken: String,
        val username: String,
    )

    private fun readAuthlibSession(accountId: String): StoredAuthlibSession? {
        val raw = vault.read(accountId) ?: return null
        val json =
            runCatching { JsonParser.parseString(raw).asJsonObject }.getOrNull() ?: return null
        val accessToken = json.string("accessToken").trim()
        val clientToken = json.string("clientToken").trim()
        if (accessToken.isBlank() || clientToken.isBlank()) return null
        return StoredAuthlibSession(accessToken, clientToken, json.string("username"))
    }

    /** 令牌按现有凭据库格式（加密的单个 JSON 字符串）保存，绝不写入 launcher.json。 */
    private fun encodeAuthlibSession(
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

    private suspend fun exchangeMinecraft(microsoftToken: String): GameCredentials {
        val xboxBody =
            JsonObject().apply {
                add(
                    "Properties",
                    JsonObject().apply {
                        addProperty("AuthMethod", "RPS")
                        addProperty("SiteName", "user.auth.xboxlive.com")
                        addProperty("RpsTicket", "d=$microsoftToken")
                    },
                )
                addProperty("RelyingParty", "http://auth.xboxlive.com")
                addProperty("TokenType", "JWT")
            }
        val xbox = network.post("https://user.auth.xboxlive.com/user/authenticate", xboxBody)
        val xstsBody =
            JsonObject().apply {
                add(
                    "Properties",
                    JsonObject().apply {
                        addProperty("SandboxId", "RETAIL")
                        add(
                            "UserTokens",
                            com.google.gson
                                .JsonArray()
                                .apply { add(xbox.string("Token")) },
                        )
                    },
                )
                addProperty("RelyingParty", "rp://api.minecraftservices.com/")
                addProperty("TokenType", "JWT")
            }
        val xsts =
            try {
                network.post("https://xsts.auth.xboxlive.com/xsts/authorize", xstsBody)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                error("Xbox 验证失败：请确认已创建 Xbox 档案，并检查家庭账户权限")
            }
        val userHash =
            xsts
                .obj("DisplayClaims")
                .array("xui")[0]
                .asJsonObject
                .string("uhs")
        val login =
            try {
                network.post(
                    "https://api.minecraftservices.com/authentication/login_with_xbox",
                    JsonObject().apply {
                        addProperty("identityToken", "XBL3.0 x=$userHash;${xsts.string("Token")}")
                    },
                )
            } catch (error: ServiceHttpException) {
                if (error.statusCode == 403) {
                    error(
                        "Minecraft 拒绝了此应用的登录请求 (403)。请检查 MoliFox 应用的 Minecraft 接口访问审核及权限；这不一定是账户密码错误。",
                    )
                }
                throw error
            }
        val token = login.string("access_token")
        val profile =
            network.authorizedJson("https://api.minecraftservices.com/minecraft/profile", token)
        val activeSkin =
            profile
                .array("skins")
                .firstOrNull { it.asJsonObject.string("state") == "ACTIVE" }
                ?.asJsonObject
        val skinUrl = activeSkin?.string("url").orEmpty()
        return GameCredentials(
            profile.string("name"),
            profile.string("id"),
            token,
            "msa",
            skinUrl,
            if (activeSkin?.string("variant").equals("SLIM", true)) {
                SkinVariant.Slim
            } else {
                SkinVariant.Classic
            },
        )
    }
}
