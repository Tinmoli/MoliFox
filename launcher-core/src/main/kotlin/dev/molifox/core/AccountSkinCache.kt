// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责账户皮肤纹理的获取与本地缓存。

package dev.molifox.core

import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import javax.imageio.ImageIO
import kotlin.io.path.*
import kotlinx.coroutines.*

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
            uri.path.matches(Regex("/texture/[a-fA-F0-9]{32,128}"))
    ) {
        "不支持的皮肤纹理地址"
    }
    return "https://textures.minecraft.net${uri.rawPath}"
}

internal fun validSkinTexture(path: Path): Boolean = runCatching {
    if (!path.isRegularFile() || Files.isSymbolicLink(path) || path.fileSize() !in 1..1024L * 1024)
        return false
    ImageIO.createImageInputStream(path.toFile()).use { input ->
        val reader = ImageIO.getImageReaders(input).asSequence().firstOrNull() ?: return false
        try {
            reader.input = input
            val width = reader.getWidth(0)
            val height = reader.getHeight(0)
            reader.formatName.equals("png", true) &&
                width in 64..4096 &&
                width % 64 == 0 &&
                (height == width || height * 2 == width) &&
                reader.read(0) != null
        } finally {
            reader.dispose()
        }
    }
}
    .getOrDefault(false)

internal class AccountSkinCache(
    private val store: LauncherStore,
    network: NetworkClient,
    private val fetchTexture: suspend (String) -> ByteArray = network::skinTexture,
) {
    suspend fun load(account: Account, refresh: Boolean): Path? =
        withContext(Dispatchers.IO) {
            if (account.type != AccountType.Microsoft || account.skinUrl.isBlank())
                return@withContext null
            var temporary: Path? = null
            var cached: Path? = null
            try {
                val url = normalizeSkinTextureUrl(account.skinUrl)
                val key =
                    MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).joinToString(
                        ""
                    ) {
                        "%02x".format(it)
                    }
                val cache = safePath(store.cacheDirectory, "skins/$key.png")
                cached = cache
                if (!refresh && validSkinTexture(cache)) return@withContext cache
                val bytes = fetchTexture(url)
                cache.parent.createDirectories()
                val staging = cache.resolveSibling("${cache.fileName}.${UUID.randomUUID()}.tmp")
                temporary = staging
                staging.writeBytes(bytes)
                require(validSkinTexture(staging)) { "皮肤文件不是有效的 Minecraft PNG 纹理" }
                Files.move(staging, cache, StandardCopyOption.REPLACE_EXISTING)
                cache
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                store.log("皮肤纹理获取失败：${failure.message}")
                cached?.takeIf(::validSkinTexture)
            } finally {
                temporary?.deleteIfExists()
            }
        }
}
