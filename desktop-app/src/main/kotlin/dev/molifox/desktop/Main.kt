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
import androidx.compose.foundation.background
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
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
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
import dev.molifox.desktop.platform.dialogs.FilePicker
import dev.molifox.desktop.platform.dialogs.PickMode
import dev.molifox.desktop.platform.dialogs.PickRequest
import dev.molifox.desktop.platform.dialogs.SystemFilePicker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext
import java.awt.Dimension
import java.nio.file.Path
import javax.swing.JOptionPane

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

    /** 联机目前只有占位页：服务器列表、局域网与联机大厅都还没有实现。 */
    Multiplayer("联机", "服务器列表、局域网与联机大厅", Icons.Outlined.Groups),
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
                choose("选择 Modrinth / MoliFox 整合包", extensions = listOf("mrpack", "zip"))
                    .firstOrNull()
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

    fun startupMillis() = (System.nanoTime() - startupStartedAt) / 1_000_000
    System.setProperty("java.net.useSystemProxies", "true")
    val controller =
        try {
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
            runCatching { created.discoverStartupGameDirectories() }
                .onFailure { created.store.log("自动识别游戏目录失败：${it.message}", true) }
            created.store.log("启动：discovery done，用时 ${startupMillis()} ms")
            created
        } catch (error: Exception) {
            error.printStackTrace()
            System.getenv("MOLIFOX_DIAGNOSTIC_LOG")?.let { diagnosticPath ->
                runCatching {
                    java.nio.file.Files.writeString(
                        java.nio.file.Path
                            .of(diagnosticPath),
                        error.stackTraceToString(),
                    )
                }
            }
            JOptionPane.showMessageDialog(
                null,
                error.message,
                "MoliFox 启动失败",
                JOptionPane.ERROR_MESSAGE,
            )
            return
        }
    try {
        application {
            val launcherState by controller.state.collectAsState()
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
                when (launcherWindowAction(event, traySupported)) {
                    LauncherWindowAction.Keep -> Unit
                    LauncherWindowAction.Minimize -> windowState.isMinimized = true
                    LauncherWindowAction.Hide -> windowVisible = false
                    LauncherWindowAction.Restore -> {
                        windowVisible = true
                        windowState.isMinimized = false
                        mainWindow?.toFront()
                    }
                    LauncherWindowAction.Exit -> exitApplication()
                }
            }
            if (!windowVisible && traySupported) {
                Tray(
                    painterResource("icons/app/icon.png"),
                    tooltip = "MoliFox · 游戏运行中",
                    onAction = {
                        windowVisible = true
                        windowState.isMinimized = false
                    },
                ) {
                    Item(
                        "显示启动器",
                        onClick = {
                            windowVisible = true
                            windowState.isMinimized = false
                            mainWindow?.toFront()
                        },
                    )
                    Item("停止当前运行", onClick = { controller.game.stop() })
                    Separator()
                    Item(
                        "退出启动器",
                        onClick = {
                            windowVisible = true
                            windowState.isMinimized = false
                            if (controller.jobs.busy) confirmExit = true else exitApplication()
                        },
                    )
                }
            }
            // 窗口透明必须在创建时决定；运行中切换该设置需重启生效。
            val windowTransparent = launcherState.data.settings.windowTransparent
            Window(
                onCloseRequest = {
                    if (controller.jobs.busy) confirmExit = true else exitApplication()
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
                val actions = remember(window) { UiActions(controller, window) }
                MoliTheme(launcherState.data.settings) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            // 实时上报启动器窗口大小，供「与启动器一样大」的游戏窗口模式使用。
                            .onSizeChanged {
                                controller.reportLauncherWindowSize(it.width, it.height)
                            }.background(
                                if (launcherState.data.settings.windowTransparent) {
                                    MaterialTheme.colorScheme.background.copy(alpha = .82f)
                                } else {
                                    MaterialTheme.colorScheme.background
                                },
                            ).border(1.dp, MaterialTheme.colorScheme.outline)
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
                                            if (controller.jobs.busy) {
                                                confirmExit = true
                                            } else {
                                                exitApplication()
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
                            onDismissRequest = { confirmExit = false },
                            title = { Text("退出 MoliFox？") },
                            text = { Text("下载任务将取消，正在运行的 Minecraft 会继续运行。") },
                            confirmButton = {
                                TextButton(onClick = { exitApplication() }) { Text("退出启动器") }
                            },
                            dismissButton = {
                                TextButton(onClick = { confirmExit = false }) { Text("继续使用") }
                            },
                        )
                    }
                }
            }
        }
    } finally {
        controller.close()
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
    val navigate: (Page) -> Unit = { navigation.navigate(it, resources = true) }
    val install: () -> Unit = { navigation.install() }
    // 两个入口都进同一个实例详情页，但返回目标不同：
    // 开始页的「版本设置」返回开始，实例列表里的「设置」返回列表。
    val manageFromHome: (String) -> Unit = { navigation.openInstance(it, Page.Home) }
    val manageInList: (String) -> Unit = { navigation.openInstance(it, Page.Instances) }
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(
                    horizontal = Metrics.pageHorizontal,
                    vertical = Metrics.pageVertical,
                ),
        ) {
            // 页面内部使用 ColumnScope.weight 撑满剩余高度；AnimatedContent 的内容是
            // Box 作用域，因此这里显式标注接收者，动画分支补一层 Column 提供真实作用域。
            val pageContent: @Composable ColumnScope.(Page) -> Unit = { page ->
                when (page) {
                    Page.Home ->
                        HomePage(
                            state,
                            actions,
                            install,
                            navigate,
                            manageFromHome,
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
                            manageInList,
                            { deleting = it },
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
                    Page.Multiplayer -> MultiplayerPage()
                    Page.Settings -> SettingsPage(state, actions)
                }
            }
            val current = state.data.instances.find { it.id == navigation.editing }
            // 实例详情与主页面共用一次切换动画；key 语义保持不变。
            val target =
                if (current != null) "instance:" + current.id else "page:" + navigation.page.name
            val content: @Composable ColumnScope.(String) -> Unit = { targetKey ->
                if (targetKey.startsWith("instance:")) {
                    val instance =
                        state.data.instances.find { it.id == targetKey.removePrefix("instance:") }
                    if (instance != null) {
                        key(instance.id) {
                            InstanceDetailsPage(
                                instance,
                                state,
                                actions,
                                // 返回目标由打开详情的那一页决定：开始页进来回开始，列表进来回列表。
                                { navigation.closeInstance() },
                                { deleting = instance },
                                navigate,
                                onTidyInstance = { target ->
                                    // 整理会删除空目录，放到后台线程执行；结果只报告，不提供删除入口。
                                    actions.controller.store.notify("正在整理「${target.name}」的目录…")
                                    actions.run {
                                        val report =
                                            withContext(Dispatchers.IO) {
                                                actions.controller.tidyInstance(target.id)
                                            }
                                        withContext(Dispatchers.Swing) {
                                            tidyReport = target.name to report
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
                                                movingInstance = target to destination
                                            }
                                        }
                                    }
                                },
                            )
                        }
                    } else {
                        Column(Modifier.fillMaxSize()) { pageContent(navigation.page) }
                    }
                } else {
                    val page =
                        Page.entries.firstOrNull { targetKey == "page:" + it.name }
                            ?: navigation.page
                    pageContent(page)
                }
            }
            // 120ms 淡入 + 4dp 上移；关闭动画时直接呈现，不做过渡。
            if (LocalReducedMotion.current) {
                Column(Modifier.fillMaxSize()) { content(target) }
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
                    Column(Modifier.fillMaxSize()) { content(stateKey) }
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
    deleting?.let { instance ->
        // 删除实例会移除版本文件与隔离游戏目录，统一走带路径说明的二次确认。
        DeleteInstanceDialog(
            instance,
            actions,
            onDismiss = { deleting = null },
            onDeleted = {
                deleting = null
                // 删除的正是当前详情页时按来源返回；在列表里删除则留在列表。
                if (navigation.editing == instance.id) {
                    navigation.closeInstance()
                } else {
                    navigation.editing = null
                }
            },
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
        TidyReportDialog(name, report) { tidyReport = null }
    }
    movingInstance?.let { (instance, destination) ->
        InstanceMoveDialog(
            instance,
            destination,
            actions,
            { movingInstance = null },
            { pendingMove = instance.id to instance.gameDirectoryId },
        )
    }
}
