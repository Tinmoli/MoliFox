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
import dev.molifox.desktop.MoliTextButton as TextButton
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
    val strings = LocalStrings.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        listOf(
            EntryKind.World to strings.t("dialogs.recent.worlds"),
            EntryKind.Server to strings.t("dialogs.recent.servers"),
        ).forEach { (entry, label) ->
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
    val strings = LocalStrings.current
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
                .semantics { contentDescription = strings.t("dialogs.recent.fullList") }
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
                        strings.t("dialogs.recent.title"),
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    SmallIconButton(Icons.Outlined.Refresh, strings.t("dialogs.recent.refresh"), onClick = onRefresh)
                    SmallIconButton(Icons.Outlined.Close, strings.t("dialogs.recent.close"), onClick = onDismiss)
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
                            ).semantics { contentDescription = strings.t("dialogs.recent.showHidden") },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(showHidden, null)
                        Text(strings.t("dialogs.recent.showHidden"), fontSize = 12.sp)
                    }
                }
                CompactSearchField(
                    search,
                    { search = it },
                    Modifier.fillMaxWidth().semantics { contentDescription = strings.t("dialogs.recent.searchLabel") },
                    strings.t("dialogs.recent.searchPlaceholder"),
                )
                if (visible.isEmpty()) {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text(
                            when {
                                loading -> strings.t("dialogs.recent.loading")
                                search.isNotBlank() -> strings.t("dialogs.recent.noResults")
                                else -> strings.t("dialogs.recent.emptyCategory")
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
                    strings.t("dialogs.recent.count", visible.size),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
