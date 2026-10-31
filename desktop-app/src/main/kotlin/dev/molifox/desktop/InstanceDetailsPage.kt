// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 实例详情页：页头、页签切换、自动保存与对话框，各页签内容在 instance 包内。

package dev.molifox.desktop

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.molifox.core.GameInstance
import dev.molifox.core.InstanceMemoryPolicy
import dev.molifox.core.JavaRuntime
import dev.molifox.core.LauncherSettings
import dev.molifox.core.LauncherState
import dev.molifox.core.PackFolder
import dev.molifox.core.applyInstanceMemoryPolicy
import dev.molifox.core.domain.instance.GameFolder
import dev.molifox.core.instanceLaunchNumberField
import dev.molifox.core.instanceMemoryPolicy
import dev.molifox.core.parseInstanceLaunchNumberInput
import dev.molifox.desktop.instance.InstanceDetailsRail
import dev.molifox.desktop.instance.InstanceRenameDialog
import dev.molifox.desktop.instance.InstanceSettingsAutoSave
import dev.molifox.desktop.instance.InstanceSettingsSaver
import dev.molifox.desktop.instance.InstanceSettingsTab
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext

/** 页签序号：光影 3、存档 4、原理图 5；其余页签按内容目录的固定顺序排列。 */
private const val TAB_INDEX_SHADER_PACKS = 3
private const val TAB_INDEX_SAVES = 4
private const val TAB_INDEX_SCHEMATICS = 5

