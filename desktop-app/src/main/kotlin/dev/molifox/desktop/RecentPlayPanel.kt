// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责首页最近游玩的加载、直达启动、隐藏和经确认的实际删除。

package dev.molifox.desktop

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.molifox.core.EntryKind
import dev.molifox.core.GameInstance
import dev.molifox.core.GameLaunchTarget
import dev.molifox.core.LauncherController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlin.io.path.isDirectory

@Composable
fun RecentPlayPanel(
    instance: GameInstance?,
    actions: UiActions,
    busy: Boolean,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    kind: EntryKind,
    onKindChange: (EntryKind) -> Unit,
    onLaunch: (GameInstance, GameLaunchTarget?) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (instance == null) return
    val controller = actions.controller
    val scope = rememberCoroutineScope()
    val state by controller.state.collectAsState()
    val clipboard = LocalClipboardManager.current
    var refresh by remember(instance.id) { mutableIntStateOf(0) }
    var tick by remember(instance.id) { mutableIntStateOf(0) }
    var showAll by remember(instance.id) { mutableStateOf(false) }
    var deleting by remember(instance.id) { mutableStateOf<RecentPlayItem?>(null) }
    var loading by remember(instance.id) { mutableStateOf(true) }
    val items by
        produceState(
            emptyList<RecentPlayItem>(),
            instance.id,
            refresh,
            tick,
            state.recentUsageRevision,
        ) {
            loading = true
            try {
                readRecentPlays(controller, instance)?.let { value = it }
            } finally {
                loading = false
            }
        }
    LaunchedEffect(instance.id) {
        while (isActive) {
            delay(30_000)
            tick++
        }
    }

    val perform = ioAction(scope, controller) { refresh++ }
    val callbacks =
        RecentPlayActions(
            play = { item ->
                showAll = false
                onLaunch(instance, GameLaunchTarget(item.kind, item.key))
            },
            primary = { item -> openRecentPlayPrimary(controller, clipboard, item, perform) },
            hide = { item -> hideRecentPlay(controller, instance.id, item, perform) },
            delete = { deleting = it },
            statusQuery = actions.serverStatusQuery,
        )
    val visible = items.filter { it.kind == kind && !it.hidden }.take(8)
    RecentPlayCard(
        kind = kind,
        onKindChange = onKindChange,
        expanded = expanded,
        onExpandedChange = onExpandedChange,
        onRefresh = { refresh++ },
        onShowAll = { showAll = true },
        loading = loading,
        visible = visible,
        busy = busy,
        refreshTick = refresh + tick,
        callbacks = callbacks,
        modifier = modifier,
    )
    if (showAll) {
        RecentPlayDialog(
            items,
            kind,
            busy,
            refresh + tick,
            loading,
            callbacks,
            onRefresh = {
                refresh++
            },
            onDismiss = { showAll = false },
        )
    }
    deleting?.let { item ->
        DeleteRecentPlayDialog(
            item = item,
            onDismiss = { deleting = null },
            onConfirm = {
                deleting = null
                perform { deleteRecentPlay(controller, instance, item) }
            },
        )
    }
}

/** 读取最近游玩；可预期的读取失败只提示，保留上一次的列表内容。 */
private suspend fun readRecentPlays(
    controller: LauncherController,
    instance: GameInstance,
): List<RecentPlayItem>? =
    try {
        withContext(Dispatchers.IO) { loadRecentPlays(controller, instance) }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: IOException) {
        notifyRecentPlayFailure(controller, failure)
        null
    } catch (failure: IllegalStateException) {
        notifyRecentPlayFailure(controller, failure)
        null
    } catch (failure: IllegalArgumentException) {
        notifyRecentPlayFailure(controller, failure)
        null
    }

private fun notifyRecentPlayFailure(
    controller: LauncherController,
    failure: Exception,
) {
    controller.store.notify(tr("pages.recent.readFailed", failure.message.orEmpty()))
}

