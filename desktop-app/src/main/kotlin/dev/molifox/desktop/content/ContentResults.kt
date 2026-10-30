// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 内容结果区：结果列表 / 网格、结果行与卡片、分页、空状态与首屏骨架。

package dev.molifox.desktop.content

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FilterAlt
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.molifox.core.ContentHit
import dev.molifox.core.ContentSearch
import dev.molifox.desktop.EmptyState
import dev.molifox.desktop.LocalAnimationsEnabled
import dev.molifox.desktop.Metrics
import dev.molifox.desktop.MutedText
import dev.molifox.desktop.Panel
import dev.molifox.desktop.ResourceThumbnail
import dev.molifox.desktop.SmallIconButton
import dev.molifox.desktop.Tag
import dev.molifox.desktop.UiActions
import dev.molifox.desktop.hoverHighlight
import dev.molifox.desktop.pressScale
import kotlinx.coroutines.launch

/** 骨架：28dp 环 + 3 行灰条，稳定占位，避免整页在加载与结果之间跳动。 */
@Composable
private fun SkeletonResults() {
    val alpha =
        if (LocalAnimationsEnabled.current) {
            val transition = rememberInfiniteTransition(label = "skeleton")
            val value by
                transition.animateFloat(
                    0.45f,
                    0.9f,
                    infiniteRepeatable(tween(760), RepeatMode.Reverse),
                    label = "skeleton-alpha",
                )
            value
        } else {
            0.6f
        }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(10.dp))
            Text(
                "正在检索资源…",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        repeat(3) { SkeletonRow(alpha) }
    }
}

@Composable
private fun SkeletonRow(alpha: Float) {
    val bar = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .18f * alpha)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).background(bar))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Box(
                Modifier
                    .fillMaxWidth(.45f)
                    .height(12.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(bar),
            )
            Box(
                Modifier
                    .fillMaxWidth(.8f)
                    .height(9.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(bar),
            )
        }
    }
}

/** 列表结果行：图标 40/44 + 两行文字 + 标签 + 安装，整行 56dp，悬停淡入、按压缩放。 */
@Composable
private fun ContentResultRow(
    hit: ContentHit,
    actions: UiActions,
    modifier: Modifier = Modifier,
    canInstall: Boolean,
    onInstall: () -> Unit,
    onOpen: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(Metrics.innerRadius)
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(shape)
            .hoverHighlight(shape, alpha = .05f)
            // 整行点击打开项目页；按下时轻微缩放（pressScale 不消费指针事件，不影响内部按钮）。
            .pressScale()
            .clickable(onClick = onOpen)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ResourceThumbnail(hit, actions)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    hit.title,
                    Modifier.weight(1f, fill = false),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.width(8.dp))
                Tag(contentKindLabel(hit.type))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    hit.slug,
                    fontSize = 11.sp,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                )
                Text(" · ", fontSize = 11.sp, color = colors.outline)
                Text(
                    hit.description,
                    Modifier.weight(1f),
                    fontSize = 12.sp,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.width(10.dp))
                Icon(Icons.Outlined.Download, null, Modifier.size(12.dp), colors.onSurfaceVariant)
                Spacer(Modifier.width(4.dp))
                Text(
                    formatDownloads(hit.downloads),
                    fontSize = 11.sp,
                    color = colors.onSurfaceVariant,
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    hit.author,
                    fontSize = 11.sp,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        TextButton(onInstall, enabled = canInstall) {
            Icon(Icons.Outlined.Add, null, Modifier.size(14.dp))
            Spacer(Modifier.width(3.dp))
            Text("选择版本", fontSize = 12.sp)
        }
        SmallIconButton(Icons.Outlined.Info, "查看 " + hit.title + " 的详情与版本", onOpen)
    }
}

