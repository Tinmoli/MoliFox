// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 使用独立验收窗口和真实鼠标点击验证最近游玩、服务器删除、隐藏恢复及任务浮窗计时。

package dev.molifox.desktop

import TestWindowFocus
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.sun.jna.Native
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef.HWND
import dev.molifox.core.EntryKind
import dev.molifox.core.GameInstance
import dev.molifox.core.HomePagePreferences
import dev.molifox.core.JobStatus
import dev.molifox.core.LauncherController
import dev.molifox.core.MotdSegment
import dev.molifox.core.NbtCompound
import dev.molifox.core.NbtList
import dev.molifox.core.NbtString
import dev.molifox.core.ServerStatus
import dev.molifox.core.listServers
import dev.molifox.core.ping
import kotlinx.coroutines.delay
import java.awt.EventQueue
import java.awt.Robot
import java.awt.event.InputEvent
import java.awt.event.WindowEvent
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import javax.accessibility.AccessibleContext
import javax.imageio.ImageIO
import kotlin.concurrent.thread
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isHidden
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.outputStream
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import java.awt.Window as AwtWindow

private val testWindow = AtomicReference<ComposeWindow>()

fun main() {
    val root = Path.of(System.getProperty("molifox.test.dir")).toAbsolutePath().normalize()
    check(root.startsWith(Path.of(".work/tests").toAbsolutePath().normalize()))
    val controller = LauncherController(dataDirectory = root.resolve("profile"))
    val instance =
        GameInstance(
            name = "Recent UI",
            gameVersion = "26.3",
            versionId = "26.3",
            installed = true,
            gameDirectoryName = "26.3",
        )
    controller.store.mutate {
        it.copy(
            instances = listOf(instance),
            selectedInstanceId = instance.id,
            settings =
                it.settings.copy(
                    autoCheckUpdates = false,
                    pages =
                        it.settings.pages.copy(
                            home =
                                HomePagePreferences(
                                    recentPlayExpanded = true,
                                    recentPlayKind = "server",
                                ),
                        ),
                ),
        )
    }
    val game = controller.store.gamePath(instance).apply { createDirectories() }
    writeFixture(
        game.resolve("26.3.json"),
        mapOf("id" to "26.3", "mainClass" to "fixture.Main", "libraries" to emptyList<Any>()),
    )
    game.resolve("26.3.jar").writeBytes(byteArrayOf())
    game
        .resolve("saves/UI World")
        .createDirectories()
        .resolve("level.dat")
        .writeText("fixture")
    val servers =
        NbtCompound(
            mapOf(
                "servers" to
                    NbtList(
                        listOf(
                            NbtCompound(
                                mapOf(
                                    "name" to NbtString("UI Server A"),
                                    "ip" to NbtString("127.0.0.1:25566"),
                                ),
                            ),
                            NbtCompound(
                                mapOf(
                                    "name" to NbtString("UI Server B"),
                                    "ip" to
                                        NbtString(
                                            "very-long-address-for-ellipsis.example.invalid:25565",
                                        ),
                                ),
                            ),
                        ),
                        10,
                    ),
            ),
        )
    // The core writer is internal to its module; encode this tiny fixture independently.
    java.io.DataOutputStream(game.resolve("servers.dat").outputStream()).use { output ->
        output.writeByte(10)
        output.writeUTF("")
        output.writeByte(9)
        output.writeUTF("servers")
        output.writeByte(10)
        output.writeInt(2)
        checkNotNull(servers.list("servers")).compounds().forEach { entry ->
            for (key in listOf("name", "ip")) {
                output.writeByte(8)
                output.writeUTF(key)
                output.writeUTF(checkNotNull(entry.string(key)))
            }
            output.writeByte(0)
        }
        output.writeByte(0)
    }
    val original = game.resolve("servers.dat").readBytes()
    val status =
        ServerStatus(
            true,
            12,
            80,
            listOf(
                MotdSegment("茉狐生存服务器\n", 0xAD3C6C, true, false, false, false),
                MotdSegment("和朋友一起探索新的世界", 0x4559AD, false, false, false, false),
            ),
            "fixture",
            42,
        )
    val statusQuery =
        dev.molifox.core.domain.server.ServerStatusQuery { address ->
            if (address == "127.0.0.1:25566") status else status.copy(ping = 178)
        }
    val error = AtomicReference<Throwable?>()
    val driver =
        thread(name = "recent-play-ui-driver") {
            try {
                exerciseRecentUi(controller, instance, root, original)
            } catch (failure: Throwable) {
                runCatching { screenshot(Robot(), "MoliFox-recent-failure.png") }
                error.set(failure)
                System.err.println(failure.stackTraceToString())
            } finally {
                EventQueue.invokeLater {
                    testWindow
                        .get()
                        ?.dispatchEvent(WindowEvent(testWindow.get(), WindowEvent.WINDOW_CLOSING))
                }
            }
        }
    try {
        application(exitProcessOnExit = false) {
            Window(
                onCloseRequest = { exitApplication() },
                title = "MoliFox · Recent Play Test",
                state =
                    rememberWindowState(
                        width = 1180.dp,
                        height = 720.dp,
                        position = WindowPosition(60.dp, 60.dp),
                    ),
            ) {
                testWindow.set(window)
                val state by controller.state.collectAsState()
                val actions =
                    remember { UiActions(controller, window, serverStatusQuery = statusQuery) }
                var expanded by remember { mutableStateOf(true) }
                var kind by remember { mutableStateOf(EntryKind.Server) }
                MoliTheme(state.data.settings) {
                    Surface(Modifier.fillMaxSize()) {
                        Box(Modifier.fillMaxSize().padding(20.dp)) {
                            Column(Modifier.fillMaxSize()) {
                                RecentPlayPanel(
                                    instance,
                                    actions,
                                    controller.busy,
                                    expanded,
                                    { expanded = it },
                                    kind,
                                    { kind = it },
                                    { _, target ->
                                        writeFixture(root.resolve("clicked-target.json"), target)
                                    },
                                    Modifier.fillMaxWidth().weight(1f),
                                )
                            }
                            TaskProgressButton(state, actions, Modifier.align(Alignment.BottomEnd))
                            if (actions.taskCenterOpen) TaskCenterDialog(state, actions)
                        }
                    }
                }
            }
        }
        driver.join(15_000)
        check(!driver.isAlive) { "UI driver did not finish" }
        error.get()?.let { throw IllegalStateException("Recent play UI failed", it) }
    } finally {
        controller.close()
    }
}

