// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 在启用语言跟随时更新实例的游戏语言，保留 options.txt 中其他选项。

package dev.molifox.core

import dev.molifox.core.i18n.AppLanguage
import dev.molifox.core.i18n.I18n
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.isRegularFile
import kotlin.io.path.isSymbolicLink
import kotlin.io.path.readText
import kotlin.io.path.writeText

private const val MAX_LANGUAGE_OPTIONS_BYTES = 2L * 1024 * 1024
private const val FALLBACK_GAME_LANGUAGE = "zh_cn"

/** 启动器界面语言 → Minecraft 的 lang 代码；`auto` 先按系统语言解析，zh-HK / zh-TW 都落到 zh_tw。 */
internal fun gameLanguageCode(settingValue: String): String =
    AppLanguage.fromSetting(AppLanguage.resolveTag(settingValue)).gameLanguage.ifBlank { FALLBACK_GAME_LANGUAGE }

/** 1.10 及更早版本要求区域大写：`zh_cn` → `zh_CN`；语言码本身已是小写。 */
internal fun legacyGameLanguageCode(settingValue: String): String {
    val code = gameLanguageCode(settingValue)
    return code.substringBefore('_') + "_" + code.substringAfter('_').uppercase(java.util.Locale.ROOT)
}

internal fun applyGameLanguage(
    store: LauncherStore,
    instance: GameInstance,
) {
    val settings = store.data.settings
    if (!settings.gameLanguageFollowsLauncher) return
    val file = store.gamePath(instance).resolve("options.txt")
    require(
        !file.isSymbolicLink() &&
            (
                !file.exists(NOFOLLOW_LINKS) ||
                    (
                        file.isRegularFile(NOFOLLOW_LINKS) &&
                            file.fileSize() <= MAX_LANGUAGE_OPTIONS_BYTES
                    )
            ),
    ) {
        I18n.t("miscA1.gameLanguage.optionsUnwritable", file)
    }
    val old = if (file.exists()) file.readText() else ""
    val newline = if ("\r\n" in old) "\r\n" else "\n"
    val legacy =
        Regex("1\\.(\\d+)(?:\\..*)?")
            .matchEntire(instance.gameVersion)
            ?.groupValues
            ?.get(1)
            ?.toIntOrNull()
            ?.let { it < 11 } == true
    val code =
        if (legacy) legacyGameLanguageCode(settings.language) else gameLanguageCode(settings.language)
    val lines = old.lineSequence().filterNot { it.startsWith("lang:") }.toMutableList()
    while (lines.lastOrNull() == "") lines.removeAt(lines.lastIndex)
    val updated = (lines + "lang:$code").joinToString(newline, postfix = newline)
    if (updated == old) return
    writeLanguageOptions(file, updated)
}

private fun writeLanguageOptions(
    file: Path,
    updated: String,
) {
    file.parent.createDirectories()
    val temporary = Files.createTempFile(file.parent, ".molifox-language-", ".tmp")
    try {
        temporary.writeText(updated)
        try {
            Files.move(temporary, file, REPLACE_EXISTING, ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary, file, REPLACE_EXISTING)
        }
    } finally {
        temporary.deleteIfExists()
    }
}
