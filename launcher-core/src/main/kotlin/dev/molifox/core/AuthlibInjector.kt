// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责外置认证注入器的下载缓存与启动参数。

package dev.molifox.core

import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URI
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Base64
import java.util.UUID
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.fileSize
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.isRegularFile
import kotlin.io.path.isSymbolicLink
import kotlin.io.path.name
import kotlin.io.path.readText

private const val MIN_AUTHLIB_JAR_BYTES = 100L * 1024
private const val ZIP_SIGNATURE_BYTES = 4
private const val ZIP_LOCAL_HEADER_VERSION_BYTE = 4
private const val ZIP_LOCAL_HEADER_MIN_VERSION_LOW_BYTE = 3
private const val ZIP_LOCAL_HEADER_VERSION_INDEX = 3
private const val MAX_AUTHLIB_METADATA_BYTES = 1024L * 1024

/**
 * authlib-injector 下载与预取元数据。
 *
 * 文件缓存固定在 `<启动器数据目录>/cache/authlib-injector/`：
 * - `authlib-injector.jar`：下载自 BMCLAPI 镜像（失败时回退官方发布地址）；
 * - `metadata/<hash>.json`：服务器元数据，24 小时刷新一次，断网时沿用旧缓存。
 *
 * 下载失败返回 null（[ensureJar]）或抛出带中文可操作提示的 [AuthlibException]（[jvmArguments]），
 * 由调用方决定如何提示，绝不静默带着缺失的注入器启动游戏。
 */
