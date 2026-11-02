// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责截图库的网格、空状态、状态条与预览弹窗。

package dev.molifox.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BrokenImage
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.molifox.core.GameScreenshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import javax.imageio.ImageIO

@Composable
internal fun ScreenshotGrid(
    loading: Boolean,
    screenshots: List<GameScreenshot>,
    filtered: List<GameScreenshot>,
    selected: Set<String>,
    busy: Boolean,
    actions: UiActions,
    onPreview: (GameScreenshot) -> Unit,
    onSelectedChange: (Set<String>) -> Unit,
) {
    Box(Modifier.fillMaxWidth().height(330.dp)) {
        if (loading) {
            CircularProgressIndicator(Modifier.align(Alignment.Center).size(26.dp))
        } else if (filtered.isEmpty()) {
            ScreenshotEmptyState(empty = screenshots.isEmpty())
        } else {
            LazyVerticalGrid(
                GridCells.Adaptive(180.dp),
                Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(filtered, key = { it.name }) { shot ->
                    ScreenshotTile(
                        shot = shot,
                        actions = actions,
                        selected = shot.name in selected,
                        enabled = !busy,
                        onPreview = { onPreview(shot) },
                        onToggle = { checked ->
                            onSelectedChange(
                                if (checked) selected + shot.name else selected - shot.name,
                            )
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun BoxScope.ScreenshotEmptyState(empty: Boolean) {
    val strings = LocalStrings.current
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
        Text(if (empty) strings.t("dialogs.screenshot.empty") else strings.t("dialogs.screenshot.noMatch"))
        MutedText(if (empty) strings.t("dialogs.screenshot.emptyHint") else strings.t("dialogs.screenshot.noMatchHint"))
    }
}

@Composable
private fun ScreenshotTile(
    shot: GameScreenshot,
    actions: UiActions,
    selected: Boolean,
    enabled: Boolean,
    onPreview: () -> Unit,
    onToggle: (Boolean) -> Unit,
) {
    val strings = LocalStrings.current
    val image by ScreenshotImageState(shot, actions)
    Column(
        Modifier
            .background(
                if (selected) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .25f)
                },
            ).padding(6.dp),
    ) {
        Box(
            Modifier.fillMaxWidth().height(106.dp).clickable(onClick = onPreview),
            contentAlignment = Alignment.Center,
        ) {
            if (image != null) {
                Image(
                    requireNotNull(image),
                    strings.t("dialogs.screenshot.previewLabel", shot.name),
                    Modifier.fillMaxSize(),
                )
            } else {
                Icon(Icons.Outlined.BrokenImage, strings.t("dialogs.screenshot.previewFailed"))
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                selected,
                onToggle,
                enabled = enabled,
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
                    strings.t("dialogs.screenshot.previewFailedHint")
                },
        )
    }
}

@Composable
internal fun ScreenshotStatus(
    error: String?,
    message: String?,
    busy: Boolean,
) {
    error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
    message?.let { MutedText(it) }
    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
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
internal fun ScreenshotPreviewDialog(
    shot: GameScreenshot?,
    actions: UiActions,
    onDismiss: () -> Unit,
) {
    val strings = LocalStrings.current
    if (shot == null) return
    val image by ScreenshotImageState(shot, actions, 1024)
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    fun act(action: suspend () -> Unit) {
        scope.launch {
            busy = true
            try {
                runScreenshotAction(action) { failure -> error = failure.message }
            } finally {
                busy = false
            }
        }
    }
    Modal(
        strings.t("dialogs.screenshot.previewTitle"),
        shot.name,
        width = 860.dp,
        onDismiss = { if (!busy) onDismiss() },
    ) {
        Box(Modifier.fillMaxWidth().height(360.dp), contentAlignment = Alignment.Center) {
            if (image != null) {
                Image(requireNotNull(image), shot.name, Modifier.fillMaxSize())
            } else {
                Text(strings.t("dialogs.screenshot.previewBody"))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SecondaryButton(
                {
                    act {
                        val file = actions.controller.screenshots.resolve(shot)
                        val full =
                            withContext(Dispatchers.IO) {
                                ImageIO.read(file.toFile()) ?: error(strings.t("dialogs.screenshot.decodeFailed"))
                            }
                        Toolkit
                            .getDefaultToolkit()
                            .systemClipboard
                            .setContents(ScreenshotTransferable(full), null)
                        message = strings.t("dialogs.screenshot.copied")
                    }
                },
                enabled = !busy && image != null,
            ) {
                Text(strings.t("dialogs.screenshot.copy"))
            }
            SecondaryButton(
                {
                    act {
                        val source = actions.controller.screenshots.resolve(shot)
                        val imported =
                            withContext(Dispatchers.IO) {
                                actions.controller.appearance.importBackground(source)
                            }
                        actions.controller.saveSettings(
                            actions.controller.store.data.settings.copy(
                                backgroundPath = imported.toString(),
                                backgroundRevision = System.currentTimeMillis(),
                            ),
                        )
                        message = strings.t("dialogs.screenshot.setBackgroundDone")
                    }
                },
                enabled = !busy && image != null,
            ) {
                Text(strings.t("dialogs.screenshot.setBackground"))
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        message?.let { MutedText(it) }
        DialogActions(strings.t("dialogs.screenshot.closePreview"), onDismiss, enabled = !busy)
    }
}
