// 将主导航的联机入口连接到应用持有的后端；离开页面不会停止联机。
package dev.molifox.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import dev.molifox.desktop.multiplayer.MultiplayerScreen
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

@Composable
fun MultiplayerPage(actions: UiActions) {
    val backend = remember(actions.multiplayer) { actions.multiplayer.backend }
    MultiplayerScreen(
        backend = backend,
        onCopy = { address ->
            actions.run {
                Toolkit.getDefaultToolkit().systemClipboard.setContents(
                    StringSelection(address),
                    null,
                )
                actions.controller.store.notify("已复制到剪贴板")
            }
        },
    )
}
