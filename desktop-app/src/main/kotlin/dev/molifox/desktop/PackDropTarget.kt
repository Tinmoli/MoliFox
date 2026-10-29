// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责接收窗口上的整合包文件拖放，并复用现有预览与安装入口。

package dev.molifox.desktop

import androidx.compose.foundation.border
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.awtTransferable
import androidx.compose.ui.unit.dp
import java.awt.datatransfer.DataFlavor
import java.io.File

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun Modifier.packDropTarget(actions: UiActions): Modifier {
    var hovered by remember { mutableStateOf(false) }
    val target =
        remember(actions) {
            object : DragAndDropTarget {
                override fun onEntered(event: DragAndDropEvent) {
                    hovered = true
                }

                override fun onExited(event: DragAndDropEvent) {
                    hovered = false
                }

                override fun onEnded(event: DragAndDropEvent) {
                    hovered = false
                }

                override fun onDrop(event: DragAndDropEvent): Boolean {
                    hovered = false
                    return runCatching {
                        val files =
                            event.awtTransferable.getTransferData(DataFlavor.javaFileListFlavor)
                                as? List<*>
                        require(files?.size == 1 && files.single() is File) { "请一次拖入一个整合包文件" }
                        val file = files.single() as File
                        require(
                            file.extension.lowercase(java.util.Locale.ROOT) in
                                setOf("mrpack", "zip")
                        ) {
                            "请拖入 .mrpack 或整合包 .zip 文件"
                        }
                        actions.previewPackFile(file.toPath())
                        true
                    }
                        .getOrElse {
                            actions.controller.store.notify(it.message ?: "无法读取拖入的文件")
                            false
                        }
                }
            }
        }
    return this.then(
            if (hovered)
                Modifier.border(
                    2.dp,
                    MaterialTheme.colorScheme.primary,
                    RoundedCornerShape(Metrics.cardRadius),
                )
            else Modifier
        )
        .dragAndDropTarget(
            shouldStartDragAndDrop = { event ->
                event.awtTransferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor)
            },
            target = target,
        )
}
