package dev.molifox.core

import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.awt.Robot
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import kotlin.io.path.*
import kotlinx.coroutines.*

/** Opt-in acceptance against actual game files. A rendered window is not a main-menu verdict. */
fun main(): Unit = runBlocking {
    val prepared = System.getProperty("molifox.game.prepared", "false").toBoolean()
    val loader = System.getProperty("molifox.game.loader", "Vanilla")
    val report = System.getProperty("molifox.game.report")
    val root = Path.of(requireNotNull(System.getProperty("molifox.test.dir")))
    var failure: Throwable? = null
    val includeVanilla = System.getProperty("molifox.game.includeVanilla", "true").toBoolean()
    val targets =
        if (prepared && loader != "Vanilla" && includeVanilla) listOf(loader, "Vanilla")
        else listOf(loader)
    for (target in targets) {
        for (file in listOf("game-status.json", "game-review.json", "game-input.json")) root
            .resolve(file)
            .deleteIfExists()
        if (target != loader) {
            val original =
                gson.fromJson(
                    root.resolve("prepared-instance.json").readText(),
                    GameInstance::class.java,
                )
            writeJson(
                root.resolve("prepared-instance.json"),
                original.copy(
                    id = java.util.UUID.randomUUID().toString(),
                    loader = LoaderType.Vanilla,
                    loaderVersion = "",
                    versionId = original.gameVersion,
                ),
            )
        }
        System.setProperty("molifox.game.loader", target)
        System.setProperty(
            "molifox.game.report",
            report.replace("-${loader.lowercase()}.json", "-${target.lowercase()}.json"),
        )
        try {
            runRealGameSmoke()
        } catch (error: Throwable) {
            if (failure == null) failure = error else failure.addSuppressed(error)
        }
    }
    failure?.let { throw it }
}

