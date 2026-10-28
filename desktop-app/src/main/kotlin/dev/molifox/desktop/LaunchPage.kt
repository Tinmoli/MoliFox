// 负责开始页账户摘要、实例选择和启动操作。

package dev.molifox.desktop

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.molifox.core.*
import java.nio.file.Files
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 开始页展示账户摘要、最近实例、当前游戏及世界与服务器入口。 */
@Composable
fun ColumnScope.HomePage(
    state: LauncherState,
    actions: UiActions,
    onNew: () -> Unit,
    navigate: (Page) -> Unit,
    onManage: (String) -> Unit,
    onOpenAccounts: () -> Unit,
) {
    // 选中记录可能为空或指向已删除的实例：先按「当前游戏目录 → 最近游玩」回退，再把回退结果
    // 写回状态。这样自动识别目录后开始页不会停在「未选择版本」，其他页面读到的也是同一个实例。
    val stored = state.data.instances.find { it.id == state.data.selectedInstanceId }
    val selected =
        stored
            ?: state.data.instances
                .filter { it.gameDirectoryId == state.data.activeGameDirectoryId }
                .maxByOrNull { it.lastPlayed ?: it.createdAt }
            ?: state.data.instances.maxByOrNull { it.lastPlayed ?: it.createdAt }
    LaunchedEffect(stored?.id, selected?.id) {
        if (stored != null) return@LaunchedEffect
        // 只回写与当前游戏目录一致的实例，避免把用户刚选定的空目录又切走；
        // 跨目录的可见实例仍然可以直接启动（点击时才会重新持久化选中）。
        val target =
            selected?.takeIf { it.gameDirectoryId == state.data.activeGameDirectoryId }
                ?: return@LaunchedEffect
        withContext(Dispatchers.IO) { runCatching { actions.controller.selectInstance(target.id) } }
    }
    // 版本配置与客户端 jar 是否还在：只在选中项变化时于 IO 线程检查一次，缺失时如实说明，
    // 不再把用户直接甩到下载页却不解释原因。
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
    // 开始页的启动路径：先把选中实例写回状态，再把任务交给控制器；进度、取消与失败原因
    // 全部落在浮窗里，不再把用户带去日志页。
    fun beginLaunch(instance: GameInstance) {
        launchCard.begin(instance)
        actions.run { actions.controller.selectInstance(instance.id) }
        try {
            launchCard.attach(actions.controller.launch(instance.id))
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
            Entrance(visible, Modifier.width(296.dp).fillMaxHeight()) {
                Column(
                    Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(Metrics.cardGap),
                ) {
                    // 账户摘要卡提供头像、名字和账户列表入口。
                    HomeCard("游戏账户") { HomeAccountSummary(state, actions, onOpenAccounts) }
                    if (preferences.showRecent)
                        HomeCard(
                            modifier = Modifier.fillMaxWidth().weight(1f),
                            fillContent = true,
                        ) {
                            // 账户卡下方最多显示六个最近实例，其余从实例页查看。
                            val all =
                                state.data.instances.sortedByDescending {
                                    it.lastPlayed ?: it.createdAt
                                }
                            val recent = all.take(minOf(preferences.recentCount, 6))
                            Text(
                                "最近游玩的游戏实例",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(6.dp))
                            if (recent.isEmpty())
                                EmptyState(
                                    "还没有游戏版本",
                                    "添加后可从这里选择并启动",
                                    Icons.Outlined.Widgets,
                                )
                            else {
                                val listState = rememberLazyListState()
                                Box(Modifier.weight(1f).fillMaxWidth()) {
                                    LazyColumn(
                                        Modifier.fillMaxSize(),
                                        state = listState,
                                        verticalArrangement = Arrangement.spacedBy(2.dp),
                                    ) {
                                        items(recent, key = { it.id }) { instance ->
                                            RecentRow(
                                                actions = actions,
                                                instance = instance,
                                                current = instance.id == selected?.id,
                                                onClick = {
                                                    actions.run {
                                                        actions.controller.selectInstance(
                                                            instance.id
                                                        )
                                                    }
                                                },
                                            )
                                        }
                                    }
                                    VerticalScrollbar(
                                        rememberScrollbarAdapter(listState),
                                        Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                                    )
                                }
                            }
                            // 超出 6 条时不截断内容，只把「其余」指向实例页。
                            if (all.size > recent.size)
                                TextButton(
                                    { navigate(Page.Instances) },
                                    Modifier.align(Alignment.End),
                                    contentPadding = PaddingValues(horizontal = 8.dp),
                                ) {
                                    Text("查看全部", fontSize = 12.sp)
                                }
                        }
                    else Spacer(Modifier.weight(1f))
                }
            }
            Entrance(visible, Modifier.weight(1f).fillMaxHeight()) {
                Column(
                    Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(Metrics.cardGap),
                ) {
                    HomeCard("当前游戏") {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (selected != null)
                                InstanceArtwork(selected, actions, Modifier.size(40.dp))
                            else GrassBlockIcon(size = 40.dp)
                            Spacer(Modifier.width(12.dp))
                            Column(
                                Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(3.dp),
                            ) {
                                Text(
                                    selected?.name ?: "尚未选择版本",
                                    style = MaterialTheme.typography.titleLarge,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (selected != null)
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        Text(
                                            selected.gameVersion + " · " + selected.loader.title,
                                            fontSize = 12.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Tag(
                                            if (actions.controller.store.isolated(selected)) "版本隔离"
                                            else "共用目录"
                                        )
                                    }
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
                        Spacer(Modifier.height(6.dp))
                        Text(
                            selected?.let { actions.controller.store.gamePath(it).toString() }
                                ?: actions.controller.store.minecraftRoot.toString(),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(12.dp))
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            if (selected != null)
                                SecondaryButton(
                                    { onManage(selected.id) },
                                    enabled = !busy,
                                    contentPadding =
                                        PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                                ) {
                                    Text("管理", fontSize = 13.sp)
                                }
                            Spacer(Modifier.weight(1f))
                            PrimaryButton("安装版本", Icons.Outlined.Add, onClick = onNew)
                        }
                        Spacer(Modifier.height(12.dp))
                        // 当前游戏区域提供安装与启动操作。
                        LaunchActionButton(
                            progress = launchJob?.progress,
                            running = running,
                            subtitle =
                                when {
                                    selected == null -> "未选择版本"
                                    !selected.installed -> selected.name + " · 未安装完成"
                                    selected.externalProfile && !filesPresent ->
                                        selected.name + " · 版本文件缺失"
                                    !filesPresent -> selected.name + " · 启动时补全文件"
                                    else -> selected.name
                                },
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
                                !selected.installed -> {
                                    // 修复任务仍在后台推进；这里不再把用户带去日志页，进度可从左侧「日志」入口查看。
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
                    }
                    // 世界与服务器区域只列当前选中实例；没有选中实例时
                    // 整页已经是「未选择版本」空态，这里不再画第二个空块。「查看全部」在面板内开浮层。
                    if (selected != null)
                        RecentPlayPanel(
                            instance = selected,
                            actions = actions,
                            busy = busy,
                            onLaunch = { beginLaunch(it) },
                            modifier = Modifier.fillMaxWidth().weight(1f),
                        )
                    else Spacer(Modifier.weight(1f))
                }
            }
        }
        // 启动浮窗固定在右下角：不遮挡左下的「启动游戏」按钮，点击页面其他区域也不会关闭它。
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
 * 白卡片：与 Panel 同一套尺寸（圆角 6、1dp 细边、无阴影、内边距 16）。 单独实现是因为账户与最近游玩卡需要内容列撑满剩余高度，Panel 的内容列是包内容的。 [title]
 * 留空时不渲染标题行与分隔线：左栏「最近游玩的游戏实例」用的就是无标题卡片 + 12sp 小标题。 右栏的 [RecentPlayPanel] 复用同一张卡片，因此这里是 internal 而不是
 * private。
 */
@Composable
internal fun HomeCard(
    title: String = "",
    modifier: Modifier = Modifier,
    fillContent: Boolean = false,
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
                Modifier.padding(Metrics.cardPadding)
                    .then(if (fillContent) Modifier.weight(1f) else Modifier),
                content = content,
            )
        }
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

/** 最近游玩行：44dp、悬停淡入，行尾箭头表示进入该版本。 */
@Composable
private fun RecentRow(
    instance: GameInstance,
    current: Boolean,
    actions: UiActions,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(Metrics.innerRadius)
    Row(
        Modifier.fillMaxWidth()
            .height(Metrics.rowHeight)
            .hoverHighlight(shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        InstanceArtwork(instance, actions, Modifier.size(28.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                instance.name,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                instance.gameVersion + " · " + playedAtLabel(instance),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (current) Tag("当前")
        Spacer(Modifier.width(4.dp))
        Icon(
            Icons.Outlined.ChevronRight,
            null,
            Modifier.size(16.dp),
            MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 启动主按钮：全宽、40dp、圆角 4，双行文字；有真实启动任务时在左侧显示进度环与百分比。 */
@Composable
private fun LaunchActionButton(
    progress: Float?,
    running: Boolean,
    subtitle: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Button(
        onClick,
        Modifier.fillMaxWidth().height(40.dp).pressScale(),
        enabled = enabled,
        shape = RoundedCornerShape(Metrics.innerRadius),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
    ) {
        if (progress != null)
            CircularProgressIndicator(
                Modifier.size(14.dp),
                strokeWidth = 2.dp,
                color = LocalContentColor.current,
            )
        else Icon(Icons.Outlined.PlayArrow, null, Modifier.size(17.dp))
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f, fill = false), horizontalAlignment = Alignment.Start) {
            Text(
                when {
                    progress != null -> "正在启动 " + (progress * 100).toInt() + "%"
                    running -> "再次启动"
                    else -> "启动游戏"
                },
                fontSize = 14.sp,
                lineHeight = 17.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                subtitle,
                fontSize = 11.sp,
                lineHeight = 14.sp,
                color = LocalContentColor.current.copy(alpha = .8f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private val playedAtFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm")

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

/** 最近游玩时间：ISO 时间戳转本地「月-日 时:分」，解析失败时退回原始日期部分。 */
private fun playedAtLabel(instance: GameInstance): String {
    val raw = instance.lastPlayed ?: instance.createdAt
    return runCatching {
        Instant.parse(raw).atZone(ZoneId.systemDefault()).format(playedAtFormatter)
    }
        .getOrNull() ?: raw.take(10)
}
