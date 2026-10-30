// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 组织实例详情导航、设置表单与各内容页签。

package dev.molifox.desktop

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.onClick
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.Architecture
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.key
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.molifox.core.GameInstance
import dev.molifox.core.InstanceMemoryPolicy
import dev.molifox.core.IsolationSource
import dev.molifox.core.JavaRuntime
import dev.molifox.core.LauncherState
import dev.molifox.core.MAX_GAME_WINDOW_SIZE
import dev.molifox.core.MIN_GAME_WINDOW_SIZE
import dev.molifox.core.MIN_INSTANCE_MEMORY_MB
import dev.molifox.core.PackFolder
import dev.molifox.core.VersionIsolation
import dev.molifox.core.applyInstanceMemoryPolicy
import dev.molifox.core.domain.instance.GameFolder
import dev.molifox.core.domain.instance.instanceOperationAvailability
import dev.molifox.core.effectiveGameMemory
import dev.molifox.core.instanceLaunchNumberField
import dev.molifox.core.instanceMemoryLimitMb
import dev.molifox.core.instanceMemoryPolicy
import dev.molifox.core.instanceMemorySliderValue
import dev.molifox.core.normalizeInstanceLaunchNumberInput
import dev.molifox.core.parseInstanceLaunchNumberInput
import dev.molifox.core.validateInstanceDirectoryName
import dev.molifox.core.validateInstanceLaunchOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext
import kotlin.io.path.name

