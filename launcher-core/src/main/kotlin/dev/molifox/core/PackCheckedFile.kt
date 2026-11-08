// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 描述整合包清单中已核对的文件路径、摘要和下载地址。

package dev.molifox.core

internal data class PackCheckedFile(
    val path: String,
    val hash: String,
    val url: String = "",
)
