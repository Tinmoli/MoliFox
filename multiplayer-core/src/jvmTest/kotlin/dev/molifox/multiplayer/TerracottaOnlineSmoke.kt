// 手动联机验收：真实下载/安装陶瓦核心、启动进程、读取状态并触发一次局域网扫描。
package dev.molifox.multiplayer

import dev.molifox.multiplayer.data.TerracottaInstaller
import dev.molifox.multiplayer.platform.TerracottaApi
import dev.molifox.multiplayer.platform.TerracottaProcess
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess

private const val SMOKE_PLAYER = "MoliFox-Smoke"
private const val PROGRESS_STEP_PERCENT = 10
private const val SCAN_SETTLE_MILLIS = 3_000L
private const val RESET_SETTLE_MILLIS = 1_000L

/**
 * 用法：`terracottaOnlineSmoke <工作目录> [install|launch]`。
 *
 * - `install`：下载并安装核心，然后启动核心验证控制接口。
 * - `launch`：假定核心已安装，只启动并验证控制接口（用于离线环境）。
 *
 * 该任务会访问外网并启动真实进程，因此只在显式执行 Gradle 任务时运行。
 */
internal fun main(args: Array<String>) {
    val root = Path.of(args.firstOrNull() ?: error("需要工作目录参数"))
    val mode = args.getOrNull(1) ?: "install"
    Files.createDirectories(root)
    val logger = { message: String, error: Boolean ->
        println((if (error) "[error] " else "[info] ") + message)
    }
    val installer = TerracottaInstaller(root, logger)
    try {
        prepareCore(installer, mode)
        verifyCore(installer, logger)
        println("[ok] 联机核心验收通过")
    } catch (failure: Throwable) {
        println("[error] 联机核心验收失败：${failure.message}")
        exitProcess(1)
    }
}

private fun prepareCore(
    installer: TerracottaInstaller,
    mode: String,
) {
    if (mode != "install") {
        val status = installer.inspect()
        check(status.installed) { "核心未安装，先运行 install 模式" }
        println("[ok] 核心已存在：$status")
        return
    }
    var lastStep = -1
    val status =
        runBlocking {
            installer
                .install { progress ->
                    val percent = progress?.times(100)?.toInt() ?: -1
                    if (percent / PROGRESS_STEP_PERCENT != lastStep) {
                        lastStep = percent / PROGRESS_STEP_PERCENT
                        println("[info] 下载进度：$percent%")
                    }
                }.getOrThrow()
        }
    println("[ok] 核心已安装：$status")
}

private fun verifyCore(
    installer: TerracottaInstaller,
    logger: (String, Boolean) -> Unit,
) {
    val core = TerracottaProcess(installer.executable(), logger)
    try {
        val api = core.start()
        println("[ok] 启动后状态：${api.snapshot()}")
        api.startScanning(SMOKE_PLAYER, emptyList())
        Thread.sleep(SCAN_SETTLE_MILLIS)
        println("[ok] 扫描后状态：${api.snapshot()}")
        api.idle()
        Thread.sleep(RESET_SETTLE_MILLIS)
        println("[ok] 复位后状态：${api.snapshot()}")
        println("[ok] 日志片段：${(api as? TerracottaApi)?.logs()?.take(300)?.replace('\n', ' ')}")
    } finally {
        core.stop()
    }
}
