// 负责当前实例的最近世界、服务器和展开列表。

package dev.molifox.desktop

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import dev.molifox.core.*
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.isDirectory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image as SkiaImage

/** 面板卡片最多显示的行数；完整列表在「查看全部」浮层里展开，不截断数据来源。 */
private const val RECENT_PLAY_ROWS = 8

/**
 * 开始页右栏下半：「最近游玩的存档或服务器」。
 *
 * 标题行的分段开关决定正文只渲染**一类**内容——「存档」或「服务器」，不再把两类混排：
 * 存档来自 `saves/`（[SaveManager.listSaves]），服务器来自游戏目录的 `servers.dat`（[listServers]），
 * 各自按最近使用时间倒序取前 [RECENT_PLAY_ROWS] 条。 条目优先使用本启动器记录的 [RecentUsage.lastUsed]，世界没有记录时退回目录修改时间。
 * 每次加载/刷新在读完磁盘后调用 [RecentUsage.pruneMissing]，把已经消失的世界或服务器记录 清掉；读取与清理全部在 `Dispatchers.IO`
 * 上完成，刷新按钮只是递增 [refreshToken] 重新读一遍磁盘。
 *
 * 「开始游戏」走调用方传入的 [onLaunch]——开始页的主「启动游戏」按钮用的就是同一条 [LauncherController.launch] 路径；本面板只负责先记一次
 * [RecentUsage.record]。 「查看全部」在面板内打开「存档与服务器」浮层（Esc 或关闭按钮退出）。 [instance] 为 null（未选择版本）时整块不渲染。
 *
 * 面板可折叠：标题行的箭头 180° 旋转（180ms）+ 内容区高度动画，折叠后只剩约 44dp 的标题行。展开状态与选中的类别都由调用方读写页面偏好
 * [HomePagePreferences.recentPlayExpanded] / [HomePagePreferences.recentPlayKind]，因此重启后保持上次的选择。折叠不会中断启动——
 * 「开始游戏」的协程挂在面板自身的作用域上， 折叠只是把内容区移出组合；标题行的刷新与「查看全部」在折叠时仍然可用。
 */
