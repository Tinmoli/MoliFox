// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

package dev.molifox.core

// 旧版强调色名与配色来源在读取设置时需要迁移；独立成文件，避免设置校验文件承担主题迁移职责。
internal fun normalizedAccentColor(value: String?): String =
    value?.takeUnless { it in setOf("minimal", "monochrome") } ?: "sakura"

/** 配色来源只有跟随背景图/自定义两档；旧配置的 default 迁移为自定义，缺失时按旧 automaticAccent 推断。 */
internal fun normalizedThemeColorSource(
    stored: String,
    legacyAutomaticAccent: Boolean,
): String =
    when (stored) {
        "background" -> "background"
        "manual" -> "manual"
        "default" -> "manual"
        else -> if (legacyAutomaticAccent) "background" else "manual"
    }
