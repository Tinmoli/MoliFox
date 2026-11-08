// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责存档名称的合法性校验与避让命名。

package dev.molifox.core

import dev.molifox.core.i18n.I18n
import java.nio.file.LinkOption
import java.nio.file.Path
import kotlin.io.path.exists

/** 存档名单层名称的长度上限。 */
internal const val MAX_SAVE_NAME_LENGTH = 80

/** 同名避让时最多尝试的序号；超过就要求用户改名。 */
private const val MAX_SAVE_NAME_ATTEMPTS = 999

/** 安全化后保留的字符数，给时间戳等后缀留出文件名长度。 */
private const val SAVE_LABEL_MAX_LENGTH = 60

/** 目标已有同名存档时按序号避让（`World` → `World 2`），从不覆盖。 */
internal fun uniqueSaveName(
    directory: Path,
    base: String,
    isZip: Boolean,
): String {
    fun withExtension(name: String) = if (isZip) "$name.zip" else name

    fun taken(name: String) =
        !isPlainSaveName(withExtension(name)) ||
            directory.resolve(withExtension(name)).exists(LinkOption.NOFOLLOW_LINKS)
    if (!taken(base)) return withExtension(base)
    for (index in 2..MAX_SAVE_NAME_ATTEMPTS) {
        val candidate = "$base $index"
        if (candidate.length <= MAX_SAVE_NAME_LENGTH && !taken(candidate)) {
            return withExtension(candidate)
        }
    }
    error(I18n.t("saveA2.nameRules.tooManyDuplicates"))
}

/** 存档名必须是单层名称：不含分隔符、盘符与 Windows 保留名。 */
internal fun isPlainSaveName(name: String): Boolean {
    if (name.isBlank() || name.length > MAX_SAVE_NAME_LENGTH) return false
    if ('/' in name || '\\' in name || ':' in name) return false
    if (name.any { it < ' ' }) return false
    if (name.endsWith('.') || name.endsWith(' ')) return false
    // 用“当前目录 + 相对名称”做合法路径校验，不依赖 java.io.tmpdir（构建环境可能把它设成非法值）。
    return runCatching { safePath(Path.of("").toAbsolutePath(), name) }.isSuccess
}

/** 文件名安全化：去掉各平台的非法字符与结尾的点/空格，限制长度，空名称回退为 `save`。 */
internal fun safeSaveLabel(name: String): String {
    val cleaned =
        name
            .trim()
            .replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]"), "_")
            .trimEnd('.', ' ')
            .trim()
    val limited = cleaned.take(SAVE_LABEL_MAX_LENGTH).trimEnd('.', ' ')
    return limited.ifBlank { "save" }
}

internal fun String.removeSuffixIgnoreCase(suffix: String): String =
    if (endsWith(suffix, ignoreCase = true)) dropLast(suffix.length) else this