@Composable
fun RecentPlayPanel(
    instance: GameInstance?,
    actions: UiActions,
    busy: Boolean,
    /** 内容区是否展开；由调用方从页面偏好读出。 */
    expanded: Boolean,
    /** 点折叠箭头时回写页面偏好。 */
    onExpandedChange: (Boolean) -> Unit,
    /** 正文显示哪一类：存档或服务器；由调用方从页面偏好读出。 */
    kind: EntryKind,
    /** 点分段开关时回写页面偏好。 */
    onKindChange: (EntryKind) -> Unit,
    onLaunch: (GameInstance) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 未选择版本时整页已经是「未选择版本」空态，这里不再画第二个空块。
    if (instance == null) return
    val controller = actions.controller
    val usage = remember(controller) { controller.store.recentUsage }
    val liveState by controller.state.collectAsState()
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    var refreshToken by remember(instance.id) { mutableStateOf(0) }
    // 删除存档先确认：实际动作用 SaveManager 移入回收站，不在面板里做硬删除。
    var deleting by remember(instance.id) { mutableStateOf<SaveEntry?>(null) }
    // 「查看全部」在本页浮层里展开全部存档与服务器。
    var showAll by remember(instance.id) { mutableStateOf(false) }
    val data by
        produceState(RecentPlayData(), instance.id, refreshToken, liveState.recentUsageRevision) {
            // 切实例时先清掉上一个实例的行，避免短暂显示别人的存档；同一实例刷新则保留旧行。
            if (value.instanceId != instance.id) value = RecentPlayData(instanceId = instance.id)
            value =
                withContext(Dispatchers.IO) {
                    runCatching { loadRecentPlays(controller, instance, usage) }
                        .getOrElse { RecentPlayData(instanceId = instance.id, loading = false) }
                }
        }
    // 正文只渲染选中的一类；「查看全部」与空提示都按这一类的数量判断。
    val itemsOfKind = remember(data.all, kind) { data.all.filter { it.kind == kind } }
    val shownItems = itemsOfKind.take(RECENT_PLAY_ROWS)
    val hasMore = itemsOfKind.size > shownItems.size
    // 点「开始游戏」：先写最近使用记录，再走与主启动按钮相同的入口。记录的写入放到 IO 线程，
    // 启动本身仍是调用方那条同步路径，进度与失败原因继续留在开始页的启动浮窗。
    fun beginPlay(kind: EntryKind, key: String) {
        // 记录选中条目的使用时间；启动仍进入游戏主界面。
        scope.launch {
            withContext(Dispatchers.IO) { runCatching { usage.record(kind, instance.id, key) } }
            refreshToken += 1
            onLaunch(instance)
        }
    }
    fun openSaveFolder(entry: SaveEntry) {
        // 世界目录本身；`.zip` 存档只能打开它所在的 saves/ 目录。
        val target = if (entry.path.isDirectory()) entry.path else (entry.path.parent ?: return)
        scope.launch {
            withContext(Dispatchers.IO) {
                runCatching { controller.platform.openFolder(target) }
            }
        }
    }
    fun deleteSave(entry: SaveEntry) {
        scope.launch {
            val moved =
                withContext(Dispatchers.IO) {
                    runCatching { controller.deleteSave(instance.id, entry.name) }
                        .getOrDefault(false)
                }
            if (!moved)
                controller.store.notify("「" + entry.name + "」没有移入回收站：存档可能已不存在，或回收站不可用；详情见日志。")
            else {
                // 行菜单删除成功后立刻删掉这条世界记录；下一次加载的 pruneMissing 还会按磁盘
                // 再兜一次（例如存档被别的工具删掉、或删除时记录恰好不存在）。
                withContext(Dispatchers.IO) {
                    runCatching { usage.forget(EntryKind.World, instance.id, entry.name) }
                }
            }
            refreshToken += 1
        }
    }
    fun forgetServer(item: RecentPlayItem) {
        scope.launch {
            withContext(Dispatchers.IO) {
                runCatching { usage.forget(EntryKind.Server, instance.id, item.key) }
            }
            controller.store.notify("已删除「" + item.title + "」的最近游玩记录")
            refreshToken += 1
        }
    }
    fun copyAddress(item: RecentPlayItem) {
        val copied = runCatching { clipboard.setText(AnnotatedString(item.key)) }.isSuccess
        controller.store.notify(if (copied) "已复制服务器地址：" + item.key else "复制失败，请手动复制：" + item.key)
    }
    // 关闭动画或开启「减少动态效果」时直接跳变：箭头与内容区都不播放过渡。
    val motionMillis = if (LocalAnimationsEnabled.current && !LocalReducedMotion.current) 180 else 0
    HomeCard(modifier = modifier, contentPadding = PaddingValues(0.dp)) {
        // 标题行固定 44dp：折叠后整张卡片就只有这一行。
        Row(
            Modifier.fillMaxWidth().height(44.dp).padding(horizontal = Metrics.cardPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "最近游玩的存档或服务器",
                Modifier.weight(1f),
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // 折叠后内容区不在组合里，「查看全部」改由标题行提供，浮层照样打得开。
            if (!expanded && hasMore)
                TextButton(
                    { showAll = true },
                    Modifier.height(Metrics.controlHeight),
                    contentPadding = PaddingValues(horizontal = 8.dp),
                ) {
                    Text("查看全部", fontSize = 12.sp)
                }
            // 分段开关：只切换正文显示哪一类，两类数据仍然一次读盘、共用缓存。
            KindSwitch(kind, onKindChange)
            Spacer(Modifier.width(6.dp))
            SmallIconButton(
                Icons.Outlined.Refresh,
                "刷新存档与服务器",
                onClick = {
                    // 手动刷新时丢掉服务器状态缓存，可见的行会重新 ping 一次。
                    ServerPingCache.clear()
                    refreshToken += 1
                },
            )
            // 折叠箭头：展开时转 180° 朝上，折叠时朝下。
            val angle by
                animateFloatAsState(
                    targetValue = if (expanded) 180f else 0f,
                    animationSpec = tween(motionMillis),
                    label = "recentPlayChevron",
                )
            Box(Modifier.rotate(angle)) {
                SmallIconButton(
                    Icons.Outlined.ExpandMore,
                    if (expanded) "折叠最近游玩的存档或服务器" else "展开最近游玩的存档或服务器",
                    onClick = { onExpandedChange(!expanded) },
                )
            }
        }
        // 内容区高度动画：AnimatedVisibility 默认裁剪，折叠时高度收到 0，卡片跟着变矮。
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(tween(motionMillis)),
            exit = shrinkVertically(tween(motionMillis)),
        ) {
            Column(
                Modifier.fillMaxWidth()
                    .fillMaxHeight()
                    .padding(
                        start = Metrics.cardPadding,
                        end = Metrics.cardPadding,
                        bottom = Metrics.cardPadding,
                    )
            ) {
                Spacer(Modifier.height(2.dp))
                when {
                    data.loading && data.all.isEmpty() -> Spacer(Modifier.weight(1f))
                    shownItems.isEmpty() ->
                        Text(
                            if (kind == EntryKind.World) "当前实例还没有存档" else "当前实例还没有服务器",
                            Modifier.padding(top = 4.dp, bottom = 2.dp),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    else -> {
                        // 一次组合共用一个「现在」，同一屏内各行的时间基准一致。
                        val now = Instant.now()
                        val listState = rememberLazyListState()
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            LazyColumn(
                                Modifier.fillMaxSize(),
                                state = listState,
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                items(shownItems, key = { it.kind.name + ":" + it.key }) { item ->
                                    when (item.kind) {
                                        EntryKind.World ->
                                            WorldPlayRow(
                                                entry = item,
                                                timeLabel = relativeTimeLabel(item.usedAt, now),
                                                enabled = !busy,
                                                onPlay = { beginPlay(EntryKind.World, item.key) },
                                                onOpenFolder = {
                                                    item.save?.let { openSaveFolder(it) }
                                                },
                                                onDelete = { item.save?.let { deleting = it } },
                                            )
                                        EntryKind.Server -> {
                                            // 只有进入组合（可见）的行才 ping；缓存 60 秒，滚动回来不重发。
                                            val ping = rememberServerPing(item.key, refreshToken)
                                            ServerPlayRow(
                                                entry = item,
                                                timeLabel =
                                                    if (item.usedAt == null) "尚未游玩"
                                                    else relativeTimeLabel(item.usedAt, now),
                                                status = ping?.status,
                                                pinged = ping != null,
                                                enabled = !busy,
                                                onPlay = { beginPlay(EntryKind.Server, item.key) },
                                                onCopyAddress = { copyAddress(item) },
                                                onForget = { forgetServer(item) },
                                            )
                                        }
                                    }
                                }
                            }
                            VerticalScrollbar(
                                rememberScrollbarAdapter(listState),
                                Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                            )
                        }
                        if (hasMore)
                            TextButton(
                                { showAll = true },
                                Modifier.align(Alignment.End),
                                contentPadding = PaddingValues(horizontal = 8.dp),
                            ) {
                                Text("查看全部", fontSize = 12.sp)
                            }
                    }
                }
            }
        }
    }
    // 浮层列出全部存档与服务器（不受面板 8 行上限约束），行样式与面板一致。
    if (showAll)
        AllRecentPlayDialog(
            data = data,
            busy = busy,
            refreshToken = refreshToken,
            onDismiss = { showAll = false },
            onRefresh = {
                ServerPingCache.clear()
                refreshToken += 1
            },
            onPlay = { kind, key ->
                showAll = false
                beginPlay(kind, key)
            },
            onOpenFolder = { openSaveFolder(it) },
            onDelete = { deleting = it },
            onCopyAddress = { copyAddress(it) },
            onForgetServer = { forgetServer(it) },
        )
    deleting?.let { save ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("删除这个存档？") },
            text = { Text("「" + save.name + "」会移入回收站，可从回收站恢复。") },
            confirmButton = {
                TextButton({
                    deleting = null
                    deleteSave(save)
                }) {
                    Text("移入回收站", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton({ deleting = null }) { Text("取消") } },
        )
    }
}

