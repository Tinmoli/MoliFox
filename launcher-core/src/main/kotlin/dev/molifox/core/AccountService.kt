package dev.molifox.core

import com.google.gson.JsonObject
import java.util.UUID
import kotlin.io.path.*
import kotlinx.coroutines.*

class AccountService(
    private val store: LauncherStore,
    private val network: NetworkClient,
    private val scope: CoroutineScope,
) {
    val vault = TokenVault(store)
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
                    if (current.activeAccountId == id) accounts.firstOrNull()?.id
                    else current.activeAccountId,
            )
        }
    }

    suspend fun beginMicrosoftLogin(): DeviceCode {
        val clientId = store.data.settings.microsoftClientId.trim()
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
        )
            store.mutate {
                it.copy(
                    accounts =
                        it.accounts.map { item ->
                            if (item.id == account.id)
                                item.copy(
                                    name = credentials.name,
                                    skinUrl = credentials.skinUrl,
                                    skinVariant = credentials.skinVariant,
                                )
                            else item
                        }
                )
            }
        return credentials
    }

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
                            com.google.gson.JsonArray().apply { add(xbox.string("Token")) },
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
        val userHash = xsts.obj("DisplayClaims").array("xui")[0].asJsonObject.string("uhs")
        val login =
            try {
                network.post(
                    "https://api.minecraftservices.com/authentication/login_with_xbox",
                    JsonObject().apply {
                        addProperty("identityToken", "XBL3.0 x=$userHash;${xsts.string("Token")}")
                    },
                )
            } catch (error: ServiceHttpException) {
                if (error.statusCode == 403)
                    error(
                        "Minecraft 拒绝了此应用的登录请求 (403)。请检查 MoliFox 应用的 Minecraft 接口访问审核及权限；这不一定是账户密码错误。"
                    )
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
            if (activeSkin?.string("variant").equals("SLIM", true)) SkinVariant.Slim
            else SkinVariant.Classic,
        )
    }
}
