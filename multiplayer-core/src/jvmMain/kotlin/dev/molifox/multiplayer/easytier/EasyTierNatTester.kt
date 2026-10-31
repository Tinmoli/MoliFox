// 网络类型诊断：调用 EasyTier 自带的 STUN 测试，判断 NAT 类型与 IPv6 支持。
package dev.molifox.multiplayer.easytier

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.molifox.multiplayer.common.NatDiagnostics
import dev.molifox.multiplayer.common.NatType
import kotlinx.coroutines.CancellationException
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/** 执行网络诊断；测试注入假实现，生产调用本机 `easytier-cli`。 */
fun interface NatTester {
    suspend fun test(cliExecutable: Path): Result<NatDiagnostics>
}

/**
 * 用 `easytier-cli -o json stun` 做 NAT 类型测试：不需要启动 EasyTier 核心，
 * 也不需要管理员权限，几秒内返回 UDP/TCP NAT 类型与公网地址。
 */
class EasyTierNatTester(
    private val runner: EasyTierCommandRunner = ProcessEasyTierCommandRunner(),
    private val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
) : NatTester {
    override suspend fun test(cliExecutable: Path): Result<NatDiagnostics> =
        runCatching {
            require(Files.isRegularFile(cliExecutable)) {
                "还没有安装 EasyTier，无法测试网络类型。"
            }
            val command = listOf(cliExecutable.toString(), "-o", "json", "stun")
            val output =
                runner.run(command, timeoutMillis).getOrElse { failure ->
                    throw IOException("无法运行 EasyTier 网络测试：${failure.message}", failure)
                }
            check(output.exitCode == 0) { "EasyTier 网络测试失败（退出码 ${output.exitCode}）" }
            parseNatDiagnostics(output.output).getOrElse { failure -> throw failure }
        }.onFailure { failure ->
            if (failure is CancellationException) throw failure
        }

    private companion object {
        const val DEFAULT_TIMEOUT_MILLIS = 40_000L
    }
}

/** 解析 `easytier-cli -o json stun`；容忍日志前缀，只取第一段 JSON 对象。 */
internal fun parseNatDiagnostics(output: String): Result<NatDiagnostics> =
    runCatching {
        val start = output.indexOf('{')
        val end = output.lastIndexOf('}')
        if (start < 0 || end <= start) {
            throw IOException("EasyTier 网络测试没有返回结果。")
        }
        val json = JsonParser.parseString(output.substring(start, end + 1))
        if (!json.isJsonObject) throw IOException("EasyTier 网络测试结果不是 JSON 对象。")
        val root = json.asJsonObject
        val ips =
            root
                .getAsJsonArray("public_ip")
                ?.mapNotNull { element -> element.stringOrNull() }
                ?: emptyList()
        NatDiagnostics(
            udpNatType = NatType.fromCode(root.intOrNull("udp_nat_type")),
            tcpNatType = NatType.fromCode(root.intOrNull("tcp_nat_type")),
            supportsIpv6 = ips.any { ip -> ip.contains(':') },
            publicIps = ips,
        )
    }

private fun JsonObject.intOrNull(key: String): Int? {
    val value = get(key) ?: return null
    if (!value.isJsonPrimitive) return null
    return runCatching { value.asJsonPrimitive.asInt }.getOrNull()
}

private fun JsonElement.stringOrNull(): String? =
    if (isJsonPrimitive) asJsonPrimitive.asString else null