/**
 * 存档行：32dp 世界图标（游戏写入的 `<saves>/<世界>/icon.png`，缺失或读不出时用草方块）、
 * 目录名、「单人存档 · 游玩时间」，随后紧跟文字操作「开始游戏 / 打开文件夹 / 删除」。
 */
@Composable
private fun WorldPlayRow(
    entry: RecentPlayItem,
    timeLabel: String,
    enabled: Boolean,
    onPlay: () -> Unit,
    onOpenFolder: () -> Unit,
    onDelete: () -> Unit,
) {
    val time = timeLabel.trim()
    PlayRowFrame(
        leading = { WorldPlayIcon(entry) },
        title = entry.title,
        subtitle = {
            PlaySubtitle(
                AnnotatedString(
                    if (time.isEmpty()) entry.subtitle else entry.subtitle + "  ·  " + time
                )
            )
        },
        actions = {
            PlayRowAction("开始游戏", MaterialTheme.colorScheme.primary, enabled, onPlay)
            PlayRowAction("打开文件夹", MaterialTheme.colorScheme.onSurface, enabled, onOpenFolder)
            PlayRowAction("删除", MaterialTheme.colorScheme.error, enabled, onDelete)
        },
    )
}

/**
 * 世界图标：优先用世界目录（或 `.zip`）里的 `icon.png`，读不出就退回草方块。
 * 图标按「路径 + 修改时间」缓存，滚动或切页回来不会重复解码。
 */
