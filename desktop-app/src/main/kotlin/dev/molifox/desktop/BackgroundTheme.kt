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
import dev.molifox.core.effectiveBackground
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.logging.Logger

/** hex 是图片种子，不是最终 primary。未取得图片时由主题边界使用默认种子。 */
internal data class BackgroundAccentState(val hex: String? = null, val loading: Boolean = false)
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
): State<BackgroundAccentState> {
    val enabled = settings.themeColorSource == "background"
    return key(enabled, settings.effectiveBackground(dark), settings.backgroundRevision, dark, assets) {
        produceState(BackgroundAccentState(loading = enabled)) {
            val hex = if (enabled && assets != null) withContext(Dispatchers.IO) {
                try {
                    assets.backgroundAccent(settings, dark)
                } catch (failure: IOException) {
                    Logger.getLogger("MoliFox.appearance").warning("Image seed fallback: ${failure.message}")
                    null
                } catch (failure: IllegalArgumentException) {
                    Logger.getLogger("MoliFox.appearance").warning("Image seed fallback: ${failure.message}")
                    null
                }
            } else null
            value = BackgroundAccentState(hex)
        }
    }
}
