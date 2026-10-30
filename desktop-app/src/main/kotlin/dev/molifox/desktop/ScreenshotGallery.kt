// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责截图库筛选、预览和文件操作。

package dev.molifox.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.dp
import dev.molifox.core.GameInstance
import dev.molifox.core.GameScreenshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import org.jetbrains.skia.Image as SkiaImage

/** 界面动作的统一失败边界：动作以 lambda 传入，失败类型无法枚举；取消仍原样抛出。 */
internal suspend fun runScreenshotAction(
    action: suspend () -> Unit,
    onFailure: (Exception) -> Unit,
): Boolean =
    try {
        action()
        true
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: IOException) {
        onFailure(failure)
        false
    } catch (failure: IllegalArgumentException) {
        onFailure(failure)
        false
    } catch (failure: IllegalStateException) {
        onFailure(failure)
        false
    }

internal fun screenshotDate(shot: GameScreenshot): String =
    Instant
        .ofEpochMilli(shot.modifiedMillis)
        .atZone(ZoneId.systemDefault())
        .toLocalDate()
        .toString()

@Composable
internal fun ScreenshotImageState(
    shot: GameScreenshot,
    actions: UiActions,
    size: Int = 480,
): State<ImageBitmap?> =
    produceState<ImageBitmap?>(null, shot, size) {
        value =
            try {
                val bytes = actions.controller.screenshots.thumbnail(shot, size)
                withContext(Dispatchers.IO) {
                    SkiaImage.makeFromEncoded(bytes).toComposeImageBitmap()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
    }

@Composable
fun ScreenshotGallery(
    instance: GameInstance,
    actions: UiActions,
    onDismiss: () -> Unit,
) {
    val manager = actions.controller.screenshots
    val scope = rememberCoroutineScope()
    var screenshots by remember(instance.id) { mutableStateOf<List<GameScreenshot>>(emptyList()) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var query by remember { mutableStateOf("") }
    var date by remember { mutableStateOf("") }
    var revision by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf<GameScreenshot?>(null) }
    var deleting by remember { mutableStateOf(false) }
    val selection = screenshots.filter { it.name in selected }
    val filtered =
        screenshots.filter {
            it.name.contains(query, true) && (date.isBlank() || screenshotDate(it) == date)
        }

    fun act(action: suspend () -> Unit) {
        if (busy) return
        scope.launch {
            busy = true
            error = null
            try {
                runScreenshotAction(action) { failure ->
                    error = failure.message ?: "截图操作失败"
                }
            } finally {
                busy = false
            }
        }
    }
    LaunchedEffect(instance.id, revision) {
        loading = true
        try {
            val listed =
                runScreenshotAction({ screenshots = manager.list(instance.id) }) { failure ->
                    error = failure.message
                }
            if (listed) {
                selected = selected.intersect(screenshots.map { it.name }.toSet())
                error = null
            }
        } finally {
            loading = false
        }
    }
    Modal(
        "游戏截图",
        "${instance.name} · ${screenshots.size} 张截图，已选择 ${selected.size} 张",
        width = 800.dp,
        onDismiss = { if (!busy) onDismiss() },
    ) {
        ScreenshotFilterBar(
            instance = instance,
            actions = actions,
            query = query,
            date = date,
            dates = screenshots.map(::screenshotDate).distinct(),
            onQueryChange = { query = it },
            onDateChange = { date = it },
            onRefresh = { revision++ },
        )
        ScreenshotSelectionBar(
            busy = busy,
            selectable = filtered.isNotEmpty(),
            hasSelected = selected.isNotEmpty(),
            hasSelection = selection.isNotEmpty(),
            onSelectAll = { selected = filtered.map { it.name }.toSet() },
            onClear = { selected = emptySet() },
            onExport = {
                act {
                    val target =
                        actions.choose("导出选中截图", directory = true).firstOrNull() ?: return@act
                    val files = manager.export(selection, target)
                    message = "已导出 ${files.size} 张；重名文件自动编号。"
                }
            },
            onDelete = { deleting = true },
        )
        ScreenshotGrid(
            loading = loading,
            screenshots = screenshots,
            filtered = filtered,
            selected = selected,
            busy = busy,
            actions = actions,
            onPreview = { preview = it },
            onSelectedChange = { selected = it },
        )
        ScreenshotStatus(error = error, message = message, busy = busy)
        DialogActions("完成", onDismiss, enabled = !busy)
    }
    ScreenshotDeleteDialog(
        visible = deleting,
        count = selection.size,
        onConfirm = {
            deleting = false
            act {
                manager.remove(selection)
                selected = emptySet()
                revision++
                message = "截图已移入回收站"
            }
        },
        onCancel = { deleting = false },
    )
    ScreenshotPreviewDialog(preview, actions) { preview = null }
}

@Composable
private fun ScreenshotFilterBar(
    instance: GameInstance,
    actions: UiActions,
    query: String,
    date: String,
    dates: List<String>,
    onQueryChange: (String) -> Unit,
    onDateChange: (String) -> Unit,
    onRefresh: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CompactTextField(query, onQueryChange, Modifier.weight(1f), placeholder = "搜索截图文件名")
        SimpleDropdown(
            date,
            listOf("" to "全部日期") + dates.map { it to it },
            onDateChange,
            Modifier.width(150.dp),
        )
        SmallIconButton(Icons.Outlined.Refresh, "刷新截图", onRefresh)
        SmallIconButton(
            Icons.Outlined.FolderOpen,
            "打开截图目录",
            { actions.run { actions.controller.openFolder(instance.id, "screenshots") } },
        )
    }
}

@Composable
private fun ScreenshotSelectionBar(
    busy: Boolean,
    selectable: Boolean,
    hasSelected: Boolean,
    hasSelection: Boolean,
    onSelectAll: () -> Unit,
    onClear: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SecondaryButton(onSelectAll, enabled = !busy && selectable) {
            Text("选择当前结果")
        }
        SecondaryButton(onClear, enabled = !busy && hasSelected) {
            Text("取消选择")
        }
        SecondaryButton(onExport, enabled = !busy && hasSelection) {
            Text("导出所选")
        }
        SecondaryButton(onDelete, enabled = !busy && hasSelection) {
            Text("移入回收站")
        }
    }
}

@Composable
private fun ScreenshotDeleteDialog(
    visible: Boolean,
    count: Int,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    if (!visible) return
    Modal("移除所选截图", "将 $count 张图片移入回收站。", onDismiss = onCancel) {
        Text("原截图会从当前实例的截图目录移除，可以在系统回收站或启动器恢复目录查找。")
        DialogActions(
            "确认移除截图",
            onConfirm,
            onCancel = onCancel,
            danger = true,
        )
    }
}