@Composable
private fun WorldPlayIcon(entry: RecentPlayItem) {
    val save = entry.save
    if (save == null) {
        GrassBlockIcon(size = 32.dp)
        return
    }
    LocalContentThumbnail(
        path = save.path,
        world = true,
        modifier = Modifier.size(32.dp).clip(RoundedCornerShape(4.dp)),
        modified = save.lastModified?.toEpochMilli(),
        fallback = { GrassBlockIcon(size = 32.dp) },
    )
}

/**
 * 服务器行：32dp `servers.dat` 图标（解不出 PNG 时退回草方块）、服务器名，
 * 副标题是「MOTD（或地址） · 玩家数/离线 · 游玩时间」；MOTD 用带颜色的分段渲染，
 * 悬停显示 `servers.dat` 里的完整地址。随后紧跟文字操作「开始游戏 / 复制地址 / 删除记录」。
 *
 * 没有查询结果时仍显示 `servers.dat` 里保存的名称与地址；离线时只多一个「离线」标签，开始游戏不受影响。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ServerPlayRow(
    entry: RecentPlayItem,
    timeLabel: String,
    status: ServerStatus?,
    pinged: Boolean,
    enabled: Boolean,
    onPlay: () -> Unit,
    onCopyAddress: () -> Unit,
    onForget: () -> Unit,
) {
    val motd = status?.motd.orEmpty().filter { it.text.isNotBlank() }
    val motdText = if (motd.isEmpty()) null else rememberMotdText(motd)
    val statusLabel =
        when {
            status != null -> status.players.toString() + "/" + status.maxPlayers
            pinged -> "离线"
            else -> null
        }
    // 状态与游玩时间并进副标题：操作要紧跟在文字后面，行尾不再留第二组标签。
    val meta = listOfNotNull(statusLabel, timeLabel.trim().ifEmpty { null }).joinToString("  ·  ")
    val subtitle =
        remember(motdText, entry.subtitle, meta) {
            buildAnnotatedString {
                append(motdText ?: AnnotatedString(entry.subtitle))
                if (meta.isNotEmpty()) append("  ·  " + meta)
            }
        }
    PlayRowFrame(
        leading = {
            if (entry.icon != null)
                Image(
                    requireNotNull(entry.icon),
                    entry.title + " 图标",
                    Modifier.size(32.dp).clip(RoundedCornerShape(4.dp)),
                    filterQuality = FilterQuality.None,
                )
            else GrassBlockIcon(size = 32.dp)
        },
        title = entry.title,
        subtitle = {
            if (motdText != null)
                TooltipArea(tooltip = { AddressTooltip(entry.subtitle) }, delayMillis = 400) {
                    PlaySubtitle(subtitle, subtitleSize = 11.sp)
                }
            else PlaySubtitle(subtitle)
        },
        actions = {
            PlayRowAction("开始游戏", MaterialTheme.colorScheme.primary, enabled, onPlay)
            PlayRowAction("复制地址", MaterialTheme.colorScheme.onSurface, enabled, onCopyAddress)
            PlayRowAction("删除记录", MaterialTheme.colorScheme.error, enabled, onForget)
        },
    )
}

/**
 * 两类行共用的骨架：32dp 图标 + 标题/副标题 + 紧随其后的文字操作，行高 52、悬停淡入。
 *
 * 文字列用 `weight(1f, fill = false)`：按内容宽度收窄但最多占满剩余空间，
 * 因此操作紧跟在标题/副标题之后，而不是贴到行尾；行里多出来的空白留在操作右侧。
 */