private fun exerciseRecentUi(
    controller: LauncherController,
    instance: GameInstance,
    root: Path,
    original: ByteArray,
) {
    await("test window") { testWindow.get()?.isShowing == true }
    val robot = Robot().apply { autoDelay = 90 }
    EventQueue.invokeAndWait {
        val screen =
            java.awt.GraphicsEnvironment
                .getLocalGraphicsEnvironment()
                .maximumWindowBounds
        testWindow
            .get()
            .setBounds(
                screen.x + 20,
                screen.y + 20,
                minOf(1180, screen.width - 40),
                minOf(720, screen.height - 40),
            )
        testWindow.get().toFront()
        testWindow.get().isAlwaysOnTop = true
    }
    val nativeWindow = testWindow.get()
    robot.mouseMove(nativeWindow.x + 100, nativeWindow.y + 14)
    robot.mousePress(InputEvent.BUTTON1_DOWN_MASK)
    robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
    robot.delay(400)
    await("initial server rows") {
        contexts("UI Server A").isNotEmpty() && contexts("UI Server B").isNotEmpty()
    }
    check(contexts("42 ms").isEmpty() && contexts("延迟 42 ms").isEmpty()) {
        "Latency must stay hidden until the signal is hovered"
    }
    focusOwnWindow()
    val signal = bounds(contexts("服务器延迟信号 UI Server A").first())
    robot.mouseMove(signal.x + signal.width / 2, signal.y + signal.height / 2)
    await("latency tooltip") { contexts("延迟 42 ms").isNotEmpty() }
    robot.mouseMove(testWindow.get().x + 100, testWindow.get().y + 14)
    await("latency tooltip dismissed") { contexts("延迟 42 ms").isEmpty() }
    screenshot(robot, "MoliFox-recent-servers.png")
    checkMotdSourceColors("MoliFox-recent-servers.png")
    val times = contexts("尚未游玩")
    check(times.size >= 2 && times.map { bounds(it).x }.distinct().size == 1) {
        "Play-time columns do not align"
    }
    click(robot, "开始游戏 UI Server A")
    await("target received") { root.resolve("clicked-target.json").exists() }
    check(
        com.google.gson.JsonParser
            .parseString(root.resolve("clicked-target.json").readText())
            .asJsonObject
            .get("key")
            .asString == "127.0.0.1:25566",
    )
    click(robot, "更多操作 UI Server A")
    click(robot, "隐藏")
    await("hidden server removed") { contexts("UI Server A").isEmpty() }
    check(
        controller.store
            .gamePath(instance)
            .resolve("servers.dat")
            .readBytes()
            .contentEquals(original),
    )
    click(robot, "查看全部")
    click(robot, "显示已隐藏")
    await("hidden server visible") { contexts("UI Server A · 已隐藏").isNotEmpty() }
    click(robot, "更多操作 UI Server A")
    click(robot, "取消隐藏")
    await("unhidden persisted") {
        !controller.store.recentUsage.isHidden(EntryKind.Server, instance.id, "127.0.0.1:25566")
    }
    click(robot, "存档")
    await("world classification") { contexts("UI World").isNotEmpty() }
    check(contexts("UI Server B", contexts("完整存档与服务器列表").last()).isEmpty())
    screenshot(robot, "MoliFox-recent-all-worlds.png")
    click(robot, "服务器")
    click(robot, "搜索存档或服务器")
    java.awt.Toolkit
        .getDefaultToolkit()
        .systemClipboard
        .setContents(java.awt.datatransfer.StringSelection("server b"), null)
    robot.keyPress(java.awt.event.KeyEvent.VK_CONTROL)
    robot.keyPress(java.awt.event.KeyEvent.VK_V)
    robot.keyRelease(java.awt.event.KeyEvent.VK_V)
    robot.keyRelease(java.awt.event.KeyEvent.VK_CONTROL)
    await("server search") { contexts("UI Server A", contexts("完整存档与服务器列表").last()).isEmpty() }
    check(contexts("UI Server B", contexts("完整存档与服务器列表").last()).isNotEmpty())
    robot.keyPress(java.awt.event.KeyEvent.VK_CONTROL)
    robot.keyPress(java.awt.event.KeyEvent.VK_A)
    robot.keyRelease(java.awt.event.KeyEvent.VK_A)
    robot.keyRelease(java.awt.event.KeyEvent.VK_CONTROL)
    robot.keyPress(java.awt.event.KeyEvent.VK_BACK_SPACE)
    robot.keyRelease(java.awt.event.KeyEvent.VK_BACK_SPACE)
    await("search cleared") { contexts("UI Server A", contexts("完整存档与服务器列表").last()).isNotEmpty() }
    click(robot, "更多操作 UI Server A")
    click(robot, "删除")
    await("delete confirmation") { contexts("删除服务器？").isNotEmpty() }
    click(robot, "删除")
    await("server removed from NBT") {
        listServers(controller.store.gamePath(instance)).none { it.ip == "127.0.0.1:25566" }
    }
    val backups =
        controller.store.dataDirectory
            .resolve("backups/servers")
            .listDirectoryEntries("*.dat")
    check(backups.any { it.readBytes().contentEquals(original) })
    click(robot, "关闭存档与服务器列表")
    controller.saveSettings(
        controller.store.data.settings
            .copy(fontScale = 1.25f),
    )
    EventQueue.invokeAndWait { testWindow.get().setSize(650, 640) }
    Thread.sleep(600)
    screenshot(robot, "MoliFox-recent-font-scale.png")
    controller.saveSettings(
        controller.store.data.settings
            .copy(fontScale = 1f, themeMode = "dark"),
    )
    EventQueue.invokeAndWait { testWindow.get().setSize(1000, 700) }
    Thread.sleep(600)
    screenshot(robot, "MoliFox-recent-dark.png")
    controller.jobs.enqueue("Download timer fixture", setOf("ui-task")) {
        progress(.5f, "downloading fixture")
        delay(1200)
    }
    await("download badge visible") { contexts("打开任务中心").isNotEmpty() }
    val badge = bounds(contexts("打开任务中心").last())
    check(badge.y + badge.height <= testWindow.get().y + testWindow.get().height - 50) {
        "Download badge overlaps the bottom controls"
    }
    await("download finished") {
        controller.state.value.jobs
            .all { it.status == JobStatus.Completed }
    }
    check(contexts("打开任务中心").isNotEmpty()) { "Success badge disappeared before its delay" }
    await("download badge hidden after five seconds", 8000) { contexts("打开任务中心").isEmpty() }
    check(
        controller.state.value.jobs
            .isEmpty(),
    ) { "Finished task records were retained" }
    val cancelledTask =
        controller.jobs.enqueue("New download fixture", setOf("ui-task")) {
            kotlinx.coroutines.awaitCancellation()
        }
    await("new task reopens badge") { contexts("打开任务中心").isNotEmpty() }
    click(robot, "打开任务中心")
    check(contexts("已结束").isEmpty() && contexts("全部").isEmpty() && contexts("清除已结束").isEmpty())
    click(robot, "取消任务 New download fixture")
    await("clear cancellation choices") {
        contexts("继续任务").isNotEmpty() && contexts("确定取消").isNotEmpty()
    }
    click(robot, "继续任务")
    check(
        controller.state.value.jobs
            .first { it.id == cancelledTask }
            .status == JobStatus.Running,
    )
    click(robot, "取消任务 New download fixture")
    click(robot, "确定取消")
    await("cancelled task removed from current list") { contexts("这里暂时没有任务").isNotEmpty() }
    check(contexts("New download fixture").isEmpty())
    click(robot, "关闭任务中心")
    await("cancelled badge hidden", 8000) { contexts("打开任务中心").isEmpty() }
    check(
        controller.state.value.jobs
            .isEmpty(),
    )
    writeFixture(
        Path.of("build/validation/recent-play-ui.json"),
        mapOf(
            "updated" to Instant.now().toString(),
            "passed" to true,
            "platform" to "Windows",
            "checks" to
                listOf(
                    "real target click",
                    "latency appears only while signal is hovered",
                    "rendered MOTD keeps exact server RGB colors",
                    "aligned fixed time columns",
                    "hide/unhide without game mutation",
                    "all categories and search",
                    "actual NBT deletion and backup",
                    "font scaling",
                    "dark theme",
                    "raised download badge clears success and cancellation after five seconds",
                    "current tasks only and explicit continue or confirm cancel buttons",
                ),
        ),
    )
    println("RECENT_PLAY_UI_OK")
}

