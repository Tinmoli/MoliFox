// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 镜像清单与探测、文件摘要：准备窗口只负责展示，源是否可用在这里判定。
package dev.molifox.bootstrap

import dev.molifox.core.i18n.I18n
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/** 一个 Maven 源。[roots] 按顺序尝试：androidx.* 只发布在 Google Maven，其余在 Central。 */
internal data class Mirror(
    val name: String,
    val roots: List<String>,
    val latencyMillis: Long = 0,
)

/** 镜像探测结果，只保留真正可用的源。 */
private const val PROBE_TIMEOUT_MILLIS = 1500L

/** 探测时读取的响应体字节数：够确认能取到内容，又不浪费流量。 */
private const val PROBE_SAMPLE_BYTES = 64

private fun mirrors(): List<Mirror> =
    listOf(
        Mirror(
            I18n.t("bootstrapB.mirror.aliyun"),
            listOf(
                "https://maven.aliyun.com/repository/central/",
                "https://maven.aliyun.com/repository/google/",
            ),
        ),
        Mirror(
            I18n.t("bootstrapB.mirror.tencentCloud"),
            listOf("https://mirrors.cloud.tencent.com/nexus/repository/maven-public/"),
        ),
        Mirror(
            I18n.t("bootstrapB.mirror.huaweiCloud"),
            listOf("https://repo.huaweicloud.com/repository/maven/"),
        ),
        Mirror(
            "Maven Central",
            listOf(
                "https://repo1.maven.org/maven2/",
                "https://dl.google.com/dl/android/maven2/",
            ),
        ),
    )

/** 探测路径取清单里真实的 kotlin-stdlib 版本，保证探测和下载的是同一批文件。 */
internal fun probePath(manifest: RuntimeManifest): String {
    val stdlib =
        manifest.dependencies.firstOrNull {
            it.group == "org.jetbrains.kotlin" && it.artifact == "kotlin-stdlib"
        } ?: error(I18n.t("bootstrap.mirror.1"))
    return "org/jetbrains/kotlin/kotlin-stdlib/${stdlib.version}/" +
        "kotlin-stdlib-${stdlib.version}.pom"
}

/** 同时探测全部镜像，按耗时升序返回可用源；失败的源不进入列表。 */
internal suspend fun selectMirrors(mirrorPath: String): List<Mirror> =
    coroutineScope {
        mirrors()
            .map { mirror -> async(Dispatchers.IO) { probeMirror(mirror, mirrorPath) } }
            .awaitAll()
            .filterNotNull()
            .sortedBy { it.latencyMillis }
    }

private suspend fun probeMirror(
    mirror: Mirror,
    mirrorPath: String,
): Mirror? =
    withTimeoutOrNull(PROBE_TIMEOUT_MILLIS) {
        for (root in mirror.roots) {
            val started = System.nanoTime()
            try {
                val connection =
                    open(root + mirrorPath).apply {
                        requestMethod = "GET"
                        connectTimeout = PROBE_TIMEOUT_MILLIS.toInt()
                        readTimeout = PROBE_TIMEOUT_MILLIS.toInt()
                    }
                try {
                    val status = connection.responseCode
                    if (status == HttpURLConnection.HTTP_OK) {
                        // 读一点响应体就断开：既确认能取到内容，也不浪费流量。
                        connection.inputStream.use { it.read(ByteArray(PROBE_SAMPLE_BYTES)) }
                        val elapsed = (System.nanoTime() - started) / 1_000_000
                        log(I18n.t("bootstrapB.mirror.probeSucceeded", mirror.name, root, elapsed))
                        return@withTimeoutOrNull mirror.copy(
                            roots = listOf(root) + mirror.roots.filter { it != root },
                            latencyMillis = elapsed,
                        )
                    }
                    log(I18n.t("bootstrap.mirror.2", mirror.name, root, status))
                } finally {
                    connection.disconnect()
                }
            } catch (error: IOException) {
                log(I18n.t("bootstrapB.mirror.probeError", mirror.name, root, error.message))
            }
        }
        null
    }

internal fun open(url: String): HttpURLConnection =
    (URI(url).toURL().openConnection() as HttpURLConnection).apply {
        instanceFollowRedirects = true
        useCaches = false
        setRequestProperty("User-Agent", "MoliFox-Bootstrap/1.0")
    }

/** 逐块计算文件 SHA-256，用于判断缓存是否仍然可信。 */
internal fun sha256Of(path: Path): String {
    val digest = MessageDigest.getInstance("SHA-256")
    Files.newInputStream(path).use { input ->
        val buffer = ByteArray(65536)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
