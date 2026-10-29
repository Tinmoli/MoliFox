// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责实例设置、组件、模组、存档和原理图页面。

package dev.molifox.desktop

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.*
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.awtTransferable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.molifox.core.*
import java.awt.datatransfer.DataFlavor
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.*
import kotlinx.coroutines.*
import kotlinx.coroutines.swing.Swing
import org.jetbrains.skia.Image as SkiaImage

/** 实例详情页签：左侧二级列表的 7 个页签。 */
private data class InstanceTab(val title: String, val icon: ImageVector)

private val instanceTabs =
    listOf(
        InstanceTab("设置", Icons.Outlined.Tune),
        InstanceTab("模组", Icons.Outlined.Extension),
        InstanceTab("资源包", Icons.Outlined.Palette),
        InstanceTab("光影", Icons.Outlined.AutoAwesome),
        InstanceTab("存档", Icons.Outlined.Save),
        InstanceTab("数据包", Icons.Outlined.FolderZip),
        InstanceTab("原理图", Icons.Outlined.Architecture),
    )

/**
 * 本次进入实例详情页时的更新检查结果：模组一份，资源包与光影一份（同一次检查）。
 *
 * 所有页签共用同一份结果，切换页签不会重复检查；离开页面时 [cancelAll] 取消还没结束的请求。
 * 打开页面时自动检查一次；左栏「检查更新」[checkAll] 会同时重查模组、资源包与光影三部分。
 */
private class InstanceUpdateCenter(
    private val controller: LauncherController,
    private val instanceId: String,
) {
    var mods by mutableStateOf<ModUpdateReport?>(null)
    var modsChecking by mutableStateOf(false)
    var modsError by mutableStateOf<String?>(null)
    var packs by mutableStateOf<PackUpdateReport?>(null)
    var packsChecking by mutableStateOf(false)
    var packsError by mutableStateOf<String?>(null)
    private var modsJob: Job? = null
    private var packsJob: Job? = null

    /** 是否有任一检查在进行中：左栏进度与按钮状态都看这个值。 */
    val checking: Boolean
        get() = modsChecking || packsChecking

    /** 打开页面时自动检查一次：模组与资源包/光影并行，互不阻塞；[refresh] 为 true 时强制重查三部分。 */
    fun checkAll(scope: CoroutineScope, refresh: Boolean = false) {
        if (refresh || mods == null) checkMods(scope)
        if (refresh || packs == null) checkPacks(scope)
    }

    fun checkMods(scope: CoroutineScope) {
        modsJob?.cancel()
        modsJob = scope.launch {
            modsChecking = true
            modsError = null
            try {
                mods =
                    withContext(Dispatchers.IO) {
                        controller.content.modUpdates.check(instanceId)
                    }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                modsError = failure.message ?: "检查模组更新失败"
            } finally {
                if (modsJob == coroutineContext[Job]) modsChecking = false
            }
        }
    }

    fun checkPacks(scope: CoroutineScope) {
        packsJob?.cancel()
        packsJob = scope.launch {
            packsChecking = true
            packsError = null
            try {
                packs =
                    withContext(Dispatchers.IO) {
                        controller.content.packUpdates.check(instanceId)
                    }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                packsError = failure.message ?: "检查资源包与光影更新失败"
            } finally {
                if (packsJob == coroutineContext[Job]) packsChecking = false
            }
        }
    }

    fun cancelMods() {
        modsJob?.cancel()
        modsJob = null
        modsChecking = false
    }

    fun cancelPacks() {
        packsJob?.cancel()
        packsJob = null
        packsChecking = false
    }

    fun cancelAll() {
        cancelMods()
        cancelPacks()
    }
}

