// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责桌面程序入口、窗口生命周期与页面协调。

package dev.molifox.desktop

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.onClick
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.CropSquare
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.ManageAccounts
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Widgets
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.molifox.bootstrap.StartupFailureDialog
import dev.molifox.core.DesktopPlatform
import dev.molifox.core.GameInstance
import dev.molifox.core.LaunchPlan
import dev.molifox.core.LauncherController
import dev.molifox.core.LauncherState
import dev.molifox.core.LauncherWindowAction
import dev.molifox.core.PackPreview
import dev.molifox.core.StartupStorageResolver
import dev.molifox.core.TidyReport
import dev.molifox.core.domain.server.ServerStatusQuery
import dev.molifox.core.launcherWindowAction
import dev.molifox.desktop.content.DownloadsPage
import dev.molifox.desktop.instances.InstancesPage
import dev.molifox.desktop.platform.dialogs.FilePicker
import dev.molifox.desktop.platform.dialogs.PickMode
import dev.molifox.desktop.platform.dialogs.PickRequest
import dev.molifox.desktop.platform.dialogs.SystemFilePicker
import dev.molifox.desktop.platform.multiplayer.DesktopMultiplayerOwner
import dev.molifox.desktop.platform.nativeWindowTransparencySupported
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext
import java.awt.Dimension
import java.nio.file.Path

/**
 * 顶部导航显示 Navigation.kt 里的 TopNavPages：开始、下载、联机、设置。
 * 日志（Downloads）与账户列表（Accounts）是子页；实例页不在顶部导航里，可从开始页进入。
 */
enum class Page(
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
) {
    Home("开始", "账户与游戏启动", Icons.Outlined.Home),
    Instances("实例", "管理版本与游戏内容", Icons.Outlined.Widgets),
    Discover("下载", "安装游戏与发现资源", Icons.Outlined.Explore),

    Multiplayer("联机", "创建房间与房间码加入", Icons.Outlined.Groups),
    Settings("设置", "游戏、外观、下载与日志", Icons.Outlined.Tune),
    Downloads("日志", "运行输出与启动诊断", Icons.Outlined.Description),

    /** 账户列表是开始页的子页，不在顶部导航里显示，打开时顶部仍高亮「开始」。 */
    Accounts("账户列表", "添加、切换与删除游戏账户", Icons.Outlined.ManageAccounts),
}