private suspend fun runRealGameSmoke() {
    check(!GraphicsEnvironment.isHeadless()) { "Real game acceptance requires a graphical desktop" }
    val testRoot = Path.of(requireNotNull(System.getProperty("molifox.test.dir")))
    val profileRoot = testRoot.resolve("profile")
    val version = System.getProperty("molifox.game.version", "1.20.1")
    val loader = LoaderType.valueOf(System.getProperty("molifox.game.loader", "Vanilla"))
    val loaderVersion = System.getProperty("molifox.game.loaderVersion", "")
    val windowMode = System.getProperty("molifox.game.windowMode", "custom")
    val prepared = System.getProperty("molifox.game.prepared", "false").toBoolean()
    val report =
        linkedMapOf<String, Any>(
            "startedAt" to Instant.now().toString(),
            "os" to System.getProperty("os.name"),
            "version" to version,
            "loader" to loader.name,
            "mainMenuConfirmed" to false,
            "installationMode" to
                if (prepared) "cross-prepared-offline-launch" else "online-install",
        )
    var gameHandle: ProcessHandle? = null
    LauncherController(dataDirectory = profileRoot).use { controller ->
        suspend fun awaitJob(id: String) {
            withTimeout(30 * 60_000L) {
                var previous = ""
                while (true) {
                    val job = controller.state.value.jobs.first { it.id == id }
                    val status = "${job.status}: ${job.detail}"
                    if (status != previous) {
                        println(status)
                        previous = status
                    }
                    when (job.status) {
                        JobStatus.Completed -> return@withTimeout
                        JobStatus.Cancelled,
                        JobStatus.Failed -> error(job.error ?: job.detail)
                        else -> delay(1_000)
                    }
                }
            }
        }
        try {
            controller.saveSettings(
                controller.store.data.settings.copy(
                    memoryMb = 2048,
                    width = 960,
                    height = 600,
                    maxConcurrent = 8,
                    downloadSource = System.getProperty("molifox.game.downloadSource", "official"),
                    gameWindowMode = windowMode,
                    javaPath = System.getProperty("molifox.game.javaPath", ""),
                )
            )
            if (windowMode == "launcher") controller.reportLauncherWindowSize(1280, 725)
            report["windowMode"] = windowMode
            report["proxyMode"] = controller.store.data.settings.effectiveProxyMode()
            report["proxyType"] =
                controller.network.client.proxySelector
                    .select(java.net.URI("https://piston-meta.mojang.com"))
                    .first()
                    .type()
                    .name
            controller.accounts.addOffline("MoliTester")
            val requestedLoader =
                if (loader == LoaderType.Vanilla) ""
                else
                    loaderVersion.ifBlank {
                        controller.installer
                            .loaderVersions(loader, version)
                            .first { it.stable }
                            .version
                    }
            report["loaderVersion"] = requestedLoader
            if (prepared) {
                val imported =
                    gson.fromJson(
                        testRoot.resolve("prepared-instance.json").readText(),
                        GameInstance::class.java,
                    )
                check(
                    imported.gameVersion == version &&
                        imported.loader == loader &&
                        imported.installed
                )
                controller.store.mutate {
                    it.copy(instances = listOf(imported), selectedInstanceId = imported.id)
                }
            } else {
                awaitJob(
                    controller.install(
                        InstallRequest("Real game acceptance", version, loader, requestedLoader)
                    )
                )
            }
            val instance = controller.store.instance(controller.store.data.selectedInstanceId!!)
            val resolved =
                VersionResolver(controller.store.minecraftRoot(instance), controller.platform)
                    .resolve(instance.versionId)
            report["requiredJava"] = resolved.requiredJava
            report["gameDirectory"] = controller.store.instancePath(instance.id).toString()
            val previousChildren =
                ProcessHandle.current().children().use {
                    it.map { child -> child.pid() }.toList().toSet()
                }
            awaitJob(controller.launch(instance.id))
            gameHandle =
                ProcessHandle.current().children().use { children ->
                    children
                        .filter { it.isAlive && it.pid() !in previousChildren }
                        .toList()
                        .singleOrNull()
                } ?: error("The launched game process was not found")
            val handle = gameHandle
            report["pid"] = handle.pid()
            report["javaExecutable"] = handle.info().command().orElse("")
            controller.java.inspect(handle.info().command().orElse(""))?.let { runtime ->
                report["actualJavaMajor"] = runtime.majorVersion
                report["javaArchitecture"] = runtime.architecture
            }
            val screenshots = testRoot.resolve("screenshots").apply { createDirectories() }
            val frame =
                screenshots.resolve(
                    "MoliFox-game-${controller.platform.osName}-${loader.name.lowercase()}.png"
                )
            val statusFile = testRoot.resolve("game-status.json")
            println("GAME_REVIEW_DIRECTORY $testRoot")
            withTimeout(10 * 60_000L) {
                while (true) {
                    val fatal =
                        controller.state.value.logs.firstOrNull {
                            it.isGame &&
                                it.message.contains(
                                    "exception in thread \"main\"",
                                    ignoreCase = true,
                                )
                        }
                    check(fatal == null) {
                        "Game launch failed: " +
                            controller.state.value.logs.takeLast(20).joinToString("\n") {
                                it.message
                            }
                    }
                    check(handle.isAlive) {
                        "Game exited before acceptance: " +
                            controller.state.value.logs.takeLast(12).joinToString("\n") {
                                it.message
                            }
                    }
                    val window = GameWindowCapture.find(handle.pid(), controller.platform.osName)
                    if (window != null) {
                        val clientSize =
                            if (window.os == "windows") WindowsGameWindow.clientSize(window)
                            else window.rectangle.width to window.rectangle.height
                        report["clientWidth"] = clientSize.first
                        report["clientHeight"] = clientSize.second
                        GameWindowCapture.capture(window, frame)
                        report["stage"] = "waiting-for-visual-review"
                        report["workingScreenshot"] = frame.toString()
                        if (frame.isRegularFile())
                            report["screenshot"] = "docs/screenshots/current/${frame.fileName}"
                        writeJson(statusFile, report)
                        val input = testRoot.resolve("game-input.json")
                        if (input.isRegularFile()) {
                            val action = readObject(input)
                            if (action.has("x") && action.has("y")) {
                                GameWindowCapture.click(window, action.int("x"), action.int("y"))
                            }
                            input.deleteIfExists()
                        }
                        val review = testRoot.resolve("game-review.json")
                        val verdict = if (review.isRegularFile()) readObject(review) else null
                        if (verdict?.boolean("mainMenuConfirmed") == true) {
                            check(frame.isRegularFile()) {
                                "A game screenshot is required for acceptance"
                            }
                            report["mainMenuConfirmed"] = true
                            if (windowMode != "fullscreen") {
                                val expected =
                                    if (windowMode == "launcher") 1280 to 725 else 960 to 600
                                check(clientSize == expected) {
                                    "Game client size $clientSize differs from requested $expected"
                                }
                            }
                            if (verdict.has("quitX") && verdict.has("quitY")) {
                                report["closeMethod"] = "visually-reviewed-menu-button"
                                GameWindowCapture.click(
                                    window,
                                    verdict.int("quitX"),
                                    verdict.int("quitY"),
                                )
                            } else {
                                report["closeMethod"] = "window-close-request"
                                GameWindowCapture.close(window)
                            }
                            break
                        }
                    }
                    delay(2_000)
                }
            }
            withTimeout(30_000) { while (controller.game.isRunning) delay(200) }
            val exit =
                controller.state.value.logs.lastOrNull { it.message.startsWith("游戏已退出，退出码 ") }
            check(exit != null && exit.message.endsWith("退出码 0")) {
                "Game did not exit normally: ${exit?.message}"
            }
            report["result"] = "passed"
            report["normalExit"] = true
        } catch (error: Throwable) {
            report["result"] = "failed"
            report["error"] = error.message ?: error.javaClass.simpleName
            throw error
        } finally {
            gameHandle?.let { handle ->
                if (handle.isAlive) {
                    handle.destroy()
                    withTimeoutOrNull(5_000) { while (handle.isAlive) delay(100) }
                    if (handle.isAlive) handle.destroyForcibly()
                    withTimeoutOrNull(5_000) { while (handle.isAlive) delay(100) }
                }
            }
            report["finishedAt"] = Instant.now().toString()
            val evidence = Path.of(System.getProperty("molifox.game.report"))
            writeJson(evidence, report)
            println("REAL_GAME_RESULT ${gson.toJson(report)}")
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
    fun find(pid: Long, os: String): GameWindow? =
        if (os == "windows") WindowsGameWindow.find(pid) else linuxWindow(pid)

    private fun command(vararg args: String): String? = runCatching {
        val process = ProcessBuilder(*args).redirectErrorStream(true).start()
        try {
            check(process.waitFor(3, TimeUnit.SECONDS))
            if (process.exitValue() == 0) process.inputStream.bufferedReader().readText().trim()
            else null
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }
        .getOrNull()

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
                }
                .toMap()
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
        if (window.os == "windows") WindowsGameWindow.activate(window)
        else {
            command("xdotool", "windowactivate", "--sync", window.id)
            command("xdotool", "getactivewindow") == window.id
        }

    fun click(window: GameWindow, x: Int, y: Int) {
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
                ) != null
            ) {
                "Cannot click the owned game window"
            }
            return
        }
        Robot().apply {
            mouseMove(window.rectangle.x + x, window.rectangle.y + y)
            mousePress(java.awt.event.InputEvent.BUTTON1_DOWN_MASK)
            delay(120)
            mouseRelease(java.awt.event.InputEvent.BUTTON1_DOWN_MASK)
        }
    }

    fun capture(window: GameWindow, destination: Path) {
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
        if (window.os == "windows") WindowsGameWindow.close(window)
        else LinuxGameWindow.close(window)
    }
}