/** 实例详情页签：左侧二级列表的 7 个页签。 */
private data class InstanceTab(
    val title: String,
    val icon: ImageVector,
)

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
            if (instanceMemoryPolicy(instance) == InstanceMemoryPolicy.Manual) {
                instance.memoryMb
            } else {
                0
            },
        )
    }
    var initial by remember(instance.id) { mutableStateOf(instance.initialMemoryMb.toString()) }
    // 窗口宽高留空表示跟随全局：0 不显示成字面 0，输入 0 也归一化成空。
    var width by remember(instance.id) { mutableStateOf(instanceLaunchNumberField(instance.width)) }
    var height by remember(
        instance.id,
    ) { mutableStateOf(instanceLaunchNumberField(instance.height)) }
    var foldersOpen by remember { mutableStateOf(false) }
    // 「管理」每次进入页面都从收起开始，不持久化展开状态。
    var manageOpen by remember(instance.id) { mutableStateOf(false) }
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
    if (screenshotGallery) ScreenshotGallery(instance, actions) { screenshotGallery = false }
    if (editingIcon) InstanceIconDialog(instance, actions) { editingIcon = false }
    LaunchedEffect(instance) {
        draft =
            draft.copy(
                name = if (draft.name == syncedInstance.name) instance.name else draft.name,
                isolation =
                    if (draft.isolation == syncedInstance.isolation) {
                        instance.isolation
                    } else {
                        draft.isolation
                    },
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
        if (draft.name.trim() != instance.name) {
            runCatching { validateInstanceDirectoryName(draft.name.trim()) }
                .exceptionOrNull()
                ?.message
        } else {
            null
        }
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
        }.exceptionOrNull()
            ?.message

    // 单项校验：失焦时只回退自己这一格，不误伤其它已经改好的字段。
    fun invalidWindowField(value: Int?): Boolean =
        value == null ||
            (value != 0 && value !in MIN_GAME_WINDOW_SIZE..MAX_GAME_WINDOW_SIZE)

    fun extraArgumentError(
        jvm: String,
        game: String,
    ): String? =
        runCatching {
            validateInstanceLaunchOptions(
                memoryMb = 0,
                width = 0,
                height = 0,
                windowMode = "inherit",
                extraJvmArgs = jvm,
                extraGameArgs = game,
            )
        }.exceptionOrNull()
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
                    val currentName =
                        actions.controller.store
                            .instance(instance.id)
                            .name
                    actions.controller.saveInstanceConfiguration(
                        editSnapshot.copy(name = currentName),
                        notify = false,
                    )
                }
            }.exceptionOrNull()
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
                        val currentName =
                            actions.controller.store
                                .instance(snapshot.id)
                                .name
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
                if (problem != null) {
                    "名称无效，已恢复为「${instance.name}」：$problem"
                } else {
                    "请等待当前任务结束后再重命名。"
                }
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
                }.exceptionOrNull()
                    ?.message
            withContext(Dispatchers.Swing) {
                nameCommitting = false
                if (failure == null) {
                    nameNotice = null
                    saveError = null
                } else {
                    nameNotice =
                        if (renamed) {
                            "名称已改为「$trimmed」，但其它设置未能保存：$failure"
                        } else {
                            "重命名失败，已恢复为「${instance.name}」：$failure"
                        }
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
    val isolated = actions.controller.store.isolated(instance)
    val availability =
        instanceOperationAvailability(isolated, instance.externalProfile, instanceBusy)
    val canMaintainInstance = availability.maintain
    val canMoveInstance = availability.move
    val moveBlockedNote = availability.moveBlockedNote
    var existingFolders by remember(instance.id) { mutableStateOf<List<GameFolder>>(emptyList()) }
    var foldersError by remember(instance.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(instance.id, foldersOpen, state.jobs.map { it.id to it.status }) {
        if (!foldersOpen) return@LaunchedEffect
        actions.controller.instanceWorkspace.existingFolders(instance.id).fold(
            onSuccess = {
                existingFolders = it
                foldersError = null
            },
            onFailure = { foldersError = it.message ?: "无法读取实例目录" },
        )
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
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .6f),
                )
                Spacer(Modifier.height(2.dp))
                InstanceRailItem(
                    "游戏文件夹",
                    Icons.Outlined.FolderOpen,
                    foldersOpen,
                    onClick = { foldersOpen = !foldersOpen },
                )
                if (foldersOpen) {
                    foldersError?.let { MutedText(it) }
                    existingFolders.forEach { gameFolder ->
                        RailSubEntry(gameFolder.title) {
                            actions.run {
                                actions.controller.openFolder(instance.id, gameFolder.relativePath)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(2.dp))
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .6f),
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
                        "移动实例",
                        Icons.AutoMirrored.Outlined.DriveFileMove,
                        enabled = canMoveInstance,
                        hint = moveBlockedNote,
                    ) { onMoveInstance(instance) }
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
                                            if (nameHint != null) {
                                                Text(
                                                    nameHint,
                                                    fontSize = 11.sp,
                                                    color = MaterialTheme.colorScheme.error,
                                                )
                                            }
                                            SecondaryButton(
                                                { editingIcon = true },
                                                enabled = !instanceBusy,
                                            ) {
                                                Text("更换实例图标…", fontSize = 12.sp)
                                            }
                                        }
                                    }
                                }
                                CompactSettingsSection(
                                    "Java 与内存",
                                    "实例可单独指定 Java；内存数值与窗口在下方「启动设置」。",
                                ) {
                                    // 紧凑行：继承时右侧显示全局实际解析到的运行时，点击打开抽屉。
                                    JavaPickerRow(
                                        value = draft.javaPath,
                                        runtimes = javaRuntimes,
                                        inheritedPath = state.data.settings.javaPath,
                                        ignoreCase =
                                            actions.controller.platform.osName == "windows",
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
                                                            ) {
                                                                memoryMb
                                                            } else {
                                                                0
                                                            },
                                                    ),
                                                    when (mode) {
                                                        "automatic" ->
                                                            InstanceMemoryPolicy.Automatic
                                                        "manual" -> InstanceMemoryPolicy.Manual
                                                        else -> InstanceMemoryPolicy.Inherit
                                                    },
                                                    state.data.settings.memoryMb,
                                                )
                                            draft = next
                                            memoryMb = next.memoryMb
                                        },
                                    )
                                    if (memoryPolicy == InstanceMemoryPolicy.Automatic) {
                                        MutedText("启动时根据系统可用内存分配；切回手动分配时按全局最大值重新设置。")
                                    }
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
                                        if (memoryPolicy == InstanceMemoryPolicy.Manual) {
                                            memoryMb
                                                .coerceIn(
                                                    MIN_INSTANCE_MEMORY_MB,
                                                    memoryLimit,
                                                ).toFloat()
                                        } else {
                                            effectiveMemory
                                                .coerceIn(MIN_INSTANCE_MEMORY_MB, memoryLimit)
                                                .toFloat()
                                        }
                                    val memoryRange =
                                        MIN_INSTANCE_MEMORY_MB.toFloat()..memoryLimit.toFloat()
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
                                                if ((initial.toIntOrNull() ?: 0) > next) {
                                                    initial = next.toString()
                                                }
                                            },
                                            Modifier.weight(1f).semantics {
                                                contentDescription = "实例最大内存滑块"
                                            },
                                            valueRange = memoryRange,
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
                                                if (!focused && invalidWindowField(widthValue)) {
                                                    width =
                                                        instanceLaunchNumberField(instance.width)
                                                }
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
                                                if (!focused && invalidWindowField(heightValue)) {
                                                    height =
                                                        instanceLaunchNumberField(instance.height)
                                                }
                                            },
                                        )
                                    }
                                    SettingRow("全屏", "关闭时按窗口尺寸显示") {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            if (draft.windowMode != "inherit") {
                                                TextButton({
                                                    draft = draft.copy(windowMode = "inherit")
                                                }) {
                                                    Text("跟随全局", fontSize = 11.sp)
                                                }
                                            }
                                            CompactSwitch(
                                                checked = fullscreenNow,
                                                onCheckedChange = { checked ->
                                                    draft =
                                                        draft.copy(
                                                            windowMode =
                                                                if (checked) {
                                                                    "fullscreen"
                                                                } else {
                                                                    "windowed"
                                                                },
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
                                            ) {
                                                draft =
                                                    draft.copy(
                                                        extraJvmArgs = instance.extraJvmArgs,
                                                    )
                                            }
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
                                            ) {
                                                draft =
                                                    draft.copy(
                                                        extraGameArgs = instance.extraGameArgs,
                                                    )
                                            }
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
                                                    (
                                                        if (state.data.settings.isolateVersions) {
                                                            "版本隔离"
                                                        } else {
                                                            "共用游戏目录"
                                                        }
                                                    ) +
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
                                                        ) {
                                                            "继承全局设置（自动推断为版本隔离）"
                                                        } else {
                                                            it.title
                                                        }
                                                },
                                                { key ->
                                                    draft =
                                                        draft.copy(
                                                            isolation =
                                                                VersionIsolation.valueOf(
                                                                    key,
                                                                ),
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
                                        }.getOrDefault("目录暂不可用"),
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
                                if (state.data.settings.pages.showAdvancedLaunchOptions) {
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
                                                ) {
                                                    draft =
                                                        draft.copy(
                                                            extraJvmArgs = instance.extraJvmArgs,
                                                        )
                                                }
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
                                                                if (enabled) {
                                                                    state.data.settings
                                                                        .advancedLaunch
                                                                        .copy()
                                                                } else {
                                                                    null
                                                                },
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
                                        ).copy(
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
                                        ?: if (!advancedValid) {
                                            "高级启动参数未通过校验。"
                                        } else {
                                            null
                                        }
                                if (problem != null) {
                                    Text(
                                        problem,
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }
                                Spacer(Modifier.height(6.dp))
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
    if (renaming) {
        var name by remember { mutableStateOf(instance.name) }
        var renameError by remember { mutableStateOf("") }
        val trimmed = name.trim()
        val validation =
            if (trimmed == instance.name) {
                null
            } else {
                runCatching { validateInstanceDirectoryName(trimmed) }.exceptionOrNull()?.message
            }
        val currentDirectory =
            runCatching {
                actions.controller.store
                    .versionGamePath(instance)
                    .fileName
                    .toString()
            }.getOrDefault(instance.gameDirectoryName)
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
                            trimmed == instance.name ->
                                "输入新的名称；保存时会把 versions/$currentDirectory 一并改名。"
                            actions.controller.isInstanceBusy(instance.id) ->
                                "请等待当前任务结束后再重命名。"
                            else ->
                                "会把 versions/$currentDirectory 重命名为 versions/$trimmed，" +
                                    "并同步同名 JSON/JAR、" +
                                    "版本配置和其他版本对它的引用。"
                        },
                        fontSize = 11.sp,
                        color =
                            if (validation != null || renameError.isNotBlank()) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
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
                        if (failure == null) {
                            renaming = false
                        } else {
                            renameError = failure.message ?: "重命名失败"
                        }
                    },
                ) {
                    Text("保存")
                }
            },
            dismissButton = { TextButton({ renaming = false }) { Text("取消") } },
        )
    }
    if (exportingMrpack) {
        ExportMrpackDialog(
            instance,
            actions,
            { exportingMrpack = false },
            { actions.taskQueued() },
        )
    }
    if (javaSheetOpen) {
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
                        if (!actions.controller.isInstanceBusy(instance.id)) {
                            actions.controller.saveInstanceConfiguration(
                                instance.copy(javaPath = path),
                            )
                        }
                    }.exceptionOrNull()
                        ?.message
            },
            onDismiss = { javaSheetOpen = false },
        )
    }
}