class UiActions
    @JvmOverloads
    constructor(
        val controller: LauncherController,
        val window: ComposeWindow,
        private val filePicker: FilePicker = SystemFilePicker(window),
        val serverStatusQuery: ServerStatusQuery = ServerStatusQuery(controller::serverStatus),
        val multiplayer: DesktopMultiplayerOwner = DesktopMultiplayerOwner(controller),
    ) {
        val navigation = DesktopNavigationState()
        var pendingPack by mutableStateOf<PackPreview?>(null)
        private var packPreviewLoading = false
        var launchPreview by mutableStateOf<LaunchPlan?>(null)
        var taskCenterOpen by mutableStateOf(false)

        fun taskQueued() {
            controller.jobs.clearFinished()
        }

        fun showTasks() {
            taskCenterOpen = true
        }

        fun previewLaunch(instanceId: String) =
            run {
                launchPreview = controller.previewLaunch(instanceId)
            }

        fun run(action: suspend () -> Unit) {
            controller.perform(action)
        }

        /** 所有路径选择交给系统窗口；空列表表示取消。 */
        suspend fun choose(request: PickRequest): List<Path> = filePicker.pick(request)

        suspend fun choose(
            title: String,
            save: Boolean = false,
            directory: Boolean = false,
            multiple: Boolean = false,
            extensions: List<String> = emptyList(),
            defaultName: String = "",
        ): List<Path> =
            choose(
                PickRequest(
                    title = title,
                    mode =
                        when {
                            save -> PickMode.Save
                            directory -> PickMode.Directory
                            else -> PickMode.File
                        },
                    multiple = multiple,
                    extensions = extensions,
                    defaultName = defaultName,
                ),
            )

        fun importPack() =
            run {
                choose(
                    "选择 Modrinth / CurseForge / MultiMC·Prism / HMCL·MCBBS / MoliFox 整合包",
                    extensions = listOf("mrpack", "zip"),
                ).firstOrNull()
                    ?.let { source -> withContext(Dispatchers.Swing) { previewPackFile(source) } }
            }

        fun previewPackFile(source: Path) {
            if (packPreviewLoading || pendingPack != null) {
                controller.store.notify("请先完成当前整合包预览")
                return
            }
            packPreviewLoading = true
            controller.store.notify("正在检查整合包…")
            run {
                try {
                    val preview = controller.content.previewPack(source)
                    withContext(Dispatchers.Swing) { pendingPack = preview }
                } finally {
                    withContext(NonCancellable + Dispatchers.Swing) { packPreviewLoading = false }
                }
            }
        }

        fun importExisting() =
            run {
                choose("选择游戏目录（包含 versions 的文件夹）", directory = true).firstOrNull()?.let {
                    controller.importExisting(it)
                }
            }

        fun exportLogs() =
            run {
                choose(
                    "保存启动器日志",
                    save = true,
                    extensions = listOf("log"),
                    defaultName = "MoliFox.log",
                ).firstOrNull()
                    ?.let { controller.exportLogs(it) }
            }

        fun exportDiagnostics() =
            run {
                choose(
                    "保存游戏诊断包",
                    save = true,
                    extensions = listOf("zip"),
                    defaultName = "MoliFox-diagnostics.zip",
                ).firstOrNull()
                    ?.let { controller.exportDiagnostics(it) }
            }
    }

fun main() {
    // 启动计时：只用来看清启动各阶段的耗时，不改变任何行为。
    val startupStartedAt = System.nanoTime()
    // 各阶段的耗时都从同一个起点算，只进日志；需要打点的函数接收这个计时器。
    val startupMillis: () -> Long = { (System.nanoTime() - startupStartedAt) / 1_000_000 }
    System.setProperty("java.net.useSystemProxies", "true")
    val controller = startLauncherController(startupMillis) ?: return
    val multiplayer = DesktopMultiplayerOwner(controller)
    try {
        application { LauncherApplication(controller, startupMillis, multiplayer) }
    } finally {
        try {
            runBlocking(Dispatchers.IO) { multiplayer.close() }
        } finally {
            controller.close()
        }
    }
}

/** 创建控制器：失败时写诊断日志并弹窗，返回 null 表示启动中止。 */
private fun startLauncherController(startupMillis: () -> Long): LauncherController? =
    // 启动链路串联平台探测、存储解析与控制器构造，异常类型无法收窄；这里是总出口，转成诊断日志与弹窗。
    runCatching {
        val platform = DesktopPlatform()
        val storage = StartupStorageResolver().resolve(platform.defaultDataDirectory)
        val nativeDirectory = storage.resolve("dependencies/native")
        java.nio.file.Files
            .createDirectories(nativeDirectory)
        System.setProperty(
            "skiko.data.path",
            nativeDirectory.toString(),
        )
        // 启动即按启动器自身位置发现旁边/内部的现有 .minecraft 或游戏根并发起登记；
        // 扫描失败只写日志，不阻止启动。
        val created = LauncherController(platform, storage)
        created.store.log("启动：store ready，用时 ${startupMillis()} ms")
        created
    }.getOrElse { error ->
        // 启动链路串联平台探测、存储解析与控制器构造，任何一步的异常都必须转成诊断日志 + 弹窗，
        // 因此这里保留兜底捕获；错误信息照常输出，不吞掉失败原因。
        System.err.println("启动失败：${error.message}")
        System.getenv("MOLIFOX_DIAGNOSTIC_LOG")?.let { diagnosticPath ->
            runCatching {
                java.nio.file.Files.writeString(
                    java.nio.file.Path
                        .of(diagnosticPath),
                    error.stackTraceToString(),
                )
            }
        }
        StartupFailureDialog.show(error, System.getenv("MOLIFOX_DIAGNOSTIC_LOG")?.let(Path::of))
        null
    }

