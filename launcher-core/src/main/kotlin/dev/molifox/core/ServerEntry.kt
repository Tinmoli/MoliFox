// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 描述游戏服务器列表中的名称、地址与可用图标。

package dev.molifox.core

/** 图标为已验证的原始 PNG 字节；缺失或无法识别时为空。 */
data class ServerEntry(
    val name: String,
    val ip: String,
    val icon: ByteArray?,
)
