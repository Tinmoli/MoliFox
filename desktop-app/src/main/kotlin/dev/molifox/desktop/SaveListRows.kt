// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 展示存档、备份条目及时间和大小。

package dev.molifox.desktop

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.onClick
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import dev.molifox.core.SaveBackupInfo
import dev.molifox.core.SaveEntry
import kotlinx.coroutines.delay
import kotlin.io.path.name

private const val SAVE_ZIP_SUFFIX_CHARS = 4

/** 一个存档行：44dp，名称 + 大小与修改时间，`hasLevelDat` 为 false 时给 11sp 提示。 */
@Composable
internal fun SaveRow(
    entry: SaveEntry,
    canManage: Boolean,
    canCopy: Boolean,
    onDetails: () -> Unit,
    onBackup: () -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
    /** 行主体点击；默认与详情一致，多选激活时由调用方改成切换选中。 */
    onRowClick: () -> Unit = onDetails,
) {
    val shape = RoundedCornerShape(Metrics.innerRadius)
    Surface(
        Modifier.fillMaxWidth().height(Metrics.rowHeight),
        shape = shape,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .55f)),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .hoverHighlight(shape, alpha = .04f)
                .clickable(
                    enabled = entry.hasLevelDat && !entry.name.endsWith(".zip", true),
                    onClick = onRowClick,
                ).padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SaveThumbnailHoverCard(
                entry = entry,
                canCopy = canCopy,
                canManage = canManage,
                onDetails = onDetails,
                onCopy = onCopy,
                onDelete = onDelete,
            )
            Column(Modifier.weight(1f)) {
                Text(
                    entry.name,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    tr("saveC.row.meta", byteSizeText(entry.size), formatSaveTime(entry.lastModified)),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (!entry.hasLevelDat) {
                Text(
                    tr("saveC.row.maybeInvalid"),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.error.copy(alpha = .85f),
                    maxLines = 1,
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                RowIconAction(
                    Icons.Outlined.Info,
                    tr("saveC.row.details"),
                    enabled = entry.hasLevelDat && !entry.name.endsWith(".zip", true),
                    onClick = onDetails,
                )
                RowIconAction(Icons.Outlined.Archive, tr("saveC.row.backup"), enabled = canManage, onClick = onBackup)
                RowIconAction(Icons.Outlined.ContentCopy, tr("saveC.row.copyTo"), enabled = canCopy, onClick = onCopy)
                RowIconAction(
                    Icons.Outlined.DeleteOutline,
                    tr("saveC.row.trash", entry.name),
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
                    byteSizeText(backup.size) +
                    if (backup.restorable) "" else tr("saveC.backup.noLevelDat"),
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
            RowIconAction(
                Icons.Outlined.Restore,
                tr("saveC.backup.restore"),
                enabled = !busy && backup.restorable,
                onClick = onRestore,
            )
            RowIconAction(
                Icons.Outlined.DeleteOutline,
                tr("saveC.backup.trash"),
                enabled = !busy,
                onClick = onDelete,
            )
        }
    }
}

/** 存档名去掉 `.zip` 后缀（不区分大小写），用于同名判断；只影响展示逻辑。 */
internal fun saveBaseName(name: String): String =
    if (name.endsWith(".zip", true)) name.dropLast(SAVE_ZIP_SUFFIX_CHARS) else name

/** 存档相关时间：本地时区 `yyyy-MM-dd HH:mm`；缺失时如实说明，不伪造时间。各列表的兜底文案不同，由调用方传入。 */
private val saveTimeFormatter: java.time.format.DateTimeFormatter =
    java.time.format.DateTimeFormatter
        .ofPattern("yyyy-MM-dd HH:mm")
        .withZone(java.time.ZoneId.systemDefault())

internal fun formatSaveTime(
    time: java.time.Instant?,
    unknownText: String = tr("saveC.row.unknownTime"),
): String = time?.let { runCatching { saveTimeFormatter.format(it) }.getOrNull() } ?: unknownText

/** 存档缩略图：悬停 250ms 后浮出详情卡片，可直接复制或删除；移入卡片不会收起。 */
@Composable
private fun SaveThumbnailHoverCard(
    entry: SaveEntry,
    canCopy: Boolean,
    canManage: Boolean,
    onDetails: () -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
) {
    val strings = LocalStrings.current
    val anchorSource = remember { MutableInteractionSource() }
    val cardSource = remember { MutableInteractionSource() }
    val anchorHovered by anchorSource.collectIsHoveredAsState()
    val cardHovered by cardSource.collectIsHoveredAsState()
    var shown by remember(entry.path) { mutableStateOf(false) }
    LaunchedEffect(anchorHovered, cardHovered) {
        if (anchorHovered || cardHovered) {
            if (!shown) {
                delay(250)
                shown = true
            }
        } else {
            delay(200)
            shown = false
        }
    }
    Box {
        Box(Modifier.hoverable(anchorSource)) { LocalContentThumbnail(entry.path, world = true) }
        if (shown) {
            Popup(alignment = Alignment.TopStart, offset = IntOffset(0, 40)) {
                Surface(
                    Modifier.width(250.dp).hoverable(cardSource),
                    shape = RoundedCornerShape(Metrics.cardRadius),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .7f)),
                    shadowElevation = 10.dp,
                ) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            entry.name,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            tr("saveC.row.meta", byteSizeText(entry.size), formatSaveTime(entry.lastModified)),
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (!entry.hasLevelDat) {
                            Text(
                                tr("saveC.row.maybeInvalid"),
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.error.copy(alpha = .85f),
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                            MoliTextButton({
                                shown = false
                                onDetails()
                            }) {
                                Text(strings.t("saveC.row.details"), fontSize = 12.sp)
                            }
                            if (canCopy) {
                                MoliTextButton({
                                    shown = false
                                    onCopy()
                                }) {
                                    Text(strings.t("saveC.row.copyTo"), fontSize = 12.sp)
                                }
                            }
                            if (canManage) {
                                MoliTextButton({
                                    shown = false
                                    onDelete()
                                }) {
                                    Text(strings.t("content.menu.delete"), fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
