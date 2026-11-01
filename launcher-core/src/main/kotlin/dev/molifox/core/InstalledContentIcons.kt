// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 优先读取包内图标或已登记来源，允许时仅用文件摘要精确识别外部包的项目头像。

package dev.molifox.core

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.molifox.core.i18n.I18n
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.io.path.isRegularFile
import kotlin.io.path.isSymbolicLink
import kotlin.io.path.name

private const val MAX_CONTENT_RECORD_BYTES = 8 * 1024 * 1024
private const val MAX_CONTENT_ICON_DIMENSION = 2048

/** WebP 容器头：RIFF 头、WEBP 标记、块名与各块内的宽高偏移。 */
private const val WEBP_MIN_HEADER_BYTES = 30
private const val WEBP_MAGIC_OFFSET = 0
private const val WEBP_FORMAT_OFFSET = 8
private const val WEBP_CHUNK_OFFSET = 12
private const val FOURCC_BYTES = 4
private const val WEBP_LOSSY_START_CODE_OFFSET = 23
private const val WEBP_LOSSY_WIDTH_OFFSET = 26
private const val WEBP_LOSSY_HEIGHT_OFFSET = 28
private const val WEBP_LOSSLESS_SIGNATURE_OFFSET = 20
private const val WEBP_LOSSLESS_SIGNATURE = 0x2f
private const val WEBP_LOSSLESS_BITS_OFFSET = 21
private const val WEBP_EXTENDED_WIDTH_OFFSET = 24
private const val WEBP_EXTENDED_HEIGHT_OFFSET = 27
private const val WEBP_DIMENSION_MASK = 0x3fff
private const val WEBP_HEIGHT_SHIFT = 14
private const val WEBP_LOSSY_START_CODE_0 = 0x9d.toByte()
private const val WEBP_LOSSY_START_CODE_1 = 0x01.toByte()
private const val WEBP_LOSSY_START_CODE_2 = 0x2a.toByte()

/** 小端字节读取。 */
private const val BYTE_MASK = 0xff
private const val BYTE_SHIFT = 8
private const val TWO_BYTE_SHIFT = 16
private const val THREE_BYTE_SHIFT = 24
private const val THREE_BYTE_INDEX = 3

internal class InstalledContentIcons(
    private val store: LauncherStore,
    private val network: NetworkClient,
    private val backend: ContentBackend,
    private val api: String = "https://api.modrinth.com/v2",
    private val identifyUnregistered: Boolean = false,
) {
    suspend fun read(
        instanceId: String,
        path: Path,
        folder: PackFolder,
    ): Result<ByteArray?> =
        withContext(Dispatchers.IO) {
            runCatching { load(instanceId, path, folder) }.onFailure {
                if (it is CancellationException) throw it
            }
        }

    private suspend fun load(
        instanceId: String,
        path: Path,
        folder: PackFolder,
    ): ByteArray? {
        val root = store.instancePath(instanceId).toAbsolutePath().normalize()
        val expected = root.resolve(folder.directory).resolve(safeFilename(path.name))
        require(path.toAbsolutePath().normalize() == expected && !path.isSymbolicLink())
        val embedded =
            if (folder ==
                PackFolder.ShaderPacks
            ) {
                shaderPackIcon(path)
            } else {
                localContentIcon(path, false)
            }
        return if (embedded != null) {
            embedded
        } else if (!path.isRegularFile()) {
            null
        } else {
            val source = registeredSource(root, path, folder)
            val url =
                if (source != null) {
                    source.second.string("iconUrl").ifBlank {
                        backend.project(source.first).string("icon_url")
                    }
                } else if (identifyUnregistered && folder == PackFolder.ShaderPacks) {
                    identifiedIcon(path, folder)
                } else {
                    ""
                }
            thumbnailWithinLimit(url)
        }
    }

    /** 下载并校验缩略图尺寸；没有地址、读不出图片或尺寸超限时返回 null。 */
    private suspend fun thumbnailWithinLimit(url: String): ByteArray? {
        if (url.isBlank()) return null
        val bytes = network.thumbnail(url)
        val (width, height) = imageDimensions(bytes) ?: return null
        return bytes.takeIf {
            width in 1..MAX_CONTENT_ICON_DIMENSION && height in 1..MAX_CONTENT_ICON_DIMENSION
        }
    }

    private suspend fun identifiedIcon(
        path: Path,
        folder: PackFolder,
    ): String {
        val hash = digest(path, "SHA-512")
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        val version =
            network
                .json(
                    "$api/version_file/$hash?algorithm=sha512",
                    "official",
                ).asJsonObject
        val hashMatches =
            version.array("files").any {
                it.asJsonObject
                    .obj("hashes")
                    .string("sha512")
                    .equals(hash, true)
            }
        val project =
            if (hashMatches) {
                val id = version.string("project_id")
                if (id.isBlank()) null else backend.project(id)
            } else {
                null
            }
        return if (project == null || project.string("project_type") != folder.kind) {
            ""
        } else {
            project.string("icon_url")
        }
    }

    private fun registeredSource(
        root: Path,
        path: Path,
        folder: PackFolder,
    ): Pair<String, JsonObject>? {
        val registry = root.resolve(".molifox-content.json")
        val match =
            if (registry.isRegularFile() && !registry.isSymbolicLink()) {
                val bytes =
                    Files
                        .newInputStream(
                            registry,
                        ).use { it.readNBytes(MAX_CONTENT_RECORD_BYTES + 1) }
                require(bytes.size <= MAX_CONTENT_RECORD_BYTES) { I18n.t("packsC.contentRecord.tooLarge") }
                val entries =
                    JsonParser.parseString(bytes.toString(Charsets.UTF_8)).asJsonObject.entrySet()
                entries.singleOrNull { (_, value) ->
                    value.isJsonObject && value.asJsonObject.string("folder") == folder.directory &&
                        value.asJsonObject.string("file") == path.name
                }
            } else {
                null
            }
        val (id, value) = match ?: return null
        val source = value.asJsonObject
        val algorithm = source.string("hashAlgorithm")
        return if (algorithm in setOf("SHA-1", "SHA-512") &&
            digest(path, algorithm).equals(source.string("hash"), true)
        ) {
            id to source
        } else {
            null
        }
    }
}

