// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 展示数据包描述、格式与行尾操作。

package dev.molifox.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.onClick
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.molifox.core.DatapackEntry

/** 一个数据包行：44dp，图标 + 名称 + 大小与状态，行尾启用开关与「删除」。 */
@Composable
internal fun DatapackRow(
    entry: DatapackEntry,
    toggleEnabled: Boolean,
    onToggle: (Boolean) -> Unit,
    deleteEnabled: Boolean,
    onDelete: () -> Unit,
) {
    val strings = LocalStrings.current
    Row(
        Modifier
            .fillMaxWidth()
            .height(Metrics.rowHeight)
            .hoverHighlight(RoundedCornerShape(Metrics.innerRadius), alpha = .04f)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            if (entry.directory) Icons.Outlined.Folder else Icons.Outlined.FolderZip,
            null,
            Modifier.size(18.dp),
            MaterialTheme.colorScheme.primary,
        )
        Column(Modifier.weight(1f)) {
            Text(entry.name, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val kind =
                if (entry.directory) {
                    strings.t("deskF.datapackRow.folder")
                } else {
                    strings.t("deskF.datapackRow.archive")
                }
            Text(
                kind +
                    " · " +
                    byteSizeText(entry.size) +
                    " · " +
                    when (entry.enabled) {
                        true -> strings.t("deskF.datapackRow.enabled")
                        false -> strings.t("deskF.datapackRow.disabled")
                        null -> strings.t("deskF.datapackRow.unknownStatus")
                    },
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        CompactSwitch(
            entry.enabled == true,
            { onToggle(entry.enabled != true) },
            enabled = toggleEnabled,
        )
        RowIconAction(
            Icons.Outlined.DeleteOutline,
            strings.t("deskF.datapackRow.trashMessage"),
            enabled = deleteEnabled,
            onClick = onDelete,
        )
    }
}
