// 负责实例设置、组件、模组、存档和原理图页面。

package dev.molifox.desktop

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.*
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.molifox.core.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.*
import kotlinx.coroutines.*
import kotlinx.coroutines.swing.Swing
import org.jetbrains.skia.Image as SkiaImage

/** 实例详情页签：左侧二级列表的 5 个页签。 */
private data class InstanceTab(val title: String, val icon: ImageVector)

private val instanceTabs =
    listOf(
        InstanceTab("设置", Icons.Outlined.Tune),
        InstanceTab("模组", Icons.Outlined.Extension),
        InstanceTab("资源包", Icons.Outlined.Palette),
        InstanceTab("存档", Icons.Outlined.Save),
        InstanceTab("原理图", Icons.Outlined.Architecture),
    )

/** 实例详情：页头 + 左侧页签栏与右侧内容区，页签为设置 / 模组 / 资源包 / 存档 / 原理图。 */
@Composable
fun ColumnScope.InstanceDetailsPage(
    instance: GameInstance,
    state: LauncherState,
    actions: UiActions,
    onBack: () -> Unit,
    onDelete: () -> Unit,
    navigate: (Page) -> Unit,
    onCopyInstance: (GameInstance) -> Unit = {},
    onTidyInstance: (GameInstance) -> Unit = {},
    onVerifyInstance: (GameInstance) -> Unit = {},
    onUpgradePack: (GameInstance) -> Unit = {},
    onMoveInstance: (GameInstance) -> Unit = {},
) {
    var tab by remember { mutableIntStateOf(0) }
    var draft by remember(instance.id) { mutableStateOf(instance) }
    var syncedInstance by remember(instance.id) { mutableStateOf(instance) }
    var memory by remember(instance.id) { mutableStateOf(instance.memoryMb.toString()) }
    var initial by remember(instance.id) { mutableStateOf(instance.initialMemoryMb.toString()) }
    var width by remember(instance.id) { mutableStateOf(instance.width.toString()) }
    var height by remember(instance.id) { mutableStateOf(instance.height.toString()) }
    var foldersOpen by remember { mutableStateOf(false) }
    var manageOpen by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var exportingMrpack by remember { mutableStateOf(false) }
    var editingIcon by remember(instance.id) { mutableStateOf(false) }
    var screenshotGallery by remember(instance.id) { mutableStateOf(false) }
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
    val effectiveMemory =
        if (
            draft.autoMemory == true ||
                (draft.autoMemory == null && memory == "0" && state.data.settings.autoMemory)
        )
            maxOf(512, actions.controller.platform.totalMemoryMb - 512)
        else memory.toIntOrNull()?.takeIf { it > 0 } ?: state.data.settings.memoryMb
    val valid =
        advancedValid &&
            draft.name.isNotBlank() &&
            (memory.toIntOrNull() ?: -1).let {
                it == 0 || it in 512..maxOf(512, actions.controller.platform.totalMemoryMb - 512)
            } &&
            (initial.toIntOrNull() ?: -1).let { it == 0 || it in 128..effectiveMemory } &&
            (width.toIntOrNull() ?: -1).let { it == 0 || it in 640..7680 } &&
            (height.toIntOrNull() ?: -1).let { it == 0 || it in 480..4320 }
    val dirty =
        draft != instance ||
            memory != instance.memoryMb.toString() ||
            initial != instance.initialMemoryMb.toString() ||
            width != instance.width.toString() ||
            height != instance.height.toString()
    val launchEnabled = instance.installed && !actions.controller.isInstanceBusy(instance.id)
    // 实例工具与整合包升级的可用性：共用游戏目录、外部目录由原目录维护，不提供这些入口。
    val isolated = actions.controller.store.isolated(instance)
    val canCopyInstance =
        isolated && !instance.externalProfile && !actions.controller.isInstanceBusy(instance.id)
    val canMaintainInstance = isolated && !actions.controller.isInstanceBusy(instance.id)
    val canUpgradePack =
        instance.packFile.isNotBlank() &&
            instance.installed &&
            !actions.controller.isInstanceBusy(instance.id)
    // 跨游戏目录移动：外部目录由原启动器维护，共用游戏目录没有独立目录可搬。
    val canMoveInstance =
        isolated && !instance.externalProfile && !actions.controller.isInstanceBusy(instance.id)
    val moveBlockedNote =
        when {
            instance.externalProfile -> "外部游戏目录的实例由原启动器维护，不能移动"
            !isolated -> "共用游戏目录没有独立目录，请先启用版本隔离"
            actions.controller.isInstanceBusy(instance.id) -> "请等待当前任务结束"
            else -> ""
        }
    val launch: () -> Unit = {
        if (state.data.activeAccountId == null) {
            actions.controller.store.notify("请先添加一个游戏账户")
            navigate(Page.Home)
        } else {
            actions.run {
                actions.controller.selectInstance(instance.id)
                actions.controller.launch(instance.id)
            }
            actions.taskQueued()
        }
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
                Tag(if (instance.installed) "已安装" else "需要修复")
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
        if (dirty)
            Text(
                if (valid) "有未保存的修改" else "设置未通过校验",
                fontSize = 11.sp,
                color =
                    if (valid) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.error,
            )
        Spacer(Modifier.width(10.dp))
        SecondaryButton(
            {
                actions.run {
                    actions.controller.saveInstanceConfiguration(
                        draft.copy(
                            memoryMb = memory.toInt(),
                            initialMemoryMb = initial.toInt(),
                            width = width.toInt(),
                            height = height.toInt(),
                        )
                    )
                    withContext(Dispatchers.Swing) { onBack() }
                }
            },
            enabled = valid && !actions.controller.isInstanceBusy(instance.id) && dirty,
        ) {
            Icon(Icons.Outlined.Check, null, Modifier.size(15.dp))
            Spacer(Modifier.width(5.dp))
            Text("保存设置", fontSize = 12.sp)
        }
        Spacer(Modifier.width(8.dp))
        PrimaryButton("启动", Icons.Outlined.PlayArrow, launchEnabled, launch)
        Spacer(Modifier.width(4.dp))
        var menu by remember { mutableStateOf(false) }
        Box {
            HeaderIconButton(Icons.Outlined.MoreHoriz, "更多操作") { menu = true }
            DropdownMenu(menu, { menu = false }, Modifier.heightIn(max = 380.dp)) {
                DropdownMenuItem(
                    { Text("查看启动参数", fontSize = 13.sp) },
                    {
                        menu = false
                        actions.previewLaunch(instance.id)
                    },
                    enabled = instance.installed && !actions.controller.isInstanceBusy(instance.id),
                )
                DropdownMenuItem(
                    { Text("打开游戏目录", fontSize = 13.sp) },
                    {
                        menu = false
                        actions.run { actions.controller.openFolder(instance.id) }
                    },
                )
                DropdownMenuItem(
                    { Text("导出整合包…", fontSize = 13.sp) },
                    {
                        menu = false
                        exportingMrpack = true
                    },
                    enabled = !actions.controller.isInstanceBusy(instance.id),
                )
                DropdownMenuItem(
                    { Text("更换实例图标…", fontSize = 13.sp) },
                    {
                        menu = false
                        editingIcon = true
                    },
                    enabled = !actions.controller.isInstanceBusy(instance.id),
                )
                DropdownMenuItem(
                    { Text("管理游戏截图…", fontSize = 13.sp) },
                    {
                        menu = false
                        screenshotGallery = true
                    },
                )
                DropdownMenuItem(
                    { Text("完整实例备份 ZIP…", fontSize = 13.sp) },
                    {
                        menu = false
                        actions.exportInstance(instance)
                    },
                    enabled = instance.installed && !actions.controller.isInstanceBusy(instance.id),
                )
                DropdownMenuItem(
                    { Text("复制实例", fontSize = 13.sp) },
                    {
                        menu = false
                        onCopyInstance(instance)
                    },
                    enabled = canCopyInstance,
                )
                DropdownMenuItem(
                    { Text("整理实例目录", fontSize = 13.sp) },
                    {
                        menu = false
                        onTidyInstance(instance)
                    },
                    enabled = canMaintainInstance,
                )
                DropdownMenuItem(
                    { Text("校验实例", fontSize = 13.sp) },
                    {
                        menu = false
                        onVerifyInstance(instance)
                    },
                    enabled = canMaintainInstance,
                )
                DropdownMenuItem(
                    { Text("升级整合包…", fontSize = 13.sp) },
                    {
                        menu = false
                        onUpgradePack(instance)
                    },
                    enabled = canUpgradePack,
                )
                // 移动实例需要独立的实例目录；不可用时在条目里直接说明原因，而不是静默禁用。
                DropdownMenuItem(
                    {
                        Column {
                            Text("移动到其他游戏目录…", fontSize = 13.sp)
                            if (moveBlockedNote.isNotBlank())
                                Text(
                                    moveBlockedNote,
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                        }
                    },
                    {
                        menu = false
                        onMoveInstance(instance)
                    },
                    enabled = canMoveInstance,
                )
                DropdownMenuItem(
                    { Text("重命名实例", fontSize = 13.sp) },
                    {
                        menu = false
                        renaming = true
                    },
                )
                DropdownMenuItem(
                    {
                        Text(
                            "删除实例",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.error,
                        )
                    },
                    {
                        menu = false
                        onDelete()
                    },
                )
            }
        }
    }
    // ---------------- 左侧页签 + 右侧内容 ----------------
    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        // 左侧页签整栏套磨砂面板：宽度、条目尺寸与点击行为都不变。
        FrostedPanel(Modifier.width(158.dp).fillMaxHeight()) {
            ScrollColumn(Modifier.fillMaxSize(), gap = 3.dp) {
                instanceTabs.forEachIndexed { index, item ->
                    InstanceRailItem(item.title, item.icon, tab == index) { tab = index }
                }
                Spacer(Modifier.height(6.dp))
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .6f)
                )
                Spacer(Modifier.height(6.dp))
                InstanceRailItem(
                    "游戏文件夹",
                    Icons.Outlined.FolderOpen,
                    foldersOpen,
                    onClick = {
                        foldersOpen = !foldersOpen
                        if (foldersOpen) manageOpen = false
                    },
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
                InstanceRailItem(
                    "管理",
                    Icons.Outlined.MoreHoriz,
                    manageOpen,
                    onClick = {
                        manageOpen = !manageOpen
                        if (manageOpen) foldersOpen = false
                    },
                )
                if (manageOpen) {
                    RailSubEntry("重命名实例") { renaming = true }
                    RailSubEntry("删除实例") { onDelete() }
                    RailSubEntry("导出整合包") { exportingMrpack = true }
                }
            }
        }
        Box(Modifier.weight(1f).fillMaxHeight()) {
            val page: @Composable (Int) -> Unit = { index ->
                Column(Modifier.fillMaxSize()) {
                    when (index) {
                        1 ->
                            InstanceMods(instance, actions, Modifier.weight(1f)) {
                                actions.run { actions.controller.selectInstance(instance.id) }
                                navigate(Page.Discover)
                            }
                        2 ->
                            InstanceFolderBrowser(
                                instance,
                                actions,
                                "resourcepacks",
                                Modifier.weight(1f),
                            )
                        3 -> InstanceSavesTab(instance, actions, Modifier.weight(1f))
                        4 ->
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
                                CompactSettingsSection("Java 与内存", "数值 0 或空路径表示使用全局设置。") {
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.Bottom,
                                    ) {
                                        CompactTextField(
                                            draft.javaPath,
                                            { draft = draft.copy(javaPath = it) },
                                            Modifier.weight(1f),
                                            label = "此版本的 Java 路径",
                                            placeholder = "留空使用全局设置",
                                        )
                                        SmallIconButton(
                                            Icons.Outlined.FolderOpen,
                                            "选择版本 Java",
                                            {
                                                actions.run {
                                                    val selected =
                                                        actions
                                                            .choose(
                                                                "选择 Java 可执行文件",
                                                                extensions =
                                                                    if (
                                                                        actions.controller.platform
                                                                            .osName == "windows"
                                                                    )
                                                                        listOf("exe")
                                                                    else emptyList(),
                                                            )
                                                            .firstOrNull()
                                                    if (selected != null)
                                                        withContext(Dispatchers.Swing) {
                                                            draft =
                                                                draft.copy(
                                                                    javaPath = selected.toString()
                                                                )
                                                        }
                                                }
                                            },
                                        )
                                    }
                                    SimpleDropdown(
                                        when {
                                            draft.autoMemory == true -> "automatic"
                                            draft.autoMemory == false ||
                                                (memory.toIntOrNull() ?: 0) > 0 -> "manual"
                                            else -> "inherit"
                                        },
                                        listOf(
                                            "inherit" to "继承全局内存策略",
                                            "automatic" to "自动分配",
                                            "manual" to "手动分配",
                                        ),
                                        { mode ->
                                            draft =
                                                draft.copy(
                                                    autoMemory =
                                                        when (mode) {
                                                            "automatic" -> true
                                                            "manual" -> false
                                                            else -> null
                                                        }
                                                )
                                            if (mode == "inherit") memory = "0"
                                            else if (mode == "manual" && memory == "0")
                                                memory = state.data.settings.memoryMb.toString()
                                        },
                                    )
                                    if (draft.autoMemory == true)
                                        MutedText("启动时根据系统可用内存分配；保留手动值，切回手动时继续使用。")
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    ) {
                                        val maximum =
                                            maxOf(
                                                512,
                                                actions.controller.platform.totalMemoryMb - 512,
                                            )
                                        val current =
                                            memory.toIntOrNull()?.takeIf { it > 0 }
                                                ?: state.data.settings.memoryMb
                                        CompactSlider(
                                            current.toFloat().coerceIn(512f, maximum.toFloat()),
                                            { dragged ->
                                                val value = steppedMemory(dragged, maximum)
                                                memory = value.toString()
                                                if ((initial.toIntOrNull() ?: 0) > value)
                                                    initial = value.toString()
                                            },
                                            Modifier.weight(1f).semantics {
                                                contentDescription = "实例内存滑块"
                                            },
                                            valueRange = 512f..maximum.toFloat(),
                                            enabled = draft.autoMemory != true && memory != "0",
                                        )
                                        Tag("%.1f GB".format(current / 1024f))
                                    }
                                }
                                CompactSettingsSection("游戏窗口") {
                                    SimpleDropdown(
                                        draft.windowMode,
                                        listOf(
                                            "inherit" to "继承全局窗口设置",
                                            "windowed" to "窗口模式",
                                            "fullscreen" to "全屏模式",
                                        ),
                                        { draft = draft.copy(windowMode = it) },
                                    )
                                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        NumberField(
                                            width,
                                            "窗口宽度（px）",
                                            { width = it },
                                            Modifier.weight(1f),
                                            "0 = 继承全局",
                                        )
                                        NumberField(
                                            height,
                                            "窗口高度（px）",
                                            { height = it },
                                            Modifier.weight(1f),
                                            "0 = 继承全局",
                                        )
                                    }
                                }
                                CompactSettingsSection("基本信息") {
                                    CompactTextField(
                                        draft.name,
                                        { draft = draft.copy(name = it.take(80)) },
                                        Modifier.fillMaxWidth(),
                                        label = "实例名称",
                                    )
                                    OutlinedTextField(
                                        draft.notes,
                                        { draft = draft.copy(notes = it.take(2000)) },
                                        Modifier.fillMaxWidth(),
                                        label = { Text("版本备注") },
                                        minLines = 2,
                                        maxLines = 4,
                                    )
                                }
                                CompactSettingsSection("图标", "默认跟随原版或加载器；整合包自带图标优先，也可以手动替换。") {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    ) {
                                        InstanceArtwork(instance, actions, Modifier.size(40.dp))
                                        SecondaryButton(
                                            { editingIcon = true },
                                            enabled =
                                                !actions.controller.isInstanceBusy(instance.id),
                                        ) {
                                            Text("更换实例图标…")
                                        }
                                    }
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
                                            Modifier.fillMaxWidth(),
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
                                                legacyMinimize =
                                                    state.data.settings.minimizeOnLaunch,
                                            )
                                        }
                                    }
                                InstanceInstallSections(instance, actions, navigate)
                                TextButton({
                                    memory = "0"
                                    initial = "0"
                                    width = "0"
                                    height = "0"
                                    draft =
                                        draft.copy(
                                            javaPath = "",
                                            windowMode = "inherit",
                                            extraJvmArgs = "",
                                        )
                                }) {
                                    Text("恢复全局启动设置", fontSize = 12.sp)
                                }
                                if (!valid)
                                    Text(
                                        "请检查名称、内存和窗口尺寸。",
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
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("重命名实例") },
            text = {
                OutlinedTextField(
                    name,
                    { name = it.take(80) },
                    Modifier.fillMaxWidth(),
                    label = { Text("实例名称") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    enabled = name.isNotBlank() && name.trim() != instance.name,
                    onClick = {
                        actions.run {
                            actions.controller.saveInstanceConfiguration(
                                instance.copy(name = name.trim())
                            )
                        }
                        renaming = false
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

/** 二级页签条目：高 34、圆角 6、图标 17、文字 13，选中用主题底 + 主题色。 */
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

private fun packSourceOf(instance: GameInstance): String =
    when {
        instance.packFile.isNotBlank() -> "整合包：" + instance.packFile.substringAfterLast('/')
        instance.externalProfile -> "外部目录导入"
        else -> "启动器安装"
    }

/** 设置页签的「当前安装」与「更换加载器」卡片：展示当前组件，并提交更换 / 回退请求。 */
@Composable
private fun ColumnScope.InstanceInstallSections(
    instance: GameInstance,
    actions: UiActions,
    navigate: (Page) -> Unit,
) {
    var targetLoader by remember(instance.id) { mutableStateOf(instance.loader) }
    var targetVersion by remember(instance.id) { mutableStateOf(instance.loaderVersion) }
    var versions by remember { mutableStateOf<List<LoaderVersion>>(emptyList()) }
    var loadingVersions by remember { mutableStateOf(false) }
    var versionError by remember { mutableStateOf<String?>(null) }
    var versionRetry by remember { mutableIntStateOf(0) }
    val editable = instance.installed
    var pending by remember { mutableStateOf<ComponentSelection?>(null) }
    var restoring by remember { mutableStateOf(false) }
    var enabledMods by remember { mutableIntStateOf(0) }
    val applyChange: (ComponentSelection, Boolean) -> Unit = { selection, restore ->
        actions.run {
            if (restore) actions.controller.restoreInstanceComponents(instance.id)
            else
                actions.controller.changeInstanceLoader(
                    instance.id,
                    selection.loader,
                    selection.loaderVersion,
                )
        }
        actions.taskQueued()
    }
    val requestChange: (ComponentSelection, Boolean) -> Unit = { selection, restore ->
        actions.run {
            enabledMods = actions.controller.content.listMods(instance.id).count { it.enabled }
            if (enabledMods == 0 && !instance.externalProfile && instance.packFile.isBlank())
                applyChange(selection, restore)
            else {
                pending = selection
                restoring = restore
            }
        }
    }
    LaunchedEffect(targetLoader, instance.gameVersion, editable, versionRetry) {
        versions = emptyList()
        versionError = null
        if (!editable || targetLoader == LoaderType.Vanilla) return@LaunchedEffect
        loadingVersions = true
        try {
            versions =
                withContext(Dispatchers.IO) {
                    actions.controller.installer.loaderVersions(targetLoader, instance.gameVersion)
                }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            versionError = failure.message
        } finally {
            loadingVersions = false
        }
    }
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
    if (editable)
        SettingsSection("更换加载器", "更改加载器会重新校验并补齐依赖文件；成功后可回退到上一组件。") {
            SimpleDropdown(
                targetLoader.title,
                LoaderType.entries.map { it.title to it.title },
                { selected ->
                    targetLoader = LoaderType.entries.first { it.title == selected }
                    targetVersion = ""
                },
            )
            if (targetLoader != LoaderType.Vanilla) {
                when {
                    loadingVersions ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            LinearProgressIndicator(Modifier.weight(1f))
                            Spacer(Modifier.width(10.dp))
                            Text(
                                "正在获取" + targetLoader.title + "版本列表",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    versionError != null ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                versionError.orEmpty(),
                                Modifier.weight(1f),
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.error,
                            )
                            TextButton({ versionRetry++ }) {
                                Text("重试", fontSize = 12.sp)
                            }
                        }
                    versions.isEmpty() ->
                        Text(
                            "该游戏版本暂无可用的 " + targetLoader.title + " 版本",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    else ->
                        SimpleDropdown(
                            targetVersion.ifBlank { "选择 " + targetLoader.title + " 版本" },
                            versions.map {
                                it.version to (it.version + if (it.stable) "" else "（测试版）")
                            },
                            { targetVersion = it },
                        )
                }
            }
            val changed =
                targetLoader != instance.loader ||
                    (targetLoader != LoaderType.Vanilla &&
                        targetVersion.isNotBlank() &&
                        targetVersion != instance.loaderVersion)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PrimaryButton(
                    "应用更改",
                    Icons.Outlined.Check,
                    changed &&
                        (targetLoader == LoaderType.Vanilla ||
                            targetVersion.isNotBlank() &&
                                !loadingVersions &&
                                versionError == null) &&
                        !actions.controller.isInstanceBusy(instance.id),
                ) {
                    requestChange(
                        ComponentSelection(
                            targetLoader,
                            if (targetLoader == LoaderType.Vanilla) "" else targetVersion,
                            "",
                        ),
                        false,
                    )
                }
                if (targetLoader != instance.loader && instance.loader != LoaderType.Vanilla)
                    TextButton({
                        targetLoader = instance.loader
                        targetVersion = instance.loaderVersion
                    }) {
                        Text("还原为当前加载器", fontSize = 12.sp)
                    }
            }
            if (targetLoader == LoaderType.Vanilla && instance.loader != LoaderType.Vanilla)
                Text(
                    "恢复原版后，已有的模组文件会保留但不再被加载。",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            instance.previousComponents?.let { previous ->
                HorizontalDivider()
                SettingRow("上一组件", previous.title) {
                    SecondaryButton(
                        { requestChange(previous, true) },
                        enabled = !actions.controller.isInstanceBusy(instance.id),
                    ) {
                        Text("回退组件", fontSize = 12.sp)
                    }
                }
            }
        }
    else
        SettingsSection("组件维护") {
            Text(
                if (instance.externalProfile) "这是外部目录中的实例。为保留原有自定义启动配置，请由原目录维护加载器；仍可在此修复已有游戏文件。"
                else "整合包的加载器由包清单定义，请通过整合包修复维护，暂不直接更换组件。",
                fontSize = 12.sp,
            )
        }
    pending?.let { selection ->
        Modal("加载器兼容提醒", "目标：${selection.title}", onDismiss = { pending = null }) {
            Text("更换前会备份原版本配置，可在成功后回退。整合包与外部实例的自定义组件可能受影响，请确认目标加载器兼容。")
            Text("当前有 $enabledMods 个已启用的模组。请确认它们支持目标加载器及版本；现有模组文件会保留。")
            if (selection.loader == LoaderType.Vanilla)
                Text(
                    "恢复原版后这些模组不会被加载。",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton({ pending = null }) { Text("取消") }
                PrimaryButton(
                    if (restoring) "保留模组并回退" else "保留模组并更换",
                    onClick = {
                        val restore = restoring
                        pending = null
                        applyChange(selection, restore)
                    },
                )
            }
        }
    }
}

/** 模组列表：顶部工具行 + 52dp 行（名称/版本与来源/开关/删除）+ 骨架 + 空态 + 搜索无结果。 */
@Composable
private fun InstanceMods(
    instance: GameInstance,
    actions: UiActions,
    modifier: Modifier,
    onDiscover: () -> Unit,
) {
    var mods by remember { mutableStateOf<List<ModFile>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    var refresh by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var loadedOnce by remember(instance.id) { mutableStateOf(false) }
    var updatesOpen by remember { mutableStateOf(false) }
    var detailMod by remember { mutableStateOf<ModFile?>(null) }
    var deletingMod by remember { mutableStateOf<ModFile?>(null) }
    val liveState by actions.controller.state.collectAsState()
    if (updatesOpen)
        ContentUpdatesDialog(instance.id, actions) {
            updatesOpen = false
            refresh++
        }
    detailMod?.let { mod -> ModDetailsDialog(mod) { detailMod = null } }
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
            loadedOnce = true
        }
    }
    Column(modifier.fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CompactSearchField(
                query,
                { query = it },
                Modifier.weight(1f),
                placeholder = "搜索已安装模组",
            )
            SmallIconButton(
                Icons.Outlined.Refresh,
                "刷新模组",
                { refresh++ },
                enabled = !actions.controller.isInstanceBusy(instance.id),
            )
            TextButton(
                { updatesOpen = true },
                enabled = !actions.controller.isInstanceBusy(instance.id),
            ) {
                Text("检查更新", fontSize = 12.sp)
            }
            SecondaryButton(
                {
                    actions.run {
                        actions.controller.requireIdle(instance.id)
                        val files =
                            actions.choose("导入本地模组", multiple = true, extensions = listOf("jar"))
                        actions.controller.content.importMods(instance.id, files)
                        withContext(Dispatchers.Swing) { refresh++ }
                    }
                },
                enabled = !actions.controller.isInstanceBusy(instance.id),
            ) {
                Text("导入模组", fontSize = 12.sp)
            }
            PrimaryButton("下载模组", Icons.Outlined.Add, onClick = onDiscover)
        }
        val filtered = mods.filter {
            it.filename.contains(query, true) ||
                it.metadata.title.contains(query, true) ||
                it.metadata.entries.any { entry -> entry.id.contains(query, true) }
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
                    EmptyState("没有匹配的模组", "换个关键词试试", Icons.Outlined.SearchOff) {
                        TextButton({ query = "" }) { Text("清除搜索") }
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
                            busy = actions.controller.isInstanceBusy(instance.id),
                            onDetail = { detailMod = mod },
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

/** 52dp 模组行：JAR 自带图标 28dp + 名称 14 + 版本/加载器/大小 12 + 来源文件名 11 + 开关与删除。 */
@Composable
private fun ModRow(
    mod: ModFile,
    jar: Path?,
    iconCacheRoot: Path?,
    modifier: Modifier,
    busy: Boolean,
    onDetail: () -> Unit,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
) {
    val shape = RoundedCornerShape(Metrics.cardRadius)
    val sourceName = mod.filename.removeSuffix(".disabled")
    val icon =
        if (jar != null && iconCacheRoot != null) rememberModIcon(jar, mod.modified, iconCacheRoot)
        else null
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
                Text(
                    mod.metadata.title.ifBlank { sourceName },
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    listOf(
                            mod.metadata.versions.ifBlank { "版本未知" },
                            mod.metadata.loader.ifBlank { "加载器未知" },
                            "%.1f MB".format(mod.size / 1048576f),
                        )
                        .filter { it.isNotBlank() }
                        .joinToString(" · "),
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
            CompactSwitch(mod.enabled, { onToggle() }, enabled = !busy)
            SmallIconButton(
                Icons.Outlined.DeleteOutline,
                "将模组移至回收站",
                onDelete,
                enabled = !busy,
            )
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
 * 存档页签：顶部搜索 / 刷新 / 打开存档文件夹，44dp 一行列出世界（名称、大小、修改时间、 「可能不是有效世界」提示），行尾「备份」「复制到…」「删除」；下方「备份」卡片列出可恢复的备份。
 *
 * 列表在 IO 线程读取（备份列举会现算 SHA1，不能放在 Compose 线程）；首次加载给骨架、读取失败可
 * 重试、没有存档给空态。任务进行中（controller.busy）禁用所有操作按钮；后台任务失败时把后端的中文消息显示在工具行下方，不静默吞掉。
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
    var restoring by remember { mutableStateOf<SaveBackupInfo?>(null) }
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
    val startRestore = { backup: SaveBackupInfo, overwrite: Boolean ->
        actionError = null
        try {
            watchingJob = actions.controller.restoreSave(instance.id, backup.id, overwrite)
        } catch (failure: Exception) {
            actionError = failure.message ?: "无法开始恢复存档"
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
    restoring?.let { backup ->
        AlertDialog(
            onDismissRequest = { restoring = null },
            title = { Text("覆盖现有存档？") },
            text = {
                Text("saves 里已有同名世界「" + backup.saveName + "」。恢复会先把现有存档自动备份到备份区，再用这个备份覆盖它。")
            },
            confirmButton = {
                TextButton({
                    restoring = null
                    startRestore(backup, true)
                }) {
                    Text("覆盖并恢复")
                }
            },
            dismissButton = { TextButton({ restoring = null }) { Text("取消") } },
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
    Column(modifier.fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CompactSearchField(
                query,
                { query = it },
                Modifier.weight(1f),
                placeholder = "搜索存档名称",
            )
            SmallIconButton(
                Icons.Outlined.Refresh,
                "刷新存档列表",
                {
                    actionError = null
                    refresh++
                },
                enabled = !busy,
            )
            SecondaryButton(
                { actions.run { actions.controller.openFolder(instance.id, "saves") } },
                enabled = !busy,
            ) {
                Icon(Icons.Outlined.FolderOpen, null, Modifier.size(15.dp))
                Spacer(Modifier.width(5.dp))
                Text("打开存档文件夹", fontSize = 12.sp)
            }
        }
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
                                    "在游戏里创建一个世界，或用「复制到…」从别的实例复制过来。",
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
                    Panel(
                        Modifier.fillMaxWidth(),
                        title = "备份",
                        detail = "备份保存在实例目录的隐藏目录里；恢复前会先备份同名存档，不会直接覆盖。",
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
                                                if (exists) restoring = backup
                                                else startRestore(backup, false)
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
            SaveRowAction("备份", canManage, onBackup)
            SaveRowAction("复制到…", canCopy, onCopy)
            SaveRowAction("删除", canManage, danger = true, onClick = onDelete)
        }
    }
}

/** 一个备份行：44dp，来源存档名 + 创建时间与大小，行尾「恢复」「删除」。 */
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
                formatSaveTime(backup.createdAt) + " · " + formatSaveSize(backup.size),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        SaveRowAction("恢复", !busy, onRestore)
        SaveRowAction("删除", !busy, danger = true, onClick = onDelete)
    }
}

/** 行尾文字操作：12sp、高 28，禁用时降到 38% 透明度，破坏性操作用错误色。 */
@Composable
private fun SaveRowAction(
    text: String,
    enabled: Boolean,
    onClick: () -> Unit,
    danger: Boolean = false,
) {
    TextButton(
        onClick = onClick,
        modifier = Modifier.height(28.dp),
        enabled = enabled,
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(
            text,
            fontSize = 12.sp,
            color =
                when {
                    !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .38f)
                    danger -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.primary
                },
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

/** 内容文件夹浏览：资源包 / 原理图共用；原理图支持导入，目录可逐级进入。 文件大小与修改时间来自文件系统，显示的都是真实数据。 */
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
    var updatesOpen by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    // 目录读取期间先显示骨架，读完再决定空态，避免空态闪一下被误判成“目录是空的”。
    var loading by remember(instance.id, folder) { mutableStateOf(true) }
    var deletingFile by remember { mutableStateOf<Path?>(null) }
    // 子目录浏览只在前端维护，不改实例目录结构；返回上一级时清空栈。
    var subPath by remember(instance.id, folder) { mutableStateOf<List<String>>(emptyList()) }
    val liveState by actions.controller.state.collectAsState()
    if (updatesOpen)
        ContentUpdatesDialog(instance.id, actions) {
            updatesOpen = false
            refresh++
        }
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
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (subPath.isNotEmpty())
                HeaderIconButton(Icons.Outlined.ArrowUpward, "返回上一级目录") {
                    subPath = subPath.dropLast(1)
                    query = ""
                }
            CompactSearchField(
                query,
                { query = it },
                Modifier.weight(1f),
                placeholder = "在文件夹内搜索",
            )
            if (importExtensions.isNotEmpty())
                SecondaryButton(
                    {
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
                    enabled = !actions.controller.isInstanceBusy(instance.id),
                ) {
                    Icon(Icons.Outlined.FileDownload, null, Modifier.size(15.dp))
                    Spacer(Modifier.width(5.dp))
                    Text(importLabel.ifBlank { "导入" }, fontSize = 12.sp)
                }
            else
            // 资源包没有导入后端：保留可见的禁用按钮与说明。
            SecondaryButton({}, enabled = false) {
                    Icon(Icons.Outlined.FileDownload, null, Modifier.size(15.dp))
                    Spacer(Modifier.width(5.dp))
                    Text("导入", fontSize = 12.sp)
                }
            if (folder in setOf("resourcepacks", "shaderpacks"))
                TextButton(
                    { updatesOpen = true },
                    enabled = !actions.controller.isInstanceBusy(instance.id),
                ) {
                    Text("检查更新", fontSize = 12.sp)
                }
            SmallIconButton(
                Icons.Outlined.Refresh,
                "刷新文件",
                { refresh++ },
                enabled = !actions.controller.isInstanceBusy(instance.id),
            )
            SecondaryButton({
                actions.run { actions.controller.openFolder(instance.id, folder) }
            }) {
                Icon(Icons.Outlined.FolderOpen, null, Modifier.size(15.dp))
                Spacer(Modifier.width(5.dp))
                Text("打开文件夹", fontSize = 12.sp)
            }
        }
        Text(
            directory.toString(),
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (importExtensions.isEmpty())
            Text(
                "导入暂未支持：请用「打开文件夹」把文件放进目录后点刷新。",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
            if (entry.path.parent?.fileName?.toString() in setOf("resourcepacks", "shaderpacks"))
                LocalContentThumbnail(entry.path, world = false)
            else
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
                SmallIconButton(
                    Icons.Outlined.DeleteOutline,
                    "移至回收站 " + entry.path.name,
                    onDelete,
                    enabled = !busy,
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