/**
 * 读取图片像素尺寸。ImageIO 覆盖 PNG / JPEG / GIF，但读不了 Modrinth 图标常用的 WebP，
 * 因此对 WebP 容器头单独取宽高；尺寸只用于拦截异常大图，实际解码仍交给 Skia。
 */
private fun imageDimensions(bytes: ByteArray): Pair<Int, Int>? =
    ImageIO.read(ByteArrayInputStream(bytes))?.let { it.width to it.height }
        ?: webpDimensions(bytes)

private fun webpDimensions(bytes: ByteArray): Pair<Int, Int>? {
    if (bytes.size < WEBP_MIN_HEADER_BYTES ||
        bytes.ascii(WEBP_MAGIC_OFFSET, FOURCC_BYTES) != "RIFF" ||
        bytes.ascii(WEBP_FORMAT_OFFSET, FOURCC_BYTES) != "WEBP"
    ) {
        return null
    }
    return when (bytes.ascii(WEBP_CHUNK_OFFSET, FOURCC_BYTES)) {
        "VP8 " -> lossyWebpDimensions(bytes)
        "VP8L" -> losslessWebpDimensions(bytes)
        "VP8X" -> extendedWebpDimensions(bytes)
        else -> null
    }
}

/** 有损 WebP：2 字节小端宽高，各取低 14 位。 */
private fun lossyWebpDimensions(bytes: ByteArray): Pair<Int, Int>? {
    val startCode =
        byteArrayOf(WEBP_LOSSY_START_CODE_0, WEBP_LOSSY_START_CODE_1, WEBP_LOSSY_START_CODE_2)
    val codeOffset = WEBP_LOSSY_START_CODE_OFFSET
    if (!bytes.copyOfRange(codeOffset, codeOffset + startCode.size).contentEquals(startCode)) {
        return null
    }
    val width = bytes.littleEndianShort(WEBP_LOSSY_WIDTH_OFFSET) and WEBP_DIMENSION_MASK
    val height = bytes.littleEndianShort(WEBP_LOSSY_HEIGHT_OFFSET) and WEBP_DIMENSION_MASK
    return if (width > 0 && height > 0) width to height else null
}

/** 无损 WebP：签名 0x2f 后 4 字节小端，宽、高各占 14 位。 */
private fun losslessWebpDimensions(bytes: ByteArray): Pair<Int, Int>? {
    if (bytes.u8(WEBP_LOSSLESS_SIGNATURE_OFFSET) != WEBP_LOSSLESS_SIGNATURE) return null
    val bits = bytes.littleEndianInt(WEBP_LOSSLESS_BITS_OFFSET)
    val width = (bits and WEBP_DIMENSION_MASK) + 1
    val height = ((bits ushr WEBP_HEIGHT_SHIFT) and WEBP_DIMENSION_MASK) + 1
    return width to height
}

/** 扩展 WebP（VP8X）：画布宽高各为 24 位小端值减一。 */
private fun extendedWebpDimensions(bytes: ByteArray): Pair<Int, Int> {
    val width = bytes.u24(WEBP_EXTENDED_WIDTH_OFFSET) + 1
    val height = bytes.u24(WEBP_EXTENDED_HEIGHT_OFFSET) + 1
    return width to height
}

private fun ByteArray.ascii(
    offset: Int,
    length: Int,
): String = String(this, offset, length, Charsets.US_ASCII)

private fun ByteArray.u8(offset: Int): Int = this[offset].toInt() and BYTE_MASK

private fun ByteArray.littleEndianShort(offset: Int): Int = u8(offset) or (u8(offset + 1) shl BYTE_SHIFT)

private fun ByteArray.u24(offset: Int): Int =
    u8(offset) or (u8(offset + 1) shl BYTE_SHIFT) or (u8(offset + 2) shl TWO_BYTE_SHIFT)

private fun ByteArray.littleEndianInt(offset: Int): Int =
    u8(offset) or
        (u8(offset + 1) shl BYTE_SHIFT) or
        (u8(offset + 2) shl TWO_BYTE_SHIFT) or
        (u8(offset + THREE_BYTE_INDEX) shl THREE_BYTE_SHIFT)