private fun writeFixture(
    path: Path,
    value: Any?,
) {
    path.parent.createDirectories()
    path.writeText(
        com.google.gson
            .GsonBuilder()
            .setPrettyPrinting()
            .create()
            .toJson(value),
    )
}

private fun await(
    label: String,
    timeout: Long = 12_000,
    predicate: () -> Boolean,
) {
    val end = System.currentTimeMillis() + timeout
    while (System.currentTimeMillis() < end) {
        if (predicate()) return
        Thread.sleep(100)
    }
    error("Timed out: $label")
}

private fun contexts(
    label: String,
    scope: AccessibleContext? = null,
): List<AccessibleContext> {
    val result = mutableListOf<AccessibleContext>()
    EventQueue.invokeAndWait {
        fun visit(
            context: AccessibleContext,
            depth: Int,
        ) {
            if (depth > 30) return
            if (
                context.accessibleComponent?.isShowing == true &&
                (context.accessibleName == label || context.accessibleDescription == label)
            ) {
                result += context
            }
            for (i in 0 until context.accessibleChildrenCount) {
                context
                    .getAccessibleChild(i)
                    ?.accessibleContext
                    ?.let { visit(it, depth + 1) }
            }
        }
        if (scope != null) {
            visit(scope, 0)
            return@invokeAndWait
        }
        val visible = AwtWindow.getWindows().filter { it.isShowing }
        val dialog = visible.filterIsInstance<java.awt.Dialog>().lastOrNull()
        val roots =
            if (dialog == null) {
                visible
            } else {
                visible.filter { candidate ->
                    candidate == dialog ||
                        generateSequence(candidate.owner) { it.owner }.any { it == dialog }
                }
            }
        roots.forEach { visit(it.accessibleContext, 0) }
    }
    return result
}

