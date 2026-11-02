// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责微软账户的设备码登录、令牌刷新与启动凭据。

package dev.molifox.core

import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import dev.molifox.core.i18n.I18n
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
    private val network: NetworkPayloadApi,
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
            I18n.t("core.microsoftLogin.1")
        }
        cancel()
        val data =
            network.form(
                "https://login.microsoftonline.com/consumers/oauth2/v2.0/devicecode",
                mapOf("client_id" to clientId, "scope" to "XboxLive.signin offline_access"),
            )
        if (data.has("error")) {
            val code = data.string("error")
            val description = data.string("error_description")
            store.log("Microsoft device authorization failed ($code): $description", true)
            error(I18n.t("core.microsoftLogin.2", code))
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
        val refresh = vault.read(account.id) ?: error(I18n.t("core.microsoftLogin.3"))
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
        check(!tokens.has("error")) { I18n.t("core.microsoftLogin.4") }
        vault.save(account.id, tokens.string("refresh_token", refresh))
        val credentials = exchange(tokens.string("access_token"))
        require(credentials.uuid == account.uuid) { I18n.t("core.microsoftLogin.5") }
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
            } catch (cancelled: CancellationException) {
                store.log(I18n.t("miscA2.microsoftLogin.cancelled"))
                throw cancelled
            } catch (failure: IOException) {
                reportPollingFailure(store, failure)
            } catch (failure: IllegalArgumentException) {
                reportPollingFailure(store, failure)
            } catch (failure: IllegalStateException) {
                reportPollingFailure(store, failure)
            } catch (failure: JsonParseException) {
                reportPollingFailure(store, failure)
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
                    error(I18n.t("core.microsoftLogin.7"))
            }
        }
        error(I18n.t("core.microsoftLogin.8"))
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
        store.notify(I18n.t("core.microsoftLogin.9", account.name), LauncherNoticeKind.LoginSignedIn)
        store.log(I18n.t("core.microsoftLogin.10", account.name))
    }

    /** Xbox Live → XSTS → Minecraft 服务，换到可直接启动游戏的档案凭据。 */
    private suspend fun exchange(microsoftToken: String): GameCredentials {
        val xboxToken = authenticateXbox(microsoftToken)
        val xsts = authorizeXsts(xboxToken)
        val login = loginWithXbox(xsts)
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

    private suspend fun authenticateXbox(microsoftToken: String): String {
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
        return network.post("https://user.auth.xboxlive.com/user/authenticate", xboxBody).string("Token")
    }

    private suspend fun authorizeXsts(xboxToken: String): XstsAuthorization {
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
                                .apply { add(xboxToken) },
                        )
                    },
                )
                addProperty("RelyingParty", "rp://api.minecraftservices.com/")
                addProperty("TokenType", "JWT")
            }
        val response =
            runCatching {
                network.post("https://xsts.auth.xboxlive.com/xsts/authorize", xstsBody)
            }.getOrElse(::throwXstsFailure)
        return XstsAuthorization(
            token = response.string("Token"),
            userHash =
                response
                    .obj("DisplayClaims")
                    .array("xui")[0]
                    .asJsonObject
                    .string("uhs"),
        )
    }

    private suspend fun loginWithXbox(xsts: XstsAuthorization): JsonObject =
        try {
            network.post(
                "https://api.minecraftservices.com/authentication/login_with_xbox",
                JsonObject().apply {
                    addProperty("identityToken", "XBL3.0 x=${xsts.userHash};${xsts.token}")
                },
            )
        } catch (failure: ServiceHttpException) {
            if (failure.statusCode == HTTP_FORBIDDEN_STATUS) {
                error(
                    I18n.t("miscA2.microsoftLogin.appRejected403") + I18n.t("core.microsoftLogin.12"),
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

private data class XstsAuthorization(
    val token: String,
    val userHash: String,
)

private fun reportPollingFailure(
    store: LauncherStore,
    failure: Exception,
) {
    val message = I18n.t("core.microsoftLogin.6")
    store.log(failure.message?.takeIf(String::isNotBlank) ?: message, true)
    store.notify(message, LauncherNoticeKind.LoginEnded)
}

private fun throwXstsFailure(failure: Throwable): Nothing =
    throw if (failure.isExpectedLoginFailure()) {
        IllegalStateException(I18n.t("core.microsoftLogin.11"), failure)
    } else {
        failure
    }

private fun Throwable.isExpectedLoginFailure(): Boolean =
    this is IOException ||
        this is IllegalArgumentException ||
        this is IllegalStateException ||
        this is JsonParseException
