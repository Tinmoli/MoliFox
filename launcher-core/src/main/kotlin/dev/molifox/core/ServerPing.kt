// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责服务器地址解析、状态查询和 MOTD 转换。

package dev.molifox.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress

/**
 * MOTD 的一段文字与它最终生效的样式。
 *
 * [color] 是 `0xRRGGBB`（不含 alpha）；null 表示沿用界面默认文字色。 [bold] / [italic] / [underlined] / [struck]
 * 是「这一段是否开启」，不是增量。
 */
data class MotdSegment(
    val text: String,
    val color: Int?,
    val bold: Boolean,
    val italic: Boolean,
    val underlined: Boolean,
    val struck: Boolean,
)

/**
 * 一次服务器列表查询的结果。
 *
 * [ping] 是握手到状态响应读完的毫秒数（不含 DNS 解析）。查询失败时不会构造本对象， 而是返回 null；因此 [online] 目前对任何非 null 结果都是
 * true；该字段描述缓存及协议降级后的在线状态。
 */
data class ServerStatus(
    val online: Boolean,
    val players: Int,
    val maxPlayers: Int,
    val motd: List<MotdSegment>,
    val version: String,
    val ping: Int,
    /** 服务端状态协议自愿公开的玩家名称样本；可能为空或少于实际在线人数。 */
    val playerSample: List<String> = emptyList(),
)

/** 从 `servers.dat` 的 `ip` 字符串解析出的地址；[port] 缺省 [DEFAULT_SERVER_PORT]。 */
data class ServerAddress(
    val host: String,
    val port: Int = DEFAULT_SERVER_PORT,
)

const val DEFAULT_SERVER_PORT = 25565

private const val DEFAULT_TIMEOUT_MILLIS = 4_000L

/** 两次尝试共用一个总预算：旧版兜底只剩不到这个时间就不再发第二次连接。 */
private const val MIN_TIMEOUT_MILLIS = 250

/** 端口合法上限（TCP 端口是 unsigned short），下限固定为 1。 */
private const val MAX_SERVER_PORT = 65535

/**
 * 解析服务器地址：`example.com` / `example.com:25566` / `[::1]:25565`。
 *
 * 只做字符串解析，**不查询 SRV 记录**：`_minecraft._tcp` 的 SRV 查询在 [pingAddress] 里进行， 因为只有真正连接时才需要它，且没有显式端口
 * （25565）时才会查。空串、含空白或控制字符、 端口越界、括号不成对都返回 null，从不抛异常。
 */
fun parseServerAddress(address: String): ServerAddress? {
    val raw = address.trim()
    val invalid =
        raw.isEmpty() ||
            raw.length > 255 ||
            raw.any { it.isWhitespace() || it.code < 0x20 }
    return if (invalid) {
        null
    } else {
        when {
            // 带方括号的 IPv6 字面量，端口写在括号之后。
            raw.startsWith("[") -> bracketedAddress(raw)
            // 只有一个冒号才拆端口；多个冒号视为不带端口的 IPv6 字面量。
            raw.count { it == ':' } == 1 -> hostPortAddress(raw)
            else -> ServerAddress(raw)
        }
    }
}

/** `[::1]` 或 `[::1]:25566`；括号不成对、端口越界返回 null。 */
private fun bracketedAddress(raw: String): ServerAddress? {
    val end = raw.indexOf(']')
    if (end <= 1) return null
    val host = raw.substring(1, end)
    val rest = raw.substring(end + 1)
    return when {
        rest.isEmpty() -> ServerAddress(host)
        !rest.startsWith(':') -> null
        else -> {
            val port = rest.substring(1).toIntOrNull()
            port?.takeIf { it in 1..MAX_SERVER_PORT }?.let { ServerAddress(host, it) }
        }
    }
}

/** `example.com:25566`；主机为空、端口不是数字或越界返回 null。 */
private fun hostPortAddress(raw: String): ServerAddress? {
    val host = raw.substringBefore(':')
    val port = raw.substringAfter(':').toIntOrNull()
    return if (host.isEmpty() || port == null || port !in 1..MAX_SERVER_PORT) {
        null
    } else {
        ServerAddress(host, port)
    }
}

