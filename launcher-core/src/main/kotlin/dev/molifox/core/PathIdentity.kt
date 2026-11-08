// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

package dev.molifox.core

import java.nio.file.Path

/** 比较两个可能尚不存在的文件路径；解析失败时只比较原始文字。 */
fun sameNormalizedPath(
    left: String,
    right: String,
    ignoreCase: Boolean,
): Boolean {
    if (left.isBlank() || right.isBlank()) return false
    if (left.equals(right, ignoreCase)) return true
    return runCatching {
        val first =
            Path
                .of(left)
                .toAbsolutePath()
                .normalize()
                .toString()
        val second =
            Path
                .of(right)
                .toAbsolutePath()
                .normalize()
                .toString()
        first.equals(second, ignoreCase)
    }.getOrDefault(false)
}
