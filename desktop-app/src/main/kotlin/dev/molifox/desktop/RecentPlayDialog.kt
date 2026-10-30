// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责完整存档/服务器列表的分类、搜索、隐藏项恢复及共用列表滚动。

package dev.molifox.desktop

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import dev.molifox.core.EntryKind

@Composable
internal fun RecentKindSwitch(
    kind: EntryKind,
    onChange: (EntryKind) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        listOf(EntryKind.World to "存档", EntryKind.Server to "服务器").forEach { (entry, label) ->
            TextButton(
                { onChange(entry) },
                contentPadding = PaddingValues(horizontal = 8.dp),
                colors =
                    ButtonDefaults.textButtonColors(
                        contentColor =
                            if (kind == entry) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                    ),
            ) {
                Text(label, fontSize = 12.sp)
            }
        }
    }
}

@Composable
internal fun RecentPlayList(
    items: List<RecentPlayItem>,
    busy: Boolean,
    refresh: Int,
    actions: RecentPlayActions,
    modifier: Modifier = Modifier,
) {
    val scroll = rememberLazyListState()
    Box(modifier) {
        LazyColumn(
            Modifier.fillMaxSize().padding(end = 10.dp),
            state = scroll,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items(items, key = { it.kind.name + ":" + it.key }) { item ->
                RecentPlayRow(item, busy, refresh, actions)
                HorizontalDivider(color = hairline().copy(alpha = .55f))
            }
        }
        VerticalScrollbar(
            rememberScrollbarAdapter(scroll),
            Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
        )
    }
}

@Composable
internal fun RecentPlayDialog(
    items: List<RecentPlayItem>,
    initialKind: EntryKind,
    busy: Boolean,
    refresh: Int,
    loading: Boolean,
    actions: RecentPlayActions,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
) {
    var kind by remember { mutableStateOf(initialKind) }
    var search by remember { mutableStateOf("") }
    var showHidden by remember { mutableStateOf(false) }
    val window = LocalWindowInfo.current.containerDpSize
    val visible =
        items.filter {
            it.kind == kind &&
                (showHidden || !it.hidden) &&
                (it.title.contains(search, true) || it.subtitle.contains(search, true))
        }
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            Modifier
                .semantics { contentDescription = "完整存档与服务器列表" }
                .width(minOf(760.dp, (window.width - 48.dp).coerceAtLeast(420.dp)))
                .height((window.height * .75f).coerceIn(320.dp, 700.dp)),
            shape = MaterialTheme.shapes.medium,
            border = BorderStroke(1.dp, hairline()),
        ) {
            Column(
                Modifier.fillMaxSize().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "存档与服务器",
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    SmallIconButton(Icons.Outlined.Refresh, "刷新全部存档与服务器", onClick = onRefresh)
                    SmallIconButton(Icons.Outlined.Close, "关闭存档与服务器列表", onClick = onDismiss)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RecentKindSwitch(kind) { kind = it }
                    Spacer(Modifier.weight(1f))
                    Row(
                        Modifier
                            .toggleable(
                                showHidden,
                                role = Role.Checkbox,
                                onValueChange = { showHidden = it },
                            ).semantics { contentDescription = "显示已隐藏" },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(showHidden, null)
                        Text("显示已隐藏", fontSize = 12.sp)
                    }
                }
                CompactSearchField(
                    search,
                    { search = it },
                    Modifier.fillMaxWidth().semantics { contentDescription = "搜索存档或服务器" },
                    "搜索名称或地址",
                )
                if (visible.isEmpty()) {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text(
                            when {
                                loading -> "正在读取…"
                                search.isNotBlank() -> "没有匹配的结果"
                                else -> "此分类暂无条目，可勾选显示已隐藏"
                            },
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    RecentPlayList(
                        visible,
                        busy,
                        refresh,
                        actions,
                        Modifier.weight(1f).fillMaxWidth(),
                    )
                }
                Text(
                    "${visible.size} 个条目",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
