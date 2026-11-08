// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责让 Windows 网络客户端使用现有系统与 Java 信任根，保持标准证书和域名校验。

package dev.molifox.core

import java.security.KeyStore
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

internal fun windowsTlsConfiguration(): Pair<SSLSocketFactory, X509TrustManager>? {
    if (!System.getProperty("os.name").startsWith("Windows", true)) return null
    // An explicit JSSE trust-store is an administrator's restriction; never extend it with OS
    // roots.
    if (
        listOf(
            "javax.net.ssl.trustStore",
            "javax.net.ssl.trustStoreType",
            "javax.net.ssl.trustStoreProvider",
        ).any { System.getProperty(it) != null }
    ) {
        return null
    }
    val defaults =
        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
            init(null as KeyStore?)
        }
    val roots =
        defaults.trustManagers
            .filterIsInstance<X509TrustManager>()
            .flatMap { it.acceptedIssuers.toList() }
            .toMutableList()
    val systemRoots =
        runCatching {
            KeyStore.getInstance("Windows-ROOT").apply { load(null, null) }
        }.getOrNull() ?: return null
    val aliases = systemRoots.aliases()
    while (aliases.hasMoreElements()) {
        (systemRoots.getCertificate(aliases.nextElement()) as? X509Certificate)?.let(roots::add)
    }
    val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }
    roots.distinct().forEachIndexed { index, certificate ->
        store.setCertificateEntry("root-$index", certificate)
    }
    val factory =
        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
            init(store)
        }
    val manager = factory.trustManagers.filterIsInstance<X509TrustManager>().single()
    val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf(manager), null) }
    return context.socketFactory to manager
}
