// 读取资源包和世界自带的 PNG 图标，限制大小和像素数，不解压归档到磁盘。

package dev.molifox.core

import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.util.zip.ZipFile
import kotlin.io.path.*

fun localContentIcon(path: Path, world: Boolean): ByteArray? = runCatching {
    require(!path.isSymbolicLink())
    val name = if (world) "icon.png" else "pack.png"
    val bytes =
        if (path.isDirectory(NOFOLLOW_LINKS)) {
            val icon = path.resolve(name)
            require(
                icon.isRegularFile(NOFOLLOW_LINKS) &&
                    !icon.isSymbolicLink() &&
                    icon.toRealPath().startsWith(path.toRealPath()) &&
                    icon.fileSize() in 24..MAXIMUM_ICON_BYTES.toLong()
            )
            Files.newInputStream(icon).use { it.readNBytes(MAXIMUM_ICON_BYTES + 1) }
        } else {
            require(path.isRegularFile(NOFOLLOW_LINKS))
            ZipFile(path.toFile()).use { zip ->
                val entry =
                    zip.getEntry(name)
                        ?: if (world)
                            zip.entries().asSequence().take(10_000).firstOrNull {
                                it.name.endsWith("/icon.png") &&
                                    zip.getEntry(it.name.removeSuffix("icon.png") + "level.dat") !=
                                        null
                            }
                        else null
                require(
                    entry != null &&
                        !entry.isDirectory &&
                        entry.size in 24..MAXIMUM_ICON_BYTES.toLong()
                )
                zip.getInputStream(entry).use { it.readNBytes(MAXIMUM_ICON_BYTES + 1) }
            }
        }
    require(
        bytes.size in 24..MAXIMUM_ICON_BYTES &&
            bytes.take(8).toByteArray().contentEquals(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10))
    )
    val header = ByteBuffer.wrap(bytes, 16, 8)
    require(header.int in 1..2048 && header.int in 1..2048)
    bytes
}
    .getOrNull()

private const val MAXIMUM_ICON_BYTES = 1024 * 1024