@Composable
private fun PlayRowFrame(
    leading: @Composable () -> Unit,
    title: String,
    subtitle: @Composable () -> Unit,
    actions: @Composable RowScope.() -> Unit,
) {
    val shape = RoundedCornerShape(Metrics.innerRadius)
    Row(
        Modifier.fillMaxWidth()
            .height(Metrics.rowHeightWithDetail)
            .hoverHighlight(shape)
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) { leading() }
        Column(
            Modifier.weight(1f, fill = false),
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            Text(
                title,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            subtitle()
        }
        Row(
            Modifier.padding(start = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = actions,
        )
    }
}

/** 行内副标题：单行省略；没有显式颜色的 MOTD 片段沿用次要文字色。 */
@Composable
private fun PlaySubtitle(text: AnnotatedString, subtitleSize: TextUnit = 12.sp) {
    Text(
        text,
        fontSize = subtitleSize,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * 行内文字操作：12sp 纯文字、无底色、无胶囊，悬停时淡出一层主题色背景。
 * 只有「开始游戏」用主题色，「删除」用错误色，其余是次要文字色；禁用时整体降到 38% 透明度。
 */
@Composable
private fun PlayRowAction(text: String, color: Color, enabled: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(Metrics.innerRadius)
    Text(
        text,
        Modifier.clip(shape)
            .hoverHighlight(shape, enabled = enabled)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            )
            .padding(horizontal = 6.dp, vertical = 4.dp),
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        color = if (enabled) color else color.copy(alpha = .38f),
        maxLines = 1,
    )
}

/**
 * 标题行的分段开关：「存档」/「服务器」二选一，正文只渲染选中的一类。
 * 选中项用主题色浅底 + 主题色文字，未选中项是次要色文字，未选中项悬停有反馈。
 */
@Composable
private fun KindSwitch(kind: EntryKind, onKindChange: (EntryKind) -> Unit) {
    val shape = RoundedCornerShape(Metrics.innerRadius)
    Row(
        Modifier.clip(shape)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = .04f))
            .padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        KindSwitchItem("存档", kind == EntryKind.World) { onKindChange(EntryKind.World) }
        KindSwitchItem("服务器", kind == EntryKind.Server) { onKindChange(EntryKind.Server) }
    }
}

/** 分段开关的一段；已选中时不再响应点击，避免重复写偏好。 */
@Composable
private fun KindSwitchItem(text: String, selected: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(Metrics.innerRadius)
    Text(
        text,
        Modifier.clip(shape)
            .background(
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = .14f)
                else Color.Transparent
            )
            .hoverHighlight(shape, enabled = !selected)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = !selected,
                onClick = onClick,
            )
            .padding(horizontal = 8.dp, vertical = 2.dp),
        fontSize = 11.sp,
        fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
        color =
            if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
    )
}

/**
 * 「查看全部」浮层：列出当前实例的全部存档与服务器（不受面板 [RECENT_PLAY_ROWS] 限制）， 分为「存档 / 服务器」两组，组内按时间倒序。
 *
 * 行样式直接复用面板的 [WorldPlayRow] / [ServerPlayRow]，所以开始游戏、打开文件夹、删除、 复制地址、删除记录的行为与卡片里完全一致。高度最多占主窗口的
 * 70%，超出时列表内部滚动； Esc 或右上角关闭按钮退出，右上角刷新重新扫描磁盘。
 */
@Composable
private fun AllRecentPlayDialog(
    data: RecentPlayData,
    busy: Boolean,
    refreshToken: Int,
    onDismiss: () -> Unit,
    onRefresh: () -> Unit,
    onPlay: (EntryKind, String) -> Unit,
    onOpenFolder: (SaveEntry) -> Unit,
    onDelete: (SaveEntry) -> Unit,
    onCopyAddress: (RecentPlayItem) -> Unit,
    onForgetServer: (RecentPlayItem) -> Unit,
) {
    // 取主窗口高度（Dp）而不是对话框自身高度：内容比窗口高时才能正确截到 70%。
    val windowHeight = LocalWindowInfo.current.containerDpSize.height
    val maxHeight =
        if (windowHeight.value.isFinite() && windowHeight.value > 0f)
            (windowHeight * .7f).coerceAtLeast(260.dp)
        else 460.dp
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            Modifier.width(520.dp).focusRequester(focusRequester).focusable().onPreviewKeyEvent {
                event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                    onDismiss()
                    true
                } else false
            },
            shape = RoundedCornerShape(Metrics.dialogRadius),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, hairline()),
        ) {
            Column(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.fillMaxWidth().height(44.dp).padding(start = 14.dp, end = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("存档与服务器", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.weight(1f))
                    SmallIconButton(
                        Icons.Outlined.Refresh,
                        "重新扫描存档与服务器",
                        onClick = onRefresh,
                    )
                    SmallIconButton(
                        Icons.Outlined.Close,
                        "关闭存档与服务器列表",
                        onClick = onDismiss,
                    )
                }
                HorizontalDivider(color = hairline())
                AllRecentPlayList(
                    items = data.all,
                    busy = busy,
                    refreshToken = refreshToken,
                    listMaxHeight = (maxHeight - 45.dp).coerceAtLeast(120.dp),
                    onPlay = onPlay,
                    onOpenFolder = onOpenFolder,
                    onDelete = onDelete,
                    onCopyAddress = onCopyAddress,
                    onForgetServer = onForgetServer,
                )
            }
        }
    }
}