private fun bounds(context: AccessibleContext): java.awt.Rectangle {
    val result = AtomicReference<java.awt.Rectangle>()
    EventQueue.invokeAndWait {
        val component = context.accessibleComponent
        result.set(java.awt.Rectangle(component.locationOnScreen, component.size))
    }
    return result.get()
}

private fun focusOwnWindow() {
    if (System.getProperty("os.name").startsWith("Windows")) {
        EventQueue.invokeAndWait {
            val native = User32.INSTANCE
            val foreground = native.GetForegroundWindow()
            val ownHandles =
                AwtWindow
                    .getWindows()
                    .filter { it.isShowing }
                    .map {
                        val handle = HWND(Native.getComponentPointer(it))
                        native.GetAncestor(handle, 2) ?: handle
                    }
            if (foreground !in ownHandles) {
                val handle = HWND(Native.getComponentPointer(testWindow.get()))
                check(TestWindowFocus.activate(native.GetAncestor(handle, 2) ?: handle)) {
                    "Test window did not receive foreground focus"
                }
            }
        }
    }
}

private fun click(
    robot: Robot,
    label: String,
) {
    focusOwnWindow()
    var area: java.awt.Rectangle? = null
    await("control $label") {
        area = runCatching { contexts(label).lastOrNull()?.let(::bounds) }.getOrNull()
        area != null
    }
    val rectangle = requireNotNull(area)
    println("RECENT_UI_CLICK $label $rectangle")
    robot.mouseMove(rectangle.x + rectangle.width / 2, rectangle.y + rectangle.height / 2)
    robot.mousePress(InputEvent.BUTTON1_DOWN_MASK)
    robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
    robot.delay(180)
}

private fun screenshot(
    robot: Robot,
    name: String,
) {
    val window = testWindow.get()
    val output = Path.of("docs/screenshots/current", name).apply { parent.createDirectories() }
    ImageIO.write(
        robot.createScreenCapture(java.awt.Rectangle(window.locationOnScreen, window.size)),
        "png",
        output.toFile(),
    )
}

private fun checkMotdSourceColors(name: String) {
    val rendered = ImageIO.read(Path.of("docs/screenshots/current", name).toFile())
    for (source in listOf(0xAD3C6C, 0x4559AD)) {
        var matching = 0
        for (y in 0 until rendered.height) {
            for (x in 0 until rendered.width) {
                if ((rendered.getRGB(x, y) and 0xFFFFFF) == source) matching++
            }
        }
        check(matching >= 10) { "MOTD source color ${source.toString(16)} was replaced" }
    }
}
