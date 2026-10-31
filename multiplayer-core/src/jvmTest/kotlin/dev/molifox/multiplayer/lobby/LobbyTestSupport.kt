// 联机会话测试辅助：假 EasyTier 节点/CLI、回环转发器与 TCP 测试服务端。
package dev.molifox.multiplayer.lobby

import dev.molifox.multiplayer.easytier.EasyTierCli
import dev.molifox.multiplayer.easytier.EasyTierCommandOutput
import dev.molifox.multiplayer.easytier.EasyTierCommandRunner
import dev.molifox.multiplayer.easytier.EasyTierForwardProtocol
import dev.molifox.multiplayer.easytier.EasyTierNode
import dev.molifox.multiplayer.easytier.EasyTierNodeRequest
import dev.molifox.multiplayer.net.LocalPortForward
import kotlinx.coroutines.delay
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Path

/** 轮询等待异步条件成立，超时抛出带说明的断言失败。 */
internal suspend fun awaitUntil(
    timeoutMillis: Long = 5_000,
    condition: () -> Boolean,
) {
    val deadline = System.currentTimeMillis() + timeoutMillis
    while (System.currentTimeMillis() < deadline) {
        if (condition()) return
        delay(POLL_INTERVAL_MILLIS)
    }
    if (!condition()) throw AssertionError("等待条件超时（${timeoutMillis}ms）")
}

private const val POLL_INTERVAL_MILLIS = 20L

/** 可控制启动、退出与停止的假 EasyTier 节点。 */
internal class FakeEasyTierNode(
    private val cli: EasyTierCli? = null,
    var startFailure: Throwable? = null,
) : EasyTierNode {
    var request: EasyTierNodeRequest? = null
        private set

    var stops = 0
        private set

    private var exitCallback: ((Int, String) -> Unit)? = null

    override suspend fun start(
        request: EasyTierNodeRequest,
        onUnexpectedExit: (Int, String) -> Unit,
    ): Result<EasyTierCli> {
        this.request = request
        exitCallback = onUnexpectedExit
        startFailure?.let { return Result.failure(it) }
        return Result.success(cli ?: error("假节点没有配置 CLI"))
    }

    override fun stop() {
        stops++
    }

    override fun close() {
        stop()
    }

    fun exit(
        code: Int = 1,
        output: String = "模拟退出",
    ) {
        exitCallback?.invoke(code, output)
    }
}

/** 记录调用并按脚本返回输出的 CLI 执行器。 */
internal class ScriptedEasyTierRunner(
    private val peersOutput: () -> String,
) : EasyTierCommandRunner {
    val commands = mutableListOf<List<String>>()

    override fun run(
        command: List<String>,
        timeoutMillis: Long,
    ): Result<EasyTierCommandOutput> {
        commands += command
        val output = if (command.any { it == "peer" }) peersOutput() else "success"
        return Result.success(EasyTierCommandOutput(0, output))
    }
}

internal fun fakeCli(peersOutput: () -> String): Pair<EasyTierCli, ScriptedEasyTierRunner> {
    val runner = ScriptedEasyTierRunner(peersOutput)
    val cli = EasyTierCli(Path.of("easytier-cli"), RPC_PORT, runner = runner)
    return cli to runner
}

private const val RPC_PORT = 12345

/** peer JSON：房主 hostname 末尾是真实 Ephemeral Scaffolding 端口。 */
internal fun hostPeersJson(scaffoldingPort: Int): String =
    """
    [{"cidr":"10.114.51.41/24","ipv4":"127.0.0.1",
      "hostname":"MoliFox-server-$scaffoldingPort","lat_ms":"-"}]
    """.trimIndent()

internal data class ForwardCall(
    val protocol: EasyTierForwardProtocol,
    val localPort: Int,
    val targetHost: String,
    val targetPort: Int,
)

/**
 * 用本机回环转发模拟 EasyTier 的端口转发：转发目标和 EasyTier 一样指向 127.0.0.1。
 * 测试里目标换成真实测试服务端端口，即可在没有虚拟网络的情况下验证完整链路。
 */
internal class LoopbackForwarder : AutoCloseable {
    val calls = mutableListOf<ForwardCall>()
    private val created = mutableListOf<LocalPortForward>()

    val forwarder =
        VirtualPortForwarder { protocol, localPort, targetHost, targetPort ->
            calls += ForwardCall(protocol, localPort, targetHost, targetPort)
            val forward = LocalPortForward(targetPort = targetPort, listenPort = localPort)
            forward.start().map { created += forward }
        }

    override fun close() {
        created.forEach { forward -> runCatching { forward.close() } }
        created.clear()
    }
}

/** 接受连接后保持打开的 TCP 服务端，模拟 Minecraft 端口。 */
internal class FakeTcpServer : AutoCloseable {
    private val server = ServerSocket(0, BACKLOG, InetAddress.getLoopbackAddress())
    private val clients = mutableListOf<Socket>()

    val port: Int = server.localPort

    private val thread =
        Thread {
            while (!server.isClosed) {
                runCatching { server.accept() }.onSuccess { socket ->
                    synchronized(clients) { clients += socket }
                }
            }
        }

    init {
        thread.isDaemon = true
        thread.start()
    }

    override fun close() {
        runCatching { server.close() }
        synchronized(clients) {
            clients.forEach { socket -> runCatching { socket.close() } }
            clients.clear()
        }
        thread.join(SHUTDOWN_TIMEOUT_MILLIS)
    }

    private companion object {
        const val BACKLOG = 16
        const val SHUTDOWN_TIMEOUT_MILLIS = 1_000L
    }
}