/** 应用作用域：窗口、托盘、主题与根布局，初始化顺序与入口内联时一致。 */
@Composable
private fun ApplicationScope.LauncherApplication(
    controller: LauncherController,
    startupMillis: () -> Long,
    multiplayer: DesktopMultiplayerOwner,
) {
    val launcherState by controller.state.collectAsState()
    // 首帧后再扫描旁边的游戏目录：启动路径只依赖已加载的配置，目录多或磁盘慢也不会拖慢窗口出现。
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            runCatching { controller.discoverStartupGameDirectories() }
                .onFailure { controller.store.log("自动识别游戏目录失败：${it.message}", true) }
        }
        controller.store.log("启动：discovery done，用时 ${startupMillis()} ms")
    }
    // 自定义左上角图标缺失或损坏时只记一行日志并回退内置图标（BrandMark 内部同样回退），启动不受影响。
    LaunchedEffect(
        launcherState.data.settings.brandIconPath,
        launcherState.data.settings.brandIconRevision,
    ) {
        val stored = launcherState.data.settings.brandIconPath
        if (stored.isBlank()) return@LaunchedEffect
        val problem = withContext(Dispatchers.IO) { brandIconProblem(stored) }
        if (problem != null) {
            controller.store.log("自定义品牌图标不可用（$problem），已回退内置图标")
        }
    }
    // 默认 1180×720（最小 1000×640），对应 1280×720 游戏画面留出窗口边框。
    val windowState = rememberWindowState(width = 1180.dp, height = 720.dp)
    // 标题栏双击与最大化按钮共用同一段切换逻辑。
    val toggleMaximize: () -> Unit = {
        windowState.placement =
            if (windowState.placement == WindowPlacement.Maximized) {
                WindowPlacement.Floating
            } else {
                WindowPlacement.Maximized
            }
    }
    var confirmExit by remember { mutableStateOf(false) }
    var windowVisible by remember { mutableStateOf(true) }
    var mainWindow by remember { mutableStateOf<ComposeWindow?>(null) }
    val traySupported =
        remember {
            runCatching { java.awt.SystemTray.isSupported() }.getOrDefault(false)
        }
    val gameRun = launcherState.gameRun
    LaunchedEffect(gameRun?.id, gameRun?.finished) {
        val event = gameRun ?: return@LaunchedEffect
        if (event.finished && launcherState.runningGames.isNotEmpty()) return@LaunchedEffect
        applyLauncherWindowAction(
            launcherWindowAction(event, traySupported),
            windowState,
            { windowVisible = it },
            { mainWindow?.toFront() },
            { exitApplication() },
        )
    }
    LauncherTray(
        visible = !windowVisible,
        supported = traySupported,
        controller = controller,
        onRestore = {
            windowVisible = true
            windowState.isMinimized = false
        },
        onShowWindow = {
            windowVisible = true
            windowState.isMinimized = false
            mainWindow?.toFront()
        },
        onQuit = {
            windowVisible = true
            windowState.isMinimized = false
            if (controller.jobs.busy ||
                multiplayer.active
            ) {
                confirmExit = true
            } else {
                exitApplication()
            }
        },
    )
    // AWT 不允许改变已显示窗口的透明模式；内容背景随设置切换，原生窗口模式保持不变。
    val windowTransparent = remember { nativeWindowTransparencySupported() }
    Window(
        onCloseRequest = {
            if (controller.jobs.busy ||
                multiplayer.active
            ) {
                confirmExit = true
            } else {
                exitApplication()
            }
        },
        state = windowState,
        visible = windowVisible,
        title = "MoliFox",
        icon = painterResource("icons/app/icon.png"),
        undecorated = true,
        transparent = windowTransparent,
        resizable = true,
    ) {
        window.minimumSize = Dimension(1000, 640)
        SideEffect { mainWindow = window }
        // 首帧真正提交后再记一次启动耗时，作为「窗口可见」的可测量时间点。
        LaunchedEffect(Unit) {
            withFrameNanos { }
            controller.store.log("启动：first frame，用时 ${startupMillis()} ms")
        }
        val actions = remember(window) { UiActions(controller, window, multiplayer = multiplayer) }
        LauncherRoot(
            controller,
            launcherState,
            actions,
            windowState,
            toggleMaximize,
            confirmExit,
            onConfirmExit = { confirmExit = true },
            onDismissConfirmExit = { confirmExit = false },
            onExit = { exitApplication() },
        )
    }
}

