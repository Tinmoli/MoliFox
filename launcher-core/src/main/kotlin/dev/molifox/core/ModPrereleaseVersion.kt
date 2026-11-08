// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责判断版本号是否为预发布版本。

package dev.molifox.core

/** 版本号是否带预发布后缀（如 `1.0.0-beta`）；构建元数据 `+...` 不算。 */
internal fun isPrereleaseVersion(value: String): Boolean {
    val core = value.trim().substringBefore('+')
    return core.contains('-') && core.substringAfter('-').isNotBlank()
}
