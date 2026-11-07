// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 把内容区的系统文件拖放事件转换为路径回调。

package dev.molifox.desktop

import androidx.compose.foundation.border
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.awtTransferable
import androidx.compose.ui.unit.dp
import java.awt.datatransfer.DataFlavor
import java.io.File
import java.nio.file.Path

/** 一次拖放最多接受的文件数：避免误拖整个目录。 */
private const val DROP_FILE_LIMIT = 20

/**
 * 内容区的拖放接收器：把传入的文件/文件夹路径交给 [onFiles]，悬停时给内容区描边。
 *
 * 只接受系统文件列表（Finder / 资源管理器的拖放都走这一种）；数量上限 20，避免误拖整个目录。
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun Modifier.contentDropTarget(
    enabled: Boolean = true,
    hint: String = "",
    onError: (String) -> Unit = {},
    onFiles: (List<Path>) -> Unit,
): Modifier {
    var hovered by remember { mutableStateOf(false) }
    val receiver by rememberUpdatedState(onFiles)
    val failure by rememberUpdatedState(onError)
    // 默认提示文案随语言变化，调用方传入的 hint 优先。
    val effectiveHint = hint.ifEmpty { tr("pages.drop.hint") }
    val target =
        remember(enabled, effectiveHint) {
            object : DragAndDropTarget {
                override fun onEntered(event: DragAndDropEvent) {
                    if (enabled) hovered = true
                }

                override fun onExited(event: DragAndDropEvent) {
                    hovered = false
                }

                override fun onEnded(event: DragAndDropEvent) {
                    hovered = false
                }

                override fun onDrop(event: DragAndDropEvent): Boolean {
                    hovered = false
                    if (!enabled) return false
                    return runCatching {
                        val raw =
                            event.awtTransferable.getTransferData(DataFlavor.javaFileListFlavor)
                                as? List<*>
                        val files = raw.orEmpty().filterIsInstance<File>().map { it.toPath() }
                        require(files.isNotEmpty()) { effectiveHint }
                        require(files.size <= DROP_FILE_LIMIT) {
                            tr("pages.drop.tooMany", DROP_FILE_LIMIT)
                        }
                        receiver(files)
                        true
                    }.getOrElse { error ->
                        failure(error.message ?: tr("pages.drop.unreadable"))
                        false
                    }
                }
            }
        }
    val shape = RoundedCornerShape(Metrics.cardRadius)
    return this
        .then(
            if (hovered && enabled) {
                Modifier.border(2.dp, MaterialTheme.colorScheme.primary, shape)
            } else {
                Modifier
            },
        ).dragAndDropTarget(
            shouldStartDragAndDrop = { event ->
                enabled &&
                    event.awtTransferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor)
            },
            target = target,
        )
}