/** 托盘菜单：窗口隐藏且系统支持托盘时才创建，动作与标题栏、关闭按钮保持一致。 */
@Composable
private fun ApplicationScope.LauncherTray(
    visible: Boolean,
    supported: Boolean,
    controller: LauncherController,
    onRestore: () -> Unit,
    onShowWindow: () -> Unit,
    onQuit: () -> Unit,
) {
    if (!visible || !supported) return
    Tray(
        painterResource("icons/app/icon.png"),
        tooltip = "MoliFox · 游戏运行中",
        onAction = onRestore,
    ) {
        Item("显示启动器", onClick = onShowWindow)
        Item("停止当前运行", onClick = { controller.game.stop() })
        Separator()
        Item("退出启动器", onClick = onQuit)
    }
}

/** 游戏运行状态变化后的窗口动作：最小化、隐藏、恢复或退出，顺序与状态判断都不变。 */
private fun applyLauncherWindowAction(
    action: LauncherWindowAction,
    windowState: WindowState,
    setWindowVisible: (Boolean) -> Unit,
    bringWindowToFront: () -> Unit,
    exitLauncher: () -> Unit,
) {
    when (action) {
        LauncherWindowAction.Keep -> Unit
        LauncherWindowAction.Minimize -> windowState.isMinimized = true
        LauncherWindowAction.Hide -> setWindowVisible(false)
        LauncherWindowAction.Restore -> {
            setWindowVisible(true)
            windowState.isMinimized = false
            bringWindowToFront()
        }
        LauncherWindowAction.Exit -> exitLauncher()
    }
}

