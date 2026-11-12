// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责设置页面的分组卡片、设置行与还原入口。

package dev.molifox.desktop

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 设置页滚动内容末尾的「还原默认配置」：上方一条分隔线，中间 12sp 次级色文字按钮。 需要下拉才能看到，不与常用开关抢注意力；点击后先弹确认，再交给调用方把全部选项写回默认值。 */
@Composable
internal fun SettingsResetFooter(onReset: () -> Unit) {
    var confirming by remember { mutableStateOf(false) }
    val strings = LocalStrings.current
    Column(Modifier.fillMaxWidth()) {
        Spacer(Modifier.height(6.dp))
        HorizontalDivider(color = hairline())
        Box(Modifier.fillMaxWidth().padding(vertical = 2.dp), contentAlignment = Alignment.Center) {
            Text(
                strings.t("settings.reset.action"),
                Modifier
                    .pressScale()
                    .clip(RoundedCornerShape(Metrics.innerRadius))
                    .hoverHighlight()
                    .clickable(role = Role.Button) { confirming = true }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
            )
        }
    }
    if (confirming) {
        Modal(
            strings.t("settings.reset.confirm.title"),
            strings.t("settings.reset.confirm.detail"),
            460.dp,
            { confirming = false },
        ) {
            MutedText(strings.t("settings.reset.confirm.note"))
            DialogActions(
                confirmText = strings.t("settings.reset.action"),
                onConfirm = {
                    onReset()
                    confirming = false
                },
                cancelText = strings.t("settings.common.cancel"),
                onCancel = { confirming = false },
            )
        }
    }
}

/** 分组小标题：11sp `onSurfaceVariant`，默认上间距 18dp，供设置页分组使用。 */
@Composable
fun SettingsGroupTitle(
    title: String,
    topPadding: Dp = 18.dp,
) {
    Text(
        title,
        Modifier.padding(start = 2.dp, top = topPadding, bottom = 2.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
    )
}

/**
 * 设置卡片：白底、圆角 6、1dp `outlineVariant@.5` 边、无阴影、内边距 16。 标题行 14sp SemiBold（可带 12sp 说明）+ 1dp
 * 分隔线；标题区使用与内容一致的卡片背景。 `action` 是标题行右侧插槽（例如外观页的「已启用」标记），垂直居中。
 */
val LocalCompactSettings = staticCompositionLocalOf { false }

@Composable
fun CompactSettingsSection(
    title: String,
    detail: String = "",
    content: @Composable ColumnScope.() -> Unit,
) {
    CompositionLocalProvider(LocalCompactSettings provides true) {
        SettingsSection(title, detail, contentGap = 6.dp, content = content)
    }
}

@Composable
fun SettingsSection(
    title: String,
    detail: String = "",
    action: @Composable RowScope.() -> Unit = {},
    contentGap: Dp = Metrics.rowGap,
    compact: Boolean = false,
    onHeaderClick: (() -> Unit)? = null,
    showContent: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Metrics.cardRadius),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, hairline()),
    ) {
        Column {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(enabled = onHeaderClick != null) { onHeaderClick?.invoke() }
                    .padding(
                        start = Metrics.cardPadding,
                        end = Metrics.cardPadding,
                        top = if (compact) 6.dp else 11.dp,
                        bottom = if (compact) 6.dp else 10.dp,
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    if (detail.isNotBlank()) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            detail,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                action()
            }
            if (showContent) {
                HorizontalDivider(color = hairline())
                Column(
                    Modifier.padding(
                        horizontal = Metrics.cardPadding,
                        vertical = if (LocalCompactSettings.current) 8.dp else 12.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(contentGap),
                    content = content,
                )
            }
        }
    }
}

/** 设置行：行高 44（带说明 52），标题 14sp、说明 12sp，行间 10dp。 */
@Composable
fun SettingRow(
    title: String,
    detail: String = "",
    content: @Composable () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .defaultMinSize(
                minHeight =
                    if (LocalCompactSettings.current) {
                        36.dp
                    } else if (detail.isBlank()) {
                        Metrics.rowHeight
                    } else {
                        Metrics.rowHeightWithDetail
                    },
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                fontSize = 14.sp,
                fontWeight = FontWeight.Normal,
            )
            if (detail.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    detail,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        content()
    }
}

/**
 * 卡片内相邻设置行之间的细分隔线：1dp、`outlineVariant@.65`，上下各留 5dp（`Metrics.rowGap/2`）。
 *
 * - 颜色取 65%，与白卡的明度差足够，深色主题同样可见；
 * - 线本身不占用水平内边距，绘制时左右各溢出 `Metrics.cardPadding`，因此是整卡宽度的通栏细线， 溢出部分由卡片圆角裁掉，不会盖到卡片外；
 * - 只放在两行之间——首行之前、末行之后都不画；卡片标题下方已有 `SettingsSection` 自己的分隔线。
 *
 * 配合 `SettingsSection(contentGap = 0.dp)` 使用，行高 44 / 52，卡片内边距保持 16dp。
 */
@Composable
fun SettingDivider(modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .65f)
    val bleed = with(LocalDensity.current) { Metrics.cardPadding.toPx() }
    Spacer(
        modifier.fillMaxWidth().padding(vertical = Metrics.rowGap / 2).height(1.dp).drawBehind {
            drawRect(
                color,
                topLeft = Offset(-bleed, 0f),
                size = Size(size.width + bleed * 2f, size.height),
            )
        },
    )
}
