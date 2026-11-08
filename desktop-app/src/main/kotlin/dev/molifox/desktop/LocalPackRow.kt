// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 展示本地资源包与光影包的名称、缩略图和操作。

package dev.molifox.desktop

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.onClick
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.SystemUpdateAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.molifox.core.LocalPackEntry
import dev.molifox.core.PackFolder
import dev.molifox.core.PackUpdateItem
import dev.molifox.core.PackUpdateStatus

/** 资源包 / 光影行：缩略图 + 名称 14 + 类型/大小/修改时间 12 + 更新标记；行尾只有删除。 */
@Composable
internal fun PackRow(
    entry: LocalPackEntry,
    folder: PackFolder,
    update: PackUpdateItem?,
    busy: Boolean,
    iconRevision: Int,
    readIcon: suspend (java.nio.file.Path) -> ByteArray?,
    onIconFailure: (Throwable) -> Unit,
    onDetail: () -> Unit,
    onUpdate: () -> Unit,
    onDelete: () -> Unit,
) {
    val strings = LocalStrings.current
    val shader = folder == PackFolder.ShaderPacks
    val shape = RoundedCornerShape(Metrics.cardRadius)
    val details =
        listOf(
            if (entry.isDirectory) strings.t("deskC.row.folder") else "ZIP",
            formatFileSize(entry.size),
            strings.t(
                "deskC.row.modifiedAt",
                if (entry.modified > 0) {
                    formatSaveTime(java.time.Instant.ofEpochMilli(entry.modified))
                } else {
                    strings.t("deskC.row.unknownTime")
                },
            ),
        ).joinToString(" · ")
    // 更新信息与文件信息合成一行，行高与模组/存档一致；「可更新」用主题色，失败原因如实附在后面。
    val meta =
        buildString {
            if (update?.status == PackUpdateStatus.Updatable) {
                append(update.currentVersion.ifBlank { strings.t("deskC.row.currentVersion") })
                append(" → ")
                append(update.latestVersion.ifBlank { update.candidate?.versionNumber.orEmpty() })
                append(" · ")
            }
            append(details)
            when (update?.status) {
                PackUpdateStatus.Latest -> append(" · ").append(strings.t("deskC.row.latest"))
                PackUpdateStatus.Unknown -> {
                    append(" · ").append(strings.t("deskC.row.uncertain"))
                    if (update.warning.isNotBlank()) append(" · ").append(update.warning)
                }
                else -> Unit
            }
        }
    Surface(
        Modifier.fillMaxWidth().heightIn(min = 52.dp),
        shape = shape,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .55f)),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .hoverHighlight(shape, alpha = .04f)
                .clickable(onClick = onDetail)
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            InstalledPackThumbnail(
                entry,
                revision = iconRevision,
                read = readIcon,
                onFailure = onIconFailure,
                modifier = Modifier.size(28.dp),
                fallback = {
                    if (shader) {
                        ShaderFallbackIcon(Modifier.size(28.dp))
                    } else {
                        Icon(
                            Icons.Outlined.Image,
                            null,
                            Modifier.size(28.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                },
            )
            Spacer(Modifier.width(9.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        entry.filename,
                        Modifier.weight(1f, fill = false),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (update?.possibleMatch == true) {
                        Spacer(Modifier.width(5.dp))
                        Tag(strings.t("deskC.row.possibleMatch"), MaterialTheme.colorScheme.tertiary)
                    }
                }
                Text(
                    meta,
                    fontSize = 12.sp,
                    color =
                        if (update?.status == PackUpdateStatus.Updatable) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            if (update?.status == PackUpdateStatus.Updatable && update.applicable && !update.possibleMatch) {
                RowIconAction(
                    Icons.Outlined.SystemUpdateAlt,
                    strings.t("content.menu.update"),
                    enabled = !busy,
                    onClick = onUpdate,
                )
            }
            RowIconAction(
                Icons.Outlined.DeleteOutline,
                strings.t("deskC.row.trashMessage", entry.filename),
                enabled = !busy,
                onClick = onDelete,
            )
        }
    }
}