/** 窗口内容：主题、标题栏、页面容器、退出确认与拖放目标。 */
@Composable
private fun FrameWindowScope.LauncherRoot(
    controller: LauncherController,
    launcherState: LauncherState,
    actions: UiActions,
    windowState: WindowState,
    toggleMaximize: () -> Unit,
    confirmExit: Boolean,
    onConfirmExit: () -> Unit,
    onDismissConfirmExit: () -> Unit,
    onExit: () -> Unit,
) {
    MoliTheme(launcherState.data.settings) {
        Box(
            Modifier
                .fillMaxSize()
                // 实时上报启动器窗口大小，供「与启动器一样大」的游戏窗口模式使用。
                .onSizeChanged {
                    controller.reportLauncherWindowSize(it.width, it.height)
                }.border(1.dp, MaterialTheme.colorScheme.outline)
                .packDropTarget(actions),
        ) {
            LauncherBackdrop(launcherState.data.settings)
            Column(Modifier.fillMaxSize()) {
                // 标题栏固定在顶部，双击拖动区切换最大化（拖动逻辑不变）。
                WindowDraggableArea {
                    AppHeader(
                        actions.navigation,
                        launcherState,
                        onTitlebarDoubleClick = toggleMaximize,
                    ) {
                        val tint = MaterialTheme.colorScheme.onSurfaceVariant
                        if (launcherState.jobs.isNotEmpty()) {
                            SmallIconButton(
                                Icons.Outlined.CloudDownload,
                                "任务中心",
                                { actions.showTasks() },
                                tint,
                            )
                        }
                        SmallIconButton(
                            Icons.Outlined.Remove,
                            "最小化",
                            { windowState.isMinimized = true },
                            tint,
                        )
                        SmallIconButton(
                            Icons.Outlined.CropSquare,
                            "最大化",
                            toggleMaximize,
                            tint,
                        )
                        SmallIconButton(
                            Icons.Outlined.Close,
                            "关闭",
                            {
                                if (controller.jobs.busy || actions.multiplayer.active) {
                                    onConfirmExit()
                                } else {
                                    onExit()
                                }
                            },
                            tint,
                        )
                    }
                }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    MoliApp(launcherState, actions)
                }
            }
        }
        if (confirmExit) {
            AlertDialog(
                onDismissRequest = onDismissConfirmExit,
                title = { Text("退出 MoliFox？") },
                text = {
                    Text(
                        if (actions.multiplayer.active) {
                            "联机将断开，下载任务将取消；正在运行的 Minecraft 会继续运行。"
                        } else {
                            "下载任务将取消，正在运行的 Minecraft 会继续运行。"
                        },
                    )
                },
                confirmButton = {
                    TextButton(onClick = { onExit() }) { Text("退出启动器") }
                },
                dismissButton = {
                    TextButton(onClick = onDismissConfirmExit) { Text("继续使用") }
                },
            )
        }
    }
}

@Composable
fun MoliApp(
    state: LauncherState,
    actions: UiActions,
) {
    val navigation = actions.navigation
    var deleting by remember { mutableStateOf<GameInstance?>(null) }
    // 实例详情页的工具弹窗：整理报告 / 目录移动。
    var tidyReport by remember { mutableStateOf<Pair<String, TidyReport>?>(null) }
    // 目录移动对话框挂在根级，避免任务更新实例记录时打断预览。
    var movingInstance by remember { mutableStateOf<Pair<GameInstance, Path>?>(null) }
    var pendingMove by remember { mutableStateOf<Pair<String, String?>?>(null) }
    // 移动完成后让实例列表跟随到新的游戏目录：同一个实例保持选中，不会从列表里消失。
    LaunchedEffect(state.data.instances, pendingMove) {
        val (id, previousDirectory) = pendingMove ?: return@LaunchedEffect
        val current = state.data.instances.find { it.id == id } ?: return@LaunchedEffect
        if (current.gameDirectoryId != previousDirectory) {
            actions.controller.selectInstance(id)
            pendingMove = null
        }
    }
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(
                    horizontal = Metrics.pageHorizontal,
                    vertical = Metrics.pageVertical,
                ),
        ) {
            val current = state.data.instances.find { it.id == navigation.editing }
            // 实例详情与主页面共用一次切换动画；key 语义保持不变。
            val target =
                if (current != null) "instance:" + current.id else "page:" + navigation.page.name
            // 120ms 淡入 + 4dp 上移；关闭动画时直接呈现，不做过渡。
            if (LocalReducedMotion.current) {
                Column(Modifier.fillMaxSize()) {
                    LauncherContent(
                        targetKey = target,
                        state = state,
                        actions = actions,
                        fallbackPage = navigation.page,
                        onDelete = { deleting = it },
                        onTidyReport = { tidyReport = it },
                        onMoveTarget = { movingInstance = it },
                    )
                }
            } else {
                val shift = with(LocalDensity.current) { 4.dp.roundToPx() }
                AnimatedContent(
                    targetState = target,
                    modifier = Modifier.fillMaxSize(),
                    transitionSpec = {
                        (
                            fadeIn(tween(Motion.FAST)) +
                                slideInVertically(tween(Motion.FAST)) { shift }
                        ) togetherWith
                            fadeOut(tween(Motion.FAST))
                    },
                    label = "content",
                ) { stateKey ->
                    Column(Modifier.fillMaxSize()) {
                        LauncherContent(
                            targetKey = stateKey,
                            state = state,
                            actions = actions,
                            fallbackPage = navigation.page,
                            onDelete = { deleting = it },
                            onTidyReport = { tidyReport = it },
                            onMoveTarget = { movingInstance = it },
                        )
                    }
                }
            }
        }
        TaskProgressButton(state, actions, Modifier.align(Alignment.BottomEnd).padding(20.dp))
        state.toast?.let { message ->
            LaunchedEffect(message) {
                delay(6500)
                actions.controller.store.dismissNotification()
            }
            Surface(
                Modifier.align(Alignment.BottomCenter).padding(24.dp).widthIn(max = 720.dp),
                color = MaterialTheme.colorScheme.inverseSurface,
                shape = RoundedCornerShape(Metrics.dialogRadius),
                shadowElevation = 6.dp,
            ) {
                Row(
                    Modifier.padding(start = 18.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        message,
                        Modifier.weight(1f, fill = false),
                        color = MaterialTheme.colorScheme.inverseOnSurface,
                        fontSize = 12.sp,
                    )
                    Spacer(Modifier.width(10.dp))
                    SmallIconButton(
                        Icons.Outlined.Close,
                        "关闭提示",
                        { actions.controller.store.dismissNotification() },
                        tint = MaterialTheme.colorScheme.inverseOnSurface,
                    )
                }
            }
        }
    }
    LauncherDialogs(
        state = state,
        actions = actions,
        deleting = deleting,
        tidyReport = tidyReport,
        movingInstance = movingInstance,
        onCloseDelete = { deleting = null },
        onDeleted = { instance ->
            deleting = null
            // 删除的正是当前详情页时按来源返回；在列表里删除则留在列表。
            if (navigation.editing == instance.id) {
                navigation.closeInstance()
            } else {
                navigation.editing = null
            }
        },
        onCloseTidy = { tidyReport = null },
        onCloseMove = { movingInstance = null },
        onMoveStarted = { instance -> pendingMove = instance.id to instance.gameDirectoryId },
    )
}

