// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 内容结果区：结果列表 / 网格、结果行与卡片、分页与空状态。

package dev.molifox.desktop.content

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
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
import dev.molifox.desktop.LocalStrings
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
    val strings = LocalStrings.current
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
            Text(strings.t("pages.content.row.chooseVersion"), fontSize = 12.sp)
        }
        SmallIconButton(
            Icons.Outlined.Info,
            strings.t("pages.content.row.details", hit.title),
            onOpen,
        )
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
    val strings = LocalStrings.current
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
                SmallIconButton(
                    Icons.Outlined.Info,
                    strings.t("pages.content.row.details", hit.title),
                    onOpen,
                )
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
                    Text(strings.t("pages.content.row.chooseVersion"), fontSize = 12.sp)
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
    ResultsHeader(
        result = result,
        query = query,
        loading = loading,
        loadedOnce = loadedOnce,
        offset = offset,
        listView = listView,
        onListViewChange = onListViewChange,
    )
    ContentResultsBody(
        result = result,
        hits = hits,
        loading = loading,
        loadedOnce = loadedOnce,
        error = error,
        listView = listView,
        query = query,
        showAuthors = showAuthors,
        showDescriptions = showDescriptions,
        onOpen = onOpen,
        onRetry = onRetry,
        onClearQuery = onClearQuery,
        actions = actions,
    )
    ResultsPager(result, offset, onOffsetChange)
}

/** 统计行：结果数、当前搜索词、页码、列表/网格切换，以及翻页时的细进度条。 */
@Composable
private fun ResultsHeader(
    result: ContentSearch,
    query: String,
    loading: Boolean,
    loadedOnce: Boolean,
    offset: Int,
    listView: Boolean,
    onListViewChange: (Boolean) -> Unit,
) {
    val strings = LocalStrings.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            if (loading && !loadedOnce) {
                strings.t("pages.content.search.checking")
            } else {
                strings.t("pages.content.results.count", result.total)
            },
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (query.isNotBlank()) {
            Text(
                // 前置分隔符留在代码里，避免 properties 值首尾空格被裁掉。
                " · " + strings.t("pages.content.results.query", query),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.weight(1f))
        Text(
            strings.t("pages.content.results.page", offset / 12 + 1),
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(6.dp))
        ViewToggle(listView, onListViewChange)
    }
    if (loading && loadedOnce) LinearProgressIndicator(Modifier.fillMaxWidth().height(3.dp))
}

/** 结果区主体：骨架、错误、空状态、列表或网格，以及右下角的回到顶部。 */
@Composable
private fun ColumnScope.ContentResultsBody(
    result: ContentSearch,
    hits: List<ContentHit>,
    loading: Boolean,
    loadedOnce: Boolean,
    error: String?,
    listView: Boolean,
    query: String,
    showAuthors: Boolean,
    showDescriptions: Boolean,
    onOpen: (ContentHit) -> Unit,
    onRetry: () -> Unit,
    onClearQuery: () -> Unit,
    actions: UiActions,
) {
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
    Box(Modifier.weight(1f).fillMaxWidth()) {
        when {
            loading && !loadedOnce -> SkeletonResults()
            error != null -> SearchErrorPanel(error.orEmpty(), onRetry)
            result.hits.isEmpty() -> NoResultsState(query, onClearQuery)
            hits.isEmpty() -> FilteredEmptyState()
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
        GoTopOverlay(scrolled, goTop)
    }
}

/** 映射失败不是网络问题：标题要区分，正文保留具体阶段与响应片段。 */
@Composable
private fun SearchErrorPanel(
    failure: String,
    onRetry: () -> Unit,
) {
    val strings = LocalStrings.current
    Panel(Modifier.fillMaxWidth()) {
        EmptyState(
            if (failure.startsWith("CurseForge")) {
                strings.t("pages.content.error.mapping")
            } else {
                strings.t("pages.content.error.network")
            },
            failure,
            Icons.Outlined.CloudOff,
        ) {
            TextButton(onRetry) { Text(strings.t("pages.content.error.retry")) }
        }
    }
}

@Composable
private fun NoResultsState(
    query: String,
    onClearQuery: () -> Unit,
) {
    val strings = LocalStrings.current
    Box(
        Modifier.fillMaxWidth().fillMaxHeight(),
        contentAlignment = Alignment.TopCenter,
    ) {
        EmptyState(
            strings.t("pages.content.empty.title"),
            strings.t("pages.content.empty.body"),
            Icons.Outlined.SearchOff,
        ) {
            if (query.isNotBlank()) {
                TextButton(onClearQuery) { Text(strings.t("pages.content.empty.clear")) }
            }
        }
    }
}

@Composable
private fun FilteredEmptyState() {
    val strings = LocalStrings.current
    Box(
        Modifier.fillMaxWidth().fillMaxHeight(),
        contentAlignment = Alignment.TopCenter,
    ) {
        EmptyState(
            strings.t("pages.content.filtered.title"),
            strings.t("pages.content.filtered.body"),
            Icons.Outlined.FilterAlt,
        )
    }
}

@Composable
private fun BoxScope.GoTopOverlay(
    scrolled: Boolean,
    goTop: () -> Unit,
) {
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

@Composable
private fun ResultsPager(
    result: ContentSearch,
    offset: Int,
    onOffsetChange: (Int) -> Unit,
) {
    val strings = LocalStrings.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        MutedText(strings.t("pages.content.pager.summary", result.total), Modifier)
        Spacer(Modifier.weight(1f))
        TextButton({ onOffsetChange(maxOf(0, offset - 12)) }, enabled = offset > 0) {
            Text(strings.t("pages.content.pager.prev"), fontSize = 12.sp)
        }
        val next = offset + 12
        TextButton({ onOffsetChange(next) }, enabled = next < result.total && offset < 9996) {
            Text(strings.t("pages.content.pager.next"), fontSize = 12.sp)
        }
    }
}
