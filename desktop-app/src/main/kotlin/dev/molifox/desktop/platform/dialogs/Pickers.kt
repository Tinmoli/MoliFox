// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责文件选择请求模型及系统选择器的统一入口。

package dev.molifox.desktop.platform.dialogs

import java.awt.Window
import java.nio.file.Path

enum class PickMode {
    File,
    Directory,
    Save,
}

data class PickRequest(
    val title: String = "",
    val mode: PickMode = PickMode.File,
    val multiple: Boolean = false,
    val extensions: List<String> = emptyList(),
    val defaultName: String = "",
    val initialDirectory: Path? = null,
)

fun interface FilePicker {
    suspend fun pick(request: PickRequest): List<Path>
}

class SystemFilePicker(
    private val owner: Window,
) : FilePicker {
    private var lastDirectory: Path? = null

    override suspend fun pick(request: PickRequest): List<Path> {
        val paths =
            NativePathPicker.pick(
                request.copy(initialDirectory = request.initialDirectory ?: lastDirectory),
                owner,
            )
        paths.firstOrNull()?.let {
            lastDirectory = if (request.mode == PickMode.Directory) it else it.parent
        }
        return paths
    }
}
