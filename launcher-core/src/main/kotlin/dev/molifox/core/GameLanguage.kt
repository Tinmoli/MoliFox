// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 在启用语言跟随时更新实例的游戏语言，保留 options.txt 中其他选项。

package dev.molifox.core

import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempFile
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.isRegularFile
import kotlin.io.path.isSymbolicLink
import kotlin.io.path.readText
import kotlin.io.path.writeText

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
                    (file.isRegularFile(NOFOLLOW_LINKS) && file.fileSize() <= 2L * 1024 * 1024)
            ),
    ) {
        "游戏语言设置文件不可写或过大：$file"
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
        settings.language.replace('-', '_').let {
            if (legacy) it else it.lowercase(java.util.Locale.ROOT)
        }
    val lines = old.lineSequence().filterNot { it.startsWith("lang:") }.toMutableList()
    while (lines.lastOrNull() == "") lines.removeAt(lines.lastIndex)
    val updated = (lines + "lang:$code").joinToString(newline, postfix = newline)
    if (updated == old) return
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
