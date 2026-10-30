// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 展示模组图标、版本、更新与启用状态。

package dev.molifox.desktop

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.onClick
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.molifox.core.ModFile
import dev.molifox.core.ModUpdateItem
import dev.molifox.core.ModUpdateStatus
import java.nio.file.Path

/** 52dp 模组行：JAR 图标 28dp + 名称 14 + 版本/加载器/大小 12 + 来源文件名 11 + 更新/开关/删除。 */
@Composable
internal fun ModRow(
    mod: ModFile,
    jar: Path?,
    readIcon: suspend (Path) -> ByteArray?,
    onIconFailure: (Throwable) -> Unit,
    modifier: Modifier,
    update: ModUpdateItem?,
    busy: Boolean,
    onDetail: () -> Unit,
    onUpdate: () -> Unit,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
) {
    val shape = RoundedCornerShape(Metrics.cardRadius)
    val sourceName = mod.filename.removeSuffix(".disabled")
    val icon =
        if (jar != null) {
            rememberModIcon(jar, mod.modified, readIcon, onIconFailure)
        } else {
            null
        }
    val details =
        listOf(
            mod.metadata.versions.ifBlank { "版本未知" },
            mod.metadata.loader.ifBlank { "加载器未知" },
            "%.1f MB".format(mod.size / 1048576f),
        ).filter { it.isNotBlank() }
            .joinToString(" · ")
    val metaDetails =
        listOf(
            mod.metadata.loader.ifBlank { "加载器未知" },
            "%.1f MB".format(mod.size / 1048576f),
        ).filter { it.isNotBlank() }
            .joinToString(" · ")
    val updateItem = update?.takeIf { it.status == ModUpdateStatus.Updatable }
    Surface(
        modifier.fillMaxWidth().heightIn(min = 52.dp).pressScale(),
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
            // 图标小于两行文字（约 38dp），行高仍由文字与 52dp 下限决定，开关和删除按钮的位置不变。
            Box(
                Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(
                        MaterialTheme.colorScheme.surfaceVariant.copy(
                            alpha = if (mod.enabled) .5f else .3f,
                        ),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (icon != null) {
                    Image(icon, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                } else {
                    Icon(
                        Icons.Outlined.Extension,
                        null,
                        Modifier.size(15.dp),
                        if (mod.enabled) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
            Spacer(Modifier.width(9.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        mod.metadata.title.ifBlank { sourceName },
                        Modifier.weight(1f, fill = false),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (update?.possibleMatch == true) {
                        Spacer(Modifier.width(5.dp))
                        Tag("可能匹配", MaterialTheme.colorScheme.tertiary)
                    }
                }
                if (updateItem != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            updateItem.currentVersion.ifBlank { "当前" } +
                                " → " +
                                updateItem.latestVersion.ifBlank {
                                    updateItem.candidate?.versionNumber.orEmpty()
                                },
                            Modifier.weight(1f, fill = false),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            metaDetails +
                                if (updateItem.candidate == null) " · 需手动下载" else "",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                } else {
                    Text(
                        details +
                            when (update?.status) {
                                ModUpdateStatus.Latest -> " · 已是最新"
                                ModUpdateStatus.Unknown -> " · 无法确认"
                                else -> ""
                            },
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Text(
                sourceName,
                Modifier.widthIn(max = 150.dp),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.width(8.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (updateItem != null && updateItem.applicable) {
                    RowTextAction("更新", enabled = !busy, onClick = onUpdate)
                }
                CompactSwitch(mod.enabled, { onToggle() }, enabled = !busy)
                RowIconAction(
                    Icons.Outlined.DeleteOutline,
                    "将模组移至回收站",
                    enabled = !busy,
                    onClick = onDelete,
                )
            }
        }
    }
}
