// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责提供测试服务器的回环地址。

package dev.molifox.core

import okhttp3.HttpUrl
import okhttp3.mockwebserver.MockWebServer

// A local hosts-file alias must never turn a fixture URL into an external HTTPS request.
internal fun MockWebServer.loopbackUrl(path: String): HttpUrl = url(path).newBuilder().host("127.0.0.1").build()
