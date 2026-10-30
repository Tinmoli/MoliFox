// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 合并最近游玩条目，在页面生命周期内展示实时查询的服务器状态。

package dev.molifox.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import dev.molifox.core.EntryKind
import dev.molifox.core.GameInstance
import dev.molifox.core.LauncherController
import dev.molifox.core.SaveEntry
import dev.molifox.core.ServerEntry
import dev.molifox.core.ServerStatus
import dev.molifox.core.UsageSummary
import dev.molifox.core.domain.server.ServerStatusQuery
import dev.molifox.core.listServers
import dev.molifox.core.parseServerAddress
import dev.molifox.core.sameServerAddress
import java.time.Instant
import org.jetbrains.skia.Image as SkiaImage

internal data class RecentPlayItem(
    val kind: EntryKind,
    val key: String,
    val title: String,
    val subtitle: String,
    val usage: UsageSummary,
    val hidden: Boolean,
    val save: SaveEntry? = null,
    val icon: ImageBitmap? = null,
)

internal fun loadRecentPlays(
    controller: LauncherController,
    instance: GameInstance,
): List<RecentPlayItem> {
    val usage = controller.store.recentUsage
    val worlds =
        controller
            .listSaves(instance.id)
            .filter { it.hasLevelDat }
            .map { save ->
                RecentPlayItem(
                    EntryKind.World,
                    save.name,
                    save.name,
                    "saves/${save.name}",
                    usage.summary(EntryKind.World, instance.id, save.name),
                    usage.isHidden(EntryKind.World, instance.id, save.name),
                    save = save,
                )
            }
    val saved = listServers(controller.store.gamePath(instance))
    val history =
        usage
            .recent(EntryKind.Server, instance.id, 100)
            .filter { key ->
                saved.none { sameServerAddress(parseServerAddress(it.ip), parseServerAddress(key)) }
            }.map { ServerEntry(it, it, null) }
    val servers =
        (saved + history)
            .distinctBy {
                parseServerAddress(it.ip)?.let { address ->
                    address.host.lowercase() + ":" + address.port
                } ?: it.ip
            }.map { server ->
                RecentPlayItem(
                    EntryKind.Server,
                    server.ip,
                    server.name,
                    server.ip,
                    usage.summary(EntryKind.Server, instance.id, server.ip),
                    usage.isHidden(EntryKind.Server, instance.id, server.ip),
                    icon =
                        server.icon?.let {
                            runCatching { SkiaImage.makeFromEncoded(it).toComposeImageBitmap() }
                                .getOrNull()
                        },
                )
            }
    return (worlds + servers).sortedByDescending { it.usage.lastEntered ?: Instant.EPOCH }
}

internal data class RecentServerStatus(
    val status: ServerStatus?,
    val at: Long,
)

@Composable
internal fun rememberRecentServerStatus(
    address: String,
    refresh: Int,
    query: ServerStatusQuery,
): RecentServerStatus? {
    var result by remember(address, query) { mutableStateOf<RecentServerStatus?>(null) }
    LaunchedEffect(address, refresh, query) {
        result = RecentServerStatus(query.read(address), System.currentTimeMillis())
    }
    return result
}
