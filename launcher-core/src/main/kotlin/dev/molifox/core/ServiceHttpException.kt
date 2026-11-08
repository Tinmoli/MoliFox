// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责远端服务错误的结构化描述。

package dev.molifox.core

import dev.molifox.core.i18n.I18n
import java.io.IOException

/** Keeps response credentials out of errors while allowing actionable authentication failures. */
class ServiceHttpException(
    val statusCode: Int,
    val serviceHost: String,
) : IOException(I18n.t("networkA.serviceHttp.failed", statusCode, serviceHost))

/** CurseForge 的状态码；调用方可据此区分可选元数据 404 与真正的请求失败。 */
class CurseForgeHttpException(
    val statusCode: Int,
    message: String,
) : IllegalStateException(message)
