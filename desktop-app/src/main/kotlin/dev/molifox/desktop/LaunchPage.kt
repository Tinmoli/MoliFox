// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责开始页账户摘要、实例选择和启动操作。

package dev.molifox.desktop

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.molifox.core.*
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 开始页：左栏是账户摘要与撑满列高的「当前游戏」卡（信息在上、启动按钮与「实例管理 / 版本设置」贴底）， 右栏是可折叠的世界 / 服务器面板。 */
@Composable
fun ColumnScope.HomePage(
    state: LauncherState,
    actions: UiActions,
    onNew: () -> Unit,
    navigate: (Page) -> Unit,
    onManage: (String) -> Unit,
    onOpenAccounts: () -> Unit,
) {
    // 页面重新可见时只做目录签名比较：安装/删除/改名/手动改文件夹后回到开始页即可看到最新扫描。
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { runCatching { actions.controller.refreshInstancesIfChanged() } }
    }
    // 选中记录可能为空或指向已删除的实例：先按「当前游戏目录 → 最近游玩」回退，再把回退结果
    // 写回状态。这样自动识别目录后开始页不会停在「未选择版本」，其他页面读到的也是同一个实例。
    val stored =
        state.data.instances.find {
            it.id == state.data.selectedInstanceId &&
                it.gameDirectoryId == state.data.activeGameDirectoryId
        }
    val selected =
        stored
            ?: state.data.instances
                .filter { it.gameDirectoryId == state.data.activeGameDirectoryId }
                .maxByOrNull { it.lastPlayed ?: it.createdAt }
    LaunchedEffect(stored?.id, selected?.id) {
        if (stored != null) return@LaunchedEffect
        // 只回写与当前游戏目录一致的实例，避免把用户刚选定的空目录又切走；
        // 跨目录的可见实例仍然可以直接启动（点击时才会重新持久化选中）。
        val target =
            selected?.takeIf { it.gameDirectoryId == state.data.activeGameDirectoryId }
                ?: return@LaunchedEffect
        withContext(Dispatchers.IO) { runCatching { actions.controller.selectInstance(target.id) } }
    }
    // 版本配置与客户端 jar 是否还在：只在选中项变化时于 IO 线程检查一次，缺失时如实说明原因。
    val filesPresent by
        produceState(true, selected?.id, selected?.versionId) {
            val instance = selected
            value =
                if (instance == null) true
                else
                    withContext(Dispatchers.IO) {
                        instanceFilesPresent(
                            actions.controller.store,
                            actions.controller.platform,
                            instance,
                        )
                    }
        }
    val account = state.data.accounts.find { it.id == state.data.activeAccountId }
    val preferences = state.data.settings.pages.home
    val busy = selected?.let { actions.controller.isInstanceBusy(it.id) } ?: actions.controller.busy
    // 启动进度只读真实的排队/运行任务，不凭空造一份「launching」状态。
    val launchJob =
        state.jobs.firstOrNull { it.status == JobStatus.Running && it.title.startsWith("启动 ") }
    val running = selected != null && state.runningGames.any { it.instanceId == selected.id }
    // 启动浮窗只在本页存活：remember 状态随页面切换一起释放，启动任务本身留在任务队列继续。
    val launchCard = remember { LaunchCardState() }
    val cardJob = state.jobs.firstOrNull { it.id == launchCard.jobId } ?: launchCard.lastJob
    LaunchedEffect(cardJob) { if (cardJob != null) launchCard.remember(cardJob) }
    // 游戏进程起来后记录一次；退出（或 gameRun 标记结束）时把浮窗收起。
    LaunchedEffect(state.runningInstanceId, state.gameRun?.finished, launchCard.instanceId) {
        val runningId = state.runningInstanceId
        val run = state.gameRun
        when {
            runningId != null && runningId == launchCard.instanceId -> launchCard.markRunning()
            launchCard.sawRunning &&
                (runningId == null ||
                    (run != null && run.instanceId == launchCard.instanceId && run.finished)) ->
                launchCard.clear()
        }
    }
    // 开始页的启动路径：先把选中实例写回状态，再把任务交给控制器；进度、取消与失败原因都落在浮窗里。
    fun beginLaunch(instance: GameInstance, target: GameLaunchTarget? = null) {
        launchCard.begin(instance)
        actions.run { actions.controller.selectInstance(instance.id) }
        try {
            launchCard.attach(actions.controller.launch(instance.id, target))
        } catch (failure: Exception) {
            launchCard.fail(failure.message ?: "启动失败，请查看日志")
        }
    }
    fun cancelLaunch() {
        launchCard.jobId?.let { actions.controller.jobs.cancel(it) }
    }
    fun closeLaunchCard() {
        val status = state.jobs.firstOrNull { it.id == launchCard.jobId }?.status
        // 运行中手动关闭等同取消；已结束则只收起浮窗。
        if (status == JobStatus.Queued || status == JobStatus.Running) cancelLaunch()
        launchCard.clear()
    }
    val visible = rememberEntranceOnce()
    // 「当前游戏」简介里的安装状态；与启动按钮的分支共用同一套判断，避免两处说法不一致。
    val installState =
        when {
            selected == null -> "未选择版本"
            selected.broken -> "无法识别 · " + selected.brokenReason
            !selected.installed -> "未安装完成"
            selected.externalProfile && !filesPresent -> "版本文件缺失"
            !filesPresent -> "启动时补全文件"
            else -> "已安装"
        }
    // 右上角显示当前实例和游戏目录统计。
    if (preferences.showStatistics)
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.End,
        ) {
            Icon(
                Icons.Outlined.Widgets,
                null,
                Modifier.size(14.dp),
                MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(5.dp))
            Text(
                state.data.instances.size.toString() +
                    " 个版本 · " +
                    state.data.accounts.size +
                    " 个账户",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    // 浮窗叠在内容之上；开始页保持可见，点击浮窗外部不会关闭它。
    Box(Modifier.weight(1f).fillMaxWidth()) {
        Row(
            Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(Metrics.cardGap),
        ) {
            // 左栏：账户摘要 + 撑满剩余高度的「当前游戏」卡（窗口最小 1000×640，内容不会被截断）。
            Entrance(visible, Modifier.width(296.dp).fillMaxHeight()) {
                Column(
                    Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(Metrics.cardGap),
                ) {
                    // 账户摘要卡提供头像、名字和账户列表入口。
                    HomeCard("游戏账户") { HomeAccountSummary(state, actions, onOpenAccounts) }
                    // 「当前游戏」撑到左栏底部：信息在上、中间弹性留白、启动与版本入口贴底。
                    HomeCard(
                        title = "当前游戏",
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        fillContent = true,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (selected != null)
                                InstanceArtwork(selected, actions, Modifier.size(40.dp))
                            else GrassBlockIcon(size = 40.dp)
                            Spacer(Modifier.width(12.dp))
                            Column(
                                Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(3.dp),
                            ) {
                                // 名称行右侧是隔离标签；简介行整宽留给「版本 · 加载器 · 安装状态」。
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        selected?.name ?: "未选择版本",
                                        Modifier.weight(1f),
                                        style = MaterialTheme.typography.titleLarge,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    if (selected != null) {
                                        Spacer(Modifier.width(6.dp))
                                        if (selected.broken)
                                            Tag("无法识别", MaterialTheme.colorScheme.error)
                                        else
                                            Tag(
                                                if (actions.controller.store.isolated(selected))
                                                    "版本隔离"
                                                else "共用目录"
                                            )
                                    }
                                }
                                if (selected != null)
                                    Text(
                                        selected.gameVersion +
                                            " · " +
                                            selected.loader.title +
                                            " · " +
                                            installState,
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                else
                                    Text(
                                        "安装 Minecraft，或选择已有的游戏目录。",
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            selected?.let { actions.controller.store.gamePath(it).toString() }
                                ?: actions.controller.store.minecraftRoot.toString(),
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        // 中间弹性留白：窗口变矮时先吃掉这里，不挤压按钮、不截断文字。
                        Spacer(Modifier.weight(1f))
                        // 整行「启动游戏」按钮；再往下是贴底的版本入口。
                        LaunchActionButton(
                            progress = launchJob?.progress,
                            running = running,
                            // 没有实例时也让按钮可点：点击后给出原因并去安装页，而不是留下一个没有解释的灰按钮。
                            enabled = !busy,
                        ) {
                            // 与实例页、实例详情页共用同一条启动路径：先持久化选中，再交给控制器排队启动。
                            // 进度、取消与失败原因都留在开始页的启动浮窗里，只有确实没有可用的游戏时才跳安装页。
                            when {
                                selected == null -> {
                                    actions.controller.store.notify("还没有可启动的游戏，已打开安装页")
                                    onNew()
                                }
                                selected.broken -> {
                                    actions.controller.store.notify(
                                        "「" + selected.name + "」无法识别：" + selected.brokenReason
                                    )
                                }
                                !selected.installed -> {
                                    // 修复任务在后台推进，进度可从左侧「日志」入口查看。
                                    actions.controller.store.notify(
                                        "「" + selected.name + "」尚未安装完成，已开始修复，可在日志中查看进度"
                                    )
                                    actions.run { actions.controller.repair(selected.id) }
                                }
                                // 外部目录的版本文件被移走或删掉时无法自动补全，直接启动只会报错。
                                selected.externalProfile && !filesPresent -> {
                                    actions.controller.store.notify(
                                        "找不到「" + selected.name + "」的版本文件，已打开安装页；" + "可重新选择目录或修复原版本"
                                    )
                                    onNew()
                                }
                                account == null -> {
                                    actions.controller.store.notify("请先添加一个游戏账户")
                                    onOpenAccounts()
                                }
                                else -> beginLaunch(selected)
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        // 底部一行两个等宽入口：实例管理（进实例列表）与版本设置（进实例详情）。
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            // 「实例管理」进实例列表页：顶部导航不含「实例」，这里是进入实例列表的入口。
                            InstanceManageButton(
                                modifier = Modifier.weight(1f),
                                onClick = { navigate(Page.Instances) },
                            )
                            // 「版本设置」进入该实例的详情 / 设置页。
                            SecondaryButton(
                                onClick = { selected?.let { onManage(it.id) } },
                                modifier = Modifier.weight(1f),
                                enabled = selected != null && !busy,
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                            ) {
                                // 只留中文文字并居中：两侧内边距对称，不再保留任何图标或图标间距。
                                Text(
                                    "版本设置",
                                    Modifier.fillMaxWidth(),
                                    fontSize = 13.sp,
                                    textAlign = TextAlign.Center,
                                )
                            }
                        }
                    }
                }
            }
            Entrance(visible, Modifier.weight(1f).fillMaxHeight()) {
                Column(
                    Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(Metrics.cardGap),
                ) {
                    // 右栏的世界 / 服务器面板：折叠后只有标题行，「查看全部」从标题行打开。
                    if (selected != null)
                        RecentPlayPanel(
                            instance = selected,
                            actions = actions,
                            busy = busy,
                            expanded = preferences.recentPlayExpanded,
                            onExpandedChange = { value ->
                                // 折叠状态写进开始页偏好：与设置页同一条保存路径，重启后按记住的值渲染。
                                applySetting(actions) {
                                    it.copy(
                                        pages =
                                            it.pages.copy(
                                                home =
                                                    it.pages.home.copy(recentPlayExpanded = value)
                                            )
                                    )
                                }
                            },
                            onLaunch = { game, target -> beginLaunch(game, target) },
                            // 正文只显示一类：存档或服务器，选择同样写入开始页偏好。
                            kind =
                                if (preferences.recentPlayKind == "server") EntryKind.Server
                                else EntryKind.World,
                            onKindChange = { value ->
                                applySetting(actions) {
                                    it.copy(
                                        pages =
                                            it.pages.copy(
                                                home =
                                                    it.pages.home.copy(
                                                        recentPlayKind =
                                                            if (value == EntryKind.Server) "server"
                                                            else "world"
                                                    )
                                            )
                                    )
                                }
                            },
                            // fill = false：折叠后卡片按标题行高度收起。
                            modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
                        )
                }
            }
        }
        // 启动浮窗固定在右下角：不遮挡左栏的「启动游戏」按钮，点击页面其他区域也不会关闭它。
        if (launchCard.visible)
            LaunchProgressCard(
                instanceName = launchCard.instanceName,
                job = cardJob,
                error = launchCard.localError,
                startedAtMillis = launchCard.startedAtMillis,
                lastLog = state.logs.lastOrNull()?.message.orEmpty(),
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 2.dp, bottom = 2.dp),
                onCancel = { cancelLaunch() },
                onRetry = { selected?.let { beginLaunch(it) } },
                onViewLogs = {
                    launchCard.clear()
                    navigate(Page.Downloads)
                },
                onDismiss = { closeLaunchCard() },
            )
    }
}

/**
 * 白卡片：与 Panel 同一套尺寸（圆角 6、1dp 细边、无阴影、内边距 16）。 单独实现是因为开始页的卡片需要自己掌控内容内边距，Panel 的内容列是固定 16。 [title]
 * 留空时不渲染标题行与分隔线。 [fillContent] 为 true 时内容列吃掉标题行以下的剩余高度，「当前游戏」卡靠它把启动按钮与版本入口贴到卡片底部； 右栏的
 * [RecentPlayPanel] 复用同一张卡片并传 0 内边距（标题行与内容区各自留白），因此这里是 internal 而不是 private。
 */
@Composable
internal fun HomeCard(
    title: String = "",
    modifier: Modifier = Modifier,
    fillContent: Boolean = false,
    contentPadding: PaddingValues = PaddingValues(Metrics.cardPadding),
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier,
        shape = RoundedCornerShape(Metrics.cardRadius),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, hairline()),
    ) {
        Column {
            if (title.isNotBlank()) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    modifier =
                        Modifier.fillMaxWidth()
                            .padding(
                                start = Metrics.cardPadding,
                                end = Metrics.cardPadding,
                                top = 11.dp,
                                bottom = 10.dp,
                            ),
                )
                HorizontalDivider(color = hairline())
            }
            Column(
                Modifier.padding(contentPadding)
                    .then(if (fillContent) Modifier.weight(1f) else Modifier),
                content = content,
            )
        }
    }
}

