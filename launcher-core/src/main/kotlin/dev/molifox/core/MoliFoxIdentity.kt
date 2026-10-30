// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责启动器版本、品牌和认证应用标识。

package dev.molifox.core

/** OAuth 公共应用标识随客户端分发，不包含客户端密钥。 */
object MoliFoxIdentity {
    const val MICROSOFT_CLIENT_ID = "f7a97d7c-8dc7-42eb-81bb-5bf19c29d213"
    const val REPOSITORY = "https://github.com/Tinmoli/MoliFox"
    const val RELEASES = "$REPOSITORY/releases"
    const val ISSUES = "$REPOSITORY/issues"
    const val LICENSE = "GPL-3.0-only · 保留署名与修改版区分"
}
