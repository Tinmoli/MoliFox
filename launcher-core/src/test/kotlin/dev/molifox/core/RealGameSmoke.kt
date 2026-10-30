// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责执行真实游戏安装与主菜单验收。

package dev.molifox.core

import com.google.gson.JsonObject
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.awt.Robot
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import kotlin.io.path.bufferedReader
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.inputStream
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText

/** Opt-in acceptance against actual game files. A rendered window is not a main-menu verdict. */
fun main(): Unit =
    runBlocking {
        val prepared = System.getProperty("molifox.game.prepared", "false").toBoolean()
        val loader = System.getProperty("molifox.game.loader", "Vanilla")
        val report = System.getProperty("molifox.game.report")
        val root = Path.of(requireNotNull(System.getProperty("molifox.test.dir")))
        var failure: Throwable? = null
        for (target in smokeTargets(prepared, loader)) {
            clearSmokeFiles(root)
            prepareVanillaTarget(root, target, loader)
            System.setProperty("molifox.game.loader", target)
            System.setProperty(
                "molifox.game.report",
                reportPath(report, loader, target),
            )
            try {
                runRealGameSmoke()
            } catch (error: Throwable) {
                failure = if (failure == null) error else failure.also { it.addSuppressed(error) }
            }
        }
        failure?.let { throw it }
    }

/** 本次运行要验收的加载器：跨准备模式下先跑目标加载器，再补一次原版。 */
private fun smokeTargets(
    prepared: Boolean,
    loader: String,
): List<String> {
    val includeVanilla = System.getProperty("molifox.game.includeVanilla", "true").toBoolean()
    return if (prepared && loader != "Vanilla" && includeVanilla) {
        listOf(loader, "Vanilla")
    } else {
        listOf(loader)
    }
}

/** 清掉上一次运行留下的结果文件，避免旧结果被误判。 */
private fun clearSmokeFiles(root: Path) {
    for (file in listOf("game-status.json", "game-review.json", "game-input.json")) {
        root.resolve(file).deleteIfExists()
    }
}

/** 跨准备模式要先切回原版：重写导入实例的加载器与随机 ID，但保留游戏版本。 */
private fun prepareVanillaTarget(
    root: Path,
    target: String,
    loader: String,
) {
    if (target == loader) return
    val original =
        gson.fromJson(
            root.resolve("prepared-instance.json").readText(),
            GameInstance::class.java,
        )
    writeJson(
        root.resolve("prepared-instance.json"),
        original.copy(
            id =
                java.util.UUID
                    .randomUUID()
                    .toString(),
            loader = LoaderType.Vanilla,
            loaderVersion = "",
            versionId = original.gameVersion,
        ),
    )
}

/** 每个加载器的报告文件名都带加载器后缀，加载器名要保持与 `-<loader>.json` 约定一致。 */
private fun reportPath(
    report: String,
    loader: String,
    target: String,
): String = report.replace("-${loader.lowercase()}.json", "-${target.lowercase()}.json")

/** 把公共缓存的 assets/libraries 复制进本次测试目录，避免重复下载。 */
private fun copySeedCache(
    seedRoot: String,
    testRoot: Path,
) {
    val source = Path.of(seedRoot).toAbsolutePath().normalize()
    require(source != testRoot && !source.startsWith(testRoot) && !testRoot.startsWith(source))
    for (folder in listOf("assets", "libraries")) {
        val cached = source.resolve(folder)
        if (cached.isDirectory()) copyTree(cached, testRoot.resolve(".minecraft/$folder"))
    }
}

/** 结果报告在安装前就落盘，失败时也能看到从哪一步开始。 */
private fun newSmokeReport(seedRoot: String?): MutableMap<String, Any> {
    val prepared = System.getProperty("molifox.game.prepared", "false").toBoolean()
    val version = System.getProperty("molifox.game.version", "1.20.1")
    val loader = LoaderType.valueOf(System.getProperty("molifox.game.loader", "Vanilla"))
    val mode =
        when {
            prepared -> "cross-prepared-offline-launch"
            seedRoot != null -> "online-install-with-copied-public-cache"
            else -> "online-install"
        }
    return linkedMapOf(
        "startedAt" to Instant.now().toString(),
        "os" to System.getProperty("os.name"),
        "version" to version,
        "loader" to loader.name,
        "mainMenuConfirmed" to false,
        "installationMode" to mode,
    )
}

private suspend fun runRealGameSmoke() {
    check(!GraphicsEnvironment.isHeadless()) { "Real game acceptance requires a graphical desktop" }
    val testRoot = Path.of(requireNotNull(System.getProperty("molifox.test.dir")))
    val seedRoot = System.getProperty("molifox.game.seedRoot", "").takeIf { it.isNotBlank() }
    if (seedRoot != null) copySeedCache(seedRoot, testRoot)
    val report = newSmokeReport(seedRoot)
    val windowMode = System.getProperty("molifox.game.windowMode", "custom")
    LauncherController(dataDirectory = testRoot.resolve("profile")).use { controller ->
        SmokeRun(controller, testRoot, report, windowMode).execute()
    }
}

