// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 描述一次启动实际采用的游戏内存分配。

package dev.molifox.core

data class GameMemoryAllocation(
    val maximumMb: Int,
    val initialMb: Int,
    val automatic: Boolean,
)
