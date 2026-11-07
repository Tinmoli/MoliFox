// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 背景来源设置行：保持单行排版，用下拉选择图片/动态图或视频；视频运行库按需下载。
package dev.molifox.desktop

import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.molifox.core.effectiveBackgroundKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Path

private val IMAGE_EXTENSIONS = listOf("png", "jpg", "jpeg", "gif", "webp", "apng")
private val VIDEO_EXTENSIONS = listOf("mp4", "mov", "m4v", "webm", "mkv")
private const val BACKGROUND_SOURCE_WIDTH_DP = 170

/** 单个明暗模式的背景来源行：下拉选图片/动态图或视频，选中后再打开文件选择。 */
@Composable
internal fun ModeBackgroundRow(
    actions: UiActions,
    apply: AppearanceChange,
    dark: Boolean,
) {
    val strings = LocalStrings.current
    var pendingVideo by remember { mutableStateOf<Path?>(null) }
    val settings = actions.controller.store.data.settings
    val videoActive = settings.effectiveBackgroundKind(dark) == "video"
    val title = strings.t(if (dark) "settings.appearance.background.dark" else "settings.appearance.background.light")
    SettingRow(title) {
        SimpleDropdown(
            value = if (videoActive) "video" else "image",
            values =
                listOf(
                    "image" to strings.t("settings.appearance.background.source.image"),
                    "video" to strings.t("settings.appearance.background.source.video"),
                ),
            onSelect = { selected ->
                actions.run {
                    val video = selected == "video"
                    val source =
                        actions
                            .choose(
                                strings.t(
                                    if (video) {
                                        "settings.appearance.background.chooseVideo"
                                    } else {
                                        "settings.appearance.background.choose"
                                    },
                                ),
                                extensions = if (video) VIDEO_EXTENSIONS else IMAGE_EXTENSIONS,
                            ).firstOrNull()
                    if (source == null) return@run
                    if (video && !VideoRuntime.isInstalled(actions.controller.dataDirectory)) {
                        pendingVideo = source
                        return@run
                    }
                    val imported =
                        withContext(Dispatchers.IO) {
                            if (video) {
                                actions.controller.appearance.importModeVideo(source, dark)
                            } else {
                                actions.controller.appearance.importModeBackground(source, dark)
                            }
                        }
                    applySide(apply, dark, imported, if (video) "video" else "image")
                }
            },
            modifier = Modifier.width(BACKGROUND_SOURCE_WIDTH_DP.dp),
            label = title,
        )
    }
    val video = pendingVideo
    if (video != null) {
        VideoDownloadDialog(
            controller = actions.controller,
            source = video,
            dark = dark,
            onDismiss = { pendingVideo = null },
            onVideoReady = { imported ->
                pendingVideo = null
                applySide(apply, dark, imported, "video")
            },
        )
    }
}

private fun applySide(
    apply: AppearanceChange,
    dark: Boolean,
    imported: Path,
    kind: String,
) {
    apply {
        if (kind == "video") {
            if (dark) {
                it.copy(
                    darkVideoPath = imported.toString(),
                    darkBackgroundKind = "video",
                    backgroundRevision = System.currentTimeMillis(),
                )
            } else {
                it.copy(
                    lightVideoPath = imported.toString(),
                    lightBackgroundKind = "video",
                    backgroundRevision = System.currentTimeMillis(),
                )
            }
        } else if (dark) {
            it.copy(
                darkBackgroundPath = imported.toString(),
                darkBackgroundKind = "image",
                backgroundRevision = System.currentTimeMillis(),
            )
        } else {
            it.copy(
                lightBackgroundPath = imported.toString(),
                lightBackgroundKind = "image",
                backgroundRevision = System.currentTimeMillis(),
            )
        }
    }
}
