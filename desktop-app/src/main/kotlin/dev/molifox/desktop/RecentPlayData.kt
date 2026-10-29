// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责最近游玩面板的磁盘快照、历史合并和有界服务器状态缓存。

package dev.molifox.desktop

import androidx.compose.runtime.*
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import dev.molifox.core.*
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
            }
            .map { ServerEntry(it, it, null) }
    val servers =
        (saved + history)
            .distinctBy {
                parseServerAddress(it.ip)?.let { address ->
                    address.host.lowercase() + ":" + address.port
                } ?: it.ip
            }
            .map { server ->
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

internal data class RecentServerStatus(val status: ServerStatus?, val at: Long)

internal object RecentServerCache {
    private val entries = ConcurrentHashMap<String, RecentServerStatus>()

    fun get(address: String) =
        entries[address]?.takeIf { System.currentTimeMillis() - it.at in 0..60_000 }

    fun put(address: String, result: RecentServerStatus) {
        if (entries.size >= 256) entries.clear()
        entries[address] = result
    }

    fun clear() = entries.clear()
}

@Composable
internal fun rememberRecentServerStatus(address: String, refresh: Int): RecentServerStatus? {
    var result by remember(address) { mutableStateOf(RecentServerCache.get(address)) }
    LaunchedEffect(address, refresh) {
        result =
            RecentServerCache.get(address)
                ?: withContext(Dispatchers.IO) {
                    RecentServerStatus(pingAddress(address), System.currentTimeMillis()).also {
                        RecentServerCache.put(address, it)
                    }
                }
    }
    return result
}
