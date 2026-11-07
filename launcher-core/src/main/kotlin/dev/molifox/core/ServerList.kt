// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责游戏服务器列表和图标的读取。

package dev.molifox.core

import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.Base64
import javax.imageio.ImageIO
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

private const val SERVERS_FILE = "servers.dat"
private const val MAX_SERVER_ICON_BYTES = 256 * 1024

/**
 * 读取游戏目录下的 `servers.dat`，返回服务器列表。
 *
 * 布局：根复合标签里名为 `servers` 的复合标签列表，每项至少有 `name` 与 `ip`，可选 `icon`（base64 图片）；较早的布局只有 `name` 与 `ip`，此时
 * [ServerEntry.icon] 为 null。 文件缺失、空文件、不是 NBT、没有 `servers` 列表都返回空列表，从不抛异常，也不修改文件。
 */
fun listServers(gameDirectory: Path): List<ServerEntry> =
    runCatching {
        val file = gameDirectory.resolve(SERVERS_FILE)
        if (!file.isRegularFile(LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(file)) {
            return@runCatching emptyList<ServerEntry>()
        }
        val root = readCompound(file) ?: return@runCatching emptyList<ServerEntry>()
        root
            .list("servers")
            ?.compounds()
            .orEmpty()
            .mapNotNull(::serverEntry)
    }.getOrElse { emptyList() }

/** 单个服务器条目；缺少地址的项直接跳过，名称留空时回退成地址。 */
private fun serverEntry(tag: NbtCompound): ServerEntry? {
    val ip = tag.string("ip")?.trim().orEmpty()
    if (ip.isEmpty()) return null
    val name =
        tag
            .string("name")
            ?.trim()
            .orEmpty()
            .ifEmpty { ip }
    return ServerEntry(name = name, ip = ip, icon = decodeServerIcon(tag.string("icon")))
}

/** base64 图标只在体积、解码与图片格式都通过时保留；所有失败都按「没有图标」处理。 */
private fun decodeServerIcon(encoded: String?): ByteArray? {
    val text = encoded?.filterNot { it.isWhitespace() }?.takeIf { it.isNotEmpty() } ?: return null
    val bytes = runCatching { Base64.getDecoder().decode(text) }.getOrNull() ?: return null
    if (bytes.isEmpty() || bytes.size > MAX_SERVER_ICON_BYTES) return null
    val readable =
        runCatching {
            ImageIO.read(ByteArrayInputStream(bytes)) != null
        }.getOrDefault(false)
    return if (readable) bytes else null
}
