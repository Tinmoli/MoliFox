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
import dev.molifox.desktop.MoliTextButton as TextButton
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
import java.time.Duration
import java.time.Instant

private const val SECONDS_PER_MINUTE = 60
private const val SECONDS_PER_HOUR = 3600
private const val SECONDS_PER_DAY = 86400

@Composable
internal fun RecentPlayRow(
    item: RecentPlayItem,
    busy: Boolean,
    refresh: Int,
    actions: RecentPlayActions,
) {
    val strings = LocalStrings.current
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
            .semantics { contentDescription = strings.t("pages.recent.row.label", item.title) },
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
                                strings.t("pages.recent.row.singleplayer"),
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
                                Modifier.semantics {
                                    contentDescription = strings.t("pages.recent.row.playLabel", item.title)
                                },
                            enabled = !busy,
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                        ) {
                            Text(strings.t("pages.recent.row.play"), fontSize = 12.sp, maxLines = 1)
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
    val strings = LocalStrings.current
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
                    item.title + if (item.hidden) " · " + strings.t("pages.recent.row.hidden") else "",
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
                    item.usage.lastEntered?.let {
                        strings.t("pages.recent.row.lastEntered", it)
                    } ?: strings.t("pages.recent.row.neverEntered"),
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
                    (
                        if (usage.active) {
                            strings.t("pages.recent.usage.active", durationLabel(usage.activeSeconds)) +
                                " · "
                        } else {
                            ""
                        }
                    ) +
                        if (usage.estimated) {
                            strings.t("pages.recent.usage.totalApprox", durationLabel(usage.totalSeconds))
                        } else {
                            strings.t("pages.recent.usage.total", durationLabel(usage.totalSeconds))
                        },
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
    val strings = LocalStrings.current
    val status = snapshot?.status
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (status == null) {
            RecentServerUnavailable(snapshot == null)
        } else {
            val playerTooltip =
                when {
                    status.players == 0 -> strings.t("pages.recent.row.noPlayers")

                    status.playerSample.isEmpty() -> strings.t("pages.recent.row.playersHidden")

                    status.playerSample.size < status.players ->
                        status.playerSample.joinToString("\n") +
                            "\n" +
                            strings.t(
                                "pages.recent.row.playersPartial",
                                status.playerSample.size,
                                status.players,
                            )

                    else -> status.playerSample.joinToString("\n")
                }
            RecentTooltip(playerTooltip) {
                Text("${status.players}/${status.maxPlayers}", fontSize = 11.sp, maxLines = 1)
            }
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
            RecentTooltip(strings.t("pages.recent.row.ping", status.ping)) {
                Row(
                    Modifier.height(14.dp).semantics {
                        contentDescription = strings.t("pages.recent.row.pingSignal", title)
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
private fun RecentServerUnavailable(querying: Boolean) {
    val strings = LocalStrings.current
    RecentTooltip(strings.t(if (querying) "pages.recent.row.querying" else "pages.recent.row.unavailable")) {
        Text(
            strings.t(if (querying) "pages.recent.row.querying" else "pages.recent.row.offline"),
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun RecentRowMenu(
    item: RecentPlayItem,
    busy: Boolean,
    actions: RecentPlayActions,
) {
    val strings = LocalStrings.current
    var open by remember { mutableStateOf(false) }
    Box {
        SmallIconButton(
            Icons.Outlined.MoreHoriz,
            strings.t("pages.recent.row.more", item.title),
            onClick = { open = true },
        )
        DropdownMenu(open, { open = false }) {
            DropdownMenuItem(
                {
                    Text(
                        if (item.kind == EntryKind.Server) {
                            strings.t("pages.recent.row.copyAddress")
                        } else {
                            strings.t("pages.recent.row.openFolder")
                        },
                    )
                },
                {
                    open = false
                    actions.primary(item)
                },
            )
            DropdownMenuItem(
                {
                    Text(
                        if (item.hidden) {
                            strings.t("pages.recent.row.unhide")
                        } else {
                            strings.t("pages.recent.row.hide")
                        },
                    )
                },
                {
                    open = false
                    actions.hide(item)
                },
            )
            DropdownMenuItem(
                { Text(strings.t("pages.recent.row.delete"), color = MaterialTheme.colorScheme.error) },
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
        seconds < SECONDS_PER_MINUTE -> tr("pages.recent.duration.lessThanMinute")
        seconds < SECONDS_PER_HOUR ->
            tr("pages.recent.duration.minutes", seconds / SECONDS_PER_MINUTE)
        else ->
            tr(
                "pages.recent.duration.hoursMinutes",
                seconds / SECONDS_PER_HOUR,
                (seconds % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE,
            )
    }

private fun recentTimeLabel(time: Instant?): String {
    if (time == null) return tr("pages.recent.time.never")
    val seconds = Duration.between(time, Instant.now()).seconds.coerceAtLeast(0)
    return when {
        seconds < SECONDS_PER_MINUTE -> tr("pages.recent.time.justNow")
        seconds < SECONDS_PER_HOUR -> tr("pages.recent.time.minutesAgo", seconds / SECONDS_PER_MINUTE)
        seconds < SECONDS_PER_DAY -> tr("pages.recent.time.hoursAgo", seconds / SECONDS_PER_HOUR)
        else -> tr("pages.recent.time.daysAgo", seconds / SECONDS_PER_DAY)
    }
}
