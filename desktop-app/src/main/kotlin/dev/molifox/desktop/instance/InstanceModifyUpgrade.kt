// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 「修改」页签的卡片组件：模组升级（四状态 + 筛选）、迁移内容树与标题/状态元素。

package dev.molifox.desktop.instance

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.molifox.core.InstanceModUpgradeItem
import dev.molifox.core.InstanceModUpgradeState
import dev.molifox.core.PackExportEntry
import dev.molifox.desktop.ExportEntryRow
import dev.molifox.desktop.LocalStrings
import dev.molifox.desktop.Metrics
import dev.molifox.desktop.MoliTextButton
import dev.molifox.desktop.SettingsSection
import dev.molifox.desktop.hairline

/** 模组升级卡：筛选 chips 与逐项判定（按状态决定默认勾选与可否勾选）；重新扫描在卡片头右侧。 */
@Composable
internal fun ModsCard(
    mods: List<InstanceModUpgradeItem>,
    scanning: Boolean,
    scanError: String?,
    checked: Set<String>,
    onToggle: (String, Boolean) -> Unit,
    onRescan: () -> Unit,
) {
    val strings = LocalStrings.current
    // 筛选状态只影响显示；重新扫描后回到「全部」，避免残留过滤掩盖新判定。
    var stateFilter by remember(mods) { mutableStateOf<InstanceModUpgradeState?>(null) }
    SettingsSection(
        strings.t("pages.instance.modify.mods.title"),
        action = {
            MoliTextButton(onRescan) {
                Text(strings.t("pages.instance.modify.mods.rescan"), fontSize = 12.sp)
            }
        },
    ) {
        when {
            scanning && mods.isEmpty() -> ModifyLoadingRow(strings.t("pages.instance.modify.mods.checking"))

            scanError != null && mods.isEmpty() ->
                Text(
                    scanError.orEmpty(),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.error,
                )

            mods.isEmpty() ->
                Text(
                    strings.t("pages.instance.modify.mods.empty"),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

            else -> {
                Text(
                    strings.t(
                        "pages.instance.modify.mods.summary",
                        mods.size,
                        mods.count { it.state == InstanceModUpgradeState.HasNewVersion },
                        mods.count { it.state == InstanceModUpgradeState.CarryAsIs },
                        mods.count { it.state == InstanceModUpgradeState.Incompatible },
                        mods.count { it.state == InstanceModUpgradeState.QueryFailed },
                    ),
                    fontSize = 12.sp,
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ModifyFilterChip(
                        strings.t("pages.instance.modify.mods.filter.all"),
                        mods.size,
                        stateFilter == null,
                    ) { stateFilter = null }
                    InstanceModUpgradeState.entries.forEach { state ->
                        ModifyFilterChip(modStateLabel(state), mods.count { it.state == state }, stateFilter == state) {
                            stateFilter = state
                        }
                    }
                }
                Text(
                    strings.t("pages.instance.modify.mods.hint"),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                (if (stateFilter == null) mods else mods.filter { it.state == stateFilter }).forEach { item ->
                    ModsRow(item, item.filename in checked, onToggle)
                }
            }
        }
    }
}

/** 单个模组行：勾选（查询失败/不兼容不可勾）+ 名称/版本 + 状态 chip。 */
@Composable
private fun ModsRow(
    item: InstanceModUpgradeItem,
    checked: Boolean,
    onToggle: (String, Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ModifyCheckbox(
            checked,
            enabled =
                item.state == InstanceModUpgradeState.HasNewVersion ||
                    item.state == InstanceModUpgradeState.CarryAsIs,
        ) { onToggle(item.filename, it) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    item.title,
                    Modifier.weight(1f),
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                ModStateChip(item.state)
            }
            Text(
                versionLine(item),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (item.note.isNotBlank()) {
                Text(
                    item.note,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** 版本行：有新版本时显示「当前 → 目标」，否则只显示当前版本。 */
private fun versionLine(item: InstanceModUpgradeItem): String =
    if (item.state == InstanceModUpgradeState.HasNewVersion && item.targetVersion.isNotBlank()) {
        listOf(item.currentVersion.ifBlank { null }, item.targetVersion)
            .filterNotNull()
            .joinToString(" → ")
    } else {
        item.currentVersion
    }

/** 四状态的界面文案：判定 chip 与筛选 chips 共用同一套说法。 */
@Composable
internal fun modStateLabel(state: InstanceModUpgradeState): String {
    val strings = LocalStrings.current
    return when (state) {
        InstanceModUpgradeState.HasNewVersion -> strings.t("pages.instance.modify.mods.state.newVersion")
        InstanceModUpgradeState.CarryAsIs -> strings.t("pages.instance.modify.mods.state.carry")
        InstanceModUpgradeState.Incompatible -> strings.t("pages.instance.modify.mods.state.incompatible")
        InstanceModUpgradeState.QueryFailed -> strings.t("pages.instance.modify.mods.state.failed")
    }
}

/** 判定状态 chip 的颜色与文案。 */
@Composable
internal fun ModStateChip(state: InstanceModUpgradeState) {
    val colors = MaterialTheme.colorScheme
    val color =
        when (state) {
            InstanceModUpgradeState.HasNewVersion -> colors.primary
            InstanceModUpgradeState.CarryAsIs -> colors.onSurfaceVariant
            InstanceModUpgradeState.Incompatible -> colors.tertiary
            InstanceModUpgradeState.QueryFailed -> colors.error
        }
    Text(
        modStateLabel(state),
        Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = .14f))
            .padding(horizontal = 10.dp, vertical = 3.dp),
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        color = color,
        maxLines = 1,
    )
}

/** 筛选 chip：名称 + 数量；选中主题色底，未选中灰底。 */
@Composable
private fun ModifyFilterChip(
    label: String,
    count: Int,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val background =
        if (selected) colors.primary.copy(alpha = .14f) else colors.onSurfaceVariant.copy(alpha = .08f)
    Text(
        "$label $count",
        Modifier
            .clip(RoundedCornerShape(50))
            .background(background)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 3.dp),
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        color = if (selected) colors.primary else colors.onSurfaceVariant,
        maxLines = 1,
    )
}

/** 迁移内容卡：与整合包导出同一棵顶层条目树（mods 不出现），文件夹可展开、子路径可单独取消。 */
@Composable
internal fun MigrationCard(
    entries: List<PackExportEntry>,
    checked: Set<String>,
    excluded: Set<String>,
    onToggle: (String, Boolean) -> Unit,
    onToggleNode: (String, Boolean) -> Unit,
) {
    val strings = LocalStrings.current
    SettingsSection(strings.t("pages.instance.modify.migration.title")) {
        Text(
            strings.t("pages.instance.modify.migration.summary", checked.size, entries.size),
            fontSize = 12.sp,
        )
        entries.forEach { entry ->
            ExportEntryRow(
                entry = entry,
                checked = entry.key in checked,
                enabled = true,
                excludedPaths = excluded,
                onToggleNode = onToggleNode,
            ) { onToggle(entry.key, it) }
        }
    }
}

/** 小号复选框：去掉默认最小触控间距，贴合紧凑的卡片行。 */
@Composable
internal fun ModifyCheckbox(
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
        Checkbox(
            checked,
            onCheckedChange,
            enabled = enabled,
            modifier = Modifier.size(20.dp),
        )
    }
}

/** 卡片头：标题 + 右侧目标状态 chip；「升级加载器」卡片只有这一行，不需要内容区。 */
@Composable
internal fun ModifyTitleCard(
    title: String,
    trailing: @Composable () -> Unit,
) {
    Surface(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Metrics.cardRadius),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, hairline()),
    ) {
        Row(
            Modifier.padding(horizontal = Metrics.cardPadding, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            trailing()
        }
    }
}

/** 目标状态 chip：主题色表示要切换的版本或「创建新实例」徽章，tertiary 表示无需修改。 */
@Composable
internal fun ModifyStateChip(
    text: String,
    warn: Boolean,
) {
    val colors = MaterialTheme.colorScheme
    Text(
        text,
        Modifier
            .clip(RoundedCornerShape(50))
            .background(if (warn) colors.tertiary.copy(alpha = .16f) else colors.primary.copy(alpha = .14f))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        color = if (warn) colors.tertiary else colors.primary,
        maxLines = 1,
    )
}