/** 单次验收的控制器、报告与已启动的游戏进程。 */
private class SmokeRun(
    private val controller: LauncherController,
    private val testRoot: Path,
    private val report: MutableMap<String, Any>,
    private val windowMode: String,
) {
    private var game: ProcessHandle? = null

    private val gameHandle: ProcessHandle
        get() = checkNotNull(game) { "The launched game process was not found" }

    /** 失败也要留下报告并回收游戏进程。 */
    suspend fun execute() {
        try {
            run()
        } catch (error: Throwable) {
            report["result"] = "failed"
            report["error"] = error.message ?: error.javaClass.simpleName
            throw error
        } finally {
            game?.let { stopGame(it) }
            finishReport()
        }
    }

    private suspend fun run() {
        val prepared = System.getProperty("molifox.game.prepared", "false").toBoolean()
        val version = System.getProperty("molifox.game.version", "1.20.1")
        val loader = LoaderType.valueOf(System.getProperty("molifox.game.loader", "Vanilla"))
        val loaderVersion = System.getProperty("molifox.game.loaderVersion", "")
        configureController()
        reportEnvironment()
        controller.accounts.addOffline("MoliTester")
        val requestedLoader = resolveLoaderVersion(loader, version, loaderVersion)
        report["loaderVersion"] = requestedLoader
        prepareInstance(prepared, version, loader, requestedLoader)
        val instance =
            controller.store.instance(
                checkNotNull(controller.store.data.selectedInstanceId),
            )
        reportInstance(instance)
        val previousChildren = childPids()
        awaitJob(controller, controller.launch(instance.id))
        startGame(previousChildren)
        awaitMainMenu(reviewFrame(loader))
        withTimeout(30_000) { while (controller.game.isRunning) delay(200) }
        verifyNormalExit()
        report["result"] = "passed"
        report["normalExit"] = true
    }

    private fun configureController() {
        controller.saveSettings(
            controller.store.data.settings.copy(
                memoryMb = 2048,
                width = 960,
                height = 600,
                maxConcurrent = 8,
                downloadSource = System.getProperty("molifox.game.downloadSource", "official"),
                gameWindowMode = windowMode,
                javaPath = System.getProperty("molifox.game.javaPath", ""),
            ),
        )
        if (windowMode == "launcher") controller.reportLauncherWindowSize(1280, 725)
    }

    private fun reportEnvironment() {
        report["windowMode"] = windowMode
        report["proxyMode"] =
            controller.store.data.settings
                .effectiveProxyMode()
        report["proxyType"] =
            controller.network.client.proxySelector
                .select(java.net.URI("https://piston-meta.mojang.com"))
                .first()
                .type()
                .name
    }

    private suspend fun resolveLoaderVersion(
        loader: LoaderType,
        version: String,
        loaderVersion: String,
    ) = if (loader == LoaderType.Vanilla) {
        ""
    } else {
        loaderVersion.ifBlank {
            controller.installer
                .loaderVersions(loader, version)
                .first { it.stable }
                .version
        }
    }

    private suspend fun prepareInstance(
        prepared: Boolean,
        version: String,
        loader: LoaderType,
        requestedLoader: String,
    ) {
        if (!prepared) {
            awaitJob(
                controller,
                controller.install(
                    InstallRequest("Real game acceptance", version, loader, requestedLoader),
                ),
            )
            return
        }
        val imported = readPreparedInstance()
        check(
            imported.gameVersion == version &&
                imported.loader == loader &&
                imported.installed,
        )
        controller.store.mutate {
            it.copy(instances = listOf(imported), selectedInstanceId = imported.id)
        }
    }

    private fun readPreparedInstance(): GameInstance =
        gson.fromJson(
            testRoot.resolve("prepared-instance.json").readText(),
            GameInstance::class.java,
        )

    private fun reportInstance(instance: GameInstance) {
        val resolved =
            VersionResolver(controller.store.minecraftRoot(instance), controller.platform)
                .resolve(instance.versionId)
        report["requiredJava"] = resolved.requiredJava
        report["gameDirectory"] = controller.store.instancePath(instance.id).toString()
    }

    private fun childPids(): Set<Long> =
        ProcessHandle.current().children().use { children ->
            children.map { child -> child.pid() }.toList().toSet()
        }

    /** 只认启动后新出现的、仍存活的子进程，避免误抓别的游戏窗口。 */
    private suspend fun startGame(previous: Set<Long>) {
        val launched =
            ProcessHandle.current().children().use { children ->
                children
                    .filter { it.isAlive && it.pid() !in previous }
                    .toList()
                    .singleOrNull()
            } ?: error("The launched game process was not found")
        game = launched
        report["pid"] = launched.pid()
        report["javaExecutable"] = launched.info().command().orElse("")
        controller.java.inspect(launched.info().command().orElse(""))?.let { runtime ->
            report["actualJavaMajor"] = runtime.majorVersion
            report["javaArchitecture"] = runtime.architecture
        }
    }

    private fun reviewFrame(loader: LoaderType): Path {
        val screenshots = testRoot.resolve("screenshots").apply { createDirectories() }
        val frame =
            screenshots.resolve(
                "MoliFox-game-${controller.platform.osName}-${loader.name.lowercase()}.png",
            )
        println("GAME_REVIEW_DIRECTORY $testRoot")
        return frame
    }

    private suspend fun awaitMainMenu(frame: Path) {
        val statusFile = testRoot.resolve("game-status.json")
        withTimeout(10 * 60_000L) {
            while (true) {
                checkGameLogs()
                val window = GameWindowCapture.find(gameHandle.pid(), controller.platform.osName)
                if (window != null && reviewWindow(window, frame, statusFile)) break
                delay(2_000)
            }
        }
    }

    /** 崩溃日志与提前退出都要在判读画面前先排除。 */
    private fun checkGameLogs() {
        val fatal =
            controller.state.value.logs.firstOrNull {
                it.isGame &&
                    it.message.contains("exception in thread \"main\"", ignoreCase = true)
            }
        check(fatal == null) {
            "Game launch failed: " +
                controller.state.value.logs
                    .takeLast(20)
                    .joinToString("\n") { it.message }
        }
        check(gameHandle.isAlive) {
            "Game exited before acceptance: " +
                controller.state.value.logs
                    .takeLast(12)
                    .joinToString("\n") { it.message }
        }
    }

    /** 截图并等待人工/外部判读；返回 true 表示主菜单已确认并已请求关闭。 */
    private fun reviewWindow(
        window: GameWindow,
        frame: Path,
        statusFile: Path,
    ): Boolean {
        val clientSize = clientSize(window)
        report["clientWidth"] = clientSize.first
        report["clientHeight"] = clientSize.second
        GameWindowCapture.capture(window, frame)
        report["stage"] = "waiting-for-visual-review"
        report["workingScreenshot"] = frame.toString()
        if (frame.isRegularFile()) {
            report["screenshot"] = "docs/screenshots/current/${frame.fileName}"
        }
        writeJson(statusFile, report)
        applyPendingInput(window)
        val verdict = readVerdict()
        if (verdict?.boolean("mainMenuConfirmed") != true) return false
        confirmMenu(window, verdict, clientSize, frame)
        return true
    }

    private fun clientSize(window: GameWindow): Pair<Int, Int> =
        if (window.os == "windows") {
            WindowsGameWindow.clientSize(window)
        } else {
            window.rectangle.width to window.rectangle.height
        }

    private fun applyPendingInput(window: GameWindow) {
        val input = testRoot.resolve("game-input.json")
        if (!input.isRegularFile()) return
        val action = readObject(input)
        if (action.has("x") && action.has("y")) {
            GameWindowCapture.click(window, action.int("x"), action.int("y"))
        }
        input.deleteIfExists()
    }

    private fun readVerdict() =
        testRoot
            .resolve("game-review.json")
            .takeIf { it.isRegularFile() }
            ?.let { readObject(it) }

    private fun confirmMenu(
        window: GameWindow,
        verdict: JsonObject,
        clientSize: Pair<Int, Int>,
        frame: Path,
    ) {
        check(frame.isRegularFile()) { "A game screenshot is required for acceptance" }
        report["mainMenuConfirmed"] = true
        if (windowMode != "fullscreen") {
            val expected = if (windowMode == "launcher") 1280 to 725 else 960 to 600
            check(clientSize == expected) {
                "Game client size $clientSize differs from requested $expected"
            }
        }
        closeGame(window, verdict)
    }

    private fun closeGame(
        window: GameWindow,
        verdict: JsonObject,
    ) {
        if (verdict.has("quitX") && verdict.has("quitY")) {
            report["closeMethod"] = "visually-reviewed-menu-button"
            GameWindowCapture.click(window, verdict.int("quitX"), verdict.int("quitY"))
        } else {
            report["closeMethod"] = "window-close-request"
            GameWindowCapture.close(window)
        }
    }

    private fun verifyNormalExit() {
        val exit =
            controller.state.value.logs
                .lastOrNull { it.message.startsWith("游戏已退出，退出码 ") }
        check(exit != null && exit.message.endsWith("退出码 0")) {
            "Game did not exit normally: ${exit?.message}"
        }
    }

    private suspend fun stopGame(handle: ProcessHandle) {
        if (!handle.isAlive) return
        handle.destroy()
        withTimeoutOrNull(5_000) { while (handle.isAlive) delay(100) }
        if (handle.isAlive) handle.destroyForcibly()
        withTimeoutOrNull(5_000) { while (handle.isAlive) delay(100) }
    }

    private fun finishReport() {
        report["finishedAt"] = Instant.now().toString()
        val evidence = Path.of(System.getProperty("molifox.game.report"))
        writeJson(evidence, report)
        println("REAL_GAME_RESULT ${gson.toJson(report)}")
    }
}

