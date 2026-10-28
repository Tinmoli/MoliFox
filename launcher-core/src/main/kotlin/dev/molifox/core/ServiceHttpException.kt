// 负责远端服务错误的结构化描述。

package dev.molifox.core

import java.io.IOException

/** Keeps response credentials out of errors while allowing actionable authentication failures. */
class ServiceHttpException(val statusCode: Int, val serviceHost: String) :
    IOException("登录服务请求失败 ($statusCode)：$serviceHost")
