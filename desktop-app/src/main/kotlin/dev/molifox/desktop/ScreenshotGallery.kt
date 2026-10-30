// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责截图库筛选、预览和文件操作。

package dev.molifox.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BrokenImage
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.molifox.core.GameInstance
import dev.molifox.core.GameScreenshot
import dev.molifox.core.importAppearanceBackground
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.time.Instant
import java.time.ZoneId
import javax.imageio.ImageIO
import org.jetbrains.skia.Image as SkiaImage

private fun screenshotDate(shot: GameScreenshot): String =
    Instant
        .ofEpochMilli(shot.modifiedMillis)
        .atZone(ZoneId.systemDefault())
        .toLocalDate()
        .toString()

@Composable
private fun ScreenshotImageState(
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
                action()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = failure.message ?: "截图操作失败"
            } finally {
                busy = false
            }
        }
    }
    LaunchedEffect(instance.id, revision) {
        loading = true
        try {
            screenshots = manager.list(instance.id)
            selected = selected.intersect(screenshots.map { it.name }.toSet())
            error = null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            error = failure.message
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
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CompactTextField(query, { query = it }, Modifier.weight(1f), placeholder = "搜索截图文件名")
            SimpleDropdown(
                date,
                listOf("" to "全部日期") +
                    screenshots.map(::screenshotDate).distinct().map { it to it },
                { date = it },
                Modifier.width(150.dp),
            )
            SmallIconButton(Icons.Outlined.Refresh, "刷新截图", { revision++ })
            SmallIconButton(
                Icons.Outlined.FolderOpen,
                "打开截图目录",
                { actions.run { actions.controller.openFolder(instance.id, "screenshots") } },
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SecondaryButton(
                { selected = filtered.map { it.name }.toSet() },
                enabled = !busy && filtered.isNotEmpty(),
            ) {
                Text("选择当前结果")
            }
            SecondaryButton({ selected = emptySet() }, enabled = !busy && selected.isNotEmpty()) {
                Text("取消选择")
            }
            SecondaryButton(
                {
                    act {
                        val target =
                            actions.choose("导出选中截图", directory = true).firstOrNull() ?: return@act
                        val files = manager.export(selection, target)
                        message = "已导出 ${files.size} 张；重名文件自动编号。"
                    }
                },
                enabled = !busy && selection.isNotEmpty(),
            ) {
                Text("导出所选")
            }
            SecondaryButton({ deleting = true }, enabled = !busy && selection.isNotEmpty()) {
                Text("移入回收站")
            }
        }
        Box(Modifier.fillMaxWidth().height(330.dp)) {
            if (loading) {
                CircularProgressIndicator(Modifier.align(Alignment.Center).size(26.dp))
            } else if (filtered.isEmpty()) {
                Column(
                    Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        Icons.Outlined.PhotoLibrary,
                        null,
                        Modifier.size(32.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(if (screenshots.isEmpty()) "这里还没有游戏截图" else "没有匹配的截图")
                    MutedText(if (screenshots.isEmpty()) "游戏内拍摄的截图会出现在这里。" else "试试其他文件名或日期。")
                }
            } else {
                LazyVerticalGrid(
                    GridCells.Adaptive(180.dp),
                    Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(filtered, key = { it.name }) { shot ->
                        val image by ScreenshotImageState(shot, actions)
                        Column(
                            Modifier
                                .background(
                                    if (shot.name in selected) {
                                        MaterialTheme.colorScheme.primaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .25f)
                                    },
                                ).padding(6.dp),
                        ) {
                            Box(
                                Modifier.fillMaxWidth().height(106.dp).clickable { preview = shot },
                                contentAlignment = Alignment.Center,
                            ) {
                                if (image != null) {
                                    Image(
                                        requireNotNull(image),
                                        "预览截图 ${shot.name}",
                                        Modifier.fillMaxSize(),
                                    )
                                } else {
                                    Icon(Icons.Outlined.BrokenImage, "截图无法预览")
                                }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(
                                    shot.name in selected,
                                    { checked ->
                                        selected =
                                            if (checked) {
                                                selected + shot.name
                                            } else {
                                                selected - shot.name
                                            }
                                    },
                                    enabled = !busy,
                                )
                                Text(
                                    shot.name,
                                    Modifier.weight(1f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    fontSize = 11.sp,
                                )
                            }
                            MutedText(
                                "${screenshotDate(shot)} · " +
                                    if (shot.previewable) {
                                        "${shot.width}×${shot.height}"
                                    } else {
                                        "无法预览，可导出原文件"
                                    },
                            )
                        }
                    }
                }
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
        message?.let { MutedText(it) }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        DialogActions("完成", onDismiss, enabled = !busy)
    }
    if (deleting) {
        Modal("移除所选截图", "将 ${selection.size} 张图片移入回收站。", onDismiss = { deleting = false }) {
            Text("原截图会从当前实例的截图目录移除，可以在系统回收站或启动器恢复目录查找。")
            DialogActions(
                "确认移除截图",
                {
                    deleting = false
                    act {
                        manager.remove(selection)
                        selected = emptySet()
                        revision++
                        message = "截图已移入回收站"
                    }
                },
                onCancel = { deleting = false },
                danger = true,
            )
        }
    }
    preview?.let { shot -> ScreenshotPreviewDialog(shot, actions) { preview = null } }
}

private class ScreenshotTransferable(
    private val image: java.awt.Image,
) : Transferable {
    override fun getTransferDataFlavors() = arrayOf(DataFlavor.imageFlavor)

    override fun isDataFlavorSupported(flavor: DataFlavor) = flavor == DataFlavor.imageFlavor

    override fun getTransferData(flavor: DataFlavor): Any =
        if (isDataFlavorSupported(flavor)) image else throw UnsupportedFlavorException(flavor)
}

@Composable
private fun ScreenshotPreviewDialog(
    shot: GameScreenshot,
    actions: UiActions,
    onDismiss: () -> Unit,
) {
    val image by ScreenshotImageState(shot, actions, 1024)
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    fun act(action: suspend () -> Unit) {
        scope.launch {
            busy = true
            try {
                action()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = failure.message
            } finally {
                busy = false
            }
        }
    }
    Modal("截图预览", shot.name, width = 860.dp, onDismiss = { if (!busy) onDismiss() }) {
        Box(Modifier.fillMaxWidth().height(360.dp), contentAlignment = Alignment.Center) {
            if (image != null) {
                Image(requireNotNull(image), shot.name, Modifier.fillMaxSize())
            } else {
                Text("此文件无法预览，可以关闭后导出或移入回收站。")
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SecondaryButton(
                {
                    act {
                        val file = actions.controller.screenshots.resolve(shot)
                        val full =
                            withContext(Dispatchers.IO) {
                                ImageIO.read(file.toFile()) ?: error("截图无法解码")
                            }
                        Toolkit
                            .getDefaultToolkit()
                            .systemClipboard
                            .setContents(ScreenshotTransferable(full), null)
                        message = "图片已复制"
                    }
                },
                enabled = !busy && image != null,
            ) {
                Text("复制图片")
            }
            SecondaryButton(
                {
                    act {
                        val source = actions.controller.screenshots.resolve(shot)
                        val imported =
                            withContext(Dispatchers.IO) {
                                importAppearanceBackground(
                                    actions.controller.store.dataDirectory,
                                    source,
                                )
                            }
                        actions.controller.saveSettings(
                            actions.controller.store.data.settings.copy(
                                backgroundPath = imported.toString(),
                                backgroundRevision = System.currentTimeMillis(),
                            ),
                        )
                        message = "已设为启动器背景"
                    }
                },
                enabled = !busy && image != null,
            ) {
                Text("设为背景")
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        message?.let { MutedText(it) }
        DialogActions("关闭预览", onDismiss, enabled = !busy)
    }
}
