// easytier-cli 门面：查询 peer、添加端口转发；进程调用可注入以便离线测试。
package dev.molifox.multiplayer.easytier

import dev.molifox.core.i18n.I18n
import java.io.BufferedReader
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** 一次命令行调用的结果；[output] 已合并 stdout 与 stderr，解析时容忍告警前缀。 */
data class EasyTierCommandOutput(
    val exitCode: Int,
    val output: String,
)

/** 执行 `easytier-cli`；测试注入假实现，生产走真实子进程。 */
fun interface EasyTierCommandRunner {
    fun run(
        command: List<String>,
        timeoutMillis: Long,
    ): Result<EasyTierCommandOutput>
}

/** 端口转发协议；Minecraft 加入只需 TCP，但和 PCL CE 一样同时添加 UDP。 */
enum class EasyTierForwardProtocol { TCP, UDP }

/**
 * `easytier-cli` 门面。所有命令都通过 `--rpc-portal 127.0.0.1:<rpc>` 访问本机核心，
 * 不依赖 EasyTier 的默认端口，也不暴露到局域网。
 */
class EasyTierCli(
    private val executable: Path,
    private val rpcPort: Int,
    private val logger: (message: String, error: Boolean) -> Unit = { _, _ -> },
    private val runner: EasyTierCommandRunner = ProcessEasyTierCommandRunner(),
    private val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
) {
    init {
        require(rpcPort in MIN_PORT..MAX_PORT) { I18n.t("multiplayer.easyTierCli.1", rpcPort) }
    }

    /** 读取虚拟网络里的节点列表；核心尚未就绪时返回失败而不是空列表。 */
    fun peers(): Result<List<EasyTierPeer>> =
        run(listOf("--rpc-portal", EasyTierArguments.rpcPortal(rpcPort), "-o", "json", "peer"))
            .mapCatching { result ->
                check(result.exitCode == 0) { I18n.t("multiplayer.easyTierCli.2", result.exitCode) }
                EasyTierPeers.parse(result.output).getOrElse { failure -> throw failure }
            }

    /** 添加一条本机回环端口到远端地址的转发；失败时返回可读原因。 */
    fun addPortForward(
        protocol: EasyTierForwardProtocol,
        localPort: Int,
        targetHost: String,
        targetPort: Int,
    ): Result<Unit> {
        val protocolName = protocol.name.lowercase()
        val command =
            listOf(
                "--rpc-portal",
                EasyTierArguments.rpcPortal(rpcPort),
                "port-forward",
                "add",
                protocolName,
                "127.0.0.1:$localPort",
                "$targetHost:$targetPort",
            )
        return run(command).mapCatching { result ->
            check(result.exitCode == 0) { I18n.t("multiplayer.easyTierCli.3", protocolName, result.exitCode) }
            logger(I18n.t("multiplayer.easyTierCli.4", protocolName, localPort, targetHost, targetPort), false)
        }
    }

    /** 执行任意子命令；退出码非 0 不算失败，由调用方按需判断。 */
    fun run(arguments: List<String>): Result<EasyTierCommandOutput> {
        val command = listOf(executable.toString()) + arguments
        val result = runner.run(command, timeoutMillis)
        result.onFailure { failure ->
            logger(I18n.t("multiplayerB.easyTierCli.runFailed", failure.message), true)
        }
        return result
    }

    private companion object {
        const val MIN_PORT = 1
        const val MAX_PORT = 65_535
        const val DEFAULT_TIMEOUT_MILLIS = 5_000L
    }
}

/**
 * 真实子进程实现：合并 stderr，最多保留 [MAX_OUTPUT_CHARS]，超时强制结束。
 * 与 PCL CE 一样把 stdout/stderr 一起解析，因为 CLI 会把告警混进输出。
 */
class ProcessEasyTierCommandRunner(
    private val workingDirectory: Path? = null,
) : EasyTierCommandRunner {
    override fun run(
        command: List<String>,
        timeoutMillis: Long,
    ): Result<EasyTierCommandOutput> {
        val process =
            try {
                val builder = ProcessBuilder(command).redirectErrorStream(true)
                workingDirectory?.let { directory -> builder.directory(directory.toFile()) }
                builder.start()
            } catch (failure: IOException) {
                return Result.failure(IOException(I18n.t("multiplayer.easyTierCli.5", failure.message), failure))
            }
        val output = StringBuilder()
        val reader =
            Thread({ readOutput(process, output) }, "easytier-cli-reader").apply {
                isDaemon = true
                start()
            }
        val finished = waitFor(process, timeoutMillis)
        if (!finished) {
            process.destroyForcibly()
            runCatching { process.waitFor() }
            return Result.failure(IOException(I18n.t("multiplayer.easyTierCli.6", timeoutMillis)))
        }
        reader.join(READER_JOIN_MILLIS)
        return Result.success(EasyTierCommandOutput(process.exitValue(), output.toString()))
    }

    private fun waitFor(
        process: Process,
        timeoutMillis: Long,
    ): Boolean =
        try {
            process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)
        } catch (failure: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }

    private fun readOutput(
        process: Process,
        output: StringBuilder,
    ) {
        try {
            process.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                appendLines(reader, output)
            }
        } catch (failure: IOException) {
            // 进程被强制结束时流会关闭，这里只记录不影响结果。
            output.append(I18n.t("multiplayer.easyTierCli.7")).append(failure.message).append('\n')
        }
    }

    /** 逐行追加并限制总长度；超过上限后继续读空流，避免子进程写管道阻塞。 */
    private fun appendLines(
        reader: BufferedReader,
        output: StringBuilder,
    ) {
        while (true) {
            val line = reader.readLine() ?: return
            if (output.length < MAX_OUTPUT_CHARS) {
                output.append(line).append('\n')
            }
        }
    }

    private companion object {
        const val MAX_OUTPUT_CHARS = 256 * 1024
        const val READER_JOIN_MILLIS = 1_000L
    }
}
