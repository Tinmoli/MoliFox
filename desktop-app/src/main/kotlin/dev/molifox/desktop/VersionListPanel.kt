// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 版本选择卡片：推荐版本、搜索与频道筛选、版本列表与其状态。

package dev.molifox.desktop

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.molifox.core.ReleaseVersion

/** 左侧版本选择卡片：推荐版本、搜索、频道与版本列表。 */
@Composable
internal fun RowScope.VersionListPanel(
    versions: List<ReleaseVersion>,
    loading: Boolean,
    error: String?,
    query: String,
    onQueryChange: (String) -> Unit,
    channel: String,
    onChannelChange: (String) -> Unit,
    selected: String,
    onSelect: (String) -> Unit,
    busy: Boolean,
    onRefresh: () -> Unit,
) {
    Panel(Modifier.weight(1.15f).fillMaxHeight(), padding = 14) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Minecraft 版本", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.weight(1f))
            SmallIconButton(
                Icons.Outlined.Refresh,
                "刷新版本列表",
                onRefresh,
                enabled = !busy && !loading,
            )
        }
        Spacer(Modifier.height(8.dp))
        if (!loading && error == null) {
            versions
                .filter { it.type == "release" }
                .maxByOrNull { it.releaseTime }
                ?.let { latest ->
                    RecommendedVersionCard(latest) { id ->
                        onSelect(id)
                        onChannelChange("release")
                        onQueryChange("")
                    }
                    Spacer(Modifier.height(8.dp))
                }
        }
        CompactSearchField(
            query,
            onQueryChange,
            Modifier.fillMaxWidth(),
            placeholder = "搜索版本，例如 1.21",
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("release" to "正式版", "snapshot" to "快照", "all" to "全部")
                .forEach { (id, title) ->
                    FilterChip(
                        channel == id,
                        { onChannelChange(id) },
                        { Text(title, fontSize = 11.sp) },
                        modifier = Modifier.height(28.dp),
                    )
                }
        }
        Spacer(Modifier.height(6.dp))
        val filtered =
            versions.filter {
                it.type in listOf("release", "snapshot") &&
                    (channel == "all" || it.type == channel) &&
                    it.id.contains(query, true)
            }
        VersionListBody(filtered, loading, error, selected, onSelect, onRefresh)
    }
}

/** 版本列表主体：加载中、加载失败、无匹配与正常列表四种状态。 */
@Composable
private fun ColumnScope.VersionListBody(
    filtered: List<ReleaseVersion>,
    loading: Boolean,
    error: String?,
    selected: String,
    onSelect: (String) -> Unit,
    onRefresh: () -> Unit,
) {
    when {
        loading ->
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp)
                    Text(
                        "正在加载版本列表…",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        error != null ->
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                EmptyState("版本列表暂不可用", error.orEmpty(), Icons.Outlined.CloudOff) {
                    TextButton(onRefresh) { Text("重新加载") }
                }
            }
        filtered.isEmpty() ->
            Box(Modifier.fillMaxWidth().weight(1f)) {
                EmptyState(
                    "没有符合条件的版本",
                    "试试搜索其他版本号，或切换到「全部」频道。",
                    Icons.Outlined.SearchOff,
                )
            }
        else ->
            LazyColumn(
                Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                items(filtered, key = { it.id }) { version ->
                    VersionRow(version, version.id == selected) { onSelect(version.id) }
                }
            }
    }
}

/** 推荐卡片：直接选中最新正式版，并回到正式版频道与空搜索。 */
@Composable
private fun RecommendedVersionCard(
    latest: ReleaseVersion,
    onPick: (String) -> Unit,
) {
    Surface(
        onClick = { onPick(latest.id) },
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = MaterialTheme.shapes.small,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.StarOutline, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "推荐 · 最新正式版",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    latest.id,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Text(latest.releaseTime.take(10), fontSize = 11.sp)
        }
    }
}

/** 单行版本：选中态由行底色、文字色与对勾共同表达。 */
@Composable
private fun LazyItemScope.VersionRow(
    version: ReleaseVersion,
    active: Boolean,
    onSelect: () -> Unit,
) {
    val rowShape = RoundedCornerShape(Metrics.cardRadius)
    val rowColor by
        animateColorAsState(
            if (active) selectedRowBackground() else Color.Transparent,
            tween(120),
            label = "version-bg",
        )
    Row(
        Modifier
            .fillMaxWidth()
            .animateItem()
            .height(40.dp)
            .clip(rowShape)
            .background(rowColor)
            .rowHoverHighlight(rowShape, enabled = !active)
            .pressScale()
            .clickable(onClick = onSelect)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 游戏版本条目统一用草方块图标（原版默认图标），选中态由行底色与对勾表达。
        GrassBlockIcon(size = 18.dp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                version.id,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color =
                    if (active) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
            )
            Text(
                version.releaseTime.take(10),
                fontSize = 11.sp,
                color =
                    if (active) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
            )
        }
        if (active) {
            Icon(
                Icons.Outlined.Check,
                null,
                Modifier.size(15.dp),
                MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Spacer(Modifier.width(6.dp))
        }
        if (version.type == "snapshot") Tag("快照") else Tag("正式版")
    }
}
