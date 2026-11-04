// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 版本选择卡片：搜索与频道筛选、版本列表与其状态。

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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import dev.molifox.desktop.MoliTextButton as TextButton
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

/** 远古 Alpha/Beta 的版本类型。 */
private val oldVersionTypes = setOf("old_alpha", "old_beta")

private fun versionChannelTypes(channel: String): Set<String> =
    when (channel) {
        "release" -> setOf("release")
        "snapshot" -> setOf("snapshot")
        "old" -> oldVersionTypes
        else -> setOf("release", "snapshot") + oldVersionTypes
    }

/** 左侧版本选择卡片：搜索、频道与版本列表。 */
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
    val strings = LocalStrings.current
    Panel(Modifier.weight(1.15f).fillMaxHeight(), padding = 14) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(strings.t("deskE.version.title"), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.weight(1f))
            SmallIconButton(
                Icons.Outlined.Refresh,
                strings.t("deskE.version.refresh"),
                onRefresh,
                enabled = !busy && !loading,
            )
        }
        Spacer(Modifier.height(8.dp))
        CompactSearchField(
            query,
            onQueryChange,
            Modifier.fillMaxWidth(),
            placeholder = strings.t("deskE.version.searchPlaceholder"),
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(
                "release" to strings.t("deskE.version.channel.release"),
                "snapshot" to strings.t("deskE.version.channel.snapshot"),
                "old" to strings.t("deskE.version.channel.old"),
                "all" to strings.t("deskE.version.channel.all"),
            ).forEach { (id, title) ->
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
                it.type in versionChannelTypes(channel) &&
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
    val strings = LocalStrings.current
    when {
        loading ->
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp)
                    Text(
                        strings.t("deskE.version.loading"),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        error != null ->
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                EmptyState(
                    strings.t("deskE.version.unavailable"),
                    error.orEmpty(),
                    Icons.Outlined.CloudOff,
                ) {
                    TextButton(onRefresh) { Text(strings.t("deskE.version.reload")) }
                }
            }
        filtered.isEmpty() ->
            Box(Modifier.fillMaxWidth().weight(1f)) {
                EmptyState(
                    strings.t("deskE.version.emptyTitle"),
                    strings.t("deskE.version.emptyHint"),
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

/** 单行版本：选中态由行底色、文字色与对勾共同表达。 */
@Composable
private fun LazyItemScope.VersionRow(
    version: ReleaseVersion,
    active: Boolean,
    onSelect: () -> Unit,
) {
    val strings = LocalStrings.current
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
        when (version.type) {
            "snapshot" -> Tag(strings.t("deskE.version.channel.snapshot"))
            "old_alpha" -> Tag(strings.t("deskE.version.channel.oldAlpha"))
            "old_beta" -> Tag(strings.t("deskE.version.channel.oldBeta"))
            else -> Tag(strings.t("deskE.version.channel.release"))
        }
    }
}
