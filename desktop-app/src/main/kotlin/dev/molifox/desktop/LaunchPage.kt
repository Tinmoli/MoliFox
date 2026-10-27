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
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 开始页：左「游戏账户」卡 + 右「当前游戏」/「最近游玩」两卡。 布局按 UI_STYLE_HMCL §4 落位，业务回调与门禁保持原样。 */
@Composable
fun ColumnScope.HomePage(
    state: LauncherState,
    actions: UiActions,
    onNew: () -> Unit,
    navigate: (Page) -> Unit,
    onManage: (String) -> Unit,
) {
    val selected = state.data.instances.find { it.id == state.data.selectedInstanceId }
    val account = state.data.accounts.find { it.id == state.data.activeAccountId }
    val preferences = state.data.settings.pages.home
    val busy = actions.controller.busy
    // 启动进度只读真实的排队/运行任务，不凭空造一份「launching」状态。
    val launchJob =
        state.jobs.firstOrNull { it.status == JobStatus.Running && it.title.startsWith("启动 ") }
    val running = selected != null && state.runningInstanceId == selected.id
    val visible = rememberEntranceOnce()
    SectionTitle("开始", "Minecraft Java Edition") {
        if (preferences.showStatistics)
            Row(verticalAlignment = Alignment.CenterVertically) {
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
    }
    Row(
        Modifier.weight(1f).fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Metrics.cardGap),
    ) {
        Entrance(visible, Modifier.width(296.dp).fillMaxHeight()) {
            HomeCard("游戏账户", Modifier.fillMaxSize(), fillContent = true) {
                HomeAccountPanel(state, actions, Modifier.fillMaxWidth().weight(1f))
                Spacer(Modifier.height(10.dp))
                HorizontalDivider(color = hairline())
                Spacer(Modifier.height(10.dp))
                // 安装入口保留在账户卡底部，与启动按钮同一列，符合「安装 / 启动」两行
                SecondaryButton(
                    onNew,
                    Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(Metrics.innerRadius),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                ) {
                    Icon(Icons.Outlined.Add, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("安装游戏", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                }
                Spacer(Modifier.height(8.dp))
                LaunchActionButton(
                    progress = launchJob?.progress,
                    running = running,
                    subtitle =
                        when {
                            selected == null -> "未选择版本"
                            !selected.installed -> selected.name + " · 未安装完成"
                            else -> selected.name
                        },
                    enabled = !busy && selected != null,
                ) {
                    when {
                        selected == null -> actions.controller.store.notify("请先在「实例」中选择或安装一个版本")
                        !selected.installed -> {
                            actions.run { actions.controller.repair(selected.id) }
                            navigate(Page.Discover)
                        }
                        account == null -> actions.controller.store.notify("请先添加一个游戏账户")
                        else -> {
                            actions.run { actions.controller.launch(selected.id) }
                            navigate(Page.Discover)
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
                                {
                                    actions.run {
                                        actions.controller.openFolder(selected.id)
                                    }
                                },
                                enabled = !busy,
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                            ) {
                                Icon(Icons.Outlined.FolderOpen, null, Modifier.size(15.dp))
                                Spacer(Modifier.width(5.dp))
                                Text("打开目录", fontSize = 13.sp)
                            }
                        SecondaryButton(
                            { actions.importExisting() },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                        ) {
                            Text("选择目录", fontSize = 13.sp)
                        }
                        if (selected != null)
                            SecondaryButton(
                                { onManage(selected.id) },
                                enabled = !busy,
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                            ) {
                                Text("管理", fontSize = 13.sp)
                            }
                        Spacer(Modifier.weight(1f))
                        PrimaryButton("安装版本", Icons.Outlined.Add, onClick = onNew)
                    }
                }
                if (preferences.showRecent)
                    HomeCard(
                        "最近游玩",
                        Modifier.fillMaxWidth().weight(1f),
                        fillContent = true,
                        trailing = {
                            TextButton(
                                { navigate(Page.Instances) },
                                contentPadding = PaddingValues(horizontal = 8.dp),
                            ) {
                                Text("全部版本", fontSize = 12.sp)
                            }
                        },
                    ) {
                        val recent =
                            state.data.instances
                                .sortedByDescending { it.lastPlayed ?: it.createdAt }
                                .take(preferences.recentCount)
                        if (recent.isEmpty())
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                EmptyState(
                                    "还没有游戏版本",
                                    "添加后可从这里选择并启动",
                                    Icons.Outlined.Widgets,
                                )
                            }
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
                                                    actions.controller.selectInstance(instance.id)
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
                    }
                else Spacer(Modifier.weight(1f))
            }
        }
    }
}

/** 白卡片：与 Panel 同一套尺寸（圆角 6、1dp 细边、无阴影、内边距 16）。 单独实现是因为账户与最近游玩卡需要内容列撑满剩余高度，Panel 的内容列是包内容的。 */
@Composable
private fun HomeCard(
    title: String,
    modifier: Modifier = Modifier,
    fillContent: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier,
        shape = RoundedCornerShape(Metrics.cardRadius),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, hairline()),
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth()
                    .padding(
                        start = Metrics.cardPadding,
                        end = 8.dp,
                        top = 11.dp,
                        bottom = 10.dp,
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                trailing?.invoke()
            }
            HorizontalDivider(color = hairline())
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
                    running -> "游戏运行中"
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

/** 最近游玩时间：ISO 时间戳转本地「月-日 时:分」，解析失败时退回原始日期部分。 */
private fun playedAtLabel(instance: GameInstance): String {
    val raw = instance.lastPlayed ?: instance.createdAt
    return runCatching {
        Instant.parse(raw).atZone(ZoneId.systemDefault()).format(playedAtFormatter)
    }
        .getOrNull() ?: raw.take(10)
}