/**
 * 「实例管理」入口：外观沿用 [SimpleDropdown] 那一套控件样式（34dp、圆角、实色填充 + 1dp 细边、悬停提亮），
 * 但点开不是菜单，而是进入实例列表页——顶部导航不含「实例」，这里是进入实例列表的入口。 文字固定为「实例管理」并居中，不显示实例名，也不带次要文字或箭头。
 */
@Composable
private fun InstanceManageButton(
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val shape = RoundedCornerShape(Metrics.innerRadius)
    val animation: androidx.compose.animation.core.AnimationSpec<Color> =
        if (LocalReducedMotion.current) snap<Color>() else tween<Color>(Motion.fast)
    val fill by
        animateColorAsState(
            controlBackground(hovered = hovered),
            animation,
            label = "instancesEntryFill",
        )
    val borderColor by
        animateColorAsState(controlBorderColor(), animation, label = "instancesEntryBorder")
    Row(
        modifier
            .height(Metrics.controlHeight)
            .clip(shape)
            .background(fill, shape)
            .border(1.dp, borderColor, shape)
            .hoverable(interaction)
            .clickable(role = Role.Button, onClickLabel = "打开实例列表", onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "实例管理",
            Modifier.fillMaxWidth(),
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

/** 首次组合时置为 true：卡片入场动画只播一次，之后切回本页不再重播。 */
@Composable
private fun rememberEntranceOnce(): Boolean {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }
    return visible
}

/** 卡片入场：150ms 淡入 + 8dp 上移；关闭动画时直接呈现。 */
@Composable
private fun Entrance(
    visible: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val offset = with(LocalDensity.current) { 8.dp.roundToPx() }
    Box(modifier) {
        if (LocalAnimationsEnabled.current)
            AnimatedVisibility(
                visible = visible,
                enter = fadeIn(tween(150)) + slideInVertically(tween(150)) { offset },
                exit = ExitTransition.None,
            ) {
                content()
            }
        else content()
    }
}

/** 启动主按钮：整行铺满、44dp，圆角沿用 [Metrics.innerRadius]，15sp 单行文字；真正排队 / 启动时在左侧显示进度环与百分比。 */
@Composable
private fun LaunchActionButton(
    progress: Float?,
    running: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Button(
        onClick,
        Modifier.fillMaxWidth().height(44.dp).pressScale(),
        enabled = enabled,
        shape = RoundedCornerShape(Metrics.innerRadius),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
    ) {
        // 只有「正在启动」才出现进度环：它是任务进度。
        if (progress != null) {
            CircularProgressIndicator(
                Modifier.size(14.dp),
                strokeWidth = 2.dp,
                color = LocalContentColor.current,
            )
            Spacer(Modifier.width(8.dp))
        }
        Text(
            when {
                progress != null -> "正在启动 " + (progress * 100).toInt() + "%"
                running -> "再次启动"
                else -> "启动游戏"
            },
            fontSize = 15.sp,
            lineHeight = 19.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

/**
 * 版本文件是否仍在磁盘上：`versions/<id>/<id>.json` 能解析，且客户端 jar 存在。
 * 手动选目录后原文件被移动或删除时，登记记录仍然存在，这个检查用于区分「已安装」和「其实没有文件」。
 */
private fun instanceFilesPresent(
    store: LauncherStore,
    platform: PlatformServices,
    instance: GameInstance,
): Boolean = runCatching {
    val root = store.minecraftRoot(instance)
    val profile = VersionResolver(root, platform).resolve(instance.versionId)
    Files.isRegularFile(
        root.resolve("versions").resolve(profile.jarId).resolve(profile.jarId + ".jar")
    )
}
    .getOrDefault(false)
