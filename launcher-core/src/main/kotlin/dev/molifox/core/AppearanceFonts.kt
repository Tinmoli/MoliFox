// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

package dev.molifox.core

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name

/** 删除外观字体；只允许删除本项目字体目录中的受管文件。 */
fun removeAppearanceFont(
    dataDirectory: Path,
    font: LauncherFont,
) {
    val directory = dataDirectory.resolve("appearance/fonts").toAbsolutePath().normalize()
    val file = Path.of(font.path).toAbsolutePath().normalize()
    // A manually edited record never authorizes removal of a font outside our storage.
    if (file.parent == directory && file.name.matches(Regex("[a-f0-9]{24}\\.font"))) {
        Files.deleteIfExists(file)
    }
}
