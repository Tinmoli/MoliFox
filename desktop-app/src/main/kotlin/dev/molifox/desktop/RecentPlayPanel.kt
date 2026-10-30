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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.molifox.core.EntryKind
import dev.molifox.core.GameInstance
import dev.molifox.core.GameLaunchTarget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
                value = withContext(Dispatchers.IO) { loadRecentPlays(controller, instance) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                controller.store.notify("读取最近游玩失败：${failure.message}")
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

    fun perform(action: () -> Unit) {
        scope.launch {
            try {
                withContext(Dispatchers.IO) { action() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                controller.store.notify(failure.message ?: "操作失败，请查看日志")
            } finally {
                refresh++
            }
        }
    }
    val callbacks =
        RecentPlayActions(
            play = { item ->
                showAll = false
                onLaunch(instance, GameLaunchTarget(item.kind, item.key))
            },
            primary = { item ->
                if (item.kind == EntryKind.Server) {
                    clipboard.setText(AnnotatedString(item.key))
                    controller.store.notify("已复制服务器地址")
                } else {
                    item.save?.let { save ->
                        perform {
                            controller.platform.openFolder(
                                if (save.path.isDirectory()) save.path else save.path.parent,
                            )
                        }
                    }
                }
            },
            hide = { item ->
                perform {
                    controller.store.recentUsage.setHidden(
                        item.kind,
                        instance.id,
                        item.key,
                        !item.hidden,
                    )
                }
            },
            delete = { deleting = it },
            statusQuery = actions.serverStatusQuery,
        )
    val visible = items.filter { it.kind == kind && !it.hidden }.take(8)
    Panel(
        modifier.animateContentSize(tween(if (LocalReducedMotion.current) 0 else 180)),
        padding = 0,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp).heightIn(min = 48.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "最近游玩",
                Modifier.weight(1f),
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            RecentKindSwitch(kind, onKindChange)
            SmallIconButton(
                Icons.Outlined.Refresh,
                "刷新最近游玩的存档与服务器",
                onClick = {
                    refresh++
                },
            )
            TextButton({ showAll = true }, contentPadding = PaddingValues(horizontal = 6.dp)) {
                Text("查看全部", fontSize = 12.sp)
            }
            SmallIconButton(
                if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                if (expanded) "折叠最近游玩的存档或服务器" else "展开最近游玩的存档或服务器",
                onClick = { onExpandedChange(!expanded) },
            )
        }
        if (expanded) {
            if (visible.isEmpty()) {
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    Text(
                        if (loading) {
                            "正在读取…"
                        } else {
                            "暂无${if (kind == EntryKind.World) "存档" else "服务器"}，可在查看全部中恢复隐藏项"
                        },
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                RecentPlayList(
                    visible,
                    busy,
                    refresh,
                    callbacks,
                    Modifier.fillMaxWidth().weight(1f).padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
    }
    if (showAll) {
        RecentPlayDialog(
            items,
            kind,
            busy,
            refresh,
            loading,
            callbacks,
            onRefresh = {
                refresh++
            },
            onDismiss = { showAll = false },
        )
    }
    deleting?.let { item ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(if (item.kind == EntryKind.Server) "删除服务器？" else "删除存档？") },
            text = {
                Text(
                    if (item.kind == EntryKind.Server) {
                        "「${item.title}」将从当前游戏目录的服务器列表移除，游戏内也不再显示。共用此目录的实例同步生效；修改前会备份原列表。请先退出使用此目录的游戏。"
                    } else {
                        "「${item.title}」将移入回收站，可从回收站恢复。"
                    },
                )
            },
            confirmButton = {
                TextButton({
                    deleting = null
                    perform {
                        if (item.kind == EntryKind.Server) {
                            val result = controller.deleteServer(instance.id, item.key)
                            controller.store.notify(
                                if (result.removed > 0) "服务器已删除，原列表已备份" else "已移除这条历史记录",
                            )
                        } else {
                            require(controller.deleteSave(instance.id, item.key)) {
                                "存档未能移入回收站，请查看日志"
                            }
                            controller.store.recentUsage.forget(
                                EntryKind.World,
                                instance.id,
                                item.key,
                            )
                        }
                    }
                }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton({ deleting = null }) { Text("取消") } },
        )
    }
}
