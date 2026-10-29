// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责内置和用户字体的加载。

package dev.molifox.desktop

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.text.platform.asComposeFontFamily
import java.awt.Font as AwtFont
import java.io.File

@OptIn(
    androidx.compose.ui.ExperimentalComposeUiApi::class,
    androidx.compose.ui.text.ExperimentalTextApi::class,
)
fun launcherFontFamily(choice: String): FontFamily = runCatching {
    when {
        choice.isBlank() -> FontFamily.Default
        choice.startsWith("file:") -> {
            val file = File(choice.removePrefix("file:"))
            require(file.isFile && file.length() <= 32L * 1024 * 1024)
            FontFamily(Font(choice, file.readBytes()))
        }
        else -> AwtFont(choice.removePrefix("system:"), AwtFont.PLAIN, 14).asComposeFontFamily()
    }
}
    .getOrDefault(FontFamily.Default)
