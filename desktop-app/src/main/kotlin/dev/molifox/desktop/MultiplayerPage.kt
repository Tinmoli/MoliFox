// 将主导航的联机入口连接到应用持有的后端；离开页面不会停止联机。
package dev.molifox.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import dev.molifox.desktop.multiplayer.MultiplayerScreen
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

@Composable
fun MultiplayerPage(actions: UiActions) {
    val strings = LocalStrings.current
    val backend = remember(actions.multiplayer) { actions.multiplayer.backend }
    MultiplayerScreen(
        backend = backend,
        onCopy = { address ->
            actions.run {
                Toolkit.getDefaultToolkit().systemClipboard.setContents(
                    StringSelection(address),
                    null,
                )
                actions.controller.store.notify(strings.t("dialogs.mp.copied"))
            }
        },
        // 文件选择交给应用统一的 actions.choose：界面只拿路径字符串，不自己调 AWT 选择器。
        chooseArchive = {
            actions
                .choose(
                    title = strings.t("dialogs.mp.chooseArchive"),
                    extensions = listOf("zip"),
                    multiple = false,
                ).firstOrNull()
                ?.toString()
        },
    )
}
