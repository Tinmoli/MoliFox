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
import java.awt.datatransfer.Transferable
import java.io.File
import java.nio.file.Path

/** 一次拖放最多接受的文件数：避免误拖整个目录。 */
private const val DROP_FILE_LIMIT = 20

/**
 * 内容区的拖放接收器：把传入的文件/文件夹路径交给 [onFiles]，悬停时给内容区描边。
 *
 * 只接受系统文件列表（Finder / 资源管理器的拖放都走这一种）；数量上限 20，避免误拖整个目录。
 */
@Composable
internal fun Modifier.contentDropTarget(
    enabled: Boolean = true,
    hint: String = "",
    onError: (String) -> Unit = {},
    onFiles: (List<Path>) -> Unit,
): Modifier {
    val effectiveHint = hint.ifEmpty { tr("pages.drop.hint") }
    return desktopFileDropTarget(enabled, { tr("pages.drop.unreadable") }, onError) { raw ->
        val files = raw.orEmpty().filterIsInstance<File>().map { it.toPath() }
        require(files.isNotEmpty()) { effectiveHint }
        require(files.size <= DROP_FILE_LIMIT) { tr("pages.drop.tooMany", DROP_FILE_LIMIT) }
        onFiles(files)
    }
}

/** 共享系统文件列表接收、悬停描边及异常反馈。 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun Modifier.desktopFileDropTarget(
    enabled: Boolean,
    unreadable: () -> String,
    onError: (String) -> Unit,
    onDropFiles: (List<*>?) -> Unit,
): Modifier {
    var hovered by remember(enabled) { mutableStateOf(false) }
    val receiver by rememberUpdatedState(onDropFiles)
    val failure by rememberUpdatedState(onError)
    val fallback by rememberUpdatedState(unreadable)
    val target =
        remember(enabled) {
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
                    return receiveDesktopFileDrop(event.awtTransferable, fallback, failure, receiver)
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

internal fun receiveDesktopFileDrop(
    transferable: Transferable,
    unreadable: () -> String,
    onError: (String) -> Unit,
    onFiles: (List<*>?) -> Unit,
): Boolean =
    runCatching {
        onFiles(transferable.getTransferData(DataFlavor.javaFileListFlavor) as? List<*>)
        true
    }.getOrElse { error ->
        onError(error.message ?: unreadable())
        false
    }
