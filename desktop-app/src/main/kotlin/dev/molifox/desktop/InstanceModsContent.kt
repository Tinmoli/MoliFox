// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责实例模组列表的筛选、状态提示与条目渲染。

package dev.molifox.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import dev.molifox.desktop.MoliTextButton as TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.molifox.core.GameInstance
import dev.molifox.core.ModFile
import dev.molifox.core.ModUpdateItem
import dev.molifox.core.ModUpdateStatus
import java.nio.file.Path

/** 芯片行常驻：即使检查失败或被取消，也能继续筛选并回到「版本管理」。 */
@Composable
internal fun ModsStatusBar(
    chip: ContentChip,
    onChipSelect: (ContentChip) -> Unit,
    statusText: String,
    updateCount: Int,
    checking: Boolean,
    instanceBusy: Boolean,
    onCheckUpdates: () -> Unit,
    onOpenJarInJar: () -> Unit,
    onUpdateAll: () -> Unit,
) {
    val strings = LocalStrings.current
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ContentChipRow(
            selected = chip,
            visible =
                listOf(
                    ContentChip.All,
                    ContentChip.Disabled,
                    ContentChip.Updatable,
                    ContentChip.NoUpdate,
                ),
        ) { onChipSelect(it) }
        if (statusText.isNotBlank()) {
            Text(
                statusText,
                Modifier.weight(1f),
                fontSize = 11.sp,
                color =
                    if (updateCount > 0) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        } else {
            Spacer(Modifier.weight(1f))
        }
        TextButton(
            { onCheckUpdates() },
            enabled = !instanceBusy && !checking,
        ) {
            Text(
                if (checking) strings.t("deskD.mods.checkingShort") else strings.t("deskD.mods.checkUpdates"),
                fontSize = 12.sp,
            )
        }
        TextButton(
            { onOpenJarInJar() },
            enabled = !instanceBusy,
        ) {
            Text(strings.t("deskD.mods.jarInJar"), fontSize = 12.sp)
        }
        TextButton(
            { onUpdateAll() },
            enabled = !instanceBusy && !checking && updateCount > 0,
        ) {
            Text(strings.t("pages.updateBatch.oneClick.count", updateCount), fontSize = 12.sp)
        }
    }
}

/** 更新检查失败的提示行：保留重新检查入口。 */
@Composable
internal fun ModsErrorRow(
    message: String?,
    checking: Boolean,
    instanceBusy: Boolean,
    onRetry: () -> Unit,
) {
    val strings = LocalStrings.current
    message?.let { failure ->
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                failure,
                Modifier.weight(1f),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.error,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            TextButton(
                { onRetry() },
                enabled = !instanceBusy && !checking,
            ) {
                Text(strings.t("deskD.mods.recheck"), fontSize = 12.sp)
            }
        }
    }
}

/** 按键名、标题与条目 id 过滤，再按芯片的更新状态过滤。 */
internal fun filterMods(
    mods: List<ModFile>,
    query: String,
    chip: ContentChip,
    reportByFile: Map<String, ModUpdateItem>,
): List<ModFile> =
    mods
        .filter { mod -> matchesModQuery(mod, query) }
        .filter { mod -> matchesModChip(mod, chip, reportByFile) }

private fun matchesModQuery(
    mod: ModFile,
    query: String,
): Boolean =
    query.isBlank() ||
        mod.filename.contains(query, true) ||
        mod.metadata.title.contains(query, true) ||
        mod.metadata.entries.any { entry -> entry.id.contains(query, true) }

private fun matchesModChip(
    mod: ModFile,
    chip: ContentChip,
    reportByFile: Map<String, ModUpdateItem>,
): Boolean =
    when (chip) {
        ContentChip.All -> true
        ContentChip.Disabled -> !mod.enabled
        ContentChip.Updatable ->
            reportByFile[mod.filename]?.status == ModUpdateStatus.Updatable
        // 「未找到更新」只收「无法确认」的条目；已是最新的只在「全部」里出现。
        ContentChip.NoUpdate ->
            reportByFile[mod.filename]?.status == ModUpdateStatus.Unknown
    }