/** 浮层内容：分组的全部行；列表本身在 [listMaxHeight] 内滚动。 */
@Composable
private fun AllRecentPlayList(
    items: List<RecentPlayItem>,
    busy: Boolean,
    refreshToken: Int,
    listMaxHeight: Dp,
    onPlay: (EntryKind, String) -> Unit,
    onOpenFolder: (SaveEntry) -> Unit,
    onDelete: (SaveEntry) -> Unit,
    onCopyAddress: (RecentPlayItem) -> Unit,
    onForgetServer: (RecentPlayItem) -> Unit,
) {
    if (items.isEmpty()) {
        Text(
            "当前实例还没有存档或服务器",
            Modifier.padding(horizontal = 14.dp, vertical = 16.dp),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    val worlds = items.filter { it.kind == EntryKind.World }
    val servers = items.filter { it.kind == EntryKind.Server }
    val now = Instant.now()
    // 列表高度受 [listMaxHeight] 限制，超出的部分用滚轮滚动（对话框里不再叠加滚动条）。
    LazyColumn(
        Modifier.fillMaxWidth().heightIn(max = listMaxHeight),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (worlds.isNotEmpty()) {
            item(key = "section-worlds") { RecentSectionLabel("存档") }
            items(worlds, key = { "world:" + it.key }) { item ->
                WorldPlayRow(
                    entry = item,
                    timeLabel = relativeTimeLabel(item.usedAt, now),
                    enabled = !busy,
                    onPlay = { onPlay(EntryKind.World, item.key) },
                    onOpenFolder = { item.save?.let(onOpenFolder) },
                    onDelete = { item.save?.let(onDelete) },
                )
            }
        }
        if (servers.isNotEmpty()) {
            item(key = "section-servers") { RecentSectionLabel("服务器") }
            items(servers, key = { "server:" + it.key }) { item ->
                // 只有浮层里真正可见的服务器行才查询状态，缓存与卡片共用。
                val ping = rememberServerPing(item.key, refreshToken)
                ServerPlayRow(
                    entry = item,
                    timeLabel =
                        if (item.usedAt == null) "尚未游玩" else relativeTimeLabel(item.usedAt, now),
                    status = ping?.status,
                    pinged = ping != null,
                    enabled = !busy,
                    onPlay = { onPlay(EntryKind.Server, item.key) },
                    onCopyAddress = { onCopyAddress(item) },
                    onForget = { onForgetServer(item) },
                )
            }
        }
    }
}

/** 浮层里的分组标题：12sp 次要色，与卡片小标题同一套字号。 */
@Composable
private fun RecentSectionLabel(text: String) {
    Text(
        text,
        Modifier.padding(start = 6.dp, top = 8.dp, bottom = 2.dp),
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * MOTD 片段的样式表：每段按自己的颜色与粗体/斜体/下划线/删除线渲染， 没有指定颜色的片段沿用副标题的次要色。
 *
 * 深色主题下 `§0` 这类近黑色会看不清，统一向白色提亮 50%； 浅色主题保持服务器给出的原色。
 */
@Composable
private fun rememberMotdText(segments: List<MotdSegment>): AnnotatedString {
    val dark = isDarkScheme()
    return remember(segments, dark) {
        buildAnnotatedString {
            segments.forEach { segment ->
                val color =
                    segment.color?.let { rgb ->
                        val base = Color(0xFF000000L or rgb.toLong())
                        if (dark && base.luminance() < .28f) lerp(base, Color.White, .5f) else base
                    }
                withStyle(
                    SpanStyle(
                        color = color ?: Color.Unspecified,
                        fontWeight = if (segment.bold) FontWeight.SemiBold else null,
                        fontStyle = if (segment.italic) FontStyle.Italic else null,
                        textDecoration = segmentTextDecoration(segment),
                    )
                ) {
                    append(segment.text)
                }
            }
        }
    }
}

/** 下划线与删除线可以同时存在。 */
private fun segmentTextDecoration(segment: MotdSegment): TextDecoration? =
    when {
        segment.underlined && segment.struck ->
            TextDecoration.combine(listOf(TextDecoration.Underline, TextDecoration.LineThrough))
        segment.underlined -> TextDecoration.Underline
        segment.struck -> TextDecoration.LineThrough
        else -> null
    }

/** MOTD 取代地址时的悬停提示：显示 `servers.dat` 里的完整地址，样式与卡片一致。 */
@Composable
private fun AddressTooltip(address: String) {
    Surface(
        shape = RoundedCornerShape(Metrics.innerRadius),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, hairline()),
        shadowElevation = 2.dp,
    ) {
        Text(
            address,
            Modifier.widthIn(max = 320.dp).padding(horizontal = 8.dp, vertical = 5.dp),
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 一次查询的快照：`status == null` 表示已确认离线；缓存里没有条目才表示「还没查过」。 */
private data class ServerPingSnapshot(val status: ServerStatus?, val atMillis: Long)

/**
 * 进程内服务器状态缓存：同一地址 60 秒内不重复查询，滚动、切换页面再回来都命中缓存。 键是 `servers.dat` 里的 `ip`（含可选端口），不同实例里的同一个服务器共用一份结果。
 */
private object ServerPingCache {
    private const val TTL_MILLIS = 60_000L
    private val entries = ConcurrentHashMap<String, ServerPingSnapshot>()

    fun get(address: String, now: Long = System.currentTimeMillis()): ServerPingSnapshot? =
        entries[address]?.takeIf { now - it.atMillis in 0..TTL_MILLIS }

    fun put(address: String, status: ServerStatus?) {
        entries[address] = ServerPingSnapshot(status, System.currentTimeMillis())
    }

    fun clear() {
        entries.clear()
    }
}

/**
 * 行内查询服务器状态：只有行真正进入组合（可见）时才发请求，默认 4 秒超时， 全程在 IO 线程上，不阻塞界面。[refreshToken] 变化时重新查询——刷新按钮会先清空缓存，
 * 因此可见行会真正重发；查询失败缓存 null，界面显示「离线」并保留保存的名称与地址。
 */
@Composable
private fun rememberServerPing(address: String, refreshToken: Int): ServerPingSnapshot? {
    var snapshot by remember(address) { mutableStateOf(ServerPingCache.get(address)) }
    LaunchedEffect(address, refreshToken) {
        ServerPingCache.get(address)?.let { cached ->
            snapshot = cached
            return@LaunchedEffect
        }
        val status =
            withContext(Dispatchers.IO) { runCatching { pingAddress(address) }.getOrNull() }
        ServerPingCache.put(address, status)
        snapshot = ServerPingSnapshot(status, System.currentTimeMillis())
    }
    return snapshot
}

/** 面板的读取结果：`loading` 与「空」分开，加载中不显示空提示。 */
private data class RecentPlayData(
    val instanceId: String = "",
    /** 全部行（不截断，两类都有）；面板按选中的类别取前 [RECENT_PLAY_ROWS] 行，「查看全部」浮层列出全部。 */
    val all: List<RecentPlayItem> = emptyList(),
    val loading: Boolean = true,
)

/** 混合列表的一行：`save` 或 `server` 至少有一个非空，`icon` 只对服务器有用。 */
private data class RecentPlayItem(
    val kind: EntryKind,
    val key: String,
    val title: String,
    val subtitle: String,
    val usedAt: Instant?,
    val save: SaveEntry? = null,
    val server: ServerEntry? = null,
    val icon: ImageBitmap? = null,
)

/**
 * 在 IO 线程读一遍磁盘：存档按修改时间、服务器按最近使用时间各自倒序，再按时间合并。 单个来源失败只影响自己那一类，读取异常由调用方兜底成空列表。
 *
 * 读完顺手做一次过期清理（[RecentUsage.pruneMissing]）：世界要求 `saves/<名称>` 仍是带 `level.dat` 的世界（目录或 `.zip`
 * 都算），服务器要求 `servers.dat` 里仍有这个 `ip`。 只有扫描**成功**才清理——扫描失败时拿到的是空列表，不能据此把记录全删掉。
 */
private fun loadRecentPlays(
    controller: LauncherController,
    instance: GameInstance,
    usage: RecentUsage,
): RecentPlayData {
    // 两路各保留 Result：判断「这一轮到底读到了什么」，供下面的清理使用。
    val savesScan = runCatching { controller.listSaves(instance.id) }
    val saves = savesScan.getOrDefault(emptyList())
    val serversScan = runCatching {
        controller.store.gamePath(instance)
    }
        .getOrNull()
        ?.let { directory -> runCatching { listServers(directory) } }
    val savedServers = serversScan?.getOrDefault(emptyList()).orEmpty()
    val recentServers =
        usage
            .recent(EntryKind.Server, instance.id, 100)
            .filter { key ->
                savedServers.none {
                    sameServerAddress(parseServerAddress(it.ip), parseServerAddress(key))
                }
            }
            .map { ServerEntry(it, it, null) }
    val servers = savedServers + recentServers
    savesScan.onSuccess { entries ->
        usage.pruneMissing(
            EntryKind.World,
            instance.id,
            entries.filter { it.hasLevelDat }.map { it.name }.toSet(),
        )
    }
    val worlds =
        saves
            .sortedByDescending { it.lastModified ?: Instant.EPOCH }
            .map { save ->
                RecentPlayItem(
                    kind = EntryKind.World,
                    key = save.name,
                    title = save.name,
                    subtitle = "单人存档",
                    usedAt = save.lastModified,
                    save = save,
                )
            }
    val serverItems =
        servers
            .distinctBy { it.ip }
            .map { server ->
                RecentPlayItem(
                    kind = EntryKind.Server,
                    key = server.ip,
                    title = server.name,
                    subtitle = server.ip,
                    usedAt = usage.lastUsed(EntryKind.Server, instance.id, server.ip),
                    server = server,
                    icon = decodeServerIcon(server.icon),
                )
            }
            .sortedByDescending { it.usedAt ?: Instant.EPOCH }
    val merged = mergeByTime(worlds, serverItems)
    return RecentPlayData(
        instanceId = instance.id,
        all = merged,
        loading = false,
    )
}

/** 两个已按时间倒序的列表按时间合并；没有时间（null）的条目排在最后。 */
private fun mergeByTime(
    worlds: List<RecentPlayItem>,
    servers: List<RecentPlayItem>,
): List<RecentPlayItem> {
    val merged = ArrayList<RecentPlayItem>(worlds.size + servers.size)
    var i = 0
    var j = 0
    while (i < worlds.size && j < servers.size) {
        val worldTime = worlds[i].usedAt ?: Instant.EPOCH
        val serverTime = servers[j].usedAt ?: Instant.EPOCH
        if (worldTime >= serverTime) merged += worlds[i++] else merged += servers[j++]
    }
    while (i < worlds.size) merged += worlds[i++]
    while (j < servers.size) merged += servers[j++]
    return merged
}

/** `ServerEntry.icon` 已经是校验过的 PNG 字节；解不出来就按「没有图标」处理。 */
private fun decodeServerIcon(bytes: ByteArray?): ImageBitmap? = bytes?.let { data ->
    runCatching { SkiaImage.makeFromEncoded(data).toComposeImageBitmap() }.getOrNull()
}

/**
 * 相对时间：「刚刚 / N 分钟前 / N 小时前 / 昨天 / N 天前 / 上个月 / N 个月前 / N 年前」。
 *
 * 应用里原本没有相对时间工具——实例页用的是 「MM-dd HH:mm」绝对时间，不能直接复用；
 * 这里保留全应用唯一一份相对时间格式化，不再另写第二份。
 */
private fun relativeTimeLabel(time: Instant?, now: Instant = Instant.now()): String {
    if (time == null) return ""
    val seconds = Duration.between(time, now).seconds
    return when {
        seconds < 60 -> "刚刚"
        seconds < 3_600 -> (seconds / 60).toString() + " 分钟前"
        seconds < 86_400 -> (seconds / 3_600).toString() + " 小时前"
        seconds < 172_800 -> "昨天"
        seconds < 2_592_000 -> (seconds / 86_400).toString() + " 天前"
        seconds < 5_184_000 -> "上个月"
        seconds < 31_536_000 -> (seconds / 2_592_000).toString() + " 个月前"
        else -> (seconds / 31_536_000).toString() + " 年前"
    }
}