/** 实例详情：页头 + 左侧页签栏与右侧内容区，页签为设置 / 模组 / 资源包 / 光影 / 存档 / 数据包 / 原理图。 */
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
    var memoryMb by remember(instance.id) {
        mutableIntStateOf(
            if (instanceMemoryPolicy(instance) == InstanceMemoryPolicy.Manual) instance.memoryMb
            else 0
        )
    }
    var initial by remember(instance.id) { mutableStateOf(instance.initialMemoryMb.toString()) }
    // 窗口宽高留空表示跟随全局：0 不显示成字面 0，输入 0 也归一化成空。
    var width by remember(instance.id) { mutableStateOf(instanceLaunchNumberField(instance.width)) }
    var height by remember(instance.id) { mutableStateOf(instanceLaunchNumberField(instance.height)) }
    var foldersOpen by remember { mutableStateOf(false) }
    // 「管理」每次进入页面都从收起开始，不持久化展开状态。
    var manageOpen by remember(instance.id) { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var exportingMrpack by remember { mutableStateOf(false) }
    var editingIcon by remember(instance.id) { mutableStateOf(false) }
    var screenshotGallery by remember(instance.id) { mutableStateOf(false) }
    // 每次进入页面自动检查一次模组、资源包与光影更新；离开页面取消未完成的请求。
    val updateCenter = remember(instance.id) { InstanceUpdateCenter(actions.controller, instance.id) }
    val updateScope = rememberCoroutineScope()
    LaunchedEffect(instance.id) { updateCenter.checkAll(updateScope) }
    DisposableEffect(instance.id) {
        onDispose { updateCenter.cancelAll() }
    }
    if (screenshotGallery) ScreenshotGallery(instance, actions) { screenshotGallery = false }
    if (editingIcon) InstanceIconDialog(instance, actions) { editingIcon = false }
    LaunchedEffect(instance) {
        draft =
            draft.copy(
                name = if (draft.name == syncedInstance.name) instance.name else draft.name,
                isolation =
                    if (draft.isolation == syncedInstance.isolation) instance.isolation
                    else draft.isolation,
                iconStyle = instance.iconStyle,
                iconId = instance.iconId,
                packIconId = instance.packIconId,
            )
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
    val memoryLimit = instanceMemoryLimitMb(actions.controller.platform.totalMemoryMb)
    // 只有手动分配会持久化显式值；继承全局/自动分配一律按「没有显式值」解析。
    val savedMemoryMb = if (memoryPolicy == InstanceMemoryPolicy.Manual) memoryMb else 0
    val memoryAllocation =
        effectiveGameMemory(
            state.data.settings,
            draft.copy(memoryMb = savedMemoryMb),
            actions.controller.platform.totalMemoryMb,
            actions.controller.platform.availableMemoryMb,
            actions.controller.platform.architecture,
        )
    val effectiveMemory = memoryAllocation.maximumMb
    val widthValue = parseInstanceLaunchNumberInput(width)
    val heightValue = parseInstanceLaunchNumberInput(height)
    // 自动保存：设置页的任何改动立即写盘。名称单独在离开输入框时提交，避免每敲一个字就重命名目录。
    // 非法值一律不写盘：对应输入框失焦时回退到上一次有效值，并保留内联错误。
    var saveError by remember(instance.id) { mutableStateOf<String?>(null) }
    var nameNotice by remember(instance.id) { mutableStateOf<String?>(null) }
    // 名称提交（可能移动目录）进行中：期间不发起自动保存，避免把旧名称写回记录。
    var nameCommitting by remember(instance.id) { mutableStateOf(false) }
    val settingsScope = rememberCoroutineScope()
    val instanceBusy = actions.controller.isInstanceBusy(instance.id)
    // 只有真的改名才会移动目录，因此只在名称变化时校验目录名；旧显示名里的特殊字符不影响其它设置。
    val nameError =
        if (draft.name.trim() != instance.name)
            runCatching { validateInstanceDirectoryName(draft.name.trim()) }
                .exceptionOrNull()
                ?.message
        else null
    // 实例启动设置与启动路径共用同一套校验；非法值给出内联错误且不会被写入。
    val launchError: String? =
        runCatching {
                validateInstanceLaunchOptions(
                    memoryMb = savedMemoryMb,
                    width = widthValue ?: -1,
                    height = heightValue ?: -1,
                    windowMode = draft.windowMode,
                    extraJvmArgs = draft.extraJvmArgs,
                    extraGameArgs = draft.extraGameArgs,
                    totalMemoryMb = actions.controller.platform.totalMemoryMb,
                )
            }
            .exceptionOrNull()
            ?.message
    // 单项校验：失焦时只回退自己这一格，不误伤其它已经改好的字段。
    fun invalidWindowField(value: Int?): Boolean =
        value == null ||
            (value != 0 && value !in MIN_GAME_WINDOW_SIZE..MAX_GAME_WINDOW_SIZE)
    fun extraArgumentError(jvm: String, game: String): String? =
        runCatching {
                validateInstanceLaunchOptions(
                    memoryMb = 0,
                    width = 0,
                    height = 0,
                    windowMode = "inherit",
                    extraJvmArgs = jvm,
                    extraGameArgs = game,
                )
            }
            .exceptionOrNull()
            ?.message
    // 名称是目录名，只有离开输入框时才提交；其余字段组成自动保存快照。
    val editSnapshot =
        draft.copy(
            name = instance.name,
            memoryMb = savedMemoryMb,
            initialMemoryMb = initial.toIntOrNull() ?: 0,
            width = widthValue ?: 0,
            height = heightValue ?: 0,
        )
    LaunchedEffect(instance.id, editSnapshot, instanceBusy, nameCommitting) {
        if (instanceBusy || nameCommitting || editSnapshot == instance) return@LaunchedEffect
        // 连续输入合并成一次写盘；核心会再次校验，非法值不会落盘。
        delay(300)
        if (nameCommitting) return@LaunchedEffect
        val failure =
            runCatching {
                    withContext(Dispatchers.IO) {
                        // 名称以记录当前值为准：重命名进行中也不会把旧名称写回去。
                        val currentName = actions.controller.store.instance(instance.id).name
                        actions.controller.saveInstanceConfiguration(
                            editSnapshot.copy(name = currentName),
                            notify = false,
                        )
                    }
                }
                .exceptionOrNull()
                ?.message
        saveError = failure
    }
    // 离开页面时补写还没到防抖时间的有效改动；非法值仍由核心拒绝，不会落盘。
    val latestEdit by rememberUpdatedState(editSnapshot)
    val latestInstance by rememberUpdatedState(instance)
    DisposableEffect(instance.id) {
        onDispose {
            val snapshot = latestEdit
            if (!actions.controller.isInstanceBusy(snapshot.id) && snapshot != latestInstance) {
                actions.controller.scope.launch {
                    runCatching {
                        val currentName = actions.controller.store.instance(snapshot.id).name
                        actions.controller.saveInstanceConfiguration(
                            snapshot.copy(name = currentName),
                            notify = false,
                        )
                    }
                }
            }
        }
    }
    // 提交名称：只有真的变化才调用 renameInstance，失败或非法时回退到目录当前名称。
    fun commitName() {
        if (nameCommitting) return
        val trimmed = draft.name.trim()
        if (trimmed == instance.name) {
            if (draft.name != trimmed) draft = draft.copy(name = trimmed)
            nameNotice = null
            return
        }
        val problem =
            runCatching { validateInstanceDirectoryName(trimmed) }.exceptionOrNull()?.message
        if (problem != null || instanceBusy) {
            draft = draft.copy(name = instance.name)
            nameNotice =
                if (problem != null) "名称无效，已恢复为「${instance.name}」：$problem"
                else "请等待当前任务结束后再重命名。"
            return
        }
        val candidate = editSnapshot.copy(name = trimmed)
        nameCommitting = true
        settingsScope.launch {
            var renamed = false
            val failure =
                runCatching {
                        withContext(Dispatchers.IO) {
                            actions.controller.renameInstance(instance.id, trimmed)
                            renamed = true
                            actions.controller.saveInstanceConfiguration(candidate, notify = false)
                        }
                    }
                    .exceptionOrNull()
                    ?.message
            withContext(Dispatchers.Swing) {
                nameCommitting = false
                if (failure == null) {
                    nameNotice = null
                    saveError = null
                } else {
                    nameNotice =
                        if (renamed) "名称已改为「$trimmed」，但其它设置未能保存：$failure"
                        else "重命名失败，已恢复为「${instance.name}」：$failure"
                    if (!renamed) draft = draft.copy(name = instance.name)
                    saveError = null
                }
            }
        }
    }
    // 离开「设置」页签时，把仍停留在名称输入框里的有效改名提交掉。
    LaunchedEffect(tab) {
        if (tab != 0) commitName()
    }
    // 实例工具的可用性：共用游戏目录、外部目录由原目录维护，不提供这些入口。
    val isolated = actions.controller.store.isolated(instance)
    val canMaintainInstance = isolated && !instanceBusy
    // 跨游戏目录移动：外部目录由原启动器维护，共用游戏目录没有独立目录可搬。
    val canMoveInstance = isolated && !instance.externalProfile && !instanceBusy
    val moveBlockedNote =
        when {
            instance.externalProfile -> "外部游戏目录的实例由原启动器维护，不能移动"
            !isolated -> "共用游戏目录没有独立目录，请先把此实例设为「版本隔离」"
            instanceBusy -> "请等待当前任务结束"
            else -> ""
        }
    // ---------------- 页头 ----------------
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        // 返回目标由导航状态决定（实例列表或开始页的版本设置），所以标签保持中性。
        HeaderIconButton(Icons.Outlined.ArrowBack, "返回", onClick = onBack)
        Spacer(Modifier.width(10.dp))
        // 实例页头也显示图标：默认草方块，自定义图片或加载器图标会替换它。
        IconButton(
            { editingIcon = true },
            enabled = !actions.controller.isInstanceBusy(instance.id),
        ) {
            InstanceArtwork(instance, actions, Modifier.size(34.dp))
        }
        TextButton(
            { editingIcon = true },
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
                    }
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
    // ---------------- 左侧页签 + 右侧内容 ----------------
    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        // 左侧页签整栏套磨砂面板：宽度统一 158dp，条目 32dp 高、间距 4dp；内容超出时整栏滚动。
        FrostedPanel(Modifier.width(158.dp).fillMaxHeight()) {
            ScrollColumn(Modifier.fillMaxSize(), gap = 4.dp) {
                instanceTabs.forEachIndexed { index, item ->
                    InstanceRailItem(item.title, item.icon, tab == index) { tab = index }
                }
                Spacer(Modifier.height(2.dp))
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .6f)
                )
                Spacer(Modifier.height(2.dp))
                InstanceRailItem(
                    "游戏文件夹",
                    Icons.Outlined.FolderOpen,
                    foldersOpen,
                    onClick = { foldersOpen = !foldersOpen },
                )
                if (foldersOpen)
                    listOf(
                            "实例文件夹" to "",
                            "模组文件夹" to "mods",
                            "资源包文件夹" to "resourcepacks",
                            "光影包文件夹" to "shaderpacks",
                            "存档文件夹" to "saves",
                            "原理图文件夹" to "schematics",
                            "配置文件夹" to "config",
                            "日志文件夹" to "logs",
                            "崩溃报告文件夹" to "crash-reports",
                            "截图文件夹" to "screenshots",
                        )
                        .filter { (_, subfolder) ->
                            runCatching {
                                    java.nio.file.Files.readAttributes(
                                            actions.controller.store
                                                .instancePath(instance.id)
                                                .resolve(subfolder),
                                            java.nio.file.attribute.BasicFileAttributes::class.java,
                                        )
                                        .isDirectory
                                }
                                .getOrDefault(false)
                        }
                        .forEach { (title, subfolder) ->
                            RailSubEntry(title) {
                                actions.run {
                                    if (subfolder.isBlank())
                                        actions.controller.openFolder(instance.id)
                                    else actions.controller.openFolder(instance.id, subfolder)
                                }
                            }
                        }
                Spacer(Modifier.height(2.dp))
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .6f)
                )
                Spacer(Modifier.height(2.dp))
                // 「管理」默认收起：标题行可点、可聚焦，箭头随展开状态旋转；展开后每项仍是一行直接执行。
                InstanceRailSectionHeader("管理", manageOpen) { manageOpen = !manageOpen }
                if (manageOpen) {
                    InstanceRailAction(
                        "查看启动参数",
                        Icons.Outlined.Terminal,
                        enabled =
                            instance.installed && !actions.controller.isInstanceBusy(instance.id),
                    ) { actions.previewLaunch(instance.id) }
                    InstanceRailAction("打开游戏目录", Icons.Outlined.FolderOpen) {
                        actions.run { actions.controller.openFolder(instance.id) }
                    }
                    InstanceRailAction(
                        "导出整合包",
                        Icons.Outlined.Archive,
                        enabled = !actions.controller.isInstanceBusy(instance.id),
                    ) { exportingMrpack = true }
                    InstanceRailAction("游戏截图", Icons.Outlined.PhotoLibrary) {
                        screenshotGallery = true
                    }
                    InstanceRailAction(
                        "整理实例目录",
                        Icons.Outlined.CleaningServices,
                        enabled = canMaintainInstance,
                    ) { onTidyInstance(instance) }
                    
                    InstanceRailAction(
                        "删除实例",
                        Icons.Outlined.DeleteOutline,
                        danger = true,
                    ) { onDelete() }
                }
            }
        }
        Box(Modifier.weight(1f).fillMaxHeight()) {
            val page: @Composable (Int) -> Unit = { index ->
                Column(Modifier.fillMaxSize()) {
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
                        5 -> InstanceDatapacksTab(instance, actions, Modifier.weight(1f))
                        6 ->
                            InstanceFolderBrowser(
                                instance,
                                actions,
                                "schematics",
                                Modifier.weight(1f),
                                importExtensions = listOf("litematic", "schem", "schematic", "nbt"),
                                importLabel = "导入原理图",
                                allowDelete = true,
                            )
                        else ->
                            ScrollColumn(Modifier.weight(1f).fillMaxHeight()) {
                                // 名称与图标放在设置页最上方：图标在左、名称在右，与其余设置卡同款排版。
                                CompactSettingsSection(
                                    "名称与图标",
                                    "名称离开输入框时生效并重命名实例目录；图标默认跟随原版或加载器，整合包自带图标优先。",
                                ) {
                                    Row(
                                        Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                                    ) {
                                        InstanceArtwork(instance, actions, Modifier.size(40.dp))
                                        Column(
                                            Modifier.weight(1f),
                                            verticalArrangement = Arrangement.spacedBy(6.dp),
                                        ) {
                                            CompactTextField(
                                                draft.name,
                                                {
                                                    draft = draft.copy(name = it.take(80))
                                                    nameNotice = null
                                                },
                                                Modifier.fillMaxWidth(),
                                                label = "实例名称（离开输入框时同时重命名目录）",
                                                onFocusChange = { focused ->
                                                    if (!focused) commitName()
                                                },
                                            )
                                            val nameHint = nameError ?: nameNotice
                                            if (nameHint != null)
                                                Text(
                                                    nameHint,
                                                    fontSize = 11.sp,
                                                    color = MaterialTheme.colorScheme.error,
                                                )
                                            SecondaryButton(
                                                { editingIcon = true },
                                                enabled = !instanceBusy,
                                            ) {
                                                Text("更换实例图标…", fontSize = 12.sp)
                                            }
                                        }
                                    }
                                }
                                CompactSettingsSection("Java 与内存", "实例可单独指定 Java；内存数值与窗口在下方「启动设置」。") {
                                    // 紧凑行：继承时右侧显示全局实际解析到的运行时，点击打开抽屉。
                                    JavaPickerRow(
                                        value = draft.javaPath,
                                        runtimes = javaRuntimes,
                                        inheritedPath = state.data.settings.javaPath,
                                        ignoreCase = actions.controller.platform.osName == "windows",
                                        detecting = javaDetecting,
                                        onClick = {
                                            javaSaveError = null
                                            javaSheetOpen = true
                                        },
                                    )
                                    javaSaveError?.let {
                                        Text(
                                            it,
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.error,
                                        )
                                    }
                                    SimpleDropdown(
                                        when (memoryPolicy) {
                                            InstanceMemoryPolicy.Inherit -> "inherit"
                                            InstanceMemoryPolicy.Automatic -> "automatic"
                                            InstanceMemoryPolicy.Manual -> "manual"
                                        },
                                        listOf(
                                            "inherit" to "继承全局内存策略",
                                            "automatic" to "自动分配",
                                            "manual" to "手动分配",
                                        ),
                                        { mode ->
                                            val next =
                                                applyInstanceMemoryPolicy(
                                                    draft.copy(
                                                        memoryMb =
                                                            if (
                                                                memoryPolicy ==
                                                                    InstanceMemoryPolicy.Manual
                                                            )
                                                                memoryMb
                                                            else 0
                                                    ),
                                                    when (mode) {
                                                        "automatic" -> InstanceMemoryPolicy.Automatic
                                                        "manual" -> InstanceMemoryPolicy.Manual
                                                        else -> InstanceMemoryPolicy.Inherit
                                                    },
                                                    state.data.settings.memoryMb,
                                                )
                                            draft = next
                                            memoryMb = next.memoryMb
                                        },
                                    )
                                    if (memoryPolicy == InstanceMemoryPolicy.Automatic)
                                        MutedText("启动时根据系统可用内存分配；切回手动分配时按全局最大值重新设置。")
                                }
                                CompactSettingsSection(
                                    "启动设置",
                                    "最大内存只在「手动分配」时可调；窗口宽高留空表示跟随全局，参数留空表示不追加。",
                                ) {
                                    val fullscreenNow =
                                        when (draft.windowMode) {
                                            "fullscreen" -> true
                                            "windowed" -> false
                                            else ->
                                                when (state.data.settings.gameWindowMode) {
                                                    "fullscreen" -> true
                                                    "launcher" -> false
                                                    else -> state.data.settings.fullscreen
                                                }
                                        }
                                    val memoryLabel =
                                        when (memoryPolicy) {
                                            InstanceMemoryPolicy.Inherit ->
                                                "跟随全局（当前 $effectiveMemory MB）"
                                            InstanceMemoryPolicy.Automatic ->
                                                "自动分配（当前 $effectiveMemory MB）"
                                            InstanceMemoryPolicy.Manual ->
                                                "$memoryMb MB（%.1f GB）".format(memoryMb / 1024f)
                                        }
                                    val memoryHint =
                                        when (memoryPolicy) {
                                            InstanceMemoryPolicy.Inherit ->
                                                "当前继承全局内存策略；切换到「手动分配」后才能调整。"
                                            InstanceMemoryPolicy.Automatic ->
                                                "启动时根据系统可用内存分配；切回手动分配时按全局最大值重新设置。"
                                            InstanceMemoryPolicy.Manual ->
                                                "拖动滑块按 256 MB 步进；初始内存会自动收窄到最大值以内。"
                                        }
                                    // 非手动策略滑块只显示解析后的有效值，不接受拖动。
                                    val sliderValue =
                                        if (memoryPolicy == InstanceMemoryPolicy.Manual)
                                            memoryMb.coerceIn(
                                                    MIN_INSTANCE_MEMORY_MB,
                                                    memoryLimit,
                                                )
                                                .toFloat()
                                        else
                                            effectiveMemory
                                                .coerceIn(MIN_INSTANCE_MEMORY_MB, memoryLimit)
                                                .toFloat()
                                    Row(
                                        Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    ) {
                                        CompactSlider(
                                            sliderValue,
                                            { raw ->
                                                val next =
                                                    instanceMemorySliderValue(
                                                        raw,
                                                        actions.controller.platform.totalMemoryMb,
                                                    )
                                                memoryMb = next
                                                if ((initial.toIntOrNull() ?: 0) > next)
                                                    initial = next.toString()
                                            },
                                            Modifier.weight(1f).semantics {
                                                contentDescription = "实例最大内存滑块"
                                            },
                                            valueRange =
                                                MIN_INSTANCE_MEMORY_MB.toFloat()..
                                                    memoryLimit.toFloat(),
                                            enabled = memoryPolicy == InstanceMemoryPolicy.Manual,
                                        )
                                        Tag(memoryLabel)
                                    }
                                    MutedText(memoryHint)
                                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        NumberField(
                                            value = width,
                                            label = "窗口宽度（px）",
                                            onChange = {
                                                width = normalizeInstanceLaunchNumberInput(it)
                                            },
                                            modifier = Modifier.weight(1f),
                                            placeholder = "—",
                                            hint =
                                                "留空表示跟随全局（当前 ${state.data.settings.width} px）",
                                            onFocusChange = { focused ->
                                                if (!focused && invalidWindowField(widthValue))
                                                    width = instanceLaunchNumberField(instance.width)
                                            },
                                        )
                                        NumberField(
                                            value = height,
                                            label = "窗口高度（px）",
                                            onChange = {
                                                height = normalizeInstanceLaunchNumberInput(it)
                                            },
                                            modifier = Modifier.weight(1f),
                                            placeholder = "—",
                                            hint =
                                                "留空表示跟随全局（当前 ${state.data.settings.height} px）",
                                            onFocusChange = { focused ->
                                                if (!focused && invalidWindowField(heightValue))
                                                    height = instanceLaunchNumberField(instance.height)
                                            },
                                        )
                                    }
                                    SettingRow("全屏", "关闭时按窗口尺寸显示") {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            if (draft.windowMode != "inherit")
                                                TextButton({
                                                    draft = draft.copy(windowMode = "inherit")
                                                }) {
                                                    Text("跟随全局", fontSize = 11.sp)
                                                }
                                            CompactSwitch(
                                                checked = fullscreenNow,
                                                onCheckedChange = { checked ->
                                                    draft =
                                                        draft.copy(
                                                            windowMode =
                                                                if (checked) "fullscreen"
                                                                else "windowed"
                                                        )
                                                },
                                            )
                                        }
                                    }
                                    OutlinedTextField(
                                        draft.extraJvmArgs,
                                        { draft = draft.copy(extraJvmArgs = it.take(4096)) },
                                        Modifier.fillMaxWidth().onFocusChanged { focus ->
                                            // 非法 JVM 参数不写盘；离开输入框时回退到上一次有效值。
                                            if (
                                                !focus.isFocused &&
                                                    extraArgumentError(draft.extraJvmArgs, "") !=
                                                        null
                                            )
                                                draft =
                                                    draft.copy(
                                                        extraJvmArgs = instance.extraJvmArgs
                                                    )
                                        },
                                        label = { Text("额外 JVM 参数（留空跟随全局）") },
                                        placeholder = {
                                            Text(
                                                "-XX:+UseG1GC",
                                                fontFamily = FontFamily.Monospace,
                                                fontSize = 12.sp,
                                            )
                                        },
                                        textStyle =
                                            MaterialTheme.typography.bodyMedium.copy(
                                                fontFamily = FontFamily.Monospace,
                                                fontSize = 12.sp,
                                            ),
                                        minLines = 2,
                                        maxLines = 4,
                                    )
                                    OutlinedTextField(
                                        draft.extraGameArgs,
                                        { draft = draft.copy(extraGameArgs = it.take(4096)) },
                                        Modifier.fillMaxWidth().onFocusChanged { focus ->
                                            // 非法游戏参数不写盘；离开输入框时回退到上一次有效值。
                                            if (
                                                !focus.isFocused &&
                                                    extraArgumentError("", draft.extraGameArgs) !=
                                                        null
                                            )
                                                draft =
                                                    draft.copy(
                                                        extraGameArgs = instance.extraGameArgs
                                                    )
                                        },
                                        label = { Text("额外游戏参数（追加在标准参数之后）") },
                                        placeholder = {
                                            Text(
                                                "--skipMultiplayerWarning",
                                                fontFamily = FontFamily.Monospace,
                                                fontSize = 12.sp,
                                            )
                                        },
                                        textStyle =
                                            MaterialTheme.typography.bodyMedium.copy(
                                                fontFamily = FontFamily.Monospace,
                                                fontSize = 12.sp,
                                            ),
                                        minLines = 2,
                                        maxLines = 4,
                                    )
                                    launchError?.let {
                                        Text(
                                            it,
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.error,
                                        )
                                    }
                                }
                                CompactSettingsSection("基本信息") {
                                    OutlinedTextField(
                                        draft.notes,
                                        { draft = draft.copy(notes = it.take(2000)) },
                                        Modifier.fillMaxWidth(),
                                        label = { Text("版本备注") },
                                        minLines = 2,
                                        maxLines = 4,
                                    )
                                }
                                CompactSettingsSection("目录与隔离", "目录名就是实例名；名称离开输入框后自动生效。") {
                                    val isolationSource =
                                        actions.controller.store.isolationSource(draft)
                                    val isolationEvidence =
                                        actions.controller.store.isolationEvidence(draft)
                                    SettingRow(
                                        "版本隔离",
                                        when (isolationSource) {
                                            IsolationSource.Inferred ->
                                                actions.controller.store.isolationTitle(draft) +
                                                    "：目录内有 " +
                                                    (isolationEvidence ?: "游戏数据") +
                                                    "，按独立目录处理；选择下拉项可覆盖"
                                            IsolationSource.Explicit ->
                                                when (draft.isolation) {
                                                    VersionIsolation.Enabled ->
                                                        "使用实例自己的目录，存档、Mod 与其他实例互不影响"
                                                    VersionIsolation.Disabled ->
                                                        "与其他关闭隔离的实例共用游戏根目录"
                                                    VersionIsolation.Inherit -> ""
                                                }
                                            IsolationSource.GlobalDefault ->
                                                "跟随全局设置（当前为" +
                                                    (if (state.data.settings.isolateVersions) "版本隔离"
                                                    else "共用游戏目录") +
                                                    "）"
                                        },
                                    ) {
                                        Box(Modifier.width(190.dp)) {
                                            SimpleDropdown(
                                                draft.isolation.name,
                                                VersionIsolation.entries.map {
                                                    it.name to
                                                        if (
                                                            it == VersionIsolation.Inherit &&
                                                                isolationSource ==
                                                                    IsolationSource.Inferred
                                                        )
                                                            "继承全局设置（自动推断为版本隔离）"
                                                        else it.title
                                                },
                                                { key ->
                                                    draft =
                                                        draft.copy(
                                                            isolation = VersionIsolation.valueOf(key)
                                                        )
                                                },
                                            )
                                        }
                                    }
                                    SettingRow(
                                        "实际游戏目录",
                                        runCatching {
                                                actions.controller.store
                                                    .gamePath(draft)
                                                    .toString()
                                            }
                                            .getOrDefault("目录暂不可用"),
                                    ) {}
                                }
                                CompactSettingsSection("版本信息") {
                                    SettingRow("Minecraft", instance.gameVersion) {
                                        Tag(instance.loader.title)
                                    }
                                    SettingRow("包来源", packSourceOf(instance)) {
                                        Text(instance.versionId, fontSize = 12.sp)
                                    }
                                    Text(
                                        "创建于 " +
                                            instance.createdAt.take(10) +
                                            " · 游玩 " +
                                            instance.playTimeSeconds / 60 +
                                            " 分钟",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    SecondaryButton({
                                        actions.run { actions.controller.openFolder(instance.id) }
                                    }) {
                                        Icon(
                                            Icons.Outlined.FolderOpen,
                                            null,
                                            Modifier.size(15.dp),
                                        )
                                        Spacer(Modifier.width(5.dp))
                                        Text("打开游戏目录", fontSize = 12.sp)
                                    }
                                }
                                if (state.data.settings.pages.showAdvancedLaunchOptions)
                                    SettingsSection(
                                        "高级启动参数",
                                        "留空继承全局 JVM 参数；填写后覆盖全局附加参数。",
                                    ) {
                                        OutlinedTextField(
                                            draft.extraJvmArgs,
                                            { draft = draft.copy(extraJvmArgs = it) },
                                            Modifier.fillMaxWidth().onFocusChanged { focus ->
                                                if (
                                                    !focus.isFocused &&
                                                        extraArgumentError(
                                                            draft.extraJvmArgs,
                                                            "",
                                                        ) != null
                                                )
                                                    draft =
                                                        draft.copy(
                                                            extraJvmArgs = instance.extraJvmArgs
                                                        )
                                            },
                                            label = { Text("此版本的附加 JVM 参数") },
                                            minLines = 1,
                                            maxLines = 4,
                                        )
                                        SettingRow("使用独立高级启动设置") {
                                            CompactSwitch(
                                                draft.advancedLaunch != null,
                                                { enabled ->
                                                    draft =
                                                        draft.copy(
                                                            advancedLaunch =
                                                                if (enabled)
                                                                    state.data.settings
                                                                        .advancedLaunch
                                                                        .copy()
                                                                else null
                                                        )
                                                    advancedValid = true
                                                },
                                            )
                                        }
                                        draft.advancedLaunch?.let { value ->
                                            AdvancedLaunchEditor(
                                                value,
                                                actions.controller.platform.osName,
                                                { draft = draft.copy(advancedLaunch = it) },
                                                { advancedValid = it },
                                            )
                                        }
                                    }
                                InstanceInstallSections(instance, actions)
                                TextButton({
                                    memoryMb = 0
                                    initial = "0"
                                    width = ""
                                    height = ""
                                    draft =
                                        applyInstanceMemoryPolicy(
                                            draft,
                                            InstanceMemoryPolicy.Inherit,
                                            state.data.settings.memoryMb,
                                        )
                                            .copy(
                                                javaPath = "",
                                                windowMode = "inherit",
                                                extraJvmArgs = "",
                                                extraGameArgs = "",
                                            )
                                }) {
                                    Text("恢复全局启动设置", fontSize = 12.sp)
                                }
                                val problem =
                                    launchError
                                        ?: saveError
                                        ?: if (!advancedValid) "高级启动参数未通过校验。"
                                        else null
                                if (problem != null)
                                    Text(
                                        problem,
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                Spacer(Modifier.height(6.dp))
                            }
                    }
                }
            }
            if (LocalAnimationsEnabled.current)
                Crossfade(tab, animationSpec = tween(150), label = "instance-tab") { index ->
                    page(index)
                }
            else page(tab)
        }
    }
    if (renaming) {
        var name by remember { mutableStateOf(instance.name) }
        var renameError by remember { mutableStateOf("") }
        val trimmed = name.trim()
        val validation =
            if (trimmed == instance.name) null
            else runCatching { validateInstanceDirectoryName(trimmed) }.exceptionOrNull()?.message
        val currentDirectory =
            runCatching {
                    actions.controller.store.versionGamePath(instance).fileName.toString()
                }
                .getOrDefault(instance.gameDirectoryName)
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("重命名实例") },
            text = {
                Column {
                    OutlinedTextField(
                        name,
                        {
                            name = it.take(80)
                            renameError = ""
                        },
                        Modifier.fillMaxWidth(),
                        label = { Text("实例名称") },
                        singleLine = true,
                        isError = validation != null || renameError.isNotBlank(),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        when {
                            validation != null -> validation
                            renameError.isNotBlank() -> renameError
                            trimmed == instance.name -> "输入新的名称；保存时会把 versions/$currentDirectory 一并改名。"
                            actions.controller.isInstanceBusy(instance.id) ->
                                "请等待当前任务结束后再重命名。"
                            else ->
                                "会把 versions/$currentDirectory 重命名为 versions/$trimmed，并同步同名 JSON/JAR、" +
                                    "版本配置和其他版本对它的引用。"
                        },
                        fontSize = 11.sp,
                        color =
                            if (validation != null || renameError.isNotBlank())
                                MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled =
                        trimmed != instance.name &&
                            validation == null &&
                            !actions.controller.isInstanceBusy(instance.id),
                    onClick = {
                        val failure =
                            runCatching { actions.controller.renameInstance(instance.id, trimmed) }
                                .exceptionOrNull()
                        if (failure == null) renaming = false
                        else renameError = failure.message ?: "重命名失败"
                    },
                ) {
                    Text("保存")
                }
            },
            dismissButton = { TextButton({ renaming = false }) { Text("取消") } },
        )
    }
    if (exportingMrpack)
        ExportMrpackDialog(
            instance,
            actions,
            { exportingMrpack = false },
            { actions.taskQueued() },
        )
    if (javaSheetOpen)
        JavaPickerSheet(
            value = draft.javaPath,
            runtimes = javaRuntimes,
            inheritedPath = state.data.settings.javaPath,
            ignoreCase = actions.controller.platform.osName == "windows",
            detecting = javaDetecting,
            onSelect = { path ->
                draft = draft.copy(javaPath = path)
                // 立即写入实例配置：启动读取的是已保存值，无需重启启动器。
                javaSaveError =
                    runCatching {
                            if (!actions.controller.isInstanceBusy(instance.id))
                                actions.controller.saveInstanceConfiguration(
                                    instance.copy(javaPath = path)
                                )
                        }
                        .exceptionOrNull()
                        ?.message
            },
            onDismiss = { javaSheetOpen = false },
        )
}