/** 轮询安装/启动作业，并打印状态变化；失败时把作业错误原样抛出。 */
private suspend fun awaitJob(
    controller: LauncherController,
    id: String,
) {
    withTimeout(30 * 60_000L) {
        var previous = ""
        while (true) {
            val job =
                controller.state.value.jobs
                    .first { it.id == id }
            val status = "${job.status}: ${job.detail}"
            if (status != previous) {
                println(status)
                previous = status
            }
            when (job.status) {
                JobStatus.Completed -> return@withTimeout
                JobStatus.Cancelled,
                JobStatus.Failed,
                -> error(job.error ?: job.detail)
                else -> delay(1_000)
            }
        }
    }
}

internal data class GameWindow(
    val os: String,
    val id: String,
    val pid: Long,
    val rectangle: Rectangle,
)

/** Looks up and captures only a window whose process is a child launched by this check. */
internal object GameWindowCapture {
    fun find(
        pid: Long,
        os: String,
    ): GameWindow? = if (os == "windows") WindowsGameWindow.find(pid) else linuxWindow(pid)

    private fun command(vararg args: String): String? =
        runCatching {
            val process = ProcessBuilder(*args).redirectErrorStream(true).start()
            try {
                check(process.waitFor(3, TimeUnit.SECONDS))
                if (process.exitValue() == 0) {
                    process.inputStream
                        .bufferedReader()
                        .readText()
                        .trim()
                } else {
                    null
                }
            } finally {
                if (process.isAlive) process.destroyForcibly()
            }
        }.getOrNull()

