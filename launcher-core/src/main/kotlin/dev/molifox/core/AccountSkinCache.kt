// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责账户皮肤纹理的获取与本地缓存。

package dev.molifox.core

import dev.molifox.core.i18n.I18n
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.URI
import java.net.URISyntaxException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.util.UUID
import javax.imageio.ImageIO
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.fileSize
import kotlin.io.path.isRegularFile
import kotlin.io.path.reader
import kotlin.io.path.writeBytes

/** 皮肤纹理缓存的体积上限：超过 1 MiB 的字节一定不是合法的 Minecraft 纹理。 */
private const val MAX_SKIN_TEXTURE_BYTES = 1024L * 1024

/** 皮肤纹理的最小边长，同时也是宽高必须对齐的步长（纹理边长是它的整数倍）。 */
private const val SKIN_TEXTURE_EDGE_UNIT = 64

/** 皮肤纹理允许的最大边长：再大就不是客户端会加载的纹理。 */
private const val MAX_SKIN_TEXTURE_EDGE = 4096

/** Mojang profile responses can contain HTTP CDN URLs; only that exact origin is upgraded. */
internal fun normalizeSkinTextureUrl(url: String): String {
    val uri = URI(url)
    require(
        uri.scheme in setOf("http", "https") &&
            uri.host.equals("textures.minecraft.net", true) &&
            uri.userInfo == null &&
            uri.port == -1 &&
            uri.rawQuery == null &&
            uri.rawFragment == null &&
            uri.path.matches(Regex("/texture/[a-fA-F0-9]{32,128}")),
    ) {
        I18n.t("core.accountSkinCache.1")
    }
    return "https://textures.minecraft.net${uri.rawPath}"
}

internal fun isValidSkinTexture(path: Path): Boolean =
    runCatching {
        if (!path.isRegularFile() ||
            Files.isSymbolicLink(
                path,
            ) || path.fileSize() !in 1..MAX_SKIN_TEXTURE_BYTES
        ) {
            return false
        }
        ImageIO.createImageInputStream(path.toFile()).use { input ->
            val reader = ImageIO.getImageReaders(input).asSequence().firstOrNull() ?: return false
            try {
                reader.input = input
                val width = reader.getWidth(0)
                val height = reader.getHeight(0)
                reader.formatName.equals("png", true) &&
                    width in SKIN_TEXTURE_EDGE_UNIT..MAX_SKIN_TEXTURE_EDGE &&
                    width % SKIN_TEXTURE_EDGE_UNIT == 0 &&
                    (height == width || height * 2 == width) &&
                    reader.read(0) != null
            } finally {
                reader.dispose()
            }
        }
    }.getOrDefault(false)

internal class AccountSkinCache(
    private val store: LauncherStore,
    network: NetworkClient,
    private val removeTemporary: (Path) -> Unit = { it.deleteIfExists() },
    private val fetchTexture: suspend (String) -> ByteArray = network::skinTexture,
) {
    suspend fun load(
        account: Account,
        refresh: Boolean,
    ): Path? =
        withContext(Dispatchers.IO) {
            if (account.type != AccountType.Microsoft || account.skinUrl.isBlank()) {
                return@withContext null
            }
            var temporary: Path? = null
            var cached: Path? = null
            try {
                val url = normalizeSkinTextureUrl(account.skinUrl)
                val key = textureKey(url)
                val cache = safePath(store.cacheDirectory, "skins/$key.png")
                cached = cache
                if (!refresh && isValidSkinTexture(cache)) return@withContext cache
                val bytes = fetchTexture(url)
                cache.parent.createDirectories()
                val staging = cache.resolveSibling("${cache.fileName}.${UUID.randomUUID()}.tmp")
                temporary = staging
                writeTexture(bytes, staging, cache)
                cache
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: URISyntaxException) {
                fallbackToCached(failure, cached)
            } catch (failure: IOException) {
                fallbackToCached(failure, cached)
            } catch (failure: IllegalArgumentException) {
                fallbackToCached(failure, cached)
            } catch (failure: IllegalStateException) {
                fallbackToCached(failure, cached)
            } catch (failure: NoSuchAlgorithmException) {
                fallbackToCached(failure, cached)
            } finally {
                temporary?.let(::cleanTemporary)
            }
        }

    /** 清理失败不能覆盖已经完成的加载、回退或取消。 */
    private fun cleanTemporary(path: Path) {
        try {
            removeTemporary(path)
        } catch (failure: IOException) {
            store.log(I18n.t("miscA1.accountSkinCache.cleanupFailed", path, failure.message))
        } catch (failure: SecurityException) {
            store.log(I18n.t("miscA1.accountSkinCache.cleanupFailed", path, failure.message))
        }
    }

    private fun textureKey(url: String): String =
        MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).joinToString(
            "",
        ) {
            "%02x".format(it)
        }

    private fun writeTexture(
        bytes: ByteArray,
        staging: Path,
        cache: Path,
    ) {
        staging.writeBytes(bytes)
        require(isValidSkinTexture(staging)) { I18n.t("core.accountSkinCache.2") }
        Files.move(staging, cache, StandardCopyOption.REPLACE_EXISTING)
    }

    /** 取回失败时退回上一次成功缓存的纹理；没有可用缓存就当作该账户没有皮肤。 */
    private fun fallbackToCached(
        failure: Throwable,
        cached: Path?,
    ): Path? {
        store.log(I18n.t("miscA1.accountSkinCache.fetchFailed", failure.message))
        return cached?.takeIf(::isValidSkinTexture)
    }
}
