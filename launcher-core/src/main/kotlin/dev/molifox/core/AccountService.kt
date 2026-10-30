// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责账户创建、登录、启动凭据刷新和本地退出。

package dev.molifox.core

import kotlinx.coroutines.CoroutineScope

class AccountService(
    private val store: LauncherStore,
    private val network: NetworkClient,
    private val scope: CoroutineScope,
    internal val authlibClient: AuthlibClient = AuthlibClient(network),
    servers: AuthlibServerApi = AuthlibServers(store, authlibClient),
) : AuthlibServerApi by servers {
    val vault = TokenVault(store)
    internal val authlibInjector = AuthlibInjector(network, authlibClient)
    private val authlibAccounts = AuthlibAccounts(store, vault, authlibClient)
    private val microsoftLogin = MicrosoftLogin(store, network, vault, scope)

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
    ): Result<Account> = authlibAccounts.addAccount(serverUrl, username, password)

    /** 开始微软设备码登录；返回的验证码与过期时间由界面展示。 */
    suspend fun beginMicrosoftLogin(): DeviceCode = microsoftLogin.begin()

    fun cancelLogin() {
        microsoftLogin.cancel()
    }

    /**
     * 当前（或指定）账户的启动凭据：离线账户直接用本地 UUID，外置账户与微软账户分别交给各自的登录实现，
     * 必要时刷新令牌并同步游戏档案。
     */
    suspend fun credentials(accountId: String? = store.data.activeAccountId): GameCredentials {
        val account = store.data.accounts.find { it.id == accountId } ?: error("请先添加并选择游戏账户")
        return when (account.type) {
            AccountType.Offline -> GameCredentials(account.name, account.uuid)
            AccountType.Authlib -> authlibAccounts.credentials(account)
            AccountType.Microsoft -> microsoftLogin.credentials(account)
        }
    }
}
