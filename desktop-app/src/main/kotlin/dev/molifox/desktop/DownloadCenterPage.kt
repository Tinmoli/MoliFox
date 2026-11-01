// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责原版和模组加载器的下载选择。

package dev.molifox.desktop

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Work
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.molifox.core.LauncherState
import dev.molifox.desktop.content.ContentBrowser

/** 下载分类：左侧二级列表的条目定义与顺序。 */
private data class DownloadKind(
    val id: String,
    val title: String,
    val icon: ImageVector,
)

// 分类名随语言变化，用函数而非常量，避免类初始化时把语言定死。
private fun downloadKinds(): List<DownloadKind> =
    listOf(
        DownloadKind("minecraft", tr("pages.download.kind.vanilla"), Icons.Outlined.SportsEsports),
        DownloadKind("modpack", tr("pages.download.kind.modpack"), Icons.Outlined.Work),
        DownloadKind("mod", tr("pages.download.kind.mod"), Icons.Outlined.Extension),
        DownloadKind("resourcepack", tr("pages.download.kind.resourcepack"), Icons.Outlined.Palette),
        DownloadKind("shader", tr("pages.download.kind.shader"), Icons.Outlined.AutoAwesome),
        DownloadKind("datapack", tr("pages.download.kind.datapack"), Icons.Outlined.Storage),
        DownloadKind("world", tr("pages.download.kind.world"), Icons.Outlined.Public),
    )

@Composable
fun ColumnScope.DownloadCenterPage(
    state: LauncherState,
    actions: UiActions,
    kind: String,
    onKindChange: (String) -> Unit,
) {
    // 分类竖排在左侧，页面不显示“下载中心”标题，节省纵向空间。
    // 右侧内容随分类淡入切换（150ms），避免整块内容硬切。
    Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        // 分类左栏与右侧内容区都套磨砂面板，两栏撑满行高；左栏留 8dp 上下内边距，与右栏卡片的内缩对齐成一块。
        FrostedPanel(
            Modifier.width(158.dp).fillMaxHeight(),
            contentPadding = PaddingValues(vertical = 8.dp),
        ) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                downloadKinds().forEach { item ->
                    DownloadKindItem(item.title, item.icon, kind == item.id) {
                        onKindChange(item.id)
                    }
                }
                Spacer(Modifier.height(6.dp))
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .6f),
                )
            }
        }
        // 内容区磨砂在卡片背后：卡片自身的白底不变，只在卡片之间的空隙里透出磨砂。
        FrostedPanel(
            Modifier.weight(1f).fillMaxHeight(),
            contentPadding = PaddingValues(8.dp),
        ) {
            Box(Modifier.fillMaxSize()) {
                if (LocalAnimationsEnabled.current) {
                    Crossfade(
                        kind,
                        animationSpec = tween(150),
                        label = "download-kind",
                    ) { current ->
                        DownloadKindContent(current, state, actions) {
                            actions.taskQueued()
                        }
                    }
                } else {
                    DownloadKindContent(kind, state, actions) {
                        actions.taskQueued()
                    }
                }
            }
        }
    }
}

/** 二级分类条目：高 34dp、圆角 6、图标 17、文字 13；选中态用主题底 + 主题色并做颜色过渡。 */
@Composable
private fun DownloadKindItem(
    title: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(Metrics.cardRadius)
    val contentColor by
        animateColorAsState(
            if (selected) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            tween(150),
            label = "kind-color",
        )
    val background by
        animateColorAsState(
            if (selected) selectedRowBackground() else Color.Transparent,
            tween(150),
            label = "kind-bg",
        )
    Row(
        Modifier
            .fillMaxWidth()
            .height(Metrics.navItemHeight)
            .clip(shape)
            .background(background)
            .rowHoverHighlight(shape, enabled = !selected)
            .pressScale()
            .selectable(selected, role = Role.Tab, onClick = onClick)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(17.dp), contentColor)
        Spacer(Modifier.width(9.dp))
        Text(
            title,
            Modifier.weight(1f),
            color = contentColor,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
        )
    }
}

/** Crossfade 的内容是 Box 作用域，这里补一层 Column 让子页面能使用 weight 撑满剩余高度。 */
@Composable
private fun DownloadKindContent(
    current: String,
    state: LauncherState,
    actions: UiActions,
    onInstalled: () -> Unit,
) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (current == "minecraft") {
            InstallGamePage(state, actions, onInstalled)
        } else {
            ContentBrowser(state, actions, current)
        }
    }
}

/** 紧凑搜索框：34dp 高、圆角 4、1dp 细边、13sp 文字。 桌面端列表页的搜索都在工具栏里，用 M3 默认 56dp 输入框会把整行撑高。 */
@Composable
internal fun CompactSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
) {
    val strings = LocalStrings.current
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(4.dp)
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val hovered by interaction.collectIsHoveredAsState()
    val borderColor by
        animateColorAsState(
            controlBorderColor(focused),
            tween(120),
            label = "search-border",
        )
    val fillColor by
        animateColorAsState(
            controlBackground(focused, hovered),
            tween(120),
            label = "search-fill",
        )
    Row(
        modifier
            .height(34.dp)
            .clip(shape)
            .background(fillColor, shape)
            .border(1.dp, borderColor, shape)
            .padding(horizontal = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Search, null, Modifier.size(16.dp), colors.onSurfaceVariant)
        Spacer(Modifier.width(7.dp))
        BasicTextField(
            value,
            onValueChange,
            Modifier.weight(1f),
            textStyle =
                MaterialTheme.typography.bodyMedium.copy(
                    fontSize = 13.sp,
                    color = colors.onSurface,
                ),
            singleLine = true,
            cursorBrush = SolidColor(colors.primary),
            interactionSource = interaction,
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) {
                        Text(
                            placeholder.ifEmpty { strings.t("pages.common.search") },
                            fontSize = 12.sp,
                            color = colors.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    inner()
                }
            },
        )
        if (value.isNotEmpty()) {
            IconButton({ onValueChange("") }, Modifier.size(20.dp)) {
                Icon(
                    Icons.Outlined.Close,
                    strings.t("pages.common.clearSearch"),
                    Modifier.size(13.dp),
                    colors.onSurfaceVariant,
                )
            }
        }
    }
}
