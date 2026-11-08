// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责启动器版本、品牌和认证应用标识。

package dev.molifox.core

import dev.molifox.core.i18n.I18n
import java.util.Properties

/**
 * OAuth 公共应用标识随客户端分发，不包含客户端密钥。
 *
 * Microsoft Client ID 不写进仓库源码：构建时从环境变量 `MOLIFOX_MS_CLIENT_ID`
 * （或 `MICROSOFT_CLIENT_ID`、本机 `local.properties`）生成 `microsoft-identity.properties`，
 * 运行时读资源。取不到时为空串，用户仍可在设置里填写自定义 UUID。
 */
object MoliFoxIdentity {
    /** 完整产品名：只用于关于页、用户协议和可执行文件属性，日常界面仍用简称 MoliFox。 */
    const val NAME = "MoliFox Launcher"

    val MICROSOFT_CLIENT_ID: String = loadClientId()

    const val REPOSITORY = "https://github.com/Tinmoli/MoliFox"
    const val RELEASES = "$REPOSITORY/releases"
    const val ISSUES = "$REPOSITORY/issues"

    /** 许可证标语按当前语言读取；许可证标识本身（GPL-3.0-only）不翻译。 */
    val LICENSE: String get() = I18n.t("miscA2.identity.license")

    private const val RESOURCE = "/microsoft-identity.properties"
    private const val CLIENT_ID_KEY = "microsoft.client.id"

    private fun loadClientId(): String =
        runCatching {
            val stream = MoliFoxIdentity::class.java.getResourceAsStream(RESOURCE) ?: return ""
            stream.use { input ->
                Properties()
                    .apply { load(input) }
                    .getProperty(CLIENT_ID_KEY, "")
                    .trim()
            }
        }.getOrDefault("")
}
