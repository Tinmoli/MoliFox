// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责截图库筛选、预览和文件操作。

package dev.molifox.desktop

import androidx.collection.LruCache
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import org.jetbrains.skia.Image as SkiaImage

/** 缩略图缓存条目上限：网格与预览同屏用量远到不了这个数。 */
private const val SCREENSHOT_THUMBNAIL_CACHE_ENTRIES = 64

/**
 * 截图缩略图的解码结果缓存，键是截图记录加请求尺寸（网格 480 与预览 1024 各占一项）。
 * 网格滚动、开关预览会让同一截图反复进入组合，不缓存就每次重新读盘抽样解码。
 * 这是 UI 展示用的解码缓存，不是业务数据缓存，条目可随时按原流程重建；
 * 必须有界：截图会被删除、重拍，缓存键含文件修改时间，旧键条目不会再用也不会自动失效，
 * 无界缓存会让内存随使用增长，超限按 LRU 逐出，被逐出的截图下次显示时重新读盘即可。
 */
private val screenshotThumbnailCache =
    LruCache<Pair<GameScreenshot, Int>, ImageBitmap>(SCREENSHOT_THUMBNAIL_CACHE_ENTRIES)

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
    produceState<ImageBitmap?>(screenshotThumbnailCache.get(shot to size), shot, size) {
        // 键未变且缓存里就是当前显示的位图时才直接保留；刷新会生成同名但修改时间
        // 更新的记录，组合槽可能保留，旧位图必须重新解码，不能沿用。
        if (value != null && value == screenshotThumbnailCache.get(shot to size)) return@produceState
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
        val decoded = value
        // 解码失败不缓存，保持原有“下次进入组合时重试”的行为。
        if (decoded != null) {
            screenshotThumbnailCache.put(shot to size, decoded)
        }
    }

@Composable
fun ScreenshotGallery(
    instance: GameInstance,
    actions: UiActions,
    onDismiss: () -> Unit,
) {
    val strings = LocalStrings.current
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
                    error = failure.message ?: tr("dialogs.screenshot.actionFailed")
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
        strings.t("dialogs.screenshot.title"),
        strings.t("dialogs.screenshot.subtitle", instance.name, screenshots.size, selected.size),
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
                        actions
                            .choose(
                                strings.t("dialogs.screenshot.exportSelected"),
                                directory = true,
                            ).firstOrNull() ?: return@act
                    val files = manager.export(selection, target)
                    message = strings.t("dialogs.screenshot.exported", files.size)
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
        DialogActions(strings.t("dialogs.common.done"), onDismiss, enabled = !busy)
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
                message = strings.t("dialogs.screenshot.trashed")
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
    val strings = LocalStrings.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CompactTextField(
            query,
            onQueryChange,
            Modifier.weight(1f),
            placeholder = strings.t("dialogs.screenshot.searchPlaceholder"),
        )
        SimpleDropdown(
            date,
            listOf("" to strings.t("dialogs.screenshot.allDates")) + dates.map { it to it },
            onDateChange,
            Modifier.width(150.dp),
        )
        SmallIconButton(Icons.Outlined.Refresh, strings.t("dialogs.screenshot.refresh"), onRefresh)
        SmallIconButton(
            Icons.Outlined.FolderOpen,
            strings.t("dialogs.screenshot.openFolder"),
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
    val strings = LocalStrings.current
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SecondaryButton(onSelectAll, enabled = !busy && selectable) {
            Text(strings.t("dialogs.screenshot.selectAll"))
        }
        SecondaryButton(onClear, enabled = !busy && hasSelected) {
            Text(strings.t("dialogs.screenshot.clearSelection"))
        }
        SecondaryButton(onExport, enabled = !busy && hasSelection) {
            Text(strings.t("dialogs.screenshot.exportChosen"))
        }
        SecondaryButton(onDelete, enabled = !busy && hasSelection) {
            Text(strings.t("dialogs.screenshot.moveToTrash"))
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
    val strings = LocalStrings.current
    if (!visible) return
    Modal(
        strings.t("dialogs.screenshot.removeTitle"),
        strings.t("dialogs.screenshot.removeMessage", count),
        onDismiss = onCancel,
    ) {
        Text(strings.t("dialogs.screenshot.removeHint"))
        DialogActions(
            strings.t("dialogs.screenshot.removeConfirm"),
            onConfirm,
            onCancel = onCancel,
            danger = true,
        )
    }
}
