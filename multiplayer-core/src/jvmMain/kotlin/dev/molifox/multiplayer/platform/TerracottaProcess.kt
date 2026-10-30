// 启动并守护陶瓦核心进程：读取端口文件、收集少量输出，退出时同步关闭。
package dev.molifox.multiplayer.platform

import com.google.gson.JsonParser
import java.nio.file.Files
import java.nio.file.Path
import java.util.ArrayDeque
import kotlin.concurrent.thread

/**
 * 陶瓦核心进程。
 *
 * 启动方式与官方启动器一致：`<核心> --hmcl <临时目录>/http`，核心把 `{"port":N}` 写入该文件；
 * 之后所有控制都走 127.0.0.1 上这个端口的 HTTP 接口。
 */
class TerracottaProcess(
    private val executable: Path,
    private val logger: (message: String, error: Boolean) -> Unit,
) : TerracottaCore {
    private var process: Process? = null
    private var apiClient: TerracottaControl? = null
    private var workDirectory: Path? = null
    private val output = ArrayDeque<String>()

    override val api: TerracottaControl?
        @Synchronized get() = apiClient

    @Synchronized
    override fun isAlive(): Boolean = process?.isAlive == true

    /** 启动核心并等待端口就绪；失败时清理进程并抛出中文原因。 */
    @Synchronized
    override fun start(): TerracottaControl {
        if (isAlive() && apiClient != null) return checkNotNull(apiClient)
        stop()
        val work = Files.createTempDirectory("molifox-terracotta-")
        workDirectory = work
        val portFile = work.resolve("http")
        logger("启动陶瓦联机核心：$executable", false)
        val started =
            ProcessBuilder(executable.toString(), "--hmcl", portFile.toString())
                .redirectErrorStream(true)
                .start()
        process = started
        pumpOutput(started)
        val deadline = System.currentTimeMillis() + START_TIMEOUT_MILLIS
        while (System.currentTimeMillis() < deadline) {
            if (Files.isRegularFile(portFile)) {
                val port = readPort(portFile)
                val api: TerracottaControl = TerracottaApi(port)
                apiClient = api
                logger("陶瓦联机核心已就绪，控制端口 $port", false)
                return api
            }
            if (!started.isAlive) {
                val code = started.exitValue()
                stop()
                throw IllegalStateException(
                    "联机核心启动失败（退出码 $code）：${recentOutput()}",
                )
            }
            Thread.sleep(POLL_INTERVAL_MILLIS)
        }
        stop()
        throw IllegalStateException("联机核心启动超时，请查看日志后重试。")
    }

    /** 关闭核心：先请求回到等待状态，再结束进程；任何失败都只记录日志。 */
    @Synchronized
    override fun stop() {
        val running = process
        runCatching { apiClient?.idle() }
            .onFailure { logger("请求陶瓦核心退出失败：${it.message}", true) }
        if (running != null) {
            running.destroy()
            if (!running.waitFor(STOP_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)) {
                running.destroyForcibly()
            }
        }
        process = null
        apiClient = null
        workDirectory?.let { directory ->
            runCatching { deleteRecursively(directory) }
        }
        workDirectory = null
    }

    /** 最近的核心输出，用于启动失败时定位问题。 */
    @Synchronized
    override fun recentOutput(): String =
        output.toList().takeLast(MAX_OUTPUT_LINES).joinToString(" | ")

    private fun readPort(portFile: Path): Int {
        repeat(PORT_READ_ATTEMPTS) {
            val text = runCatching { Files.readString(portFile) }.getOrNull()
            val port =
                runCatching {
                    JsonParser
                        .parseString(text)
                        .asJsonObject
                        .get("port")
                        .asInt
                }.getOrNull()
            if (port != null && port in VALID_PORT_RANGE) return port
            Thread.sleep(PORT_READ_RETRY_MILLIS)
        }
        throw IllegalStateException("联机核心没有写出有效端口，请重新启动联机。")
    }

    private fun pumpOutput(started: Process) {
        thread(isDaemon = true, name = "terracotta-output") {
            runCatching {
                started.inputStream.bufferedReader().forEachLine { line ->
                    synchronized(this@TerracottaProcess) {
                        if (output.size >= MAX_OUTPUT_LINES) output.removeFirst()
                        output.addLast(line)
                    }
                }
            }
        }
    }

    private fun deleteRecursively(path: Path) {
        if (!Files.exists(path)) return
        Files.walk(path).use { stream ->
            stream.sorted(Comparator.reverseOrder()).forEach { child ->
                runCatching { Files.deleteIfExists(child) }
            }
        }
    }

    companion object {
        private const val START_TIMEOUT_MILLIS = 30_000L
        private const val POLL_INTERVAL_MILLIS = 200L
        private const val PORT_READ_ATTEMPTS = 20
        private const val PORT_READ_RETRY_MILLIS = 100L
        private const val STOP_TIMEOUT_SECONDS = 5L
        private const val MAX_OUTPUT_LINES = 200
        private val VALID_PORT_RANGE = 1..65535
    }
}
