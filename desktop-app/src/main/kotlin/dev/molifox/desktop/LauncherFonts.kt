// 解析系统字体的规范族名并加载实际字形，缺字时明确提示系统回退。
package dev.molifox.desktop

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.text.platform.Typeface
import dev.molifox.core.i18n.I18n
import org.jetbrains.skia.FontMgr
import org.jetbrains.skia.FontStyle
import java.io.File
import java.util.Locale
import java.awt.Font as AwtFont

private const val MAX_FONT_FILE_SIZE_BYTES = 32L * 1024 * 1024
private const val SYSTEM_FONT_LOOKUP_SIZE = 14
private const val CHINESE_FONT_SAMPLE = "茉狐中文字体"

@OptIn(
    androidx.compose.ui.ExperimentalComposeUiApi::class,
    androidx.compose.ui.text.ExperimentalTextApi::class,
)
fun launcherFontFamily(choice: String): FontFamily =
    runCatching {
        when {
            choice.isBlank() -> FontFamily.Default
            choice.startsWith("file:") -> {
                val file = selectedFontFile(choice)
                FontFamily(Font(choice, file.readBytes()))
            }
            else -> {
                val name = canonicalSystemFontName(choice.removePrefix("system:"))
                val native =
                    requireNotNull(FontMgr.default.matchFamilyStyle(name, FontStyle.NORMAL)) {
                        I18n.t("settings.font.loadFailed", name)
                    }
                FontFamily(Typeface(native, name))
            }
        }
    }.getOrElse {
        System.err.println(I18n.t("logs.font.loadFailed", it.stackTraceToString()))
        FontFamily.Default
    }

internal fun canonicalSystemFontName(name: String): String =
    AwtFont(name, AwtFont.PLAIN, SYSTEM_FONT_LOOKUP_SIZE).getFamily(Locale.ENGLISH)

internal fun fontSupportMessage(choice: String): String? {
    if (choice.isBlank()) return null
    return runCatching {
        val font =
            if (choice.startsWith("file:")) {
                selectedFontFile(choice).inputStream().use {
                    AwtFont.createFont(AwtFont.TRUETYPE_FONT, it)
                }
            } else {
                AwtFont(
                    canonicalSystemFontName(choice.removePrefix("system:")),
                    AwtFont.PLAIN,
                    SYSTEM_FONT_LOOKUP_SIZE,
                )
            }
        if (font.canDisplayUpTo(CHINESE_FONT_SAMPLE) == -1) {
            null
        } else {
            I18n.t("settings.font.support.missing")
        }
    }.getOrElse {
        System.err.println(I18n.t("logs.font.supportCheckFailed", it.stackTraceToString()))
        I18n.t("settings.font.support.unreadable")
    }
}

private fun selectedFontFile(choice: String): File =
    File(choice.removePrefix("file:")).also {
        require(it.isFile && it.length() <= MAX_FONT_FILE_SIZE_BYTES) {
            I18n.t("settings.font.fileInvalid")
        }
    }