/** 页头 30dp 图标按钮：浅圆角悬停底，保持标题区紧凑。 */
@Composable
private fun HeaderIconButton(
    icon: ImageVector,
    label: String,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(Metrics.innerRadius)
    Box(
        Modifier.size(30.dp)
            .clip(shape)
            .hoverHighlight(shape, alpha = .06f)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, Modifier.size(17.dp), tint)
    }
}

/** 二级栏条目（页签与「管理」动作共用）：高 32、圆角 6、左右 10dp，图标 17 与 13sp 文字同一行。 */
@Composable
private fun InstanceRailItem(
    title: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(Metrics.cardRadius)
    val contentColor =
        if (selected) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier.fillMaxWidth()
            .height(Metrics.navItemHeight)
            .clip(shape)
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
            )
            .hoverHighlight(shape, enabled = !selected, alpha = .05f)
            .pressScale()
            .selectable(selected, role = Role.Tab, onClick = onClick)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(17.dp), contentColor)
        Spacer(Modifier.width(9.dp))
        Text(
            title,
            Modifier.weight(1f),
            color = contentColor,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
        )
    }
}

/**
 * 「管理」分区标题：整行可点击/可聚焦，Enter 与空格切换展开，右侧箭头随状态旋转。
 *
 * 高度与动作行一致，展开后列表仍按 4dp 间距对齐；默认收起由调用方的状态决定。
 */
@Composable
private fun InstanceRailSectionHeader(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val shape = RoundedCornerShape(Metrics.cardRadius)
    val chevron by
        animateFloatAsState(
            targetValue = if (expanded) 180f else 0f,
            animationSpec = tween(if (LocalReducedMotion.current) 0 else Motion.fast),
            label = "railSectionChevron",
        )
    Row(
        Modifier.fillMaxWidth()
            .height(Metrics.navItemHeight)
            .clip(shape)
            .hoverHighlight(shape, alpha = .05f)
            .pressScale()
            .clickable(role = Role.Button, onClick = onToggle)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) false
                else
                    when (event.key) {
                        Key.Enter,
                        Key.NumPadEnter,
                        Key.Spacebar -> {
                            onToggle()
                            true
                        }
                        else -> false
                    }
            }
            .focusable()
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            Modifier.weight(1f),
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
        Icon(
            Icons.Outlined.ExpandMore,
            if (expanded) "折叠$title" else "展开$title",
            Modifier.size(14.dp).rotate(chevron),
            MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 「管理」区的动作行：与页签同宽、同 32dp 高、同排版，只把选中态换成可禁用。
 *
 * [hint] 在不可用时说明原因，直接显示在行内，而不是静默禁用。
 */
@Composable
private fun InstanceRailAction(
    title: String,
    icon: ImageVector,
    enabled: Boolean = true,
    danger: Boolean = false,
    hint: String = "",
    // 行尾附加内容（例如检查更新的进度圈）；不改变 32dp 行高与图标文字排版。
    trailing: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(Metrics.cardRadius)
    val contentColor =
        when {
            !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .38f)
            danger -> MaterialTheme.colorScheme.error
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        }
    val showHint = hint.isNotBlank() && !enabled
    Row(
        Modifier.fillMaxWidth()
            .heightIn(min = Metrics.navItemHeight)
            .clip(shape)
            .hoverHighlight(shape, enabled = enabled, alpha = .05f)
            .pressScale()
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(17.dp), contentColor)
        Spacer(Modifier.width(9.dp))
        if (showHint)
            Column(Modifier.weight(1f)) {
                Text(title, color = contentColor, fontSize = 13.sp, maxLines = 1)
                Text(
                    hint,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .7f),
                    fontSize = 10.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        else
            Text(
                title,
                Modifier.weight(1f),
                color = contentColor,
                fontSize = 13.sp,
                maxLines = 1,
            )
        trailing?.invoke()
    }
}

private fun packSourceOf(instance: GameInstance): String =
    when {
        instance.packFile.isNotBlank() -> "整合包：" + instance.packFile.substringAfterLast('/')
        instance.externalProfile -> "外部目录导入"
        else -> "启动器安装"
    }

