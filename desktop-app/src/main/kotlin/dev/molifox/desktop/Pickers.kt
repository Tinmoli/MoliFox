// 负责文件选择请求模型及系统选择器的统一入口。

package dev.molifox.desktop

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

object PathPicker {
    var owner: Window? = null
    private var lastDirectory: Path? = null

    suspend fun pick(request: PickRequest): List<Path> {
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
