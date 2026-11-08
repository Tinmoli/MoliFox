// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

package dev.molifox.desktop

/** hex 是图片种子，不是最终 primary。未取得图片时由主题边界使用默认种子。 */
internal data class BackgroundAccentState(
    val hex: String? = null,
    val loading: Boolean = false,
)
