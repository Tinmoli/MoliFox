// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责微软账户的设备码登录、令牌刷新与启动凭据。

package dev.molifox.core

import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.UUID

private const val MILLIS_PER_SECOND = 1000L
private const val DEVICE_CODE_EXPIRY_SECONDS = 900
private const val MIN_POLL_INTERVAL_SECONDS = 5
private const val DEFAULT_POLL_INTERVAL_SECONDS = 5
private const val SLOW_DOWN_STEP_SECONDS = 5
private const val HTTP_FORBIDDEN_STATUS = 403

internal class MicrosoftLogin(
    private val store: LauncherStore,
    private val network: NetworkClient,
    private val vault: TokenVault,
    private val scope: CoroutineScope,
) {
    private var loginJob: Job? = null

    /** 开始设备码登录，返回需要展示给用户的验证码与过期时间。 */
    suspend fun begin(): DeviceCode {
        val clientId =
            store.data.settings.microsoftClientId
                .trim()
        require(runCatching { UUID.fromString(clientId) }.isSuccess) {
            "请先在设置中填写 Microsoft 应用客户端 ID，并启用公共客户端设备登录"
        }
        cancel()
        val data =
            network.form(
                "https://login.microsoftonline.com/consumers/oauth2/v2.0/devicecode",
                mapOf("client_id" to clientId, "scope" to "XboxLive.signin offline_access"),
            )
        check(!data.has("error")) {
            "无法开始微软登录：${data.string("error_description", data.string("error"))}"
        }
        val deviceCode = data.string("device_code")
        val expiresAt =
            System.currentTimeMillis() +
                data.int("expires_in", DEVICE_CODE_EXPIRY_SECONDS) * MILLIS_PER_SECOND
        loginJob =
            startPolling(
                clientId,
                deviceCode,
                expiresAt,
                data.int("interval", DEFAULT_POLL_INTERVAL_SECONDS),
            )
        return DeviceCode(data.string("user_code"), data.string("verification_uri"), expiresAt)
    }

    fun cancel() {
        loginJob?.cancel()
        loginJob = null
    }

    /** 用保存的刷新令牌换取启动凭据，并同步档案名字与皮肤。 */
    suspend fun credentials(account: Account): GameCredentials {
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
        val credentials = exchange(tokens.string("access_token"))
        require(credentials.uuid == account.uuid) { "登录账户与保存的游戏档案不匹配" }
        syncProfile(account, credentials)
        return credentials
    }

    /** 后台轮询设备码结果；失败只写日志并提示界面，不打断作用域。 */
    private fun startPolling(
        clientId: String,
        deviceCode: String,
        expiresAt: Long,
        initialInterval: Int,
    ): Job =
        scope.launch(Dispatchers.IO) {
            try {
                awaitToken(clientId, deviceCode, expiresAt, initialInterval)
            } catch (_: CancellationException) {
                store.log("微软登录已取消")
            } catch (failure: IOException) {
                store.log(failure.message ?: "微软登录失败", true)
                store.notify(failure.message ?: "微软登录失败")
            } catch (failure: IllegalArgumentException) {
                store.log(failure.message ?: "微软登录失败", true)
                store.notify(failure.message ?: "微软登录失败")
            } catch (failure: IllegalStateException) {
                store.log(failure.message ?: "微软登录失败", true)
                store.notify(failure.message ?: "微软登录失败")
            } catch (failure: JsonParseException) {
                store.log(failure.message ?: "微软登录失败", true)
                store.notify(failure.message ?: "微软登录失败")
            }
        }

    /** 轮询设备码结果，直到拿到令牌、被拒绝或验证码过期。 */
    private suspend fun awaitToken(
        clientId: String,
        deviceCode: String,
        expiresAt: Long,
        initialInterval: Int,
    ) {
        var interval = maxOf(MIN_POLL_INTERVAL_SECONDS, initialInterval)
        while (System.currentTimeMillis() < expiresAt) {
            delay(interval * MILLIS_PER_SECOND)
            val tokens =
                network.form(
                    "https://login.microsoftonline.com/consumers/oauth2/v2.0/token",
                    mapOf(
                        "client_id" to clientId,
                        "grant_type" to "urn:ietf:params:oauth:grant-type:device_code",
                        "device_code" to deviceCode,
                    ),
                )
            val errorCode = tokens.string("error")
            when {
                errorCode == "slow_down" -> interval += SLOW_DOWN_STEP_SECONDS
                errorCode.isEmpty() -> {
                    saveAccount(tokens)
                    return
                }
                errorCode != "authorization_pending" ->
                    error("登录已过期、被拒绝或取消，请重新登录")
            }
        }
        error("登录验证码已过期")
    }

    /** 保存新登录的账户与刷新令牌；已有同一档案的账户时复用其 id。 */
    private suspend fun saveAccount(tokens: JsonObject) {
        val credentials = exchange(tokens.string("access_token"))
        currentCoroutineContext().ensureActive()
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
                accounts = it.accounts.filter { item -> item.id != account.id } + account,
                activeAccountId = account.id,
            )
        }
        store.notify("已登录：${account.name}")
        store.log("微软账户登录成功：${account.name}")
    }

    /** Xbox Live → XSTS → Minecraft 服务，换到可直接启动游戏的档案凭据。 */
    private suspend fun exchange(microsoftToken: String): GameCredentials {
        val xbox = authenticateXbox(microsoftToken)
        val xsts = authorizeXsts(xbox)
        val userHash =
            xsts
                .obj("DisplayClaims")
                .array("xui")[0]
                .asJsonObject
                .string("uhs")
        val login = loginWithXbox(userHash, xsts)
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

    private suspend fun authenticateXbox(microsoftToken: String): JsonObject {
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
        return network.post("https://user.auth.xboxlive.com/user/authenticate", xboxBody)
    }

    private suspend fun authorizeXsts(xbox: JsonObject): JsonObject {
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
        return try {
            network.post("https://xsts.auth.xboxlive.com/xsts/authorize", xstsBody)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IOException) {
            error("Xbox 验证失败：请确认已创建 Xbox 档案，并检查家庭账户权限")
        } catch (_: IllegalArgumentException) {
            error("Xbox 验证失败：请确认已创建 Xbox 档案，并检查家庭账户权限")
        } catch (_: IllegalStateException) {
            error("Xbox 验证失败：请确认已创建 Xbox 档案，并检查家庭账户权限")
        } catch (_: JsonParseException) {
            error("Xbox 验证失败：请确认已创建 Xbox 档案，并检查家庭账户权限")
        }
    }

    private suspend fun loginWithXbox(
        userHash: String,
        xsts: JsonObject,
    ): JsonObject =
        try {
            network.post(
                "https://api.minecraftservices.com/authentication/login_with_xbox",
                JsonObject().apply {
                    addProperty("identityToken", "XBL3.0 x=$userHash;${xsts.string("Token")}")
                },
            )
        } catch (failure: ServiceHttpException) {
            if (failure.statusCode == HTTP_FORBIDDEN_STATUS) {
                error(
                    "Minecraft 拒绝了此应用的登录请求 (403)。" +
                        "请检查 MoliFox 应用的 Minecraft 接口访问审核及权限；这不一定是账户密码错误。",
                )
            }
            throw failure
        }

    /** 档案名字或皮肤变化时写回账户列表，避免界面显示旧信息。 */
    private fun syncProfile(
        account: Account,
        credentials: GameCredentials,
    ) {
        val changed =
            account.name != credentials.name ||
                account.skinUrl != credentials.skinUrl ||
                account.skinVariant != credentials.skinVariant
        if (!changed) return
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
}
