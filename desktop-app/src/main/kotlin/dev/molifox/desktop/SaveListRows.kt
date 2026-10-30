// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 展示存档、备份条目及时间和大小。

package dev.molifox.desktop

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.molifox.core.SaveBackupInfo
import dev.molifox.core.SaveEntry
import kotlin.io.path.name

private const val SAVE_ZIP_SUFFIX_CHARS = 4
private const val SAVE_GIB_BYTES = 1073741824L
private const val SAVE_GIB_DIVISOR = 1073741824f
private const val SAVE_MIB_BYTES = 1048576L
private const val SAVE_MIB_DIVISOR = 1048576f
private const val SAVE_KIB_BYTES = 1024L
private const val SAVE_KIB_DIVISOR = 1024f

/** 一个存档行：44dp，名称 + 大小与修改时间，`hasLevelDat` 为 false 时给 11sp 提示。 */
@Composable
internal fun SaveRow(
    entry: SaveEntry,
    canManage: Boolean,
    canCopy: Boolean,
    onBackup: () -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
) {
    val shape = RoundedCornerShape(Metrics.innerRadius)
    Surface(
        Modifier.fillMaxWidth().height(Metrics.rowHeight),
        shape = shape,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .55f)),
    ) {
        Row(
            Modifier.fillMaxWidth().hoverHighlight(shape, alpha = .04f).padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            LocalContentThumbnail(entry.path, world = true)
            Column(Modifier.weight(1f)) {
                Text(
                    entry.name,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    formatSaveSize(entry.size) + " · 修改于 " + formatSaveTime(entry.lastModified),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (!entry.hasLevelDat) {
                Text(
                    "可能不是有效世界",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.error.copy(alpha = .85f),
                    maxLines = 1,
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                RowTextAction("备份", enabled = canManage, onClick = onBackup)
                RowTextAction("复制到…", enabled = canCopy, onClick = onCopy)
                RowIconAction(
                    Icons.Outlined.DeleteOutline,
                    "将「${entry.name}」移至回收站",
                    enabled = canManage,
                    onClick = onDelete,
                )
            }
        }
    }
}

/** 一个备份行：44dp，来源存档名 + 创建时间与大小；没有 level.dat 的压缩包不提供恢复。 */
@Composable
internal fun SaveBackupRow(
    backup: SaveBackupInfo,
    busy: Boolean,
    onRestore: () -> Unit,
    onDelete: () -> Unit,
) {
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
            Icons.Outlined.Archive,
            null,
            Modifier.size(18.dp),
            MaterialTheme.colorScheme.primary,
        )
        Column(Modifier.weight(1f)) {
            Text(
                backup.saveName,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                formatSaveTime(backup.createdAt) +
                    " · " +
                    formatSaveSize(backup.size) +
                    if (backup.restorable) "" else " · 不含 level.dat",
                fontSize = 12.sp,
                color =
                    if (backup.restorable) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.error.copy(alpha = .8f)
                    },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            RowTextAction("恢复", enabled = !busy && backup.restorable, onClick = onRestore)
            RowIconAction(
                Icons.Outlined.DeleteOutline,
                "将备份移至回收站",
                enabled = !busy,
                onClick = onDelete,
            )
        }
    }
}

/** 存档名去掉 `.zip` 后缀（不区分大小写），用于同名判断；只影响展示逻辑。 */
internal fun saveBaseName(name: String): String =
    if (name.endsWith(".zip", true)) name.dropLast(SAVE_ZIP_SUFFIX_CHARS) else name

/** 存档相关时间：本地时区 `yyyy-MM-dd HH:mm`；缺失时如实说明，不伪造时间。 */
private val saveTimeFormatter: java.time.format.DateTimeFormatter =
    java.time.format.DateTimeFormatter
        .ofPattern("yyyy-MM-dd HH:mm")
        .withZone(java.time.ZoneId.systemDefault())

internal fun formatSaveTime(time: java.time.Instant?): String =
    time?.let { runCatching { saveTimeFormatter.format(it) }.getOrNull() } ?: "未知时间"

internal fun formatSaveSize(bytes: Long): String =
    when {
        bytes >= SAVE_GIB_BYTES -> "%.1f GB".format(bytes / SAVE_GIB_DIVISOR)
        bytes >= SAVE_MIB_BYTES -> "%.1f MB".format(bytes / SAVE_MIB_DIVISOR)
        bytes >= SAVE_KIB_BYTES -> "%.1f KB".format(bytes / SAVE_KIB_DIVISOR)
        else -> bytes.toString() + " B"
    }
