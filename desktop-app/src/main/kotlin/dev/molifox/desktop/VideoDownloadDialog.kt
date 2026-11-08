// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 选择视频背景时先确认下载运行库；下载、校验、解压、准备播放都在这里完成。
package dev.molifox.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import dev.molifox.core.LauncherController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Path

private enum class VideoDialogPhase { CONFIRM, WORKING, ERROR }

@Composable
internal fun VideoDownloadDialog(
    controller: LauncherController,
    source: Path,
    dark: Boolean,
    onDismiss: () -> Unit,
    onVideoReady: (Path) -> Unit,
) {
    val strings = LocalStrings.current
    val asset = remember { mpvRuntimeAsset() }
    var phase by remember { mutableStateOf(VideoDialogPhase.CONFIRM) }
    var progress by remember { mutableFloatStateOf(0f) }
    var failure by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val working = phase == VideoDialogPhase.WORKING
    AlertDialog(
        properties = DialogProperties(dismissOnClickOutside = !working, dismissOnBackPress = !working),
        onDismissRequest = { if (!working) onDismiss() },
        title = { Text(strings.t("settings.appearance.background.videoTitle")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (asset == null) {
                    Text(strings.t("settings.appearance.background.videoUnsupported"))
                } else if (phase == VideoDialogPhase.ERROR) {
                    Text(strings.t("settings.appearance.background.videoFailed", failure))
                } else if (phase == VideoDialogPhase.WORKING) {
                    Text(strings.t("settings.appearance.background.videoWorking", (progress * 100).toInt()))
                    LinearProgressIndicator(Modifier.fillMaxWidth().height(3.dp))
                } else {
                    Text(
                        strings.t(
                            "settings.appearance.background.videoMessage",
                            megabytesText(asset.size),
                        ),
                    )
                }
            }
        },
        confirmButton = {
            if (asset != null) {
                MoliTextButton(
                    onClick = {
                        if (working) return@MoliTextButton
                        phase = VideoDialogPhase.WORKING
                        progress = 0f
                        scope.launch {
                            runCatching {
                                VideoRuntime.ensure(controller) { progress = it }
                                val imported =
                                    withContext(Dispatchers.IO) {
                                        controller.appearance.importModeVideo(source, dark)
                                    }
                                // 导入后的外观应用与关弹窗放进同一次兜底：失败显示在弹窗里，
                                // 不让异常从组合协程冒出（否则 bootstrap 会弹「遇到问题」并退出整个进程）。
                                onVideoReady(imported)
                            }.onFailure { error ->
                                failure = error.message.orEmpty()
                                phase = VideoDialogPhase.ERROR
                            }
                        }
                    },
                    enabled = !working,
                ) {
                    Text(
                        strings.t(
                            if (phase == VideoDialogPhase.ERROR) {
                                "settings.appearance.background.videoRetry"
                            } else {
                                "settings.appearance.background.videoDownload"
                            },
                        ),
                    )
                }
            }
        },
        dismissButton = {
            MoliTextButton(onClick = onDismiss, enabled = !working) {
                Text(strings.t("settings.appearance.background.videoCancel"))
            }
        },
    )
}