/** 在 IO 线程执行一次条目动作；可预期的失败转为提示，结束后无论成败都刷新列表。 */
private fun ioAction(
    scope: CoroutineScope,
    controller: LauncherController,
    onDone: () -> Unit,
): (() -> Unit) -> Unit =
    { action ->
        scope.launch {
            try {
                withContext(Dispatchers.IO) { action() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: IOException) {
                controller.store.notify(failure.message ?: tr("pages.recent.actionFailed"))
            } catch (failure: IllegalStateException) {
                controller.store.notify(failure.message ?: tr("pages.recent.actionFailed"))
            } catch (failure: IllegalArgumentException) {
                controller.store.notify(failure.message ?: tr("pages.recent.actionFailed"))
            } finally {
                onDone()
            }
        }
    }

/** 列表行的主要动作：服务器复制地址，存档打开所在文件夹。 */
private fun openRecentPlayPrimary(
    controller: LauncherController,
    clipboard: ClipboardManager,
    item: RecentPlayItem,
    perform: (() -> Unit) -> Unit,
) {
    if (item.kind == EntryKind.Server) {
        clipboard.setText(AnnotatedString(item.key))
        controller.store.notify(tr("pages.recent.copied"))
    } else {
        item.save?.let { save ->
            perform {
                controller.platform.openFolder(
                    if (save.path.isDirectory()) save.path else save.path.parent,
                )
            }
        }
    }
}

/** 隐藏或恢复一条最近游玩记录；与删除分开，不改动磁盘内容。 */
private fun hideRecentPlay(
    controller: LauncherController,
    instanceId: String,
    item: RecentPlayItem,
    perform: (() -> Unit) -> Unit,
) {
    perform {
        controller.store.recentUsage.setHidden(item.kind, instanceId, item.key, !item.hidden)
    }
}

/** 删除一条记录：服务器写回原列表，存档移入回收站并清掉历史。 */
private fun deleteRecentPlay(
    controller: LauncherController,
    instance: GameInstance,
    item: RecentPlayItem,
) {
    if (item.kind == EntryKind.Server) {
        val result = controller.deleteServer(instance.id, item.key)
        controller.store.notify(
            if (result.removed > 0) {
                tr("pages.recent.deletedServer")
            } else {
                tr("pages.recent.removed")
            },
        )
    } else {
        require(controller.deleteSave(instance.id, item.key)) {
            tr("pages.recent.trashFailed")
        }
        controller.store.recentUsage.forget(EntryKind.World, instance.id, item.key)
    }
}

/** 空列表文案：加载中与没有内容分开描述，内容按存档/服务器区分。 */
private fun recentPlayEmptyText(
    loading: Boolean,
    kind: EntryKind,
): String =
    if (loading) {
        tr("pages.recent.loading")
    } else if (kind == EntryKind.World) {
        tr("pages.recent.empty.world")
    } else {
        tr("pages.recent.empty.server")
    }

@Composable
private fun RecentPlayCard(
    kind: EntryKind,
    onKindChange: (EntryKind) -> Unit,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onRefresh: () -> Unit,
    onShowAll: () -> Unit,
    loading: Boolean,
    visible: List<RecentPlayItem>,
    busy: Boolean,
    refreshTick: Int,
    callbacks: RecentPlayActions,
    modifier: Modifier,
) {
    val strings = LocalStrings.current
    Panel(
        modifier.animateContentSize(tween(if (LocalReducedMotion.current) 0 else 180)),
        padding = 0,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp).heightIn(min = 48.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                strings.t("pages.recent.title"),
                Modifier.weight(1f),
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            RecentKindSwitch(kind, onKindChange)
            SmallIconButton(
                Icons.Outlined.Refresh,
                strings.t("pages.recent.refresh"),
                onClick = onRefresh,
            )
            TextButton(onShowAll, contentPadding = PaddingValues(horizontal = 6.dp)) {
                Text(strings.t("pages.recent.viewAll"), fontSize = 12.sp)
            }
            SmallIconButton(
                if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                if (expanded) {
                    strings.t("pages.recent.collapse")
                } else {
                    strings.t("pages.recent.expand")
                },
                onClick = { onExpandedChange(!expanded) },
            )
        }
        if (expanded) {
            if (visible.isEmpty()) {
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    Text(
                        recentPlayEmptyText(loading, kind),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                RecentPlayList(
                    visible,
                    busy,
                    refreshTick,
                    callbacks,
                    Modifier.fillMaxWidth().weight(1f).padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun DeleteRecentPlayDialog(
    item: RecentPlayItem,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val strings = LocalStrings.current
    AlertDialog(
        properties =
            androidx.compose.ui.window
                .DialogProperties(dismissOnClickOutside = false),
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (item.kind == EntryKind.Server) {
                    strings.t("pages.recent.deleteServer.title")
                } else {
                    strings.t("pages.recent.deleteWorld.title")
                },
            )
        },
        text = {
            Text(
                if (item.kind == EntryKind.Server) {
                    strings.t("pages.recent.deleteServer.body", item.title)
                } else {
                    strings.t("pages.recent.deleteWorld.body", item.title)
                },
            )
        },
        confirmButton = {
            TextButton(onConfirm) {
                Text(strings.t("pages.recent.delete"), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onDismiss) { Text(strings.t("pages.recent.cancel")) }
        },
    )
}