/**
 * 查询一个 Minecraft 服务器的状态，失败返回 null（从不抛异常）。
 *
 * 先走现代 Server List Ping（握手 next state = 1 + status 请求），失败时退回旧版 `0xFE 0x01` 查询；整段在 [Dispatchers.IO]
 * 上执行，连接与读取共用 [timeoutMillis] （会被限制在 250 ms–30 s）。DNS 解析不受 socket 超时约束，可能额外多花一点时间。
 */
suspend fun ping(
    host: String,
    port: Int = DEFAULT_SERVER_PORT,
    timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
): ServerStatus? {
    val target = host.trim()
    if (target.isEmpty() || port !in 1..MAX_SERVER_PORT) return null
    val timeout = timeoutMillis.coerceIn(250L, 30_000L).toInt()
    return withContext(Dispatchers.IO) {
        try {
            pingServerTargets(ServerAddress(target, port), serverSocketAddresses(target, port), timeout)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }
}

/**
 * 直接查询 `servers.dat` 里那种 `host:port` 字符串；地址解析失败返回 null。
 *
 * 默认端口的域名先查 SRV，使用系统返回的真实主机和端口；只有确认无记录才按原地址连接。
 */
suspend fun pingAddress(
    address: String,
    timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
): ServerStatus? = queryServerAddress(address, timeoutMillis) { _, _ -> }

internal suspend fun queryServerAddress(
    address: String,
    timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
    log: (String, Boolean) -> Unit,
): ServerStatus? =
    withContext(Dispatchers.IO) {
        val parsed = parseServerAddress(address) ?: return@withContext null
        val target =
            resolvePingTarget(parsed) { host ->
                minecraftSrvResolver.lookup(host).also { result ->
                    if (result is SrvLookup.Failed) {
                        log(
                            "Server status SRV $host: ${result.error.stackTraceToString()}",
                            true,
                        )
                    }
                }
            } ?: return@withContext null
        log("Server status $address -> ${target.host}:${target.port}", false)
        try {
            val connections = serverSocketAddresses(target.host, target.port)
            val status =
                pingServerTargets(target, connections, timeoutMillis.coerceIn(250L, 30_000L).toInt()) {
                    log("Server status $address: $it", true)
                }
            if (status == null) log("Server status $address: no valid status response", true)
            status
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: IOException) {
            log("Server status DNS ${target.host}: ${failure.javaClass.simpleName}: ${failure.message}", true)
            null
        }
    }

private fun serverSocketAddresses(
    host: String,
    port: Int,
): List<InetSocketAddress> = InetAddress.getAllByName(host).distinct().map { InetSocketAddress(it, port) }

/** 多地址共享总预算；单个 IPv4/IPv6 不可达时，仍给其余地址留出机会。 */
internal fun pingServerTargets(
    target: ServerAddress,
    connections: List<InetSocketAddress>,
    timeoutMillis: Int,
    log: (String) -> Unit = {},
): ServerStatus? {
    val deadline = System.nanoTime() + timeoutMillis * 1_000_000L
    for ((index, connection) in connections.withIndex()) {
        val remaining = ((deadline - System.nanoTime()) / 1_000_000L).toInt()
        if (remaining <= 0) break
        val budget = (remaining / (connections.size - index)).coerceAtLeast(1)
        val started = System.nanoTime()
        val report: (Exception) -> Unit = {
            log("$connection: ${it.javaClass.simpleName}: ${it.message}")
        }
        var status = modernPing(target.host, target.port, budget, connection, report)
        val legacyBudget = budget - ((System.nanoTime() - started) / 1_000_000L).toInt()
        if (status == null && legacyBudget >= MIN_TIMEOUT_MILLIS) {
            status = legacyPing(target.host, target.port, legacyBudget, connection, report)
        }
        if (status != null) return status
    }
    return null
}