/** 模组列表：骨架、错误、空态与 52dp 行，行内只在 JAR 有路径时读图标。 */
@Composable
internal fun ModsListBody(
    mods: List<ModFile>,
    filtered: List<ModFile>,
    loading: Boolean,
    error: String?,
    chip: ContentChip,
    query: String,
    busy: Boolean,
    applying: Boolean,
    reportByFile: Map<String, ModUpdateItem>,
    modsDirectory: Path?,
    readIcon: suspend (Path) -> ByteArray?,
    onIconFailure: (Throwable) -> Unit,
    onRetry: () -> Unit,
    onDiscover: () -> Unit,
    onClearFilter: () -> Unit,
    onDetail: (ModFile) -> Unit,
    onUpdate: (ModFile, ModUpdateItem) -> Unit,
    onToggle: (ModFile) -> Unit,
    onRename: (ModFile) -> Unit,
    onOpenFolder: () -> Unit,
    onUpdateSelection: (List<ModFile>) -> Unit,
    onEnableSelection: (List<ModFile>, Boolean) -> Unit,
    onDelete: (List<ModFile>) -> Unit,
    modifier: Modifier,
) {
    val strings = LocalStrings.current
    when {
        loading && mods.isEmpty() -> ListSkeleton()

        error != null ->
            Panel(Modifier.fillMaxWidth()) {
                EmptyState(strings.t("deskD.mods.listLoadFailed"), error.orEmpty(), Icons.Outlined.CloudOff) {
                    TextButton(onRetry) { Text(strings.t("deskD.mods.retry")) }
                }
            }

        mods.isEmpty() ->
            Panel(Modifier.fillMaxWidth()) {
                EmptyState(
                    strings.t("deskD.mods.emptyTitle"),
                    strings.t("deskD.mods.emptyHint"),
                    Icons.Outlined.Extension,
                ) {
                    PrimaryButton(strings.t("deskD.mods.download"), Icons.Outlined.Add, onClick = onDiscover)
                }
            }

        filtered.isEmpty() -> ModsNoMatchState(chip, query, onClearFilter)

        else ->
            ModsLoadedList(
                filtered = filtered,
                busy = busy,
                applying = applying,
                reportByFile = reportByFile,
                modsDirectory = modsDirectory,
                readIcon = readIcon,
                onIconFailure = onIconFailure,
                onDetail = onDetail,
                onUpdate = onUpdate,
                onToggle = onToggle,
                onRename = onRename,
                onOpenFolder = onOpenFolder,
                onUpdateSelection = onUpdateSelection,
                onEnableSelection = onEnableSelection,
                onDelete = onDelete,
                modifier = modifier,
            )
    }
}

/** 筛选后为空：区分「某个芯片下没有条目」与「关键词没有匹配」。 */
@Composable
private fun ModsNoMatchState(
    chip: ContentChip,
    query: String,
    onClearFilter: () -> Unit,
) {
    val strings = LocalStrings.current
    val chipFiltered = chip != ContentChip.All && query.isBlank()
    Panel(Modifier.fillMaxWidth()) {
        EmptyState(
            if (chipFiltered) {
                strings.t("deskD.mods.noChipMatch", chip.title)
            } else {
                strings.t("deskD.mods.noQueryMatch")
            },
            if (chipFiltered) {
                strings.t("deskD.mods.switchToAll")
            } else {
                strings.t("deskD.mods.tryOtherKeyword")
            },
            Icons.Outlined.SearchOff,
        ) {
            TextButton(onClearFilter) { Text(strings.t("deskD.mods.clearFilter")) }
        }
    }
}

/** 依赖管理、项目详情、更新确认与删除确认：只显示当前需要的那个对话框。 */
@Composable
internal fun ModsDialogs(
    jarInJarOpen: Boolean,
    instance: GameInstance,
    actions: UiActions,
    mods: List<ModFile>,
    detailMod: ModFile?,
    confirmingUpdate: ModUpdateItem?,
    deletingMods: List<ModFile>,
    renamingMod: ModFile?,
    onCloseJarInJar: () -> Unit,
    onCloseDetail: () -> Unit,
    onConfirmUpdate: (ModUpdateItem) -> Unit,
    onDismissConfirm: () -> Unit,
    onDeleteMods: (List<ModFile>) -> Unit,
    onDismissDelete: () -> Unit,
    onRenameMod: (ModFile, String) -> Unit,
    onDismissRename: () -> Unit,
) {
    if (jarInJarOpen) {
        JarInJarDialog(instance, mods, actions) { onCloseJarInJar() }
    }
    detailMod?.let { mod -> ModDetailDialogHost(mod, instance, actions, onCloseDetail) }
    // 名称搜索得到的匹配先确认来源项目，避免把同名模组替换掉。
    confirmingUpdate?.let { item ->
        ModUpdateConfirmation(
            item,
            onConfirm = { onConfirmUpdate(item) },
            onDismiss = onDismissConfirm,
        )
    }
    // 删除模组会从 mods 目录移入回收站，先确认再执行。
    if (deletingMods.isNotEmpty()) {
        DeleteModConfirmation(
            deletingMods,
            onConfirm = { onDeleteMods(deletingMods) },
            onDismiss = onDismissDelete,
        )
    }
    renamingMod?.let { mod ->
        ContentRenameDialog(
            title = tr("content.rename.modTitle"),
            currentName = mod.filename,
            onConfirm = { onRenameMod(mod, it) },
            onDismiss = onDismissRename,
        )
    }
}

/** 顶部细进度条：检查中或刷新已有列表时显示，首次加载交给骨架。 */
@Composable
internal fun ModsThinProgress(visible: Boolean) {
    if (visible) {
        LinearProgressIndicator(Modifier.fillMaxWidth().height(3.dp))
    }
}

/** 列表底部的文件统计：加载中或没有条目时不显示。 */
@Composable
internal fun ModsCountFooter(
    loading: Boolean,
    mods: List<ModFile>,
) {
    val strings = LocalStrings.current
    if (!loading && mods.isNotEmpty()) {
        Text(
            strings.t("deskD.mods.countFooter", mods.size, mods.count { it.enabled }),
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
