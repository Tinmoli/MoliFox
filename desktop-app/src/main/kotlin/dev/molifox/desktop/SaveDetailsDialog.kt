// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 存档详情弹窗：信息与设置页、数据包页。

package dev.molifox.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.molifox.core.GameInstance
import dev.molifox.core.SaveEntry
import dev.molifox.core.SaveSettingsChanges
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

private const val DETAILS_DIALOG_WIDTH_DP = 820
private const val DETAILS_DIALOG_HEIGHT_DP = 620
private const val DETAILS_DIALOG_MAX_WIDTH_FRACTION = 0.92f
private const val DETAILS_DIALOG_MAX_HEIGHT_FRACTION = 0.9f
private const val DETAILS_TAB_INFO = 0
private const val DETAILS_TAB_DATAPACKS = 1
private const val SAVE_ZIP_SUFFIX_CHARS = 4

/** 一个存档的详情弹窗：可查看只读信息、修改难度/作弊设置，管理该存档的数据包。 */
@Composable
internal fun SaveDetailsDialog(
    instance: GameInstance,
    entry: SaveEntry,
    actions: UiActions,
    canEdit: Boolean,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
) {
    var tab by remember(entry.name) { mutableIntStateOf(DETAILS_TAB_INFO) }
    var saving by remember(entry.name) { mutableStateOf(false) }
    var saveError by remember(entry.name) { mutableStateOf<String?>(null) }
    var saveDone by remember(entry.name) { mutableStateOf(false) }
    var reload by remember(entry.name) { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val dismiss = { if (!saving) onDismiss() }
    val save: (SaveSettingsChanges) -> Unit = { changes ->
        saving = true
        saveError = null
        saveDone = false
        scope.launch {
            try {
                val written =
                    withContext(Dispatchers.IO) {
                        actions.controller.saves.updateSettings(instance.id, entry.name, changes)
                    }
                if (written) {
                    saveDone = true
                    reload++
                    onSaved()
                } else {
                    saveError = "没有写入：目标字段不存在或值没有变化"
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: IOException) {
                saveError = failure.message ?: "写入存档文件失败"
            } catch (failure: IllegalArgumentException) {
                saveError = failure.message ?: "保存存档设置失败"
            } catch (failure: IllegalStateException) {
                saveError = failure.message ?: "保存存档设置失败"
            } finally {
                saving = false
            }
        }
    }
    val canManageDatapacks = entry.hasLevelDat && !entry.name.endsWith(".zip", true)
    Dialog(
        onDismissRequest = dismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            Modifier
                .widthIn(max = DETAILS_DIALOG_WIDTH_DP.dp)
                .fillMaxWidth(DETAILS_DIALOG_MAX_WIDTH_FRACTION)
                .heightIn(max = DETAILS_DIALOG_HEIGHT_DP.dp)
                .fillMaxHeight(DETAILS_DIALOG_MAX_HEIGHT_FRACTION),
            shape = RoundedCornerShape(Metrics.cardRadius),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
        ) {
            Column(Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 14.dp)) {
                SaveDetailsHeader(entry, enabled = !saving, onDismiss = dismiss)
                if (canManageDatapacks) {
                    TabRow(selectedTabIndex = tab) {
                        Tab(
                            selected = tab == DETAILS_TAB_INFO,
                            onClick = { tab = DETAILS_TAB_INFO },
                            enabled = !saving,
                        ) {
                            Text("信息", fontSize = 12.sp, modifier = Modifier.padding(10.dp))
                        }
                        Tab(
                            selected = tab == DETAILS_TAB_DATAPACKS,
                            onClick = { tab = DETAILS_TAB_DATAPACKS },
                            enabled = !saving,
                        ) {
                            Text("数据包", fontSize = 12.sp, modifier = Modifier.padding(10.dp))
                        }
                    }
                }
                when (tab) {
                    DETAILS_TAB_DATAPACKS ->
                        SaveDatapackPanel(instance, entry.name, actions, Modifier.weight(1f))
                    else ->
                        SaveInfoPane(
                            instance = instance,
                            saveName = entry.name,
                            actions = actions,
                            canEdit = canEdit,
                            saving = saving,
                            saveError = saveError,
                            saveDone = saveDone,
                            onDismissNotice = { saveDone = false },
                            onSave = save,
                            reload = reload,
                            modifier = Modifier.weight(1f),
                        )
                }
            }
        }
    }
}

@Composable
private fun SaveDetailsHeader(
    entry: SaveEntry,
    enabled: Boolean,
    onDismiss: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                entry.name.removeSuffixIgnoreCaseZip(),
                fontSize = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "存档详情",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        SmallIconButton(
            icon = Icons.Outlined.Close,
            label = "关闭存档详情",
            onClick = onDismiss,
            enabled = enabled,
            size = 30.dp,
        )
    }
}

/** 去掉展示用的 `.zip` 后缀；详情弹窗只对真实世界目录开放数据包与设置。 */
internal fun String.removeSuffixIgnoreCaseZip(): String =
    if (endsWith(".zip", true)) dropLast(SAVE_ZIP_SUFFIX_CHARS) else this
