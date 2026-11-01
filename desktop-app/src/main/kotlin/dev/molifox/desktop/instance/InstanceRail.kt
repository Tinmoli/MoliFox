// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 实例详情左侧页签栏：页签、游戏文件夹分组与「管理」折叠区。

package dev.molifox.desktop.instance

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.Architecture
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import dev.molifox.core.GameInstance
import dev.molifox.core.JobStatus
import dev.molifox.core.domain.instance.GameFolder
import dev.molifox.core.domain.instance.instanceOperationAvailability
import dev.molifox.desktop.FrostedPanel
import dev.molifox.desktop.InstanceRailAction
import dev.molifox.desktop.InstanceRailItem
import dev.molifox.desktop.InstanceRailSectionHeader
import dev.molifox.desktop.LocalStrings
import dev.molifox.desktop.MutedText
import dev.molifox.desktop.RailSubEntry
import dev.molifox.desktop.ScrollColumn
import dev.molifox.desktop.UiActions
import dev.molifox.desktop.title

/** 原理图页签的标题；实例没有 schematics 目录时隐藏，索引保持不变。 */
internal const val SCHEMATICS_TAB_KEY = "dialogs.pack.schematics"

/** 实例详情页签：左侧二级列表的 6 个页签。 */
private data class InstanceTab(
    val titleKey: String,
    val icon: ImageVector,
)

private val instanceTabs =
    listOf(
        InstanceTab("nav.settings", Icons.Outlined.Tune),
        InstanceTab("pages.content.kind.mod", Icons.Outlined.Extension),
        InstanceTab("pages.content.kind.resourcepack", Icons.Outlined.Palette),
        InstanceTab("pages.content.kind.shader", Icons.Outlined.AutoAwesome),
        InstanceTab("dialogs.recent.worlds", Icons.Outlined.Save),
        InstanceTab(SCHEMATICS_TAB_KEY, Icons.Outlined.Architecture),
    )

/**
 * 左侧页签栏：6 个页签、游戏文件夹分组与管理动作。
 *
 * 只负责导航与目录/管理区的展开状态；实例本身的维护动作通过回调交给页面。
 */
@Composable
internal fun InstanceDetailsRail(
    instance: GameInstance,
    actions: UiActions,
    selectedTab: Int,
    onSelectTab: (Int) -> Unit,
    jobStates: List<Pair<String, JobStatus>>,
    onExport: () -> Unit,
    onScreenshots: () -> Unit,
    onTidy: () -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit,
    /** 实例里没有 schematics 目录时不显示「原理图」页签；其余页签行为不变。 */
    showSchematics: Boolean = true,
) {
    val strings = LocalStrings.current
    var foldersOpen by remember { mutableStateOf(false) }
    // 「管理」每次进入页面都从收起开始，不持久化展开状态。
    var manageOpen by remember(instance.id) { mutableStateOf(false) }
    var existingFolders by remember(instance.id) { mutableStateOf<List<GameFolder>>(emptyList()) }
    var foldersError by remember(instance.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(instance.id, foldersOpen, jobStates) {
        if (!foldersOpen) return@LaunchedEffect
        actions.controller.instanceWorkspace.existingFolders(instance.id).fold(
            onSuccess = {
                existingFolders = it
                foldersError = null
            },
            onFailure = { foldersError = it.message ?: strings.t("dialogs.export.readDirFailed") },
        )
    }
    val isolated = actions.controller.store.isolated(instance)
    val availability =
        instanceOperationAvailability(
            isolated,
            instance.externalProfile,
            actions.controller.isInstanceBusy(instance.id),
        )
    val canMaintainInstance = availability.maintain
    val canMoveInstance = availability.move
    val moveBlockedNote = availability.moveBlockedNote
    // 左侧页签整栏套磨砂面板：宽度统一 158dp，条目 32dp 高、间距 4dp；内容超出时整栏滚动。
    FrostedPanel(Modifier.width(158.dp).fillMaxHeight()) {
        ScrollColumn(Modifier.fillMaxSize(), gap = 4.dp) {
            instanceTabs.forEachIndexed { index, item ->
                if (!showSchematics && item.titleKey == SCHEMATICS_TAB_KEY) return@forEachIndexed
                InstanceRailItem(strings.t(item.titleKey), item.icon, selectedTab == index) {
                    onSelectTab(index)
                }
            }
            Spacer(Modifier.height(2.dp))
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .6f),
            )
            Spacer(Modifier.height(2.dp))
            InstanceRailItem(
                strings.t("instA1.rail.gameFolders"),
                Icons.Outlined.FolderOpen,
                foldersOpen,
                onClick = { foldersOpen = !foldersOpen },
            )
            if (foldersOpen) {
                foldersError?.let { MutedText(it) }
                existingFolders.forEach { gameFolder ->
                    RailSubEntry(gameFolder.title) {
                        actions.run {
                            actions.controller.openFolder(instance.id, gameFolder.relativePath)
                        }
                    }
                }
            }
            Spacer(Modifier.height(2.dp))
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .6f),
            )
            Spacer(Modifier.height(2.dp))
            // 「管理」默认收起：标题行可点、可聚焦，箭头随展开状态旋转；展开后每项仍是一行直接执行。
            InstanceRailSectionHeader(strings.t("pages.content.tag.management"), manageOpen) {
                manageOpen = !manageOpen
            }
            if (manageOpen) {
                InstanceRailAction(
                    strings.t("pages.logs.action.viewLaunchArgs"),
                    Icons.Outlined.Terminal,
                    enabled =
                        instance.installed && !actions.controller.isInstanceBusy(instance.id),
                ) { actions.previewLaunch(instance.id) }
                InstanceRailAction(strings.t("pages.instances.menu.openFolder"), Icons.Outlined.FolderOpen) {
                    actions.run { actions.controller.openFolder(instance.id) }
                }
                InstanceRailAction(
                    strings.t("instA1.rail.exportModpack"),
                    Icons.Outlined.Archive,
                    enabled = !actions.controller.isInstanceBusy(instance.id),
                ) { onExport() }
                InstanceRailAction(strings.t("dialogs.screenshot.title"), Icons.Outlined.PhotoLibrary) {
                    onScreenshots()
                }
                InstanceRailAction(
                    strings.t("dialogs.tidy.title"),
                    Icons.Outlined.CleaningServices,
                    enabled = canMaintainInstance,
                ) { onTidy() }
                InstanceRailAction(
                    strings.t("core.isolationMigration.30"),
                    Icons.AutoMirrored.Outlined.DriveFileMove,
                    enabled = canMoveInstance,
                    hint = moveBlockedNote,
                ) { onMove() }
                InstanceRailAction(
                    strings.t("dialogs.delete.title"),
                    Icons.Outlined.DeleteOutline,
                    danger = true,
                ) { onDelete() }
            }
        }
    }
}
