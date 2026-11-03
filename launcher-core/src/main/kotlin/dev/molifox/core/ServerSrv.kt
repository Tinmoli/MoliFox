// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责解析 Minecraft 服务器的 SRV 记录：没有显式端口时按 `_minecraft._tcp` 找到真实主机与端口。

package dev.molifox.core

import dev.molifox.core.platform.systemDnsResolver
import kotlinx.coroutines.CancellationException
import java.io.IOException
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask

/** SRV 解析结果：Minecraft 客户端实际连接的主机与端口。 */
data class SrvTarget(
    val host: String,
    val port: Int,
)

/** SRV 查询名固定为 `_minecraft._tcp.<host>`（RFC 2782 的服务名约定）。 */
internal const val MINECRAFT_SRV_PREFIX = "_minecraft._tcp."

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

/** 只调用平台系统解析器；不自行选择公共 DNS 或通过命令行查询。 */
internal fun querySrvRecords(host: String): List<String> = systemDnsResolver().querySrv(MINECRAFT_SRV_PREFIX + host)

internal sealed interface SrvLookup {
    data class Found(
        val target: SrvTarget,
    ) : SrvLookup

    data object Absent : SrvLookup

    data object Unavailable : SrvLookup

    data class Failed(
        val error: Throwable,
    ) : SrvLookup
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
        val result: SrvLookup,
        val expiresAt: Long,
    )

    private val cache = ConcurrentHashMap<String, Entry>()
    private val inFlight = ConcurrentHashMap<String, FutureTask<SrvLookup>>()

    fun lookup(host: String): SrvLookup {
        val key = host.lowercase(Locale.ROOT).trimEnd('.')
        cached(key)?.let { return it }
        val candidate = FutureTask { cached(key) ?: load(key) }
        val running = inFlight.putIfAbsent(key, candidate) ?: candidate
        try {
            if (running === candidate) candidate.run()
            return running.get()
        } catch (failure: ExecutionException) {
            throw (failure.cause ?: failure)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw CancellationException("System DNS wait interrupted").apply { initCause(interrupted) }
        } finally {
            if (running === candidate) inFlight.remove(key, candidate)
        }
    }

    private fun cached(key: String): SrvLookup? = cache[key]?.takeIf { it.expiresAt > clock() }?.result

    private fun load(key: String): SrvLookup {
        val result =
            try {
                classifySrvRecords(query(key))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: IOException) {
                SrvLookup.Failed(failure)
            } catch (failure: LinkageError) {
                SrvLookup.Failed(failure)
            }
        if (result !is SrvLookup.Failed) {
            val ttl = if (result is SrvLookup.Found) POSITIVE_CACHE_MILLIS else NEGATIVE_CACHE_MILLIS
            cache[key] = Entry(result, clock() + ttl)
        }
        return result
    }

    fun clearCache() = cache.clear()
}

internal val minecraftSrvResolver = SrvResolver()

internal fun classifySrvRecords(records: List<String>): SrvLookup {
    selectSrvTarget(records)?.let { return SrvLookup.Found(it) }
    return when {
        records.isEmpty() -> SrvLookup.Absent

        records.any {
            it.trim().split(Regex("\\s+")).let { fields ->
                fields.size == SRV_RECORD_MIN_FIELDS &&
                    fields.last() == "."
            }
        } -> SrvLookup.Unavailable

        else -> SrvLookup.Failed(IOException("System DNS returned malformed SRV records"))
    }
}

/**
 * 决定 ping 真正连接的目标：
 * - 端口是 [DEFAULT_SERVER_PORT]（没有显式端口）且主机不是 IP 字面量时才查 SRV；
 * - 用户显式写了其他端口时按原样连接，和原版客户端一致；
 * - 确认没有记录时回退原地址；查询失败或服务明确不可用时停止，不猜默认端口。
 */
internal fun resolvePingTarget(
    address: ServerAddress,
    resolveSrv: (String) -> SrvLookup = minecraftSrvResolver::lookup,
): ServerAddress? {
    if (address.port != DEFAULT_SERVER_PORT || address.host.isIpLiteral()) return address
    return when (val result = resolveSrv(address.host)) {
        is SrvLookup.Found -> ServerAddress(result.target.host, result.target.port)
        SrvLookup.Absent -> address
        else -> null
    }
}

/** 只把点分 IPv4 与含冒号的 IPv6 当作字面量，避免对 IP 做无意义的 SRV 查询。 */
internal fun String.isIpLiteral(): Boolean = contains(':') || IPV4_LITERAL.matches(this)
