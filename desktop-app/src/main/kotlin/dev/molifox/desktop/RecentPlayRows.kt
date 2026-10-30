// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责存档和服务器共用行布局，固定地址与时间列、状态操作区及条目菜单。

package dev.molifox.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.molifox.core.EntryKind
import dev.molifox.core.ping
import java.time.Duration
import java.time.Instant

@Composable
internal fun RecentPlayRow(
    item: RecentPlayItem,
    busy: Boolean,
    refresh: Int,
    actions: RecentPlayActions,
) {
    val server = item.kind == EntryKind.Server
    val snapshot =
        if (server) {
            rememberRecentServerStatus(
                item.key,
                refresh,
                actions.statusQuery,
            )
        } else {
            null
        }
    val shape = RoundedCornerShape(Metrics.innerRadius)
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .hoverHighlight(shape)
            .padding(horizontal = 8.dp, vertical = 8.dp)
            .semantics { contentDescription = "最近游玩条目 ${item.title}" },
    ) {
        val compact = maxWidth < 550.dp
        Column(
            Modifier.fillMaxWidth().heightIn(min = 64.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                RecentIdentity(item, Modifier.width(252.dp))
                if (!compact) {
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        if (server) {
                            RecentMotd(snapshot?.status?.motd.orEmpty())
                        } else {
                            Text(
                                "单人存档",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                } else {
                    Spacer(Modifier.weight(1f))
                }
                Column(
                    Modifier.width(124.dp),
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (server) RecentServerMetrics(item.title, snapshot)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(
                            { actions.play(item) },
                            modifier =
                                Modifier.semantics { contentDescription = "开始游戏 ${item.title}" },
                            enabled = !busy,
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                        ) {
                            Text("开始游戏", fontSize = 12.sp, maxLines = 1)
                        }
                        RecentRowMenu(item, busy, actions)
                    }
                }
            }
            if (compact && server) {
                RecentMotd(snapshot?.status?.motd.orEmpty(), Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun RecentIdentity(
    item: RecentPlayItem,
    modifier: Modifier,
) {
    Row(
        modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier.size(36.dp).clip(RoundedCornerShape(4.dp)),
            contentAlignment = Alignment.Center,
        ) {
            val save = item.save
            when {
                save != null ->
                    LocalContentThumbnail(
                        save.path,
                        true,
                        Modifier.size(36.dp),
                        save.lastModified?.toEpochMilli(),
                        fallback = { GrassBlockIcon(size = 36.dp) },
                    )
                item.icon != null -> Image(item.icon, null, Modifier.size(36.dp))
                else -> GrassBlockIcon(size = 36.dp)
            }
        }
        Column(Modifier.width(204.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            RecentTooltip(item.title) {
                Text(
                    item.title + if (item.hidden) " · 已隐藏" else "",
                    Modifier.width(204.dp),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RecentTooltip(item.subtitle, Modifier.width(96.dp)) {
                    Text(
                        item.subtitle,
                        Modifier.width(96.dp),
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                RecentTooltip(
                    item.usage.lastEntered?.let { "最近进入：$it" } ?: "尚未记录进入时间",
                    Modifier.width(96.dp),
                ) {
                    Text(
                        recentTimeLabel(item.usage.lastEntered),
                        Modifier.width(96.dp),
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            val usage = item.usage
            if (usage.totalSeconds > 0 || usage.active) {
                Text(
                    (if (usage.active) "本次 ${durationLabel(usage.activeSeconds)} · " else "") +
                        (if (usage.estimated) "累计约 " else "累计 ") +
                        durationLabel(usage.totalSeconds),
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun RecentServerMetrics(
    title: String,
    snapshot: RecentServerStatus?,
) {
    val status = snapshot?.status
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (status == null) {
            Text(
                if (snapshot == null) "查询中…" else "离线",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text("${status.players}/${status.maxPlayers}", fontSize = 11.sp, maxLines = 1)
            Spacer(Modifier.width(8.dp))
            val color =
                when {
                    status.ping < 80 -> Color(0xFF38945B)
                    status.ping < 200 -> Color(0xFFB08725)
                    else -> Color(0xFFCC5050)
                }
            val litBars =
                when {
                    status.ping < 80 -> 4
                    status.ping < 200 -> 3
                    else -> 2
                }
            RecentTooltip("延迟 ${status.ping} ms") {
                Row(
                    Modifier.height(14.dp).semantics {
                        contentDescription = "服务器延迟信号 $title"
                    },
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(1.5.dp),
                ) {
                    repeat(4) { index ->
                        Box(
                            Modifier
                                .width(2.dp)
                                .height((4 + index * 2.5).dp)
                                .background(
                                    if (index < litBars) color else color.copy(alpha = .22f),
                                    RoundedCornerShape(1.dp),
                                ),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RecentRowMenu(
    item: RecentPlayItem,
    busy: Boolean,
    actions: RecentPlayActions,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        SmallIconButton(Icons.Outlined.MoreHoriz, "更多操作 ${item.title}", onClick = { open = true })
        DropdownMenu(open, { open = false }) {
            DropdownMenuItem(
                { Text(if (item.kind == EntryKind.Server) "复制地址" else "打开文件夹") },
                {
                    open = false
                    actions.primary(item)
                },
            )
            DropdownMenuItem(
                { Text(if (item.hidden) "取消隐藏" else "隐藏") },
                {
                    open = false
                    actions.hide(item)
                },
            )
            DropdownMenuItem(
                { Text("删除", color = MaterialTheme.colorScheme.error) },
                {
                    open = false
                    actions.delete(item)
                },
                enabled = !busy,
            )
        }
    }
}

private fun durationLabel(seconds: Long): String =
    when {
        seconds < 60 -> "不足 1 分钟"
        seconds < 3600 -> "${seconds / 60} 分钟"
        else -> "${seconds / 3600} 小时 ${(seconds % 3600) / 60} 分钟"
    }

private fun recentTimeLabel(time: Instant?): String {
    if (time == null) return "尚未游玩"
    val seconds = Duration.between(time, Instant.now()).seconds.coerceAtLeast(0)
    return when {
        seconds < 60 -> "刚刚"
        seconds < 3600 -> "${seconds / 60} 分钟前"
        seconds < 86400 -> "${seconds / 3600} 小时前"
        else -> "${seconds / 86400} 天前"
    }
}
