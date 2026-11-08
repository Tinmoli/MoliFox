// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

package dev.molifox.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.staticCompositionLocalOf
import dev.molifox.core.AppearanceAssetService
import dev.molifox.core.LauncherSettings
import dev.molifox.core.backgroundAccent
import dev.molifox.core.effectiveBackground
import dev.molifox.core.effectiveVideo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.logging.Logger

/** 主题边界的稳定入口；该局部值是整个界面共享的配色状态，刻意只在根节点提供一次。 */
@Suppress("CompositionLocalAllowlist")
internal val LocalBackgroundAccent = staticCompositionLocalOf { BackgroundAccentState() }

/**
 * 只在图片来源、文件修订或浅深色图片切换时重新读取。
 * produceState 的任务随组合销毁而取消；旧图片结果不会覆盖新来源。
 * 解码、量化和排序全部在 IO 工作线程，完成后才发布可观察状态。
 */
@Composable
internal fun rememberBackgroundAccent(
    settings: LauncherSettings,
    dark: Boolean,
    assets: AppearanceAssetService?,
    videoAccent: String?,
): State<BackgroundAccentState> {
    val enabled = settings.themeColorSource == "background"
    val videoPath = settings.effectiveVideo(dark)
    return key(
        enabled,
        settings.effectiveBackground(dark),
        settings.backgroundRevision,
        dark,
        assets,
        videoPath,
        videoAccent,
    ) {
        produceState(BackgroundAccentState(loading = enabled)) {
            val imageHex =
                if (enabled && assets != null) {
                    withContext(Dispatchers.IO) {
                        try {
                            assets.backgroundAccent(settings, dark)
                        } catch (failure: IOException) {
                            Logger.getLogger("MoliFox.appearance").warning("Image seed fallback: ${failure.message}")
                            null
                        } catch (failure: IllegalArgumentException) {
                            Logger.getLogger("MoliFox.appearance").warning("Image seed fallback: ${failure.message}")
                            null
                        }
                    }
                } else {
                    null
                }
            // 视频没有可解码的图片字节；首帧由 VideoBackdrop 解码后回传，取到前先沿用图片种子。
            val hex = videoAccent?.takeIf { videoPath != null } ?: imageHex
            value = BackgroundAccentState(hex)
        }
    }
}
