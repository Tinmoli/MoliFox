// EasyTier 核心进程生命周期：分配回环 RPC 端口、启动、等 CLI 可用、退出检测与幂等停止。
package dev.molifox.multiplayer.easytier

import dev.molifox.core.i18n.I18n
import kotlinx.coroutines.delay
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** 分配一个本机空闲回环端口；测试可注入固定序列。 */
fun interface LoopbackPortAllocator {
    fun allocate(): Int
}

/** 绑定 127.0.0.1:0 取系统分配端口后立即释放；真正的监听交给 EasyTier。 */
object FreeLoopbackPortAllocator : LoopbackPortAllocator {
    override fun allocate(): Int =
        ServerSocket(0, 1, InetAddress.getByName(LOOPBACK_ADDRESS)).use { socket ->
            socket.localPort
        }

    private const val LOOPBACK_ADDRESS = "127.0.0.1"
}

/** 已启动的核心进程；只暴露停止与存活判断，避免把 java.lang.Process 泄漏到编排层。 */
interface EasyTierProcessHandle {
    val alive: Boolean

    /** 先请求正常结束，超时后强制结束；可重复调用。 */
    fun stop()
}

/** 启动核心进程；测试注入假实现，生产用 [JavaProcessLauncher]。 */
fun interface EasyTierProcessLauncher {
    fun launch(
        command: List<String>,
        workingDirectory: Path,
        onOutput: (String) -> Unit,
        onExit: (Int) -> Unit,
    ): Result<EasyTierProcessHandle>
}

/** 真实实现：合并 stdout/stderr 逐行回调，进程退出时回调退出码。 */
class JavaProcessLauncher : EasyTierProcessLauncher {
    override fun launch(
        command: List<String>,
        workingDirectory: Path,
        onOutput: (String) -> Unit,
        onExit: (Int) -> Unit,
    ): Result<EasyTierProcessHandle> {
        val process =
            try {
                ProcessBuilder(command)
                    .directory(workingDirectory.toFile())
                    .redirectErrorStream(true)
                    .start()
            } catch (failure: IOException) {
                return Result.failure(IOException(I18n.t("multiplayer.easyTierRuntime.1", failure.message), failure))
            }
        val reader =
            Thread({ drainOutput(process, onOutput) }, "easytier-core-log").apply {
                isDaemon = true
                start()
            }
        process.onExit().thenAccept { finished ->
            runCatching { reader.join(READER_JOIN_MILLIS) }
            onExit(finished.exitValue())
        }
        return Result.success(JavaProcessHandle(process))
    }

    private fun drainOutput(
        process: Process,
        onOutput: (String) -> Unit,
    ) {
        runCatching {
            process.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    onOutput(line)
                }
            }
        }
    }

    private companion object {
        const val READER_JOIN_MILLIS = 1_000L
    }
}