/**
 * 页面容器：实例详情优先，否则显示当前主页面。
 *
 * 页面内部使用 ColumnScope.weight 撑满剩余高度；AnimatedContent 的内容是 Box 作用域，
 * 因此调用方补一层 Column 提供真实作用域。
 */
@Composable
private fun ColumnScope.LauncherContent(
    targetKey: String,
    state: LauncherState,
    actions: UiActions,
    fallbackPage: Page,
    onDelete: (GameInstance) -> Unit,
    onTidyReport: (Pair<String, TidyReport>) -> Unit,
    onMoveTarget: (Pair<GameInstance, Path>) -> Unit,
) {
    val navigation = actions.navigation
    val instance =
        if (targetKey.startsWith("instance:")) {
            state.data.instances.find { it.id == targetKey.removePrefix("instance:") }
        } else {
            null
        }
    if (instance == null) {
        val page =
            Page.entries.firstOrNull { targetKey == "page:" + it.name } ?: fallbackPage
        LauncherPages(page, state, actions, onDelete)
        return
    }
    key(instance.id) {
        InstanceDetailsPage(
            instance,
            state,
            actions,
            // 返回目标由打开详情的那一页决定：开始页进来回开始，列表进来回列表。
            { navigation.closeInstance() },
            { onDelete(instance) },
            { navigation.navigate(it, resources = true) },
            onTidyInstance = { target ->
                // 整理会删除空目录，放到后台线程执行；结果只报告，不提供删除入口。
                actions.controller.store.notify("正在整理「${target.name}」的目录…")
                actions.run {
                    val report =
                        withContext(Dispatchers.IO) {
                            actions.controller.tidyInstance(target.id)
                        }
                    withContext(Dispatchers.Swing) {
                        onTidyReport(target.name to report)
                    }
                }
            },
            onMoveInstance = { target ->
                // 先选目标游戏目录，再打开只读移动预览；预览会重新扫描实例目录。
                actions.run {
                    val destination =
                        actions
                            .choose(
                                "选择目标游戏目录（实例会放到其中的 versions 文件夹）",
                                directory = true,
                            ).firstOrNull()
                    if (destination != null) {
                        withContext(Dispatchers.Swing) {
                            onMoveTarget(target to destination)
                        }
                    }
                }
            },
        )
    }
}

