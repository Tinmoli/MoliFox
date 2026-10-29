// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责远端服务错误的结构化描述。

package dev.molifox.core

import java.io.IOException

/** Keeps response credentials out of errors while allowing actionable authentication failures. */
class ServiceHttpException(val statusCode: Int, val serviceHost: String) :
    IOException("登录服务请求失败 ($statusCode)：$serviceHost")

/** CurseForge 的状态码；调用方可据此区分可选元数据 404 与真正的请求失败。 */
class CurseForgeHttpException(val statusCode: Int, message: String) : IllegalStateException(message)
