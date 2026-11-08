// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 提供 Compose 层的本地化文案入口；语言切换时由根节点换掉 LocalStrings 触发重组。

package dev.molifox.desktop

import androidx.compose.runtime.staticCompositionLocalOf
import dev.molifox.core.i18n.I18n
import dev.molifox.core.i18n.Strings

/**
 * 当前语言文案。
 *
 * 默认值取 [I18n.current]，便于预览、单独渲染某个 Composable 的测试场景；正式界面在 LauncherApplication 里统一提供。
 */
internal val LocalStrings = staticCompositionLocalOf<Strings> { I18n.current }

/** 非 Composable 的界面辅助函数（UiActions 里的提示、错误等）使用全局当前语言。 */
internal fun tr(
    key: String,
    vararg args: Any?,
): String = I18n.t(key, *args)
