package dev.molifox.desktop

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.*
import dev.molifox.core.*
import java.awt.Dimension
import java.nio.file.Path
import javax.swing.JOptionPane
import kotlinx.coroutines.*
import kotlinx.coroutines.swing.Swing

/** 主导航只展示 entries 的前四项；Downloads（日志）与个性化外观都在设置页内。 */
enum class Page(val title: String, val subtitle: String, val icon: ImageVector) {
    Home("开始", "账户与游戏启动", Icons.Outlined.Home),
    Instances("实例", "管理版本与游戏内容", Icons.Outlined.Widgets),
    Discover("下载", "安装游戏与发现资源", Icons.Outlined.Explore),
    Settings("设置", "游戏、外观、下载与日志", Icons.Outlined.Tune),
    Downloads("日志", "查看下载进度与运行输出", Icons.Outlined.CloudDownload),
}

class UiActions(val controller: LauncherController, val window: ComposeWindow) {
    val navigation = DesktopNavigationState()
    var pendingPack by mutableStateOf<PackPreview?>(null)
    var launchPreview by mutableStateOf<LaunchPlan?>(null)

    fun previewLaunch(instanceId: String) = run {
        launchPreview = controller.previewLaunch(instanceId)
    }

    fun run(action: suspend () -> Unit) {
        controller.perform(action)
    }

    /**
     * 所有路径选择统一走应用内的 Compose 选择器（[PathPicker]），不再打开 Swing 文件对话框。
     * 入参与返回形状保持不变：空列表表示取消，结果一律是绝对路径。
     */
    suspend fun choose(
        title: String,
        save: Boolean = false,
        directory: Boolean = false,
        multiple: Boolean = false,
        extensions: List<String> = emptyList(),
        defaultName: String = "",
    ): List<Path> =
        PathPicker.pick(
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
            )
        )

    fun importPack() = run {
        choose("选择 Modrinth / MoliFox 整合包", extensions = listOf("mrpack", "zip"))
            .firstOrNull()
            ?.let { pendingPack = controller.content.previewPack(it) }
    }

    fun importExisting() = run {
        choose("选择游戏目录（包含 versions 的文件夹）", directory = true).firstOrNull()?.let {
            controller.importExisting(it)
        }
    }

    fun exportInstance(instance: GameInstance) = run {
        choose(
                "完整实例备份（包含存档、模组和配置）",
                save = true,
                extensions = listOf("zip"),
                defaultName = instance.name.replace(Regex("[<>:\"/\\\\|?*]"), "_") + ".zip",
            )
            .firstOrNull()
            ?.let { controller.exportInstance(instance.id, it) }
    }

    fun exportLogs() = run {
        choose("保存启动器日志", save = true, extensions = listOf("log"), defaultName = "MoliFox.log")
            .firstOrNull()
            ?.let { controller.exportLogs(it) }
    }

    fun exportDiagnostics() = run {
        choose(
                "保存游戏诊断包",
                save = true,
                extensions = listOf("zip"),
                defaultName = "MoliFox-diagnostics.zip",
            )
            .firstOrNull()
            ?.let { controller.exportDiagnostics(it) }
    }
}