/** 实例详情：页头 + 左侧页签栏与右侧内容区，页签为设置 / 模组 / 资源包 / 光影 / 存档 / 原理图。 */
@Composable
fun ColumnScope.InstanceDetailsPage(
    instance: GameInstance,
    state: LauncherState,
    actions: UiActions,
    onBack: () -> Unit,
    onDelete: () -> Unit,
    navigate: (Page) -> Unit,
    onTidyInstance: (GameInstance) -> Unit = {},
    onMoveInstance: (GameInstance) -> Unit = {},
) {
    var tab by remember { mutableIntStateOf(0) }
    var draft by remember(instance.id) { mutableStateOf(instance) }
    var syncedInstance by remember(instance.id) { mutableStateOf(instance) }
    // 内存改为与设置页同款的滑块；只有「手动分配」策略才保存显式值，其它策略存 0（跟随全局）。
    var memoryMb by remember(instance.id) { mutableIntStateOf(initialInstanceMemoryMb(instance)) }
    var initial by remember(instance.id) { mutableStateOf(instance.initialMemoryMb.toString()) }
    // 窗口宽高留空表示跟随全局：0 不显示成字面 0，输入 0 也归一化成空。
    var width by remember(instance.id) { mutableStateOf(instanceLaunchNumberField(instance.width)) }
    var height by remember(
        instance.id,
    ) { mutableStateOf(instanceLaunchNumberField(instance.height)) }
    var renaming by remember { mutableStateOf(false) }
    var exportingMrpack by remember { mutableStateOf(false) }
    var editingIcon by remember(instance.id) { mutableStateOf(false) }
    var screenshotGallery by remember(instance.id) { mutableStateOf(false) }
    // 每次进入页面自动检查一次模组、资源包与光影更新；离开页面取消未完成的请求。
    val updateCenter =
        remember(instance.id) { InstanceUpdateCenter(actions.controller, instance.id) }
    val updateScope = rememberCoroutineScope()
    LaunchedEffect(instance.id) { updateCenter.checkAll(updateScope) }
    DisposableEffect(instance.id) {
        onDispose { updateCenter.cancelAll() }
    }
    InstanceDetailsOverlays(
        instance = instance,
        actions = actions,
        screenshotGallery = screenshotGallery,
        editingIcon = editingIcon,
        onDismissGallery = { screenshotGallery = false },
        onDismissIcon = { editingIcon = false },
    )
    LaunchedEffect(instance) {
        draft = mergeSyncedDraft(draft, syncedInstance, instance)
        syncedInstance = instance
    }
    var advancedValid by remember { mutableStateOf(true) }
    // 实例页的 Java 单选列表与设置页共用 JavaService 检测结果；只在「设置」页签打开时检测。
    var javaRuntimes by remember(instance.id) { mutableStateOf<List<JavaRuntime>>(emptyList()) }
    var javaDetecting by remember(instance.id) { mutableStateOf(false) }
    // Java 选择改为紧凑行 + 底部抽屉；选择后立即写入实例配置，启动时直接生效。
    var javaSheetOpen by remember(instance.id) { mutableStateOf(false) }
    var javaSaveError by remember(instance.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(tab, instance.id) {
        if (tab != 0) return@LaunchedEffect
        javaDetecting = true
        try {
            javaRuntimes =
                withContext(Dispatchers.IO) {
                    runCatching { actions.controller.java.detect() }.getOrDefault(emptyList())
                }
        } finally {
            javaDetecting = false
        }
    }
    val memoryPolicy = instanceMemoryPolicy(draft)
    // 只有手动分配会持久化显式值；继承全局/自动分配一律按「没有显式值」解析。
    val savedMemoryMb = savedInstanceMemoryMb(memoryPolicy, memoryMb)
    val widthValue = parseInstanceLaunchNumberInput(width)
    val heightValue = parseInstanceLaunchNumberInput(height)
    // 自动保存：设置页的任何改动立即写盘。名称单独在离开输入框时提交，避免每敲一个字就重命名目录。
    // 非法值一律不写盘：对应输入框失焦时回退到上一次有效值，并保留内联错误。
    val settingsScope = rememberCoroutineScope()
    val saver = remember(instance.id) { InstanceSettingsSaver(actions, settingsScope) }
    val instanceBusy = actions.controller.isInstanceBusy(instance.id)
    // 「原理图」页签只在实例已有 schematics 目录时出现；其它页签照旧。
    var schematicsFolder by remember(instance.id) { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(instance.id, state.jobs) {
        schematicsFolder =
            withContext(Dispatchers.IO) {
                actions.controller.instanceWorkspace
                    .existingFolders(instance.id)
                    .map { GameFolder.Schematics in it }
                    .getOrNull()
            }
    }
    // 名称是目录名，只有离开输入框时才提交；其余字段组成自动保存快照。
    val editSnapshot =
        draft.copy(
            name = instance.name,
            memoryMb = savedMemoryMb,
            initialMemoryMb = initial.toIntOrNull() ?: 0,
            width = widthValue ?: 0,
            height = heightValue ?: 0,
        )
    InstanceSettingsAutoSave(instance, actions, saver, editSnapshot, instanceBusy)
    // 离开「设置」页签时，把仍停留在名称输入框里的有效改名提交掉。
    LaunchedEffect(tab) {
        if (tab != 0) {
            saver.commitName(instance, { draft }, editSnapshot, instanceBusy) { draft = it }
        }
    }

    // ---------------- 页头 ----------------
    InstanceDetailsHeader(instance, actions, onBack) { editingIcon = true }
    // ---------------- 左侧页签 + 右侧内容 ----------------
    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        InstanceDetailsRail(
            instance = instance,
            actions = actions,
            selectedTab = tab,
            onSelectTab = { tab = it },
            jobStates = state.jobs.map { it.id to it.status },
            onExport = { exportingMrpack = true },
            onScreenshots = { screenshotGallery = true },
            onTidy = { onTidyInstance(instance) },
            onMove = { onMoveInstance(instance) },
            onDelete = { onDelete() },
            showSchematics = schematicsFolder != false,
        )
        InstanceTabContent(
            instance = instance,
            actions = actions,
            settings = state.data.settings,
            tab = tab,
            draft = draft,
            onDraftChange = { draft = it },
            saver = saver,
            editSnapshot = editSnapshot,
            instanceBusy = instanceBusy,
            memoryMb = memoryMb,
            onMemoryMbChange = { memoryMb = it },
            initialMemory = initial,
            onInitialMemoryChange = { initial = it },
            width = width,
            onWidthChange = { width = it },
            height = height,
            onHeightChange = { height = it },
            javaRuntimes = javaRuntimes,
            javaDetecting = javaDetecting,
            javaSaveError = javaSaveError,
            onOpenJavaPicker = {
                javaSaveError = null
                javaSheetOpen = true
            },
            advancedValid = advancedValid,
            onAdvancedValidChange = { advancedValid = it },
            onEditIcon = { editingIcon = true },
            onResetLaunchSettings = {
                memoryMb = 0
                initial = "0"
                width = ""
                height = ""
                draft =
                    applyInstanceMemoryPolicy(
                        draft,
                        InstanceMemoryPolicy.Inherit,
                        state.data.settings.memoryMb,
                    ).copy(
                        javaPath = "",
                        windowMode = "inherit",
                        extraJvmArgs = "",
                        extraGameArgs = "",
                    )
            },
            updateCenter = updateCenter,
            navigate = navigate,
        )
    }
    InstanceDetailsDialogs(
        instance = instance,
        actions = actions,
        renaming = renaming,
        exporting = exportingMrpack,
        onDismissRename = { renaming = false },
        onDismissExport = { exportingMrpack = false },
        onTaskQueued = { actions.taskQueued() },
    )
    if (javaSheetOpen) {
        InstanceJavaPicker(
            instance = instance,
            actions = actions,
            draft = draft,
            javaRuntimes = javaRuntimes,
            inheritedPath = state.data.settings.javaPath,
            detecting = javaDetecting,
            onDraftChange = { draft = it },
            onSaveError = { javaSaveError = it },
            onDismiss = { javaSheetOpen = false },
        )
    }
}

/** 页头：返回、图标、名称、状态标签与版本/路径摘要。 */
@Composable
private fun InstanceDetailsHeader(
    instance: GameInstance,
    actions: UiActions,
    onBack: () -> Unit,
    onEditIcon: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        // 返回目标由导航状态决定（实例列表或开始页的版本设置），所以标签保持中性。
        HeaderIconButton(Icons.Outlined.ArrowBack, "返回", onClick = onBack)
        Spacer(Modifier.width(10.dp))
        // 实例页头也显示图标：默认草方块，自定义图片或加载器图标会替换它。
        IconButton(
            { onEditIcon() },
            enabled = !actions.controller.isInstanceBusy(instance.id),
        ) {
            InstanceArtwork(instance, actions, Modifier.size(34.dp))
        }
        TextButton(
            { onEditIcon() },
            enabled = !actions.controller.isInstanceBusy(instance.id),
        ) {
            Text("更换图标", fontSize = 12.sp)
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    instance.name,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(8.dp))
                Tag(
                    when {
                        instance.broken -> "无法识别"
                        instance.installed -> "已安装"
                        else -> "需要修复"
                    },
                )
            }
            Text(
                "Minecraft " +
                    instance.gameVersion +
                    " · " +
                    instance.loader.title +
                    (if (instance.loaderVersion.isBlank()) "" else " " + instance.loaderVersion) +
                    " · " +
                    actions.controller.store.gamePath(instance),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 右侧内容区：切换页签时先建好对应目录，再渲染对应的页签内容。 */
@Composable
private fun RowScope.InstanceTabContent(
    instance: GameInstance,
    actions: UiActions,
    settings: LauncherSettings,
    tab: Int,
    draft: GameInstance,
    onDraftChange: (GameInstance) -> Unit,
    saver: InstanceSettingsSaver,
    editSnapshot: GameInstance,
    instanceBusy: Boolean,
    memoryMb: Int,
    onMemoryMbChange: (Int) -> Unit,
    initialMemory: String,
    onInitialMemoryChange: (String) -> Unit,
    width: String,
    onWidthChange: (String) -> Unit,
    height: String,
    onHeightChange: (String) -> Unit,
    javaRuntimes: List<JavaRuntime>,
    javaDetecting: Boolean,
    javaSaveError: String?,
    onOpenJavaPicker: () -> Unit,
    advancedValid: Boolean,
    onAdvancedValidChange: (Boolean) -> Unit,
    onEditIcon: () -> Unit,
    onResetLaunchSettings: () -> Unit,
    updateCenter: InstanceUpdateCenter,
    navigate: (Page) -> Unit,
) {
    Box(Modifier.weight(1f).fillMaxHeight()) {
        // 页签对应的内容目录缺失时先建成，再列目录；这样左栏「游戏文件夹」只会出现真正存在的目录。
        val tabFolder = instanceTabFolder(tab)
        var folderReady by remember(instance.id, tab) { mutableStateOf(tabFolder == null) }
        LaunchedEffect(instance.id, tab) {
            val folder = tabFolder ?: return@LaunchedEffect
            folderReady = false
            actions.controller.instanceWorkspace.ensureFolder(instance.id, folder)
            folderReady = true
        }
        val page: @Composable (Int) -> Unit = { index ->
            Column(Modifier.fillMaxSize()) {
                if (!folderReady) {
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                } else {
                    when (index) {
                        1 ->
                            InstanceMods(instance, actions, updateCenter, Modifier.weight(1f)) {
                                actions.run { actions.controller.selectInstance(instance.id) }
                                navigate(Page.Discover)
                            }
                        2 ->
                            InstancePackTab(
                                instance,
                                actions,
                                updateCenter,
                                PackFolder.ResourcePacks,
                                Modifier.weight(1f),
                            )
                        3 ->
                            InstancePackTab(
                                instance,
                                actions,
                                updateCenter,
                                PackFolder.ShaderPacks,
                                Modifier.weight(1f),
                            )
                        4 -> InstanceSavesTab(instance, actions, Modifier.weight(1f))
                        5 ->
                            InstanceFolderBrowser(
                                instance,
                                actions,
                                "schematics",
                                Modifier.weight(1f),
                                importExtensions =
                                    listOf(
                                        "litematic",
                                        "schem",
                                        "schematic",
                                        "nbt",
                                    ),
                                importLabel = "导入原理图",
                                allowDelete = true,
                            )
                        else ->
                            InstanceSettingsTab(
                                instance = instance,
                                actions = actions,
                                settings = settings,
                                draft = draft,
                                onDraftChange = onDraftChange,
                                onNameChange = {
                                    onDraftChange(draft.copy(name = it.take(80)))
                                    saver.nameNotice = null
                                },
                                onCommitName = {
                                    saver.commitName(
                                        instance,
                                        { draft },
                                        editSnapshot,
                                        instanceBusy,
                                    ) { onDraftChange(it) }
                                },
                                nameNotice = saver.nameNotice,
                                memoryMb = memoryMb,
                                onMemoryMbChange = onMemoryMbChange,
                                initialMemory = initialMemory,
                                onInitialMemoryChange = onInitialMemoryChange,
                                width = width,
                                onWidthChange = onWidthChange,
                                height = height,
                                onHeightChange = onHeightChange,
                                javaRuntimes = javaRuntimes,
                                javaDetecting = javaDetecting,
                                javaSaveError = javaSaveError,
                                onOpenJavaPicker = onOpenJavaPicker,
                                saveError = saver.saveError,
                                advancedValid = advancedValid,
                                onAdvancedValidChange = onAdvancedValidChange,
                                onEditIcon = onEditIcon,
                                onResetLaunchSettings = onResetLaunchSettings,
                                modifier = Modifier.weight(1f).fillMaxHeight(),
                            )
                    }
                }
            }
        }
        if (LocalAnimationsEnabled.current) {
            Crossfade(tab, animationSpec = tween(150), label = "instance-tab") { index ->
                page(index)
            }
        } else {
            page(tab)
        }
    }
}

/** 截图浏览与图标编辑两个覆盖层；两个开关各自独立。 */
@Composable
private fun InstanceDetailsOverlays(
    instance: GameInstance,
    actions: UiActions,
    screenshotGallery: Boolean,
    editingIcon: Boolean,
    onDismissGallery: () -> Unit,
    onDismissIcon: () -> Unit,
) {
    if (screenshotGallery) ScreenshotGallery(instance, actions, onDismissGallery)
    if (editingIcon) InstanceIconDialog(instance, actions, onDismissIcon)
}

/** 重命名与整合包导出对话框；对话框内部状态自持，关闭时只回调外部。 */
@Composable
private fun InstanceDetailsDialogs(
    instance: GameInstance,
    actions: UiActions,
    renaming: Boolean,
    exporting: Boolean,
    onDismissRename: () -> Unit,
    onDismissExport: () -> Unit,
    onTaskQueued: () -> Unit,
) {
    if (renaming) InstanceRenameDialog(instance, actions, onDismissRename)
    if (exporting) ExportMrpackDialog(instance, actions, onDismissExport, onTaskQueued)
}

/** Java 选择抽屉：选中后立即写入实例配置，失败原因内联显示。 */
@Composable
private fun InstanceJavaPicker(
    instance: GameInstance,
    actions: UiActions,
    draft: GameInstance,
    javaRuntimes: List<JavaRuntime>,
    inheritedPath: String,
    detecting: Boolean,
    onDraftChange: (GameInstance) -> Unit,
    onSaveError: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    JavaPickerSheet(
        value = draft.javaPath,
        runtimes = javaRuntimes,
        inheritedPath = inheritedPath,
        ignoreCase = actions.controller.platform.osName == "windows",
        detecting = detecting,
        onSelect = { path ->
            onDraftChange(draft.copy(javaPath = path))
            // 立即写入实例配置：启动读取的是已保存值，无需重启启动器。
            onSaveError(
                runCatching {
                    if (!actions.controller.isInstanceBusy(instance.id)) {
                        actions.controller.saveInstanceConfiguration(instance.copy(javaPath = path))
                    }
                }.exceptionOrNull()
                    ?.message,
            )
        },
        onDismiss = onDismiss,
    )
}

/** 外部实例变化时只同步未被本地编辑过的字段，避免覆盖用户正在输入的值。 */
private fun mergeSyncedDraft(
    draft: GameInstance,
    synced: GameInstance,
    instance: GameInstance,
): GameInstance =
    draft.copy(
        name = if (draft.name == synced.name) instance.name else draft.name,
        isolation =
            if (draft.isolation == synced.isolation) {
                instance.isolation
            } else {
                draft.isolation
            },
        iconStyle = instance.iconStyle,
        iconId = instance.iconId,
        packIconId = instance.packIconId,
    )

/** 只有手动分配会持久化显式值；继承全局/自动分配一律按「没有显式值」解析。 */
private fun savedInstanceMemoryMb(
    policy: InstanceMemoryPolicy,
    memoryMb: Int,
): Int = if (policy == InstanceMemoryPolicy.Manual) memoryMb else 0

/** 进入页面时的内存滑块初始值：手动分配用实例值，其它策略显示 0（跟随全局）。 */
private fun initialInstanceMemoryMb(instance: GameInstance): Int =
    if (instanceMemoryPolicy(instance) == InstanceMemoryPolicy.Manual) instance.memoryMb else 0

/** 页签对应的内容目录；游戏设置不创建目录，返回 null。 */
private fun instanceTabFolder(index: Int): GameFolder? =
    when (index) {
        1 -> GameFolder.Mods
        2 -> GameFolder.ResourcePacks
        TAB_INDEX_SHADER_PACKS -> GameFolder.ShaderPacks
        TAB_INDEX_SAVES -> GameFolder.Saves
        TAB_INDEX_SCHEMATICS -> GameFolder.Schematics
        else -> null
    }
