// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责外置认证服务器的元数据校验、去重保存与显示名查询。

package dev.molifox.core

import dev.molifox.core.i18n.I18n
import kotlinx.coroutines.CancellationException
import java.io.IOException
import java.net.URI
import java.time.Instant

/** 外置认证服务器的查询与维护；[AccountService] 以委托方式实现，界面调用方式不变。 */
interface AuthlibServerApi {
    fun authlibServers(): List<AuthlibServer>

    suspend fun addAuthlibServer(serverUrl: String): Result<AuthlibServer>

    fun authlibServerName(serverUrl: String): String

    fun isBuiltinAuthlibServer(serverUrl: String): Boolean

    fun removeAuthlibServer(serverUrl: String): Boolean

    suspend fun authlibMetadata(serverUrl: String): String
}

internal class AuthlibServers(
    private val store: LauncherStore,
    private val client: AuthlibClient,
) : AuthlibServerApi {
    /** 已保存的外置认证服务器（供界面展示名称、选择与删除，不含任何令牌）。 */
    override fun authlibServers(): List<AuthlibServer> = store.data.authlibServers.toList()

    /** 校验并保存外置认证服务器：先请求 Yggdrasil 元数据，成功后按规范化 URL 去重保存。 同一 URL 的重复添加会更新显示名，不会产生重复条目。 */
    override suspend fun addAuthlibServer(serverUrl: String): Result<AuthlibServer> =
        try {
            Result.success(persistAuthlibServer(store, client.metadata(serverUrl)))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: AuthlibException) {
            Result.failure(error)
        } catch (error: IOException) {
            Result.failure(invalidServerFailure(error.message))
        }

    /** 非挂起显示名：账户列表/卡片可直接调用，不发起网络请求。 已保存的服务器取缓存名称，未保存时退回主机名（仍无法识别则为空字符串）。 */
    override fun authlibServerName(serverUrl: String): String {
        val normalized = normalizeAuthlibServerUrl(serverUrl) ?: return ""
        return store.data.authlibServers
            .find { matchesAuthlibServer(it.url, normalized) }
            ?.name
            ?: runCatching { URI(normalized).host }.getOrNull().orEmpty()
    }

    /**
     * 是否为内置默认服务器（[DEFAULT_AUTHLIB_SERVER]，当前是 LittleSkin）。
     *
     * 内置项由 [LauncherData] 默认值播种、不可删除；比对走 [matchesAuthlibServer]，因此站点根地址、
     * `/api/yggdrasil` 形式与末尾多一个 `/` 的写法都算同一台。界面用它隐藏 / 禁用删除入口，
     * [removeAuthlibServer] 再用它兜底拒绝一次。
     */
    override fun isBuiltinAuthlibServer(serverUrl: String): Boolean {
        val normalized = normalizeAuthlibServerUrl(serverUrl) ?: return false
        return matchesAuthlibServer(DEFAULT_AUTHLIB_SERVER.url, normalized)
    }

    /**
     * 删除已保存的服务器；账户本身保留，仍可用已存的令牌继续登录。
     * 内置默认服务器（LittleSkin）不可删除，返回 false 且不改动任何数据。
     */
    override fun removeAuthlibServer(serverUrl: String): Boolean {
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
    override suspend fun authlibMetadata(serverUrl: String): String {
        val normalized =
            normalizeAuthlibServerUrl(serverUrl)
                ?: throw AuthlibException(AuthlibError.InvalidServer, I18n.t("core.authlibClient.4"))
        store.data.authlibServers
            .find { matchesAuthlibServer(it.url, normalized) }
            ?.let {
                return it.name
            }
        return client.metadata(normalized).name
    }

    private fun invalidServerFailure(message: String?): AuthlibException =
        AuthlibException(AuthlibError.InvalidServer, message ?: I18n.t("core.authlibClient.4"))
}

/**
 * 保存已验证的服务器；同一规范化 URL 只保留一条，重复添加时更新显示名。
 *
 * 去重按 [matchesAuthlibServer] 判断，与删除 / 显示名查找同一套口径：内置默认项、 带或不带 `/api/yggdrasil`、以及末尾多一个 `/`
 * 的写法都算同一个服务器， 不会因为站点根地址与 API 根地址两种写法并存而多出一条 LittleSkin。
 */
internal fun persistAuthlibServer(
    store: LauncherStore,
    metadata: AuthlibMetadata,
): AuthlibServer {
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
internal fun matchesAuthlibServer(
    savedUrl: String,
    normalized: String,
): Boolean =
    savedUrl == normalized ||
        savedUrl == "$normalized/api/yggdrasil" ||
        (
            savedUrl.endsWith("/api/yggdrasil") &&
                savedUrl.removeSuffix("/api/yggdrasil") == normalized
        )