/** 设置页签的「当前安装」与「升级加载器」卡片：只展示当前组件，并提供同加载器的小版本升级。 */
@Composable
private fun ColumnScope.InstanceInstallSections(
    instance: GameInstance,
    actions: UiActions,
) {
    var versions by remember(instance.id) { mutableStateOf<List<LoaderVersion>>(emptyList()) }
    var loadingVersions by remember(instance.id) { mutableStateOf(false) }
    var versionError by remember(instance.id) { mutableStateOf<String?>(null) }
    var versionRetry by remember(instance.id) { mutableIntStateOf(0) }
    // 外部实例能否合并进原 JSON（继承链缺失等）由核心预检给出具体原因；只读，不改文件。
    var externalBlocker by remember(instance.id) { mutableStateOf<String?>(null) }
    // 只有已安装、非整合包且有加载器的实例才能升级；外部实例额外要求原 JSON 可安全合并。
    val upgradeable =
        instance.installed && instance.packFile.isBlank() && instance.loader != LoaderType.Vanilla
    val canUpgrade = upgradeable && externalBlocker == null
    LaunchedEffect(
        instance.id,
        instance.externalProfile,
        instance.versionId,
        instance.loaderVersion,
    ) {
        externalBlocker =
            if (instance.externalProfile)
                withContext(Dispatchers.IO) {
                    runCatching {
                            actions.controller.installer.externalLoaderUpgradeBlocker(instance)
                        }
                        .getOrElse { it.message ?: "外部版本配置无法读取" }
                }
            else null
    }
    LaunchedEffect(
        instance.id,
        instance.loader,
        instance.loaderVersion,
        instance.gameVersion,
        canUpgrade,
        externalBlocker,
        versionRetry,
    ) {
        versions = emptyList()
        versionError = null
        if (!canUpgrade || instance.loaderVersion.isBlank()) return@LaunchedEffect
        loadingVersions = true
        try {
            versions =
                withContext(Dispatchers.IO) {
                    actions.controller.installer.loaderVersions(
                        instance.loader,
                        instance.gameVersion,
                    )
                }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            versionError = failure.message
        } finally {
            loadingVersions = false
        }
    }
    val candidates = loaderUpgradeCandidates(instance, versions)
    SettingsSection("当前安装") {
        SettingRow("游戏版本", instance.gameVersion) {
            InstanceArtwork(instance, actions, Modifier.size(24.dp))
        }
        SettingRow(
            "模组加载器",
            if (instance.loader == LoaderType.Vanilla) "原版（未安装加载器）"
            else instance.loader.title + " " + instance.loaderVersion,
        ) {}
        Text(
            "版本 ID：" + instance.versionId,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SecondaryButton(
                {
                    actions.run { actions.controller.repair(instance.id) }
                    actions.taskQueued()
                },
                enabled = !actions.controller.isInstanceBusy(instance.id),
            ) {
                Text("修复游戏", fontSize = 12.sp)
            }
        }
    }
    SettingsSection("升级加载器", "只升级同一加载器的版本；游戏版本、模组与存档保持不变。") {
        when {
            instance.broken && instance.externalProfile ->
                Text(
                    (instance.brokenReason.ifBlank { "外部版本配置无法识别" }) +
                        "。请先在原目录补齐该版本，再进行升级。",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            instance.loader == LoaderType.Vanilla ->
                Text(
                    "原版实例没有可升级的加载器。",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            instance.packFile.isNotBlank() ->
                Text(
                    "整合包的加载器由包清单定义，请通过整合包更新维护。",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            instance.externalProfile && externalBlocker != null ->
                Text(
                    externalBlocker ?: "",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            !upgradeable ->
                Text(
                    "请先完成游戏安装，再升级加载器。",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            instance.loaderVersion.isBlank() ->
                Text(
                    "无法识别当前加载器版本，请先修复游戏。",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            else -> {
                SettingRow("当前加载器", instance.loader.title + " " + instance.loaderVersion) {}
                val latest = candidates.firstOrNull()
                when {
                    loadingVersions -> Row(verticalAlignment = Alignment.CenterVertically) {
                        LinearProgressIndicator(Modifier.weight(1f))
                        Spacer(Modifier.width(10.dp))
                        Text(
                            "正在获取" + instance.loader.title + "版本列表",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    versionError != null -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            versionError.orEmpty(),
                            Modifier.weight(1f),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.error,
                        )
                        TextButton({ versionRetry++ }) { Text("重试", fontSize = 12.sp) }
                    }
                    versions.isEmpty() -> Text(
                        "该游戏版本暂无可用的 " + instance.loader.title + " 版本",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    latest == null -> Text(
                        "已是此游戏版本下最新的 " +
                            instance.loader.title +
                            " " +
                            instance.loaderVersion,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    else -> SettingRow(
                        "可升级至",
                        latest.version + if (latest.stable) "" else "（测试版）",
                    ) {
                        PrimaryButton(
                            "升级",
                            Icons.Outlined.ArrowUpward,
                            !actions.controller.isInstanceBusy(instance.id),
                        ) {
                            actions.run {
                                actions.controller.upgradeInstanceLoader(
                                    instance.id,
                                    latest.version,
                                )
                            }
                            actions.taskQueued()
                        }
                    }
                }
            }
        }
    }
}

/** 内容页签共用的筛选芯片；顺序固定，页签只挑自己需要的几项显示。 */
private enum class ContentChip(val title: String) {
    All("全部"),
    Disabled("已禁用"),
    Updatable("可更新"),
    NoUpdate("未找到更新"),
}

/** 同一组件、同一顺序、同一尺寸：只显示 [visible] 里给出的芯片。 */
@Composable
private fun ContentChipRow(
    selected: ContentChip,
    visible: List<ContentChip>,
    onSelect: (ContentChip) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        visible.forEach { chip ->
            FilterChip(
                selected = selected == chip,
                onClick = { onSelect(chip) },
                label = { Text(chip.title, fontSize = 11.sp) },
                modifier = Modifier.height(28.dp),
            )
        }
    }
}

/** 模组列表：工具行（刷新/导入/下载/打开文件夹）+ 筛选芯片 + 52dp 行（版本与来源/更新/开关/删除）+ 骨架 + 空态。 */
@Composable
private fun InstanceMods(
    instance: GameInstance,
    actions: UiActions,
    updates: InstanceUpdateCenter,
    modifier: Modifier,
    onDiscover: () -> Unit,
) {
    var mods by remember { mutableStateOf<List<ModFile>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    var refresh by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var updatesOpen by remember { mutableStateOf(false) }
    var detailMod by remember { mutableStateOf<ModFile?>(null) }
    var deletingMod by remember { mutableStateOf<ModFile?>(null) }
    var chip by remember(instance.id) { mutableStateOf(ContentChip.All) }
    val checking = updates.modsChecking
    val updateReport = updates.mods
    val updateError = updates.modsError
    val checkScope = rememberCoroutineScope()
    // 本次页面发起的更新任务；完成/失败后刷新列表并更新报告。
    var applyingJob by remember(instance.id) { mutableStateOf<Pair<String, ModUpdateItem>?>(null) }
    var confirmingUpdate by remember { mutableStateOf<ModUpdateItem?>(null) }
    val liveState by actions.controller.state.collectAsState()
    // 检查只读网络与 JAR 声明：自动检查由页面发起，手动重查只取消它自己的请求。
    val startCheck: () -> Unit = { updates.checkMods(checkScope) }
    val startUpdate: (ModUpdateItem) -> Unit = { item ->
        updates.modsError = null
        runCatching { actions.controller.updateMod(instance.id, item) }
            .onSuccess { job -> applyingJob = job to item }
            .onFailure { failure -> updates.modsError = failure.message }
    }
    if (updatesOpen)
        ContentUpdatesDialog(instance.id, actions) {
            updatesOpen = false
            refresh++
        }
    detailMod?.let { mod -> ModDetailsDialog(mod) { detailMod = null } }
    // 名称搜索得到的匹配先确认来源项目，避免把同名模组替换掉。
    confirmingUpdate?.let { item ->
        AlertDialog(
            onDismissRequest = { confirmingUpdate = null },
            title = { Text("可能不是同一项目") },
            text = {
                Text(
                    "「" +
                        item.title +
                        "」是按名称匹配的，来源项目为「" +
                        item.candidate?.projectTitle.orEmpty().ifBlank {
                            item.candidate?.projectId.orEmpty()
                        } +
                        "」。确认是同一项目后再更新。"
                )
            },
            confirmButton = {
                TextButton({
                    confirmingUpdate = null
                    startUpdate(item)
                }) {
                    Text("仍然更新", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton({ confirmingUpdate = null }) { Text("取消") } },
        )
    }
    // 删除模组会从 mods 目录移入回收站，先确认再执行。
    deletingMod?.let { mod ->
        AlertDialog(
            onDismissRequest = { deletingMod = null },
            title = { Text("删除模组？") },
            text = {
                Text(
                    "「" +
                        mod.metadata.title.ifBlank { mod.filename.removeSuffix(".disabled") } +
                        "」将移至回收站，需要时可以再找回。"
                )
            },
            confirmButton = {
                TextButton({
                    val filename = mod.filename
                    deletingMod = null
                    actions.run {
                        actions.controller.requireIdle(instance.id)
                        actions.controller.content.deleteMod(instance.id, filename)
                        withContext(Dispatchers.Swing) { refresh++ }
                    }
                }) {
                    Text("移入回收站", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton({ deletingMod = null }) { Text("取消") } },
        )
    }
    LaunchedEffect(instance.id, refresh, liveState.jobs.map { it.status }) {
        loading = true
        error = null
        try {
            mods = withContext(Dispatchers.IO) { actions.controller.content.listMods(instance.id) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            error = failure.message
        } finally {
            loading = false
        }
    }
    // 应用更新是任务中心里的后台任务；结束后刷新列表，并把旧文件对应的报告项改为已是最新。
    LaunchedEffect(applyingJob, liveState.jobs.map { it.id + ":" + it.status }) {
        val pending = applyingJob ?: return@LaunchedEffect
        val job = liveState.jobs.firstOrNull { it.id == pending.first } ?: return@LaunchedEffect
        when (job.status) {
            JobStatus.Completed -> {
                val done = pending.second
                val candidate = done.candidate
                if (candidate != null) {
                    val newFilename =
                        candidate.filename +
                            if (done.filename.endsWith(".disabled", true)) ".disabled" else ""
                    updates.mods =
                        updateReport?.let { report ->
                            report
                                .copy(
                                    items =
                                        report.items
                                            .map { item ->
                                                if (item.filename == done.filename)
                                                    item.copy(
                                                        filename = newFilename,
                                                        currentVersion = candidate.versionNumber,
                                                        status = ModUpdateStatus.Latest,
                                                        latestVersion = "",
                                                        candidate = null,
                                                        possibleMatch = false,
                                                        applicable = false,
                                                        warning = "",
                                                    )
                                                else item
                                            }
                                            .distinctBy { it.filename }
                                )
                        }
                }
                applyingJob = null
                refresh++
            }
            JobStatus.Failed, JobStatus.Cancelled -> {
                updates.modsError =
                    job.error
                        ?: if (job.status == JobStatus.Cancelled) "更新已取消" else "更新失败"
                applyingJob = null
                refresh++
            }
            else -> Unit
        }
    }
    val busy = actions.controller.isInstanceBusy(instance.id)
    Column(modifier.fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ContentToolbar(
            flexible = { ToolbarSearchField(query, { query = it }, "搜索已安装模组") },
            refresh =
                ToolbarAction("刷新", icon = Icons.Outlined.Refresh, enabled = !busy) { refresh++ },
            importAction =
                ToolbarAction("导入模组", icon = Icons.Outlined.FileDownload, enabled = !busy) {
                    actions.run {
                        actions.controller.requireIdle(instance.id)
                        val files =
                            actions.choose("导入本地模组", multiple = true, extensions = listOf("jar"))
                        actions.controller.content.importMods(instance.id, files)
                        withContext(Dispatchers.Swing) { refresh++ }
                    }
                },
            downloads =
                listOf(
                    ToolbarAction(
                        "下载模组",
                        icon = Icons.Outlined.Add,
                        style = ToolbarActionStyle.Filled,
                    ) { onDiscover() },
                ),
            openFolder =
                ToolbarAction("打开文件夹", icon = Icons.Outlined.FolderOpen, enabled = !busy) {
                    actions.run { actions.controller.openFolder(instance.id, "mods") }
                },
        )
        val reportByFile = updateReport?.items?.associateBy { it.filename }.orEmpty()
        val activeNames = mods.map { it.filename }.toSet()
        val updateCount =
            updateReport?.items.orEmpty().count {
                it.status == ModUpdateStatus.Updatable && it.filename in activeNames
            }
        val unknownCount =
            updateReport?.items.orEmpty().count {
                it.status == ModUpdateStatus.Unknown && it.filename in activeNames
            }
        // 芯片按更新状态筛选：「未找到更新」只收「无法确认」，「已是最新」只出现在「全部」。
        // 数量与芯片一致：可更新数量、无法确认数量都不含已是最新的条目。
        val statusText =
            when {
                checking -> "正在检查更新…"
                updateReport == null -> ""
                updateCount > 0 ->
                    "$updateCount 项可更新" +
                        if (unknownCount > 0) " · $unknownCount 项无法确认" else ""
                unknownCount > 0 -> "$unknownCount 项无法确认"
                else -> "全部为最新"
            }
        // 芯片行常驻：即使检查失败或被取消，也能继续筛选并回到「版本管理」。
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ContentChipRow(
                selected = chip,
                visible =
                    listOf(
                        ContentChip.All,
                        ContentChip.Disabled,
                        ContentChip.Updatable,
                        ContentChip.NoUpdate,
                    ),
            ) { chip = it }
            if (statusText.isNotBlank())
                Text(
                    statusText,
                    Modifier.weight(1f),
                    fontSize = 11.sp,
                    color =
                        if (updateCount > 0) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            else Spacer(Modifier.weight(1f))
            TextButton(
                { updates.checkAll(checkScope, refresh = true) },
                enabled = !actions.controller.isInstanceBusy(instance.id) && !checking,
            ) {
                Text(if (checking) "正在检查…" else "检查更新", fontSize = 12.sp)
            }
            TextButton(
                { updatesOpen = true },
                enabled = !actions.controller.isInstanceBusy(instance.id),
            ) {
                Text("版本管理", fontSize = 12.sp)
            }
        }
        if (checking) LinearProgressIndicator(Modifier.fillMaxWidth().height(3.dp))
        updateError?.let { failure ->
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    failure,
                    Modifier.weight(1f),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                TextButton(
                    { updates.modsError = null; startCheck() },
                    enabled = !actions.controller.isInstanceBusy(instance.id) && !checking,
                ) {
                    Text("重新检查", fontSize = 12.sp)
                }
            }
        }
        val filtered =
            mods.filter {
                    query.isBlank() ||
                        it.filename.contains(query, true) ||
                        it.metadata.title.contains(query, true) ||
                        it.metadata.entries.any { entry -> entry.id.contains(query, true) }
                }
                .filter { mod ->
                    when (chip) {
                        ContentChip.All -> true
                        ContentChip.Disabled -> !mod.enabled
                        ContentChip.Updatable ->
                            reportByFile[mod.filename]?.status == ModUpdateStatus.Updatable
                        // 「未找到更新」只收「无法确认」的条目；已是最新的只在「全部」里出现。
                        ContentChip.NoUpdate ->
                            reportByFile[mod.filename]?.status == ModUpdateStatus.Unknown
                    }
                }
        // 模组图标从 JAR 自带资源里取，缓存在启动器 cache/mod-icons；行内按 JAR 路径 + 修改时间复用。
        val modsDirectory = runCatching {
            actions.controller.store.instancePath(instance.id).resolve("mods")
        }
            .getOrNull()
        val iconCacheRoot = runCatching { actions.controller.store.cacheDirectory }.getOrNull()
        // 首次读取还没有数据时给骨架；刷新时保留旧列表，只在顶部显示一条细进度。
        if (loading && mods.isNotEmpty())
            LinearProgressIndicator(Modifier.fillMaxWidth().height(3.dp))
        when {
            loading && mods.isEmpty() -> ListSkeleton()
            error != null ->
                Panel(Modifier.fillMaxWidth()) {
                    EmptyState("无法读取模组列表", error.orEmpty(), Icons.Outlined.CloudOff) {
                        TextButton({ refresh++ }) { Text("重试") }
                    }
                }
            mods.isEmpty() ->
                Panel(Modifier.fillMaxWidth()) {
                    EmptyState(
                        "还没有安装模组",
                        "从资源库下载，或导入本地 JAR 文件。",
                        Icons.Outlined.Extension,
                    ) {
                        PrimaryButton("下载模组", Icons.Outlined.Add, onClick = onDiscover)
                    }
                }
            filtered.isEmpty() ->
                Panel(Modifier.fillMaxWidth()) {
                    EmptyState(
                        if (chip != ContentChip.All && query.isBlank())
                            "没有「${chip.title}」的模组"
                        else "没有匹配的模组",
                        if (chip != ContentChip.All && query.isBlank())
                            "切换到「全部」查看所有模组。"
                        else "换个关键词试试",
                        Icons.Outlined.SearchOff,
                    ) {
                        TextButton({
                            query = ""
                            chip = ContentChip.All
                        }) {
                            Text("清除筛选")
                        }
                    }
                }
            else ->
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(filtered, key = { it.filename }) { mod ->
                        ModRow(
                            mod,
                            jar = modsDirectory?.resolve(mod.filename),
                            iconCacheRoot = iconCacheRoot,
                            modifier = Modifier.animateItem(),
                            update = reportByFile[mod.filename],
                            busy = actions.controller.isInstanceBusy(instance.id) || applyingJob != null,
                            onDetail = { detailMod = mod },
                            onUpdate = {
                                reportByFile[mod.filename]?.let { item ->
                                    if (item.possibleMatch) confirmingUpdate = item
                                    else startUpdate(item)
                                }
                            },
                            onToggle = {
                                actions.run {
                                    actions.controller.requireIdle(instance.id)
                                    actions.controller.content.toggleMod(instance.id, mod.filename)
                                    withContext(Dispatchers.Swing) { refresh++ }
                                }
                            },
                            onDelete = { deletingMod = mod },
                        )
                    }
                }
        }
        if (!loading && mods.isNotEmpty())
            Text(
                "共 " + mods.size + " 个文件 · 已启用 " + mods.count { it.enabled },
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
    }
}

/**
 * 行尾动作按钮：模组 / 资源包 / 光影 / 存档共用。
 *
 * 文字动作高 28、12sp、左右 8dp；图标动作同高（28dp）同间距。调用方统一按「主动作 → 开关 → 删除」排序， 动作之间间隔 4dp；删除一律用图标并放最后。
 */
@Composable
private fun RowTextAction(
    text: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    TextButton(
        onClick,
        enabled = enabled,
        modifier = Modifier.height(28.dp),
        contentPadding = PaddingValues(horizontal = 8.dp),
    ) {
        Text(text, fontSize = 12.sp)
    }
}

/** 行尾图标动作：与 [RowTextAction] 同高同间距，删除类动作放最后。 */
@Composable
private fun RowIconAction(
    icon: ImageVector,
    label: String,
    enabled: Boolean = true,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    onClick: () -> Unit,
) {
    SmallIconButton(icon, label, onClick, enabled = enabled, tint = tint, size = 28.dp)
}

/** 52dp 模组行：JAR 图标 28dp + 名称 14 + 版本/加载器/大小 12 + 来源文件名 11 + 更新/开关/删除。 */
@Composable
private fun ModRow(
    mod: ModFile,
    jar: Path?,
    iconCacheRoot: Path?,
    modifier: Modifier,
    update: ModUpdateItem?,
    busy: Boolean,
    onDetail: () -> Unit,
    onUpdate: () -> Unit,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
) {
    val shape = RoundedCornerShape(Metrics.cardRadius)
    val sourceName = mod.filename.removeSuffix(".disabled")
    val icon =
        if (jar != null && iconCacheRoot != null) rememberModIcon(jar, mod.modified, iconCacheRoot)
        else null
    val details =
        listOf(
                mod.metadata.versions.ifBlank { "版本未知" },
                mod.metadata.loader.ifBlank { "加载器未知" },
                "%.1f MB".format(mod.size / 1048576f),
            )
            .filter { it.isNotBlank() }
            .joinToString(" · ")
    val metaDetails =
        listOf(
                mod.metadata.loader.ifBlank { "加载器未知" },
                "%.1f MB".format(mod.size / 1048576f),
            )
            .filter { it.isNotBlank() }
            .joinToString(" · ")
    val updateItem = update?.takeIf { it.status == ModUpdateStatus.Updatable }
    Surface(
        modifier.fillMaxWidth().heightIn(min = 52.dp).pressScale(),
        shape = shape,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .55f)),
    ) {
        Row(
            Modifier.fillMaxWidth()
                .hoverHighlight(shape, alpha = .04f)
                .clickable(onClick = onDetail)
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 图标小于两行文字（约 38dp），行高仍由文字与 52dp 下限决定，开关和删除按钮的位置不变。
            Box(
                Modifier.size(28.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(
                        MaterialTheme.colorScheme.surfaceVariant.copy(
                            alpha = if (mod.enabled) .5f else .3f
                        )
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (icon != null)
                    Image(icon, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                else
                    Icon(
                        Icons.Outlined.Extension,
                        null,
                        Modifier.size(15.dp),
                        if (mod.enabled) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
            }
            Spacer(Modifier.width(9.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        mod.metadata.title.ifBlank { sourceName },
                        Modifier.weight(1f, fill = false),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (update?.possibleMatch == true) {
                        Spacer(Modifier.width(5.dp))
                        Tag("可能匹配", MaterialTheme.colorScheme.tertiary)
                    }
                }
                if (updateItem != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            updateItem.currentVersion.ifBlank { "当前" } +
                                " → " +
                                updateItem.latestVersion.ifBlank {
                                    updateItem.candidate?.versionNumber.orEmpty()
                                },
                            Modifier.weight(1f, fill = false),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            metaDetails +
                                if (updateItem.candidate == null) " · 需手动下载" else "",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                } else
                    Text(
                        details +
                            when (update?.status) {
                                ModUpdateStatus.Latest -> " · 已是最新"
                                ModUpdateStatus.Unknown -> " · 无法确认"
                                else -> ""
                            },
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                sourceName,
                Modifier.widthIn(max = 150.dp),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.width(8.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (updateItem != null && updateItem.applicable)
                    RowTextAction("更新", enabled = !busy, onClick = onUpdate)
                CompactSwitch(mod.enabled, { onToggle() }, enabled = !busy)
                RowIconAction(
                    Icons.Outlined.DeleteOutline,
                    "将模组移至回收站",
                    enabled = !busy,
                    onClick = onDelete,
                )
            }
        }
    }
}

/** 已解码的模组图标：按「JAR 路径 + 修改时间」键控，最多保留 256 张，列表来回滚动不重复解码。 */
private object ModIconBitmaps {
    private val values =
        object : LinkedHashMap<String, ImageBitmap>(64, .75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?) =
                size > 256
        }

    @Synchronized operator fun get(key: String): ImageBitmap? = values[key]

    @Synchronized
    fun put(key: String, value: ImageBitmap) {
        values[key] = value
    }
}

/**
 * 异步读取一行模组图标：解析与解码都在 IO 线程完成（`modIcon` 只读 JAR 内的声明与图片， 结果已按 JAR 身份缓存在 cache/mod-icons），解码结果再按 JAR
 * 路径 + 修改时间放进内存缓存。 没有可用图标时返回 null，行内退回拼图占位，不改变行高。
 */
@Composable
private fun rememberModIcon(jar: Path, modified: Long, cacheRoot: Path): ImageBitmap? {
    val key =
        remember(jar, modified) {
            jar.toAbsolutePath().normalize().toString() + "|" + modified
        }
    val image by
        produceState<ImageBitmap?>(ModIconBitmaps[key], key, cacheRoot) {
            if (value != null) return@produceState
            value =
                try {
                    withContext(Dispatchers.IO) {
                        modIcon(jar, cacheRoot)?.let { file ->
                            SkiaImage.makeFromEncoded(Files.readAllBytes(file))
                                .toComposeImageBitmap()
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
            value?.let { ModIconBitmaps.put(key, it) }
        }
    return image
}

/**
 * 内容区的拖放接收器：把传入的文件/文件夹路径交给 [onFiles]，悬停时给内容区描边。
 *
 * 只接受系统文件列表（Finder / 资源管理器的拖放都走这一种）；数量上限 20，避免误拖整个目录。
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun Modifier.contentDropTarget(
    enabled: Boolean = true,
    hint: String = "请拖入文件或文件夹",
    onError: (String) -> Unit = {},
    onFiles: (List<Path>) -> Unit,
): Modifier {
    var hovered by remember { mutableStateOf(false) }
    val receiver by rememberUpdatedState(onFiles)
    val failure by rememberUpdatedState(onError)
    val target =
        remember(enabled) {
            object : DragAndDropTarget {
                override fun onEntered(event: DragAndDropEvent) {
                    if (enabled) hovered = true
                }

                override fun onExited(event: DragAndDropEvent) {
                    hovered = false
                }

                override fun onEnded(event: DragAndDropEvent) {
                    hovered = false
                }

                override fun onDrop(event: DragAndDropEvent): Boolean {
                    hovered = false
                    if (!enabled) return false
                    return runCatching {
                        val raw =
                            event.awtTransferable.getTransferData(DataFlavor.javaFileListFlavor)
                                as? List<*>
                        val files = raw.orEmpty().filterIsInstance<File>().map { it.toPath() }
                        require(files.isNotEmpty()) { hint }
                        require(files.size <= 20) { "一次最多拖入 20 个文件" }
                        receiver(files)
                        true
                    }
                        .getOrElse { error ->
                            failure(error.message ?: "无法读取拖入的文件")
                            false
                        }
                }
            }
        }
    val shape = RoundedCornerShape(Metrics.cardRadius)
    return this
        .then(
            if (hovered && enabled)
                Modifier.border(2.dp, MaterialTheme.colorScheme.primary, shape)
            else Modifier
        )
        .dragAndDropTarget(
            shouldStartDragAndDrop = { event ->
                enabled &&
                    event.awtTransferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor)
            },
            target = target,
        )
}

/** 资源包 / 光影行：缩略图 + 名称 14 + 类型/大小/修改时间 12 + 更新标记；行尾只有删除。 */
@Composable
private fun PackRow(
    entry: LocalPackEntry,
    folder: PackFolder,
    update: PackUpdateItem?,
    busy: Boolean,
    onDelete: () -> Unit,
) {
    val shader = folder == PackFolder.ShaderPacks
    val shape = RoundedCornerShape(Metrics.cardRadius)
    val details =
        listOf(
                if (entry.isDirectory) "文件夹" else "ZIP",
                formatFileSize(entry.size),
                "修改于 " +
                    if (entry.modified > 0) formatSaveTime(java.time.Instant.ofEpochMilli(entry.modified))
                    else "未知时间",
            )
            .joinToString(" · ")
    // 更新信息与文件信息合成一行，行高与模组/存档一致；「可更新」用主题色，失败原因如实附在后面。
    val meta =
        buildString {
            if (update?.status == PackUpdateStatus.Updatable) {
                append(update.currentVersion.ifBlank { "当前" })
                append(" → ")
                append(update.latestVersion.ifBlank { update.candidate?.versionNumber.orEmpty() })
                append(" · ")
            }
            append(details)
            when (update?.status) {
                PackUpdateStatus.Latest -> append(" · 已是最新")
                PackUpdateStatus.Unknown -> {
                    append(" · 无法确认")
                    if (update.warning.isNotBlank()) append(" · ").append(update.warning)
                }
                else -> Unit
            }
        }
    Surface(
        Modifier.fillMaxWidth().heightIn(min = 52.dp),
        shape = shape,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .55f)),
    ) {
        Row(
            Modifier.fillMaxWidth()
                .hoverHighlight(shape, alpha = .04f)
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LocalContentThumbnail(
                entry.path,
                world = false,
                modifier = Modifier.size(28.dp),
                modified = entry.modified,
                shader = shader,
                fallback = {
                    if (shader) ShaderFallbackIcon(Modifier.size(28.dp))
                    else
                        Icon(
                            Icons.Outlined.Image,
                            null,
                            Modifier.size(28.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                },
            )
            Spacer(Modifier.width(9.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        entry.filename,
                        Modifier.weight(1f, fill = false),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (update?.possibleMatch == true) {
                        Spacer(Modifier.width(5.dp))
                        Tag("可能匹配", MaterialTheme.colorScheme.tertiary)
                    }
                }
                Text(
                    meta,
                    fontSize = 12.sp,
                    color =
                        if (update?.status == PackUpdateStatus.Updatable)
                            MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            RowIconAction(
                Icons.Outlined.DeleteOutline,
                "将${entry.filename}移至回收站",
                enabled = !busy,
                onClick = onDelete,
            )
        }
    }
}

/**
 * 资源包 / 光影页签：搜索 + 刷新 + 导入 + 打开文件夹；更新检查由左栏「检查更新」统一触发，结果按页签显示。行列出目录里的 zip 与文件夹。
 *
 * 没有启用/禁用开关：资源包在游戏内选择，光影由 Iris / OptiFine 读取。支持把 zip 或文件夹直接拖进页面导入。
 */
@Composable
private fun InstancePackTab(
    instance: GameInstance,
    actions: UiActions,
    updates: InstanceUpdateCenter,
    folder: PackFolder,
    modifier: Modifier,
) {
    var entries by remember(instance.id, folder) { mutableStateOf<List<LocalPackEntry>>(emptyList()) }
    var query by remember(instance.id, folder) { mutableStateOf("") }
    var refresh by remember(instance.id, folder) { mutableIntStateOf(0) }
    var loading by remember(instance.id, folder) { mutableStateOf(true) }
    var error by remember(instance.id, folder) { mutableStateOf<String?>(null) }
    var actionError by remember(instance.id, folder) { mutableStateOf<String?>(null) }
    var chip by remember(instance.id, folder) { mutableStateOf(ContentChip.All) }
    var deleting by remember { mutableStateOf<LocalPackEntry?>(null) }
    var watchingJob by remember(instance.id, folder) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val liveState by actions.controller.state.collectAsState()
    val busy = actions.controller.isInstanceBusy(instance.id)
    val checking = updates.packsChecking
    val report = updates.packs
    val reportItems = report?.items(folder).orEmpty()
    // 列表还没读出来时不按文件名过滤，避免先闪一下「全部为最新」。
    val visibleItems =
        if (entries.isEmpty()) reportItems
        else {
            val installed = entries.mapTo(HashSet()) { it.filename }
            reportItems.filter { it.filename in installed }
        }
    val itemsByFile = reportItems.associateBy { it.filename }
    val updatable = visibleItems.count { it.status == PackUpdateStatus.Updatable }
    val uncertain = visibleItems.count { it.status == PackUpdateStatus.Unknown }
    val statusText =
        when {
            checking -> "正在检查更新…"
            report == null -> ""
            updatable > 0 ->
                "$updatable 项可更新" + if (uncertain > 0) " · $uncertain 项无法确认" else ""
            uncertain > 0 -> "$uncertain 项无法确认"
            else -> "全部为最新"
        }
    LaunchedEffect(instance.id, folder, refresh, liveState.jobs.map { it.id + ":" + it.status }) {
        loading = true
        error = null
        try {
            entries =
                withContext(Dispatchers.IO) { actions.controller.listPacks(instance.id, folder) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            entries = emptyList()
            error = failure.message
        } finally {
            loading = false
        }
    }
    // 导入与删除都是任务中心里的后台任务；结束后刷新列表，失败原因留在工具行下方。
    LaunchedEffect(watchingJob, liveState.jobs.map { it.id + ":" + it.status }) {
        val id = watchingJob ?: return@LaunchedEffect
        val job = liveState.jobs.firstOrNull { it.id == id } ?: return@LaunchedEffect
        when (job.status) {
            JobStatus.Failed -> {
                actionError = job.error ?: "操作失败，详情见日志"
                watchingJob = null
            }
            JobStatus.Completed,
            JobStatus.Cancelled -> {
                watchingJob = null
                refresh++
            }
            else -> Unit
        }
    }
    val startImport: (List<Path>) -> Unit = { chosen ->
        actionError = null
        if (chosen.isNotEmpty())
            try {
                watchingJob = actions.controller.importPacks(instance.id, folder, chosen)
            } catch (failure: Exception) {
                actionError = failure.message ?: "无法导入"
            }
    }
    deleting?.let { entry ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("移入回收站？") },
            text = { Text("「${entry.filename}」会移到回收站，需要时可以再找回。") },
            confirmButton = {
                TextButton({
                    deleting = null
                    actionError = null
                    actions.run {
                        actions.controller.requireIdle(instance.id)
                        val removed =
                            runCatching {
                                    actions.controller.deletePack(
                                        instance.id,
                                        folder,
                                        entry.filename,
                                    )
                                }
                                .getOrElse { failure ->
                                    withContext(Dispatchers.Swing) {
                                        actionError = failure.message ?: "无法删除"
                                    }
                                    false
                                }
                        withContext(Dispatchers.Swing) {
                            if (!removed && actionError == null)
                                actionError =
                                    "「${entry.filename}」没有移入回收站：文件可能已不存在，" +
                                        "或回收站不可用；详情见日志。"
                            // 报告里的旧条目同步移除，避免「N 项可更新」把已删除的文件算进去。
                            if (removed)
                                updates.packs?.let { current ->
                                    updates.packs =
                                        current.copy(
                                            items =
                                                current.items.filterNot {
                                                    it.folder == folder &&
                                                        it.filename == entry.filename
                                                }
                                        )
                                }
                            refresh++
                        }
                    }
                }) {
                    Text("移至回收站", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton({ deleting = null }) { Text("取消") } },
        )
    }
    Column(
        modifier
            .fillMaxHeight()
            .contentDropTarget(
                enabled = !busy,
                hint = "请拖入 .zip 或文件夹",
                onError = { actionError = it },
            ) { dropped -> startImport(dropped) },
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ContentToolbar(
            flexible = {
                ToolbarSearchField(query, { query = it }, "搜索已安装${folder.title}")
            },
            refresh =
                ToolbarAction("刷新", icon = Icons.Outlined.Refresh, enabled = !busy) { refresh++ },
            importAction =
                ToolbarAction("导入", icon = Icons.Outlined.FileDownload, enabled = !busy) {
                    actions.run {
                        val chosen =
                            actions.choose(
                                "导入${folder.title}（.zip）",
                                multiple = true,
                                extensions = listOf("zip"),
                            )
                        withContext(Dispatchers.Swing) { startImport(chosen) }
                    }
                },
            openFolder =
                ToolbarAction("打开文件夹", icon = Icons.Outlined.FolderOpen, enabled = !busy) {
                    actions.run { actions.controller.openFolder(instance.id, folder.directory) }
                },
        )
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ContentChipRow(
                selected = chip,
                visible = listOf(ContentChip.All, ContentChip.Updatable, ContentChip.NoUpdate),
            ) { chip = it }
            if (statusText.isNotBlank())
                Text(
                    statusText,
                    Modifier.weight(1f),
                    fontSize = 11.sp,
                    color =
                        if (updatable > 0) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            else Spacer(Modifier.weight(1f))
        }
        if (checking) LinearProgressIndicator(Modifier.fillMaxWidth().height(3.dp))
        updates.packsError?.let { failure ->
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    failure,
                    Modifier.weight(1f),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                TextButton(
                    { updates.packsError = null; updates.checkPacks(scope) },
                    enabled = !busy && !checking,
                ) {
                    Text("重新检查", fontSize = 12.sp)
                }
            }
        }
        actionError?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.error) }
        val filtered =
            entries
                .filter { query.isBlank() || it.filename.contains(query, true) }
                .filter { entry ->
                    val status = itemsByFile[entry.filename]?.status
                    when (chip) {
                        ContentChip.All -> true
                        ContentChip.Updatable -> status == PackUpdateStatus.Updatable
                        // 「未找到更新」只收「无法确认」的条目；已是最新的只在「全部」里出现。
                        ContentChip.NoUpdate -> status == PackUpdateStatus.Unknown
                        // 资源包与光影没有启用/禁用开关，这个芯片不会显示。
                        ContentChip.Disabled -> true
                    }
                }
        when {
            loading && entries.isEmpty() -> ListSkeleton()
            error != null ->
                Panel(Modifier.fillMaxWidth()) {
                    EmptyState("无法读取${folder.title}目录", error.orEmpty(), Icons.Outlined.CloudOff) {
                        TextButton({ refresh++ }) { Text("重试") }
                    }
                }
            entries.isEmpty() ->
                Panel(Modifier.fillMaxWidth()) {
                    EmptyState(
                        "还没有${folder.title}",
                        "点「导入」选择 .zip，或把 .zip / 文件夹直接拖进这个页面。",
                        if (folder == PackFolder.ShaderPacks) Icons.Outlined.AutoAwesome
                        else Icons.Outlined.Palette,
                    )
                }
            filtered.isEmpty() ->
                Panel(Modifier.fillMaxWidth()) {
                    EmptyState(
                        if (chip != ContentChip.All && query.isBlank())
                            "没有「${chip.title}」的${folder.title}"
                        else "没有匹配的${folder.title}",
                        if (chip != ContentChip.All && query.isBlank()) "切换到「全部」查看全部内容。"
                        else "换个关键词试试",
                        Icons.Outlined.SearchOff,
                    ) {
                        TextButton({
                            query = ""
                            chip = ContentChip.All
                        }) {
                            Text("清除筛选")
                        }
                    }
                }
            else ->
                ScrollColumn(Modifier.weight(1f).fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        filtered.forEach { entry ->
                            key(entry.filename) {
                                PackRow(
                                    entry = entry,
                                    folder = folder,
                                    update = itemsByFile[entry.filename],
                                    busy = busy,
                                    onDelete = { deleting = entry },
                                )
                            }
                        }
                    }
                }
        }
        Text(
            "共 ${entries.size} 项 · ${folder.directory}",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 存档页签：顶部搜索 / 刷新 / 打开存档文件夹，44dp 一行列出世界（名称、大小、修改时间、 「可能不是有效世界」提示），行尾「备份」「复制到…」「删除」；下方是「恢复时替换原世界」设置与「备份」卡片。
 *
 * 备份与原版游戏共用 `<游戏目录>/backups/`，原版写的备份也会列出；没有 `level.dat` 的压缩包只展示、不提供恢复。
 * 列表在 IO 线程读取；任务进行中（controller.busy）禁用所有操作按钮；后台任务失败时把后端的中文消息显示在 工具行下方，不静默吞掉。
 */
@Composable
private fun InstanceSavesTab(instance: GameInstance, actions: UiActions, modifier: Modifier) {
    var saves by remember(instance.id) { mutableStateOf<List<SaveEntry>>(emptyList()) }
    var backups by remember(instance.id) { mutableStateOf<List<SaveBackupInfo>>(emptyList()) }
    var query by remember(instance.id) { mutableStateOf("") }
    var refresh by remember(instance.id) { mutableIntStateOf(0) }
    var loading by remember(instance.id) { mutableStateOf(true) }
    var loadError by remember(instance.id) { mutableStateOf<String?>(null) }
    // 操作失败与列表读取失败分开保存：刷新列表不会把刚失败的操作提示一起清掉。
    var actionError by remember(instance.id) { mutableStateOf<String?>(null) }
    // 本次页面发起的后台任务；结束时用它刷新列表并显示失败原因。
    var watchingJob by remember(instance.id) { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<SaveEntry?>(null) }
    var deletingBackup by remember { mutableStateOf<SaveBackupInfo?>(null) }
    // 开启「恢复时替换原世界」后，恢复会替换同名世界；执行前单独确认。
    var replacing by remember { mutableStateOf<SaveBackupInfo?>(null) }
    var copying by remember { mutableStateOf<SaveEntry?>(null) }
    val liveState by actions.controller.state.collectAsState()
    val busy = actions.controller.isInstanceBusy(instance.id)
    val running = actions.controller.game.isRunning(instance.id)
    val canManage = !busy && !running
    val canCopy = canManage
    val blockedNote =
        when {
            running -> "游戏正在使用存档，请先退出游戏再进行存档操作。"
            busy -> "任务进行中，存档操作暂时不可用。"
            else -> ""
        }
    val savesDirectory = actions.controller.store.instancePath(instance.id).resolve("saves")
    // 只把存档相关任务的结束当作刷新信号，避免其它下载任务反复触发备份 SHA1 重算。
    val saveJobsKey =
        liveState.jobs.filter { it.title.contains("存档") }.map { it.id + ":" + it.status }
    LaunchedEffect(instance.id, refresh, saveJobsKey) {
        loading = true
        loadError = null
        try {
            val snapshot =
                withContext(Dispatchers.IO) {
                    actions.controller.listSaves(instance.id) to
                        actions.controller.listSaveBackups(instance.id)
                }
            saves = snapshot.first
            backups = snapshot.second
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            saves = emptyList()
            backups = emptyList()
            loadError = failure.message ?: "无法读取存档目录"
        } finally {
            loading = false
        }
    }
    LaunchedEffect(watchingJob, saveJobsKey) {
        val id = watchingJob ?: return@LaunchedEffect
        val job = liveState.jobs.firstOrNull { it.id == id } ?: return@LaunchedEffect
        when (job.status) {
            JobStatus.Failed -> {
                actionError = job.error ?: "存档操作失败，详情见日志"
                watchingJob = null
            }
            JobStatus.Completed,
            JobStatus.Cancelled -> watchingJob = null
            else -> Unit
        }
    }
    val startBackup = { entry: SaveEntry ->
        actionError = null
        try {
            watchingJob = actions.controller.backupSave(instance.id, entry.name)
        } catch (failure: Exception) {
            actionError = failure.message ?: "无法开始备份存档"
        }
    }
    val startRestore = { backup: SaveBackupInfo ->
        actionError = null
        try {
            watchingJob = actions.controller.restoreSave(instance.id, backup.id)
        } catch (failure: Exception) {
            actionError = failure.message ?: "无法开始恢复存档"
        }
    }
    // 拖入的 .zip / 世界文件夹：核心先校验（level.dat 在根或唯一一层包装目录）再解压，无效输入不落盘。
    val startImport: (List<Path>) -> Unit = { dropped ->
        actionError = null
        when {
            dropped.isEmpty() -> Unit
            dropped.size > 1 -> actionError = "请一次拖入一个存档"
            else ->
                try {
                    watchingJob = actions.controller.importSave(instance.id, dropped.single())
                } catch (failure: Exception) {
                    actionError = failure.message ?: "无法导入存档"
                }
        }
    }
    deleting?.let { entry ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("移入回收站？") },
            text = { Text("「${entry.name}」会移入回收站，可从回收站恢复。") },
            confirmButton = {
                TextButton({
                    deleting = null
                    actionError = null
                    actions.run {
                        val moved = actions.controller.deleteSave(instance.id, entry.name)
                        withContext(Dispatchers.Swing) {
                            if (!moved)
                                actionError = "「${entry.name}」没有移入回收站：存档可能已不存在，或回收站不可用；详情见日志。"
                            refresh++
                        }
                    }
                }) {
                    Text("移入回收站", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton({ deleting = null }) { Text("取消") } },
        )
    }
    deletingBackup?.let { backup ->
        AlertDialog(
            onDismissRequest = { deletingBackup = null },
            title = { Text("删除这个备份？") },
            text = {
                Text(
                    "「" +
                        backup.saveName +
                        "」（" +
                        formatSaveTime(backup.createdAt) +
                        "）的备份会移入回收站，可从回收站恢复。"
                )
            },
            confirmButton = {
                TextButton({
                    deletingBackup = null
                    actionError = null
                    actions.run {
                        val moved = actions.controller.deleteSaveBackup(instance.id, backup.id)
                        withContext(Dispatchers.Swing) {
                            if (!moved) actionError = "备份没有移入回收站：文件可能已不存在，或回收站不可用；详情见日志。"
                            refresh++
                        }
                    }
                }) {
                    Text("移入回收站", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton({ deletingBackup = null }) { Text("取消") } },
        )
    }
    replacing?.let { backup ->
        AlertDialog(
            onDismissRequest = { replacing = null },
            title = { Text("用备份替换原世界？") },
            text = {
                Text(
                    "当前世界「" + backup.saveName + "」会先备份到 backups/，再用这个备份替换它；" +
                        "当前世界会改名为「" + backup.saveName + "（替换前 时间）」保留，不会删除。"
                )
            },
            confirmButton = {
                TextButton({
                    replacing = null
                    startRestore(backup)
                }) {
                    Text("替换并恢复", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton({ replacing = null }) { Text("取消") } },
        )
    }
    copying?.let { entry ->
        CopySaveDialog(
            instance = instance,
            saveName = entry.name,
            actions = actions,
            onDismiss = { copying = null },
            onStarted = { jobId -> watchingJob = jobId },
        )
    }
    Column(
        modifier
            .fillMaxHeight()
            .contentDropTarget(
                enabled = canManage,
                hint = "请拖入存档 .zip 或世界文件夹",
                onError = { actionError = it },
            ) { dropped -> startImport(dropped) },
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ContentToolbar(
            flexible = { ToolbarSearchField(query, { query = it }, "搜索存档名称") },
            refresh =
                ToolbarAction("刷新", icon = Icons.Outlined.Refresh, enabled = !busy) {
                    actionError = null
                    refresh++
                },
            openFolder =
                ToolbarAction("打开文件夹", icon = Icons.Outlined.FolderOpen, enabled = !busy) {
                    actions.run { actions.controller.openFolder(instance.id, "saves") }
                },
        )
        Text(
            "${saves.size} 个存档 · ${backups.size} 个备份 · $savesDirectory",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (blockedNote.isNotBlank())
            Text(
                blockedNote,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        actionError?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.error) }
        // 刷新时保留旧列表，只在顶部显示一条细进度；首次读取才给骨架。
        if (loading && saves.isNotEmpty())
            LinearProgressIndicator(Modifier.fillMaxWidth().height(3.dp))
        val filtered = saves.filter { query.isBlank() || it.name.contains(query, true) }
        when {
            loading && saves.isEmpty() && backups.isEmpty() -> ListSkeleton()
            loadError != null ->
                Panel(Modifier.fillMaxWidth()) {
                    EmptyState("无法读取存档", loadError.orEmpty(), Icons.Outlined.CloudOff) {
                        TextButton({ refresh++ }) { Text("重试") }
                    }
                }
            else ->
                ScrollColumn(Modifier.weight(1f).fillMaxWidth()) {
                    when {
                        saves.isEmpty() ->
                            Panel(Modifier.fillMaxWidth()) {
                                EmptyState(
                                    "这个实例还没有存档",
                                    "在游戏里创建一个世界，用「复制到…」从别的实例复制，" +
                                        "或把存档 .zip / 世界文件夹拖进这个页面。",
                                    Icons.Outlined.Save,
                                )
                            }
                        filtered.isEmpty() ->
                            Panel(Modifier.fillMaxWidth()) {
                                EmptyState("没有匹配的存档", "换个关键词试试", Icons.Outlined.SearchOff) {
                                    TextButton({ query = "" }) { Text("清除搜索") }
                                }
                            }
                        else ->
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                filtered.forEach { entry ->
                                    key(entry.name) {
                                        SaveRow(
                                            entry = entry,
                                            canManage = canManage,
                                            canCopy = canCopy,
                                            onBackup = { startBackup(entry) },
                                            onCopy = { copying = entry },
                                            onDelete = { deleting = entry },
                                        )
                                    }
                                }
                            }
                    }
                    Panel(Modifier.fillMaxWidth()) {
                        SettingRow(
                            "恢复时替换原世界",
                            "关闭时恢复只解压成同级的新世界，不改动原存档；开启后先备份当前世界，再用备份替换它，" +
                                "当前世界改名为「（替换前 时间）」保留。",
                        ) {
                            CompactSwitch(
                                instance.replaceSaveOnRestore,
                                { enabled ->
                                    actionError = null
                                    runCatching {
                                            actions.controller.setReplaceSaveOnRestore(
                                                instance.id,
                                                enabled,
                                            )
                                        }
                                        .onFailure {
                                            actionError = it.message ?: "无法保存设置"
                                        }
                                },
                                enabled = !busy,
                            )
                        }
                    }
                    Panel(
                        Modifier.fillMaxWidth(),
                        title = "备份",
                        detail =
                            "与原版游戏共用 backups/ 目录（原版自己写的备份也会列在这里）；" +
                                "恢复默认解压成新世界，不直接覆盖。",
                    ) {
                        if (backups.isEmpty())
                            Text(
                                "还没有备份。存档行末尾的「备份」会把整个世界打包到这里。",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        else
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                backups.forEach { backup ->
                                    key(backup.id) {
                                        SaveBackupRow(
                                            backup = backup,
                                            busy = busy,
                                            onRestore = {
                                                val exists = saves.any {
                                                    it.name.equals(backup.saveName, true) ||
                                                        saveBaseName(it.name)
                                                            .equals(backup.saveName, true)
                                                }
                                                // 只有开启「恢复时替换原世界」且同名世界存在时才替换。
                                                if (instance.replaceSaveOnRestore && exists)
                                                    replacing = backup
                                                else startRestore(backup)
                                            },
                                            onDelete = { deletingBackup = backup },
                                        )
                                    }
                                }
                            }
                    }
                }
        }
    }
}

/** 一个存档行：44dp，名称 + 大小与修改时间，`hasLevelDat` 为 false 时给 11sp 提示。 */
@Composable
private fun SaveRow(
    entry: SaveEntry,
    canManage: Boolean,
    canCopy: Boolean,
    onBackup: () -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
) {
    val shape = RoundedCornerShape(Metrics.innerRadius)
    Surface(
        Modifier.fillMaxWidth().height(Metrics.rowHeight),
        shape = shape,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .55f)),
    ) {
        Row(
            Modifier.fillMaxWidth().hoverHighlight(shape, alpha = .04f).padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            LocalContentThumbnail(entry.path, world = true)
            Column(Modifier.weight(1f)) {
                Text(
                    entry.name,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    formatSaveSize(entry.size) + " · 修改于 " + formatSaveTime(entry.lastModified),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (!entry.hasLevelDat)
                Text(
                    "可能不是有效世界",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.error.copy(alpha = .85f),
                    maxLines = 1,
                )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                RowTextAction("备份", enabled = canManage, onClick = onBackup)
                RowTextAction("复制到…", enabled = canCopy, onClick = onCopy)
                RowIconAction(
                    Icons.Outlined.DeleteOutline,
                    "将「${entry.name}」移至回收站",
                    enabled = canManage,
                    onClick = onDelete,
                )
            }
        }
    }
}

/** 一个备份行：44dp，来源存档名 + 创建时间与大小；没有 level.dat 的压缩包不提供恢复。 */
@Composable
private fun SaveBackupRow(
    backup: SaveBackupInfo,
    busy: Boolean,
    onRestore: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth()
            .height(Metrics.rowHeight)
            .hoverHighlight(RoundedCornerShape(Metrics.innerRadius), alpha = .04f)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            Icons.Outlined.Archive,
            null,
            Modifier.size(18.dp),
            MaterialTheme.colorScheme.primary,
        )
        Column(Modifier.weight(1f)) {
            Text(
                backup.saveName,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                formatSaveTime(backup.createdAt) +
                    " · " +
                    formatSaveSize(backup.size) +
                    if (backup.restorable) "" else " · 不含 level.dat",
                fontSize = 12.sp,
                color =
                    if (backup.restorable) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.error.copy(alpha = .8f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            RowTextAction("恢复", enabled = !busy && backup.restorable, onClick = onRestore)
            RowIconAction(
                Icons.Outlined.DeleteOutline,
                "将备份移至回收站",
                enabled = !busy,
                onClick = onDelete,
            )
        }
    }
}

/**
 * 数据包页签：先选世界，再列出 `<世界>/datapacks/` 里的压缩包与文件夹（名称、大小、启用状态）。
 *
 * 支持安装 `.zip`（校验 `pack.mcmeta` 与 `pack_format`）、删除到回收站、打开文件夹、启用/禁用
 * （写 `level.dat` 的 `Data.Packs`）。`level.dat` 无法解析或世界正被打开时，开关禁用并在顶部说明原因，
 * 宁可只读也不冒险改世界。
 */
@Composable
private fun InstanceDatapacksTab(instance: GameInstance, actions: UiActions, modifier: Modifier) {
    var worlds by remember(instance.id) { mutableStateOf<List<SaveEntry>>(emptyList()) }
    var world by remember(instance.id) { mutableStateOf("") }
    var listing by remember(instance.id) { mutableStateOf<DatapackListing?>(null) }
    var loading by remember(instance.id) { mutableStateOf(true) }
    var loadError by remember(instance.id) { mutableStateOf<String?>(null) }
    var actionError by remember(instance.id) { mutableStateOf<String?>(null) }
    var refresh by remember(instance.id) { mutableIntStateOf(0) }
    var query by remember(instance.id) { mutableStateOf("") }
    var deleting by remember { mutableStateOf<DatapackEntry?>(null) }
    var working by remember(instance.id) { mutableStateOf<String?>(null) }
    val busy = actions.controller.isInstanceBusy(instance.id)
    val running = actions.controller.game.isRunning(instance.id)
    val canEdit = !busy && !running
    val blockedNote =
        when {
            running -> "游戏正在使用存档，请先退出游戏再管理数据包。"
            busy -> "任务进行中，数据包操作暂时不可用。"
            else -> ""
        }
    val savesDirectory = actions.controller.store.instancePath(instance.id).resolve("saves")
    val datapacksDirectory = savesDirectory.resolve(world).resolve("datapacks")
    // 数据包只属于真实世界目录，`saves/` 里的 .zip 存档没有可写的 datapacks 目录。
    LaunchedEffect(instance.id, refresh) {
        try {
            worlds =
                withContext(Dispatchers.IO) {
                    actions.controller.listSaves(instance.id).filter {
                        it.path.isDirectory() && it.hasLevelDat
                    }
                }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            worlds = emptyList()
            loadError = failure.message ?: "无法读取存档目录"
        }
        if (worlds.none { it.name == world }) world = worlds.firstOrNull()?.name.orEmpty()
    }
    LaunchedEffect(instance.id, world, refresh) {
        listing = null
        if (world.isBlank()) {
            loading = false
            return@LaunchedEffect
        }
        loading = true
        loadError = null
        try {
            listing =
                withContext(Dispatchers.IO) {
                    actions.controller.listDatapacks(instance.id, world)
                }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            loadError = failure.message ?: "无法读取数据包"
        } finally {
            loading = false
        }
    }
    val install = {
        actionError = null
        actions.run {
            val chosen =
                actions.choose("选择数据包 ZIP", extensions = listOf("zip")).firstOrNull()
            if (chosen != null) {
                try {
                    withContext(Dispatchers.IO) {
                        actions.controller.installDatapackFile(instance.id, world, chosen)
                    }
                    withContext(Dispatchers.Swing) { refresh++ }
                } catch (failure: Exception) {
                    withContext(Dispatchers.Swing) {
                        actionError = failure.message ?: "安装数据包失败"
                    }
                }
            }
        }
    }
    val toggle = { entry: DatapackEntry, enabled: Boolean ->
        actionError = null
        actions.run {
            withContext(Dispatchers.Swing) { working = entry.name }
            try {
                withContext(Dispatchers.IO) {
                    actions.controller.setDatapackEnabled(instance.id, world, entry.name, enabled)
                }
                withContext(Dispatchers.Swing) { refresh++ }
            } catch (failure: Exception) {
                withContext(Dispatchers.Swing) {
                    actionError = failure.message ?: "切换数据包状态失败"
                }
            } finally {
                withContext(Dispatchers.Swing) { working = null }
            }
        }
    }
    deleting?.let { entry ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("移入回收站？") },
            text = { Text("「${entry.name}」会从该存档的 datapacks 目录移入回收站，可从回收站恢复。") },
            confirmButton = {
                TextButton({
                    deleting = null
                    actionError = null
                    actions.run {
                        val moved =
                            withContext(Dispatchers.IO) {
                                runCatching {
                                        actions.controller.deleteDatapack(
                                            instance.id,
                                            world,
                                            entry.name,
                                        )
                                    }
                                    .getOrElse { failure ->
                                        withContext(Dispatchers.Swing) {
                                            actionError =
                                                failure.message ?: "删除数据包失败，详情见日志"
                                        }
                                        false
                                    }
                            }
                        withContext(Dispatchers.Swing) {
                            if (!moved && actionError == null)
                                actionError = "「${entry.name}」没有移入回收站：文件可能已不存在，或回收站不可用。"
                            refresh++
                        }
                    }
                }) {
                    Text("移入回收站", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton({ deleting = null }) { Text("取消") } },
        )
    }
    val current = listing
    Column(modifier.fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ContentToolbar(
            flexible = {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        "世界",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    CompactSelect(
                        world,
                        if (worlds.isEmpty()) listOf("" to "此实例还没有世界")
                        else worlds.map { it.name to it.name },
                        { world = it },
                        Modifier.weight(1f).height(ContentToolbarMetrics.height),
                        enabled = worlds.isNotEmpty() && !busy,
                    )
                }
            },
            importAction =
                ToolbarAction(
                    "安装数据包",
                    icon = Icons.Outlined.FileDownload,
                    enabled = world.isNotBlank() && canEdit,
                ) { install() },
            refresh =
                ToolbarAction("刷新", icon = Icons.Outlined.Refresh, enabled = !busy) {
                    actionError = null
                    refresh++
                },
            openFolder =
                ToolbarAction(
                    "打开文件夹",
                    icon = Icons.Outlined.FolderOpen,
                    enabled = world.isNotBlank() && !busy,
                ) {
                    actions.run { actions.controller.openWorldDatapacks(instance.id, world) }
                },
        )
        Text(
            if (world.isBlank()) "$savesDirectory"
            else "${current?.entries?.size ?: 0} 个数据包 · $datapacksDirectory",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (blockedNote.isNotBlank())
            Text(
                blockedNote,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        actionError?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.error) }
        if (loading && current != null)
            LinearProgressIndicator(Modifier.fillMaxWidth().height(3.dp))
        when {
            loadError != null ->
                Panel(Modifier.fillMaxWidth()) {
                    EmptyState("无法读取数据包", loadError.orEmpty(), Icons.Outlined.CloudOff) {
                        TextButton({ refresh++ }) { Text("重试") }
                    }
                }
            worlds.isEmpty() && !loading ->
                Panel(Modifier.fillMaxWidth()) {
                    EmptyState(
                        "这个实例还没有世界",
                        "先创建或复制一个世界，再回来管理它的数据包。",
                        Icons.Outlined.Save,
                    )
                }
            current != null && current.entries.isEmpty() ->
                Panel(Modifier.fillMaxWidth()) {
                    EmptyState(
                        "这个存档还没有数据包",
                        "点「安装数据包」选一个 .zip；也可以把文件夹放进 datapacks/。",
                        Icons.Outlined.FolderZip,
                    )
                }
            current != null -> {
                val filtered =
                    current.entries.filter { query.isBlank() || it.name.contains(query, true) }
                ScrollColumn(Modifier.weight(1f).fillMaxWidth()) {
                    if (current.note.isNotBlank())
                        Text(
                            current.note,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    if (filtered.isEmpty())
                        Panel(Modifier.fillMaxWidth()) {
                            EmptyState("没有匹配的数据包", "换个关键词试试", Icons.Outlined.SearchOff) {
                                TextButton({ query = "" }) { Text("清除搜索") }
                            }
                        }
                    else
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            filtered.forEach { entry ->
                                key(entry.name) {
                                    DatapackRow(
                                        entry = entry,
                                        toggleEnabled =
                                            current.toggleAvailable &&
                                                canEdit &&
                                                working == null &&
                                                entry.enabled != null,
                                        onToggle = { enabled -> toggle(entry, enabled) },
                                        deleteEnabled = canEdit && working == null,
                                        onDelete = { deleting = entry },
                                    )
                                }
                            }
                        }
                }
            }
        }
    }
}

/** 一个数据包行：44dp，图标 + 名称 + 大小与状态，行尾启用开关与「删除」。 */
@Composable
private fun DatapackRow(
    entry: DatapackEntry,
    toggleEnabled: Boolean,
    onToggle: (Boolean) -> Unit,
    deleteEnabled: Boolean,
    onDelete: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth()
            .height(Metrics.rowHeight)
            .hoverHighlight(RoundedCornerShape(Metrics.innerRadius), alpha = .04f)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            if (entry.directory) Icons.Outlined.Folder else Icons.Outlined.FolderZip,
            null,
            Modifier.size(18.dp),
            MaterialTheme.colorScheme.primary,
        )
        Column(Modifier.weight(1f)) {
            Text(entry.name, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                (if (entry.directory) "文件夹" else "压缩包") +
                    " · " +
                    formatSaveSize(entry.size) +
                    " · " +
                    when (entry.enabled) {
                        true -> "已启用"
                        false -> "已禁用"
                        null -> "状态未知"
                    },
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        CompactSwitch(
            entry.enabled == true,
            { onToggle(entry.enabled != true) },
            enabled = toggleEnabled,
        )
        RowIconAction(
            Icons.Outlined.DeleteOutline,
            "将数据包移至回收站",
            enabled = deleteEnabled,
            onClick = onDelete,
        )
    }
}

/** 存档名去掉 `.zip` 后缀（不区分大小写），用于同名判断；只影响展示逻辑。 */
private fun saveBaseName(name: String): String =
    if (name.endsWith(".zip", true)) name.dropLast(4) else name

/** 存档相关时间：本地时区 `yyyy-MM-dd HH:mm`；缺失时如实说明，不伪造时间。 */
private val saveTimeFormatter: java.time.format.DateTimeFormatter =
    java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        .withZone(java.time.ZoneId.systemDefault())

private fun formatSaveTime(time: java.time.Instant?): String =
    time?.let { runCatching { saveTimeFormatter.format(it) }.getOrNull() } ?: "未知时间"

private fun formatSaveSize(bytes: Long): String =
    when {
        bytes >= 1073741824L -> "%.1f GB".format(bytes / 1073741824f)
        bytes >= 1048576L -> "%.1f MB".format(bytes / 1048576f)
        bytes >= 1024L -> "%.1f KB".format(bytes / 1024f)
        else -> bytes.toString() + " B"
    }

/** 目录项：路径、是否为目录、字节大小与「yyyy-MM-dd HH:mm」格式的修改时间。 */
private data class FolderEntry(
    val path: Path,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val modified: String,
)

/** 内容文件夹浏览：原理图专用（可导入、可逐级进入、可删除）；大小与修改时间来自文件系统。 */
@Composable
private fun InstanceFolderBrowser(
    instance: GameInstance,
    actions: UiActions,
    folder: String,
    modifier: Modifier,
    importExtensions: List<String> = emptyList(),
    importLabel: String = "",
    allowDelete: Boolean = false,
) {
    var entries by remember { mutableStateOf<List<FolderEntry>>(emptyList()) }
    var refresh by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    // 目录读取期间先显示骨架，读完再决定空态，避免空态闪一下被误判成“目录是空的”。
    var loading by remember(instance.id, folder) { mutableStateOf(true) }
    var deletingFile by remember { mutableStateOf<Path?>(null) }
    // 子目录浏览只在前端维护，不改实例目录结构；返回上一级时清空栈。
    var subPath by remember(instance.id, folder) { mutableStateOf<List<String>>(emptyList()) }
    val busy = actions.controller.isInstanceBusy(instance.id)
    val liveState by actions.controller.state.collectAsState()
    val root = actions.controller.store.instancePath(instance.id).resolve(folder)
    val directory =
        if (subPath.isEmpty()) root
        else subPath.fold(root) { path, segment -> path.resolve(segment) }
    LaunchedEffect(instance.id, folder, refresh, liveState.jobs.map { it.status }, directory) {
        loading = true
        error = null
        try {
            entries = withContext(Dispatchers.IO) { readFolder(directory) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            entries = emptyList()
            error = failure.message
        } finally {
            loading = false
        }
    }
    // 原理图删除会移入回收站，这里先确认再执行。
    deletingFile?.let { file ->
        AlertDialog(
            onDismissRequest = { deletingFile = null },
            title = { Text("移至回收站？") },
            text = { Text("「" + file.name + "」会从原理图目录移至回收站，需要时可以再找回。") },
            confirmButton = {
                TextButton({
                    val name = file.name
                    deletingFile = null
                    actions.run {
                        actions.controller.requireIdle(instance.id)
                        actions.controller.content.deleteSchematic(instance.id, name)
                        withContext(Dispatchers.Swing) { refresh++ }
                    }
                }) {
                    Text("移至回收站", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton({ deletingFile = null }) { Text("取消") } },
        )
    }
    Column(modifier.fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ContentToolbar(
            leading = {
                if (subPath.isNotEmpty())
                    ToolbarIconAction(Icons.Outlined.ArrowUpward, "返回上一级目录") {
                        subPath = subPath.dropLast(1)
                        query = ""
                    }
            },
            flexible = { ToolbarSearchField(query, { query = it }, "在文件夹内搜索") },
            importAction =
                ToolbarAction(
                    importLabel.ifBlank { "导入" },
                    icon = Icons.Outlined.FileDownload,
                    enabled = !busy,
                ) {
                    actions.run {
                        val chosen =
                            actions.choose(
                                importLabel.ifBlank { "选择文件" },
                                multiple = true,
                                extensions = importExtensions,
                            )
                        if (chosen.isNotEmpty()) {
                            actions.controller.content.importSchematics(instance.id, chosen)
                            withContext(Dispatchers.Swing) { refresh++ }
                        }
                    }
                },
            refresh =
                ToolbarAction("刷新", icon = Icons.Outlined.Refresh, enabled = !busy) { refresh++ },
            openFolder =
                ToolbarAction("打开文件夹", icon = Icons.Outlined.FolderOpen) {
                    actions.run { actions.controller.openFolder(instance.id, folder) }
                },
        )
        Text(
            directory.toString(),
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (loading && entries.isNotEmpty())
            LinearProgressIndicator(Modifier.fillMaxWidth().height(3.dp))
        val filtered = entries.filter { query.isBlank() || it.path.name.contains(query, true) }
        val list: @Composable () -> Unit = {
            when {
                loading && entries.isEmpty() -> ListSkeleton()
                error != null ->
                    Panel(Modifier.fillMaxWidth()) {
                        EmptyState("无法读取这个目录", error.orEmpty(), Icons.Outlined.CloudOff) {
                            TextButton({ refresh++ }) { Text("重试") }
                        }
                    }
                entries.isEmpty() ->
                    Panel(Modifier.fillMaxWidth()) {
                        EmptyState(
                            if (subPath.isEmpty()) "这个目录还没有文件" else "这个子目录是空的",
                            "游戏生成或安装的内容会显示在这里。",
                            Icons.Outlined.FolderOpen,
                        )
                    }
                filtered.isEmpty() ->
                    Panel(Modifier.fillMaxWidth()) {
                        EmptyState("没有匹配的文件", "换个关键词试试", Icons.Outlined.SearchOff) {
                            TextButton({ query = "" }) { Text("清除搜索") }
                        }
                    }
                else ->
                    LazyColumn(
                        Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        items(filtered, key = { it.path.toString() }) { entry ->
                            FolderRow(
                                entry,
                                modifier = Modifier.animateItem(),
                                allowDelete = allowDelete && !entry.isDirectory,
                                onOpen = {
                                    if (entry.isDirectory) {
                                        subPath = subPath + entry.path.name
                                        query = ""
                                    }
                                },
                                onDelete = { deletingFile = entry.path },
                                busy = actions.controller.isInstanceBusy(instance.id),
                            )
                        }
                    }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            // 进入 / 返回子目录时整块淡入并轻微位移 4dp，告诉用户“换了一层目录”。
            if (LocalAnimationsEnabled.current)
                AnimatedContent(
                    subPath,
                    label = "folder-drill",
                    transitionSpec = {
                        (fadeIn(tween(150)) +
                            slideInHorizontally(tween(150)) { it / 24 }) togetherWith
                            fadeOut(tween(120))
                    },
                ) {
                    Box(Modifier.fillMaxSize()) { list() }
                }
            else Box(Modifier.fillMaxSize()) { list() }
        }
    }
}

/** 读取目录项：目录在前，其余按名称排序；大小与修改时间一次性在 IO 线程读取。 */
private fun readFolder(directory: Path): List<FolderEntry> {
    if (!directory.isDirectory()) return emptyList()
    return directory
        .listDirectoryEntries()
        .map { entry ->
            FolderEntry(
                path = entry,
                isDirectory = entry.isDirectory(),
                sizeBytes =
                    runCatching { if (entry.isRegularFile()) Files.size(entry) else 0L }
                        .getOrDefault(0L),
                modified =
                    runCatching {
                            java.time.Instant.ofEpochMilli(
                                    Files.getLastModifiedTime(entry).toMillis()
                                )
                                .toString()
                                .replace('T', ' ')
                                .take(16)
                        }
                        .getOrDefault(""),
            )
        }
        .sortedWith(compareBy({ !it.isDirectory }, { it.path.name.lowercase() }))
}

@Composable
private fun FolderRow(
    entry: FolderEntry,
    modifier: Modifier,
    allowDelete: Boolean,
    busy: Boolean,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
) {
    val shape = RoundedCornerShape(Metrics.cardRadius)
    Surface(
        modifier.fillMaxWidth().heightIn(min = 52.dp).pressScale(),
        shape = shape,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .55f)),
    ) {
        Row(
            Modifier.fillMaxWidth()
                .hoverHighlight(shape, alpha = .04f)
                .then(if (entry.isDirectory) Modifier.clickable(onClick = onOpen) else Modifier)
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (entry.isDirectory) Icons.Outlined.Folder else Icons.Outlined.Description,
                null,
                Modifier.size(19.dp),
                MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(
                    entry.path.name,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    listOf(
                            if (entry.isDirectory) "文件夹" else formatFileSize(entry.sizeBytes),
                            if (entry.modified.isBlank()) "" else "修改于 " + entry.modified,
                        )
                        .filter { it.isNotBlank() }
                        .joinToString(" · "),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (entry.isDirectory)
                Icon(
                    Icons.Outlined.ChevronRight,
                    null,
                    Modifier.size(16.dp),
                    MaterialTheme.colorScheme.onSurfaceVariant,
                )
            if (allowDelete)
                RowIconAction(
                    Icons.Outlined.DeleteOutline,
                    "移至回收站 " + entry.path.name,
                    enabled = !busy,
                    onClick = onDelete,
                )
        }
    }
}

private fun formatFileSize(bytes: Long): String =
    when {
        bytes >= 1048576 -> "%.1f MB".format(bytes / 1048576f)
        bytes >= 1024 -> "%.1f KB".format(bytes / 1024f)
        else -> bytes.toString() + " B"
    }

/** 列表骨架：3 行灰条，首次加载时占位，避免内容区高度跳变。 */
@Composable
private fun ListSkeleton() {
    val bar = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .16f)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(3) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(19.dp).clip(RoundedCornerShape(4.dp)).background(bar))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Box(
                        Modifier.fillMaxWidth(.4f)
                            .height(12.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(bar)
                    )
                    Box(
                        Modifier.fillMaxWidth(.7f)
                            .height(9.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(bar)
                    )
                }
            }
        }
    }
}