class AuthlibInjector(
    private val network: NetworkClient,
    private val client: AuthlibClient,
) {
    /** 返回可用的 authlib-injector jar；已有大于 100 KB 的普通 JAR 时直接复用。 */
    suspend fun ensureJar(cacheDir: Path): Path? =
        withContext(Dispatchers.IO) {
            val folder = injectorFolder(cacheDir)
            val jar = folder.resolve("authlib-injector.jar")
            if (isUsableJar(jar)) return@withContext jar
            jar.deleteIfExists()
            if (!runCatching { folder.createDirectories() }.isSuccess) return@withContext null
            val urls = artifactUrls()
            if (urls.isEmpty()) return@withContext null
            for (url in urls) {
                try {
                    network.download(DownloadArtifact(url, jar), attempts = 2)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    jar.deleteIfExists()
                    continue
                }
                if (isUsableJar(jar)) return@withContext jar
                jar.deleteIfExists()
            }
            null
        }

    /** 生成外置认证启动所需的两个 JVM 参数；拿不到 jar 或元数据时抛出可操作错误。 */
    suspend fun jvmArguments(
        cacheDir: Path,
        serverUrl: String,
    ): List<String> {
        val server =
            normalizeAuthlibServerUrl(serverUrl)
                ?: throw AuthlibException(AuthlibError.InvalidServer, "不是有效的认证服务器地址")
        val jar =
            ensureJar(cacheDir)
                ?: throw AuthlibException(
                    AuthlibError.DownloadFailed,
                    "无法下载 authlib-injector，请检查网络或代理后重试",
                )
        val prefetched = prefetchedMetadata(cacheDir, server)
        return listOf(
            "-javaagent:${jar.toAbsolutePath().normalize()}=$server",
            "-Dauthlibinjector.yggdrasil.prefetched=$prefetched",
        )
    }

    /** 服务器元数据的 Base64（authlib-injector 的 yggdrasil.prefetched 参数）。 */
    suspend fun prefetchedMetadata(
        cacheDir: Path,
        serverUrl: String,
    ): String {
        val server = requireServer(serverUrl)
        val folder = injectorFolder(cacheDir).resolve("metadata")
        val file = folder.resolve("${sha256(server)}.json")
        val cached = readCachedMetadata(file)
        if (cached != null && !isExpired(file)) return encodeBase64(cached)
        val metadata =
            try {
                client.metadata(server)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: AuthlibException) {
                // 服务器暂时不可达时仍可使用已缓存的公开元数据启动。
                if (cached != null) return encodeBase64(cached)
                throw error
            }
        runCatching {
            folder.createDirectories()
            writeAtomically(file, metadata.rawJson)
        }
        return encodeBase64(metadata.rawJson)
    }

    private fun requireServer(serverUrl: String): String =
        normalizeAuthlibServerUrl(serverUrl)
            ?: throw AuthlibException(AuthlibError.InvalidServer, "不是有效的认证服务器地址")

    private fun injectorFolder(cacheDir: Path): Path =
        cacheDir.toAbsolutePath().normalize().resolve("authlib-injector")

    private fun isUsableJar(path: Path): Boolean =
        runCatching {
            path.isRegularFile() &&
                !Files.isSymbolicLink(path) &&
                path.fileSize() > MIN_AUTHLIB_JAR_BYTES &&
                looksLikeJar(path)
        }.getOrDefault(false)

    private fun looksLikeJar(path: Path): Boolean =
        Files.newInputStream(path).use { input ->
            val header = input.readNBytes(4)
            header.size == ZIP_SIGNATURE_BYTES &&
                header[0] == 'P'.code.toByte() &&
                header[1] == 'K'.code.toByte() &&
                header[2] == ZIP_LOCAL_HEADER_MIN_VERSION_LOW_BYTE.toByte() &&
                header[ZIP_LOCAL_HEADER_VERSION_INDEX] ==
                ZIP_LOCAL_HEADER_VERSION_BYTE.toByte()
        }

    private suspend fun artifactUrls(): List<String> =
        buildList {
            for (mirror in mirrors) {
                val url = artifactUrl(mirror)
                if (url != null && url !in this) add(url)
            }
        }

    /** 读取单个镜像声明的下载地址；读取失败、字段缺失或不是 http(s) 地址时返回 null。 */
    private suspend fun artifactUrl(mirror: String): String? =
        runCatching {
            val text = network.text(mirror, "official")
            val json = JsonParser.parseString(text).asJsonObject
            val raw = json.string("download_url").ifBlank { json.string("downloadUrl") }.trim()
            check(raw.isNotBlank()) { "镜像未提供下载地址" }
            URI(mirror).resolve(raw).toString().takeIf {
                it.startsWith("https://") || it.startsWith("http://")
            }
        }.getOrElse { error ->
            when (error) {
                is CancellationException -> throw error
                is Exception -> null
                else -> throw error
            }
        }

    private fun readCachedMetadata(file: Path): String? =
        runCatching {
            if (!file.isRegularFile() || Files.isSymbolicLink(file)) return@runCatching null
            if (file.fileSize() !in 1..(MAX_AUTHLIB_METADATA_BYTES)) return@runCatching null
            val text = file.readText(Charsets.UTF_8)
            if (text.isBlank()) return@runCatching null
            if (runCatching { JsonParser.parseString(text).asJsonObject }.isFailure) {
                return@runCatching null
            }
            text
        }.getOrNull()

    private fun isExpired(file: Path): Boolean =
        runCatching {
            System.currentTimeMillis() - Files.getLastModifiedTime(file).toMillis() >
                METADATA_TTL_MILLIS
        }.getOrDefault(true)

    private companion object {
        /** 先 BMCLAPI 镜像，失败再回退官方（GitHub Pages）发布地址。 */
        val mirrors =
            listOf(
                "https://bmclapi2.bangbang93.com/mirrors/authlib-injector/artifact/latest.json",
                "https://authlib-injector.yushi.moe/artifact/latest.json",
            )
        const val METADATA_TTL_MILLIS = 24L * 60 * 60 * 1000
    }
}

private fun writeAtomically(
    file: Path,
    text: String,
) {
    val temporary = file.resolveSibling(".${file.name}-${UUID.randomUUID()}.tmp")
    try {
        Files.writeString(temporary, text, Charsets.UTF_8)
        try {
            Files.move(
                temporary,
                file,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING)
        }
    } finally {
        temporary.deleteIfExists()
    }
}

private fun encodeBase64(text: String): String =
    Base64.getEncoder().encodeToString(text.toByteArray(Charsets.UTF_8))

private fun sha256(text: String): String =
    java.security.MessageDigest
        .getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