/** 按当前页面构造对应页面；安装、导航与实例管理入口都从 actions 派生。 */
@Composable
private fun ColumnScope.LauncherPages(
    page: Page,
    state: LauncherState,
    actions: UiActions,
    onDelete: (GameInstance) -> Unit,
) {
    val navigation = actions.navigation
    val navigate: (Page) -> Unit = { navigation.navigate(it, resources = true) }
    val install: () -> Unit = { navigation.install() }
    // 两个入口都进同一个实例详情页，但返回目标不同：
    // 开始页的「版本设置」返回开始，实例列表里的「设置」返回列表。
    when (page) {
        Page.Home ->
            HomePage(
                state,
                actions,
                install,
                navigate,
                { navigation.openInstance(it, Page.Home) },
                { navigation.openAccounts() },
            )
        // 认证服务器入口直接在页面内打开 AuthlibLoginDialog，添加成功后随 store 刷新列表。
        Page.Accounts ->
            AccountListPage(state, actions) {
                navigation.closeAccounts()
            }
        Page.Instances ->
            InstancesPage(
                state,
                actions,
                install,
                { navigation.openInstance(it, Page.Instances) },
                onDelete,
                navigate,
            )
        Page.Discover ->
            DownloadCenterPage(
                state,
                actions,
                navigation.downloadKind,
                { navigation.downloadKind = it },
            )
        Page.Downloads -> DownloadsPage(state, actions)
        Page.Multiplayer -> MultiplayerPage(actions)
        Page.Settings -> SettingsPage(state, actions)
    }
}

/** 根级弹窗：删除确认、整合包导入、启动预览、Java 提示、任务中心、整理报告与目录移动。 */
@Composable
private fun LauncherDialogs(
    state: LauncherState,
    actions: UiActions,
    deleting: GameInstance?,
    tidyReport: Pair<String, TidyReport>?,
    movingInstance: Pair<GameInstance, Path>?,
    onCloseDelete: () -> Unit,
    onDeleted: (GameInstance) -> Unit,
    onCloseTidy: () -> Unit,
    onCloseMove: () -> Unit,
    onMoveStarted: (GameInstance) -> Unit,
) {
    deleting?.let { instance ->
        // 删除实例会移除版本文件与隔离游戏目录，统一走带路径说明的二次确认。
        DeleteInstanceDialog(
            instance,
            actions,
            onDismiss = onCloseDelete,
            onDeleted = { onDeleted(instance) },
        )
    }
    actions.pendingPack?.let { preview ->
        PackImportDialog(preview, actions) { actions.pendingPack = null }
    }
    actions.launchPreview?.let { plan ->
        LaunchPreviewDialog(plan) { actions.launchPreview = null }
    }
    // 启动检查：实例缺少兼容 Java 时给出安装/选择入口；显式路径或已存在兼容 Java 时不出现。
    state.javaPrompt?.let { prompt -> JavaRuntimePromptDialog(prompt, actions) }
    if (actions.taskCenterOpen) TaskCenterDialog(state, actions)
    tidyReport?.let { (name, report) ->
        TidyReportDialog(name, report, onCloseTidy)
    }
    movingInstance?.let { (instance, destination) ->
        InstanceMoveDialog(
            instance,
            destination,
            actions,
            onCloseMove,
            { onMoveStarted(instance) },
        )
    }
}