fun main() {
    System.setProperty("java.net.useSystemProxies", "true")
    val controller =
        try {
            val platform = DesktopPlatform()
            val storage = StartupStorageResolver().resolve(platform.defaultDataDirectory)
            java.nio.file.Files.createDirectories(storage.resolve("cache/skiko"))
            System.setProperty(
                "skiko.data.path",
                storage.resolve("cache/skiko").toString(),
            )
            LauncherController(platform, storage)
        } catch (error: Exception) {
            error.printStackTrace()
            System.getenv("MOLIFOX_DIAGNOSTIC_LOG")?.let { diagnosticPath ->
                runCatching {
                    java.nio.file.Files.writeString(
                        java.nio.file.Path.of(diagnosticPath),
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
            // 默认 1180×720（最小 1000×640），对应 1280×720 游戏画面留出窗口边框。
            val windowState = rememberWindowState(width = 1180.dp, height = 720.dp)
            // 标题栏双击与最大化按钮共用同一段切换逻辑。
            val toggleMaximize: () -> Unit = {
                windowState.placement =
                    if (windowState.placement == WindowPlacement.Maximized)
                        WindowPlacement.Floating
                    else WindowPlacement.Maximized
            }
            var confirmExit by remember { mutableStateOf(false) }
            var windowVisible by remember { mutableStateOf(true) }
            var mainWindow by remember { mutableStateOf<ComposeWindow?>(null) }
            val traySupported = remember {
                runCatching { java.awt.SystemTray.isSupported() }.getOrDefault(false)
            }
            val gameRun = launcherState.gameRun
            LaunchedEffect(gameRun?.id, gameRun?.finished) {
                val event = gameRun ?: return@LaunchedEffect
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
            if (!windowVisible && traySupported)
                Tray(
                    painterResource("icons/molifox.png"),
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
                            if (controller.busy) confirmExit = true else exitApplication()
                        },
                    )
                }
            // 窗口透明必须在创建时决定；运行中切换该设置需重启生效。
            val windowTransparent = launcherState.data.settings.windowTransparent
            Window(
                onCloseRequest = { if (controller.busy) confirmExit = true else exitApplication() },
                state = windowState,
                visible = windowVisible,
                title = "MoliFox",
                icon = painterResource("icons/molifox.png"),
                undecorated = true,
                transparent = windowTransparent,
                resizable = true,
            ) {
                window.minimumSize = Dimension(1000, 640)
                SideEffect { mainWindow = window }
                val actions = remember(window) { UiActions(controller, window) }
                MoliTheme(launcherState.data.settings) {
                    Box(
                        Modifier.fillMaxSize()
                            // 实时上报启动器窗口大小，供「与启动器一样大」的游戏窗口模式使用。
                            .onSizeChanged {
                                controller.reportLauncherWindowSize(it.width, it.height)
                            }
                            .background(
                                if (launcherState.data.settings.windowTransparent)
                                    MaterialTheme.colorScheme.background.copy(alpha = .82f)
                                else MaterialTheme.colorScheme.background
                            )
                            .border(1.dp, MaterialTheme.colorScheme.outline)
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
                                            if (controller.busy) confirmExit = true
                                            else exitApplication()
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
                    if (confirmExit)
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
    } finally {
        controller.close()
    }
}

@Composable
fun MoliApp(state: LauncherState, actions: UiActions) {
    val navigation = actions.navigation
    var deleting by remember { mutableStateOf<GameInstance?>(null) }
    // 实例详情页的工具与升级弹窗：复制 / 整理报告 / 校验 / 整合包升级预览。
    var copyingInstance by remember { mutableStateOf<GameInstance?>(null) }
    var tidyReport by remember { mutableStateOf<Pair<String, TidyReport>?>(null) }
    var verifyingInstance by remember { mutableStateOf<GameInstance?>(null) }
    var upgradingInstance by remember { mutableStateOf<Pair<GameInstance, Path>?>(null) }
    // B09 隔离切换与跨游戏目录移动：对话框挂在根级，任务开始后实例记录更新不会打断页面动画。
    var switchingIsolation by remember { mutableStateOf<GameInstance?>(null) }
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
    val manage: (String) -> Unit = {
        navigation.editing = it
        navigation.page = Page.Instances
    }
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize()
                .padding(
                    horizontal = Metrics.pageHorizontal,
                    vertical = Metrics.pageVertical,
                )
        ) {
            // 页面内部使用 ColumnScope.weight 撑满剩余高度；AnimatedContent 的内容是
            // Box 作用域，因此这里显式标注接收者，动画分支补一层 Column 提供真实作用域。
            val pageContent: @Composable ColumnScope.(Page) -> Unit = { page ->
                when (page) {
                    Page.Home -> HomePage(state, actions, install, navigate, manage)
                    Page.Instances ->
                        InstancesPage(
                            state,
                            actions,
                            install,
                            manage,
                            { deleting = it },
                            navigate,
                        )
                    Page.Discover ->
                        DownloadCenterPage(
                            state,
                            actions,
                            navigation.downloadKind,
                            { navigation.downloadKind = it },
                            navigate,
                        )
                    Page.Downloads -> DownloadsPage(state, actions)
                    Page.Settings -> SettingsPage(state, actions, navigate)
                }
            }
            val current = state.data.instances.find { it.id == navigation.editing }
            // 实例详情与主页面共用一次切换动画；key 语义保持不变。
            val target = if (current != null) "instance:" + current.id else "page:" + navigation.page.name
            val content: @Composable ColumnScope.(String) -> Unit = { targetKey ->
                if (targetKey.startsWith("instance:")) {
                    val instance =
                        state.data.instances.find { it.id == targetKey.removePrefix("instance:") }
                    if (instance != null)
                        key(instance.id) {
                            InstanceDetailsPage(
                                instance,
                                state,
                                actions,
                                { navigation.editing = null },
                                { deleting = instance },
                                navigate,
                                onCopyInstance = { target -> copyingInstance = target },
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
                                onVerifyInstance = { target -> verifyingInstance = target },
                                onUpgradePack = { target ->
                                    // 先选新整合包归档，再打开预览对话框；选择与预览都不阻塞界面。
                                    actions.run {
                                        val archive =
                                            actions
                                                .choose(
                                                    "选择新版本的整合包（.mrpack / .zip）",
                                                    extensions = listOf("mrpack", "zip"),
                                                )
                                                .firstOrNull()
                                        if (archive != null)
                                            withContext(Dispatchers.Swing) {
                                                upgradingInstance = target to archive
                                            }
                                    }
                                },
                                onSwitchIsolation = { target ->
                                    switchingIsolation = target
                                },
                                onMoveInstance = { target ->
                                    // 先选目标游戏目录，再打开只读移动预览；预览会重新扫描实例目录。
                                    actions.run {
                                        val destination =
                                            actions
                                                .choose(
                                                    "选择目标游戏目录（实例会放到其中的 instances 文件夹）",
                                                    directory = true,
                                                )
                                                .firstOrNull()
                                        if (destination != null)
                                            withContext(Dispatchers.Swing) {
                                                movingInstance = target to destination
                                            }
                                    }
                                },
                            )
                        }
                    else Column(Modifier.fillMaxSize()) { pageContent(navigation.page) }
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
                        (fadeIn(tween(Motion.fast)) +
                            slideInVertically(tween(Motion.fast)) { shift }) togetherWith
                            fadeOut(tween(Motion.fast))
                    },
                    label = "content",
                ) { stateKey ->
                    Column(Modifier.fillMaxSize()) { content(stateKey) }
                }
            }
        }
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
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("移除「" + instance.name + "」？") },
            text = {
                Text(
                    if (instance.gameDirectoryId != null) "移除 MoliFox 中的版本记录，原目录的游戏文件保留。"
                    else "独立实例文件将移至回收站或 MoliFox/trash。共用目录中的存档和模组保留。"
                )
            },
            confirmButton = {
                TextButton({
                    actions.run { actions.controller.deleteInstance(instance.id) }
                    deleting = null
                    navigation.editing = null
                }) {
                    Text("移除实例", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton({ deleting = null }) { Text("取消") } },
        )
    }
    actions.pendingPack?.let { preview ->
        PackImportDialog(preview, actions) { actions.pendingPack = null }
    }
    actions.launchPreview?.let { plan ->
        LaunchPreviewDialog(plan) { actions.launchPreview = null }
    }
    copyingInstance?.let { instance ->
        CopyInstanceDialog(instance, actions) { copyingInstance = null }
    }
    tidyReport?.let { (name, report) ->
        TidyReportDialog(name, report) { tidyReport = null }
    }
    verifyingInstance?.let { instance ->
        VerifyInstanceDialog(
            instance,
            actions,
            { verifyingInstance = null },
            {
                // 修复沿用原有入口：走任务队列，进度在日志页。
                actions.run { actions.controller.repair(instance.id) }
                verifyingInstance = null
                navigation.navigate(Page.Downloads)
            },
        )
    }
    upgradingInstance?.let { (instance, archive) ->
        PackUpgradeDialog(
            instance,
            archive,
            actions,
            { upgradingInstance = null },
            { navigation.navigate(Page.Downloads) },
        )
    }
    switchingIsolation?.let { instance ->
        IsolationChangeDialog(
            instance,
            actions,
            { switchingIsolation = null },
            { navigation.navigate(Page.Downloads) },
        )
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
    // 应用内路径选择器挂在根级渲染一次：所有 PathPicker 请求共用同一个对话框。
    PathPickerHost(
        quickPlaces =
            buildList {
                add("MoliFox 数据目录" to actions.controller.store.dataDirectory)
                state.data.gameDirectories.forEach { directory ->
                    runCatching { Path.of(directory.path) }.getOrNull()?.let { path ->
                        add(directory.name.ifBlank { path.fileName?.toString().orEmpty() } to path)
                    }
                }
            }
    )
}
