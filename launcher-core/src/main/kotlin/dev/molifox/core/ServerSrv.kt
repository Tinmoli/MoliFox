// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责解析 Minecraft 服务器的 SRV 记录：没有显式端口时按 `_minecraft._tcp` 找到真实主机与端口。

package dev.molifox.core

import kotlinx.coroutines.CancellationException
import java.io.IOException
import java.util.Hashtable
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.naming.NameNotFoundException
import javax.naming.NamingException
import javax.naming.directory.InitialDirContext

/** SRV 解析结果：Minecraft 客户端实际连接的主机与端口。 */
data class SrvTarget(
    val host: String,
    val port: Int,
)

/** SRV 查询名固定为 `_minecraft._tcp.<host>`（RFC 2782 的服务名约定）。 */
internal const val MINECRAFT_SRV_PREFIX = "_minecraft._tcp."

private const val SRV_LOOKUP_TIMEOUT_MILLIS = 1_500L
private const val POSITIVE_CACHE_MILLIS = 5 * 60_000L
private const val NEGATIVE_CACHE_MILLIS = 60_000L
private const val MAX_PORT = 65535
private const val SRV_RECORD_MIN_FIELDS = 4
private val IPV4_LITERAL = Regex("""\d{1,3}(?:\.\d{1,3}){3}""")

/**
 * 从 DNS 返回的 SRV 文本记录里选一条：优先取优先级最低的，同优先级取权重最高的。
 *
 * 文本格式为 `priority weight port target`；字段缺失、数字非法、端口越界、目标为空或目标为 `.` （服务明确不可用）的记录都会被忽略。
 * 目标末尾的根域点会去掉。
 */
internal fun selectSrvTarget(records: List<String>): SrvTarget? =
    records
        .mapNotNull(::parseSrvRecord)
        .minWithOrNull(compareBy<SrvRecord>({ it.priority }, { -it.weight }))
        ?.let { SrvTarget(it.target, it.port) }

private data class SrvRecord(
    val priority: Int,
    val weight: Int,
    val port: Int,
    val target: String,
)

private fun parseSrvRecord(record: String): SrvRecord? {
    val fields = record.trim().split(Regex("\\s+"))
    if (fields.size < SRV_RECORD_MIN_FIELDS) return null
    val priority = fields[0].toIntOrNull()?.takeIf { it >= 0 } ?: return null
    val weight = fields[1].toIntOrNull()?.takeIf { it >= 0 } ?: return null
    val port = fields[2].toIntOrNull()?.takeIf { it in 1..MAX_PORT } ?: return null
    val target = fields[3].trimEnd('.')
    if (target.isEmpty()) return null
    return SrvRecord(priority, weight, port, target)
}

/**
 * 用 JNDI 自带的 DNS 提供者查询 SRV 记录。
 *
 * 只做 DNS 查询、不打开游戏连接；确认没有记录时返回空列表。临时失败交给解析器记录，不作为缺失记录缓存。
 */
internal fun querySrvRecords(host: String): List<String> {
    val environment =
        Hashtable<String, String>().apply {
            put("java.naming.factory.initial", "com.sun.jndi.dns.DnsContextFactory")
            put("java.naming.provider.url", "dns:")
            put("com.sun.jndi.dns.timeout.initial", SRV_LOOKUP_TIMEOUT_MILLIS.toString())
            put("com.sun.jndi.dns.timeout.retries", "1")
            put("com.sun.jndi.dns.recursion", "1")
        }
    val context = InitialDirContext(environment)
    return try {
        val attributes = context.getAttributes(MINECRAFT_SRV_PREFIX + host, arrayOf("SRV"))
        attributes
            .getAll()
            .asSequence()
            .flatMap { attribute -> attribute.all.asSequence().mapNotNull { it as? String } }
            .toList()
    } catch (_: NameNotFoundException) {
        emptyList()
    } finally {
        runCatching { context.close() }
    }
}

/**
 * 查询并缓存 SRV 结果；查询函数与时钟可注入，便于单元测试。
 *
 * 命中缓存 5 分钟，确认没有记录时缓存 1 分钟，避免最近游玩列表每次刷新都做一次慢 DNS 查询。
 */
internal class SrvResolver(
    private val query: (String) -> List<String> = ::querySrvRecords,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private class Entry(
        val target: SrvTarget?,
        val expiresAt: Long,
    )

    private val cache = ConcurrentHashMap<String, Entry>()

    fun resolve(host: String): SrvTarget? = resolve(host) {}

    fun resolve(
        host: String,
        onFailure: (Throwable) -> Unit,
    ): SrvTarget? {
        val key = host.lowercase(Locale.ROOT).trimEnd('.')
        val now = clock()
        cache[key]?.let { if (it.expiresAt > now) return it.target }
        val records =
            try {
                query(key)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: NamingException) {
                onFailure(failure)
                null
            } catch (failure: IOException) {
                onFailure(failure)
                null
            } catch (failure: LinkageError) {
                onFailure(failure)
                null
            }
        val target = records?.let(::selectSrvTarget)
        if (records != null) {
            val ttl = if (target != null) POSITIVE_CACHE_MILLIS else NEGATIVE_CACHE_MILLIS
            cache[key] = Entry(target, now + ttl)
        }
        return target
    }

    fun clearCache() = cache.clear()
}

internal val minecraftSrvResolver = SrvResolver()

/**
 * 决定 ping 真正连接的目标：
 * - 端口是 [DEFAULT_SERVER_PORT]（没有显式端口）且主机不是 IP 字面量时才查 SRV；
 * - 用户显式写了其他端口时按原样连接，和原版客户端一致；
 * - SRV 查询失败或没有记录时回退到解析出的地址。
 */
internal fun resolvePingTarget(
    address: ServerAddress,
    resolveSrv: (String) -> SrvTarget? = minecraftSrvResolver::resolve,
): ServerAddress {
    if (address.port != DEFAULT_SERVER_PORT || address.host.isIpLiteral()) return address
    val srv =
        try {
            resolveSrv(address.host)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        } ?: return address
    return ServerAddress(srv.host, srv.port)
}

/** 只把点分 IPv4 与含冒号的 IPv6 当作字面量，避免对 IP 做无意义的 SRV 查询。 */
internal fun String.isIpLiteral(): Boolean = contains(':') || IPV4_LITERAL.matches(this)