/** [java.lang.Process] 的薄包装；停止顺序固定先 destroy 再 destroyForcibly。 */
private class JavaProcessHandle(
    private val process: Process,
) : EasyTierProcessHandle {
    @Volatile
    private var stopped = false

    override val alive: Boolean
        get() = !stopped && process.isAlive

    override fun stop() {
        if (stopped) return
        stopped = true
        process.destroy()
        if (!process.waitFor(STOP_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
            process.waitFor(FORCE_STOP_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
        const val FORCE_STOP_TIMEOUT_MILLIS = 2_000L
    }
}

/**
 * 一个 EasyTier 节点的运行时：每次 [start] 分配新的 RPC 端口并启动独立进程，
 * [stop] 结束进程并清空状态，可重复调用。
 *
 * 这里不启动 Scaffolding 或 Minecraft，也不做端口转发编排；那些属于房主/房客会话。
 */
class EasyTierRuntime(
    private val coreExecutable: Path,
    private val cliExecutable: Path,
    private val logger: (message: String, error: Boolean) -> Unit,
    private val launcher: EasyTierProcessLauncher = JavaProcessLauncher(),
    private val commandRunner: EasyTierCommandRunner = ProcessEasyTierCommandRunner(),
    private val portAllocator: LoopbackPortAllocator = FreeLoopbackPortAllocator,
    private val cliReadyTimeoutMillis: Long = DEFAULT_CLI_READY_TIMEOUT_MILLIS,
    private val cliReadyIntervalMillis: Long = DEFAULT_CLI_READY_INTERVAL_MILLIS,
) : EasyTierNode {
    private val lock = Any()
    private var handle: EasyTierProcessHandle? = null
    private var currentCli: EasyTierCli? = null
    private var rpcPort: Int? = null
    private var stopping = false
    private var recentOutput = ""

    @Volatile
    private var unexpectedExit: ((Int, String) -> Unit)? = null

    /** 当前节点是否在运行。 */
    val running: Boolean
        get() = synchronized(lock) { handle?.alive == true }

    /** 当前 RPC 端口；未启动时为 null。 */
    val currentRpcPort: Int?
        get() = synchronized(lock) { rpcPort }

    /** 当前 CLI 门面；未启动时为 null。 */
    val cli: EasyTierCli?
        get() = synchronized(lock) { currentCli }

    /**
     * 启动节点并等待 CLI 可用。成功返回该节点的 CLI 门面。
     *
     * @param onUnexpectedExit 非主动停止的进程退出回调；参数为退出码与最近日志。
     */
    override suspend fun start(
        request: EasyTierNodeRequest,
        onUnexpectedExit: (Int, String) -> Unit,
    ): Result<EasyTierCli> {
        val rpc = beginStart() ?: return Result.failure(IllegalStateException(I18n.t("multiplayer.easyTierRuntime.2")))
        val result = launchAndWait(request, rpc, onUnexpectedExit)
        if (result.isFailure) {
            stop()
        }
        return result
    }

    /** 占用一次启动机会并分配 RPC 端口；已有节点在运行时返回 null。 */
    private fun beginStart(): Int? =
        synchronized(lock) {
            if (handle != null) {
                null
            } else {
                stopping = false
                recentOutput = ""
                portAllocator.allocate().also { rpcPort = it }
            }
        }

    /** 构建参数、启动进程并等 CLI；任何一步失败都由 [start] 统一收尾。 */
    private suspend fun launchAndWait(
        request: EasyTierNodeRequest,
        rpc: Int,
        onUnexpectedExit: (Int, String) -> Unit,
    ): Result<EasyTierCli> =
        runCatching { EasyTierArguments.build(request, rpc) }.mapCatching { arguments ->
            val process = launchProcess(arguments, onUnexpectedExit)
            val candidate = EasyTierCli(cliExecutable, rpc, logger, commandRunner)
            logger(I18n.t("multiplayerB.easyTierRuntime.started", rpc), false)
            val ready = awaitCliReady(candidate, process).getOrThrow()
            synchronized(lock) { currentCli = ready }
            ready
        }

    private fun launchProcess(
        arguments: List<String>,
        onUnexpectedExit: (Int, String) -> Unit,
    ): EasyTierProcessHandle {
        unexpectedExit = onUnexpectedExit
        val launched =
            launcher.launch(
                command = listOf(coreExecutable.toString()) + arguments,
                workingDirectory = coreExecutable.parent ?: Path.of("."),
                onOutput = ::recordOutput,
                onExit = ::onProcessExit,
            )
        val process = launched.getOrElse { failure -> throw failure }
        synchronized(lock) { handle = process }
        return process
    }

    /** 主动停止：结束进程并清空 RPC/CLI；可重复调用。 */
    override fun stop() {
        val process =
            synchronized(lock) {
                if (stopping) return
                stopping = true
                currentCli = null
                rpcPort = null
                handle.also { handle = null }
            }
        process?.stop()
        if (process != null) {
            logger(I18n.t("multiplayerB.easyTierRuntime.stopped"), false)
        }
    }

    override fun close() = stop()

    /** 轮询 CLI 直到可用；进程提前退出或超时都返回失败。 */
    private suspend fun awaitCliReady(
        candidate: EasyTierCli,
        process: EasyTierProcessHandle,
    ): Result<EasyTierCli> {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(cliReadyTimeoutMillis)
        var exited = false
        while (System.nanoTime() < deadline) {
            if (!process.alive) {
                exited = true
                break
            }
            if (candidate.peers().isSuccess) {
                return Result.success(candidate)
            }
            delay(cliReadyIntervalMillis)
        }
        return if (exited) {
            Result.failure(IOException(I18n.t("multiplayer.easyTierRuntime.3", tailOutput())))
        } else {
            Result.failure(IOException(I18n.t("multiplayer.easyTierRuntime.4", cliReadyTimeoutMillis)))
        }
    }

    /** 退出回调：主动停止时静默，意外退出时带上最近日志通知上层。 */
    private fun onProcessExit(code: Int) {
        val unexpected =
            synchronized(lock) {
                if (!stopping && handle != null) unexpectedExit else null
            }
        if (unexpected != null) {
            logger(I18n.t("multiplayerB.easyTierRuntime.unexpectedExit", code, tailOutput()), true)
            unexpected(code, tailOutput())
        }
    }

    private fun recordOutput(line: String) {
        logger(line, false)
        recentOutput =
            synchronized(lock) {
                (recentOutput + line + "\n").takeLast(MAX_RECENT_OUTPUT_CHARS)
            }
    }

    private fun tailOutput(): String =
        synchronized(lock) {
            recentOutput.ifBlank { I18n.t("multiplayer.easyTierRuntime.5") }
        }

    private companion object {
        const val MAX_RECENT_OUTPUT_CHARS = 4_096
        const val DEFAULT_CLI_READY_TIMEOUT_MILLIS = 20_000L
        const val DEFAULT_CLI_READY_INTERVAL_MILLIS = 500L
    }
}