    private fun linuxWindow(pid: Long): GameWindow? {
        val id =
            command("xdotool", "search", "--onlyvisible", "--pid", pid.toString())
                ?.lineSequence()
                ?.firstOrNull() ?: return null
        val geometry = command("xdotool", "getwindowgeometry", "--shell", id) ?: return null
        val values =
            geometry
                .lineSequence()
                .mapNotNull { line ->
                    val pair = line.split('=', limit = 2)
                    if (pair.size == 2) pair[1].toIntOrNull()?.let { pair[0] to it } else null
                }.toMap()
        return GameWindow(
            "linux",
            id,
            pid,
            Rectangle(
                values.getValue("X"),
                values.getValue("Y"),
                values.getValue("WIDTH"),
                values.getValue("HEIGHT"),
            ),
        )
    }

    private fun activate(window: GameWindow): Boolean =
        if (window.os == "windows") {
            WindowsGameWindow.activate(window)
        } else {
            command("xdotool", "windowactivate", "--sync", window.id)
            command("xdotool", "getactivewindow") == window.id
        }

    fun click(
        window: GameWindow,
        x: Int,
        y: Int,
    ) {
        check(x in 0 until window.rectangle.width && y in 0 until window.rectangle.height)
        check(activate(window)) { "Game window lost focus; input was not sent" }
        if (window.os == "linux") {
            check(
                command(
                    "xdotool",
                    "mousemove",
                    "--window",
                    window.id,
                    x.toString(),
                    y.toString(),
                    "mousedown",
                    "1",
                    "sleep",
                    "0.12",
                    "mouseup",
                    "1",
                ) != null,
            ) {
                "Cannot click the owned game window"
            }
            return
        }
        Robot().apply {
            mouseMove(window.rectangle.x + x, window.rectangle.y + y)
            delay(300)
            mousePress(java.awt.event.InputEvent.BUTTON1_DOWN_MASK)
            delay(120)
            mouseRelease(java.awt.event.InputEvent.BUTTON1_DOWN_MASK)
        }
    }

    fun capture(
        window: GameWindow,
        destination: Path,
    ) {
        val active = activate(window)
        if (active && window.rectangle.width > 100 && window.rectangle.height > 100) {
            val writing = destination.resolveSibling(".${destination.fileName}.writing")
            try {
                ImageIO.write(
                    Robot().createScreenCapture(window.rectangle),
                    "png",
                    writing.toFile(),
                )
                java.nio.file.Files.move(
                    writing,
                    destination,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                )
            } finally {
                writing.deleteIfExists()
            }
        }
    }

    fun close(window: GameWindow) {
        if (window.os == "windows") {
            WindowsGameWindow.close(window)
        } else {
            LinuxGameWindow.close(window)
        }
    }
}