/** 卡片结果：最小高 168、白底 1dp 细边、悬停边框转主题色。 */
@Composable
private fun ContentResultCard(
    hit: ContentHit,
    actions: UiActions,
    modifier: Modifier = Modifier,
    canInstall: Boolean,
    showAuthors: Boolean,
    showDescriptions: Boolean,
    onInstall: () -> Unit,
    onOpen: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(Metrics.cardRadius)
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val borderColor by
        animateColorAsState(
            if (hovered) {
                colors.primary.copy(alpha = .6f)
            } else {
                colors.outlineVariant.copy(alpha = .55f)
            },
            tween(120),
            label = "card-border",
        )
    Surface(
        modifier
            .fillMaxWidth()
            .heightIn(min = 168.dp)
            .pressScale()
            .hoverable(interaction),
        shape = shape,
        color = colors.surface,
        border = BorderStroke(1.dp, borderColor),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ResourceThumbnail(hit, actions)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        hit.title,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (showAuthors) {
                        Text(
                            "by " + hit.author,
                            fontSize = 11.sp,
                            color = colors.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                SmallIconButton(Icons.Outlined.Info, "查看 " + hit.title + " 的详情与版本", onOpen)
            }
            if (showDescriptions) {
                Spacer(Modifier.height(10.dp))
                Text(
                    hit.description,
                    fontSize = 12.sp,
                    lineHeight = 19.sp,
                    color = colors.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.weight(1f))
            HorizontalDivider(color = colors.outlineVariant.copy(alpha = .7f))
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Download, null, Modifier.size(13.dp), colors.onSurfaceVariant)
                Spacer(Modifier.width(5.dp))
                Text(
                    formatDownloads(hit.downloads),
                    fontSize = 11.sp,
                    color = colors.onSurfaceVariant,
                )
                Spacer(Modifier.width(10.dp))
                Tag(contentKindLabel(hit.type))
                Spacer(Modifier.weight(1f))
                TextButton(onInstall, enabled = canInstall) {
                    Icon(Icons.Outlined.Add, null, Modifier.size(15.dp))
                    Spacer(Modifier.width(3.dp))
                    Text("选择版本", fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
internal fun ColumnScope.ContentResultsSection(
    result: ContentSearch,
    sort: String,
    query: String,
    loading: Boolean,
    loadedOnce: Boolean,
    error: String?,
    listView: Boolean,
    onListViewChange: (Boolean) -> Unit,
    showAuthors: Boolean,
    showDescriptions: Boolean,
    offset: Int,
    onOffsetChange: (Int) -> Unit,
    onOpen: (ContentHit) -> Unit,
    onRetry: () -> Unit,
    onClearQuery: () -> Unit,
    actions: UiActions,
) {
    val hits =
        result.hits.let { list ->
            when (sort) {
                "downloads" -> list.sortedByDescending { it.downloads }
                "name" -> list.sortedBy { it.title.lowercase() }
                else -> list
            }
        }
    val listState = rememberLazyListState()
    val gridState = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    val scrolled =
        if (listView) {
            listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0
        } else {
            gridState.firstVisibleItemIndex > 0
        }
    val goTop: () -> Unit = {
        scope.launch {
            if (listView) listState.animateScrollToItem(0) else gridState.animateScrollToItem(0)
        }
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            if (loading && !loadedOnce) "正在检索资源…" else "共 ${result.total} 个结果",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (query.isNotBlank()) {
            Text(
                " · 「" + query + "」",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.weight(1f))
        Text(
            "第 ${offset / 12 + 1} 页",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(6.dp))
        ViewToggle(listView, onListViewChange)
    }
    if (loading && loadedOnce) LinearProgressIndicator(Modifier.fillMaxWidth().height(3.dp))
    Box(Modifier.weight(1f).fillMaxWidth()) {
        when {
            loading && !loadedOnce -> SkeletonResults()
            error != null -> {
                val failure = error.orEmpty()
                Panel(Modifier.fillMaxWidth()) {
                    // 映射失败不是网络问题：标题要区分，正文保留具体阶段与响应片段。
                    EmptyState(
                        if (failure.startsWith("CurseForge")) {
                            "资源库返回的数据无法识别"
                        } else {
                            "暂时无法连接资源库"
                        },
                        failure,
                        Icons.Outlined.CloudOff,
                    ) {
                        TextButton(onRetry) { Text("重试") }
                    }
                }
            }
            result.hits.isEmpty() ->
                Box(
                    Modifier.fillMaxWidth().fillMaxHeight(),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    EmptyState(
                        "没有找到合适的资源",
                        "试试英文名称，或切换游戏版本与加载器。",
                        Icons.Outlined.SearchOff,
                    ) {
                        if (query.isNotBlank()) TextButton(onClearQuery) { Text("清除搜索条件") }
                    }
                }
            hits.isEmpty() ->
                Box(
                    Modifier.fillMaxWidth().fillMaxHeight(),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    EmptyState(
                        "没有符合筛选条件的资源",
                        "换个关键词或清除筛选条件。",
                        Icons.Outlined.FilterAlt,
                    )
                }
            listView ->
                LazyColumn(
                    Modifier.fillMaxSize(),
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    contentPadding = PaddingValues(bottom = 12.dp),
                ) {
                    items(hits, key = { it.id }) { hit ->
                        ContentResultRow(
                            hit,
                            actions,
                            modifier = Modifier.animateItem(),
                            canInstall = !actions.controller.busy,
                            onInstall = { onOpen(hit) },
                            onOpen = { onOpen(hit) },
                        )
                    }
                }
            else ->
                LazyVerticalGrid(
                    GridCells.Adaptive(250.dp),
                    Modifier.fillMaxSize(),
                    state = gridState,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(bottom = 12.dp),
                ) {
                    items(hits, key = { it.id }) { hit ->
                        ContentResultCard(
                            hit,
                            actions,
                            modifier = Modifier.animateItem(),
                            canInstall = !actions.controller.busy,
                            showAuthors = showAuthors,
                            showDescriptions = showDescriptions,
                            onInstall = { onOpen(hit) },
                            onOpen = { onOpen(hit) },
                        )
                    }
                }
        }
        if (LocalAnimationsEnabled.current) {
            // 显式写全名：外层是 ColumnScope，否则会解析到 ColumnScope.AnimatedVisibility 扩展。
            androidx.compose.animation.AnimatedVisibility(
                visible = scrolled,
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                enter = fadeIn(tween(120)) + slideInVertically(tween(150)) { it / 2 },
                exit = fadeOut(tween(90)) + slideOutVertically(tween(120)) { it / 2 },
            ) {
                GoTopButton(goTop)
            }
        } else if (scrolled) {
            Box(Modifier.align(Alignment.BottomEnd).padding(16.dp)) { GoTopButton(goTop) }
        }
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        MutedText("每页 12 条 · 共 ${result.total} 个资源", Modifier)
        Spacer(Modifier.weight(1f))
        TextButton({ onOffsetChange(maxOf(0, offset - 12)) }, enabled = offset > 0) {
            Text("上一页", fontSize = 12.sp)
        }
        val next = offset + 12
        TextButton({ onOffsetChange(next) }, enabled = next < result.total && offset < 9996) {
            Text("下一页", fontSize = 12.sp)
        }
    }
}
