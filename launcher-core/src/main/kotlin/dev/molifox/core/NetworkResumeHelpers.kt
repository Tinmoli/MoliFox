// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责断点续传的校验、摘要与落盘细节。

package dev.molifox.core

import dev.molifox.core.i18n.I18n
import okhttp3.Response
import okhttp3.ResponseBody
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlin.io.path.deleteIfExists
import kotlin.io.path.fileSize
import kotlin.io.path.isRegularFile

/** 断点续传用到的状态码。 */
private const val HTTP_OK = 200
private const val HTTP_PARTIAL_CONTENT = 206
private const val HTTP_RANGE_NOT_SATISFIABLE = 416

/** 本次写入的起点：断点已坏时返回 null（清空 `.part` 后重新请求），否则返回起点偏移。 */
internal fun resumableStart(
    partial: Path,
    response: Response,
    offset: Long,
): Long? =
    when {
        response.code == HTTP_RANGE_NOT_SATISFIABLE -> {
            // Range 起点超出服务器对象长度：断点已坏，丢掉后从头开始。
            partial.deleteIfExists()
            null
        }
        offset > 0 && response.code == HTTP_OK -> {
            // 服务器不支持 Range：必须清空重写，不能把完整响应拼到已有字节后面。
            partial.deleteIfExists()
            0
        }
        response.code != HTTP_PARTIAL_CONTENT -> offset
        contentRange(response.header("Content-Range"))?.first == offset -> offset
        else -> {
            partial.deleteIfExists()
            null
        }
    }

/** 非 2xx 与空响应都在这里拒绝；错误信息报告实际请求的源（可能是镜像）。 */
internal fun checkedResponseBody(response: Response): ResponseBody {
    if (!response.isSuccessful) {
        throw DownloadHttpException(response.code, response.request.url.host)
    }
    return response.body ?: throw IOException(I18n.t("networkA.resume.emptyBody"))
}

/** 已有可续传的字节数；超过声明长度说明断点与目标不符，直接丢弃。 */
internal fun resumeOffset(
    artifact: DownloadArtifact,
    partial: Path,
): Long {
    if (!partial.isRegularFile()) return 0
    val size = partial.fileSize()
    if (size <= 0) return 0
    if (artifact.size > 0 && size > artifact.size) {
        partial.deleteIfExists()
        return 0
    }
    return size
}

/** 续传时先补齐已有字节的摘要，再继续累加新数据，最终校验的是整个文件。 */
internal fun updateDigest(
    partial: Path,
    digest: MessageDigest,
    length: Long,
) {
    Files.newInputStream(partial).use { input ->
        val buffer = ByteArray(65536)
        var remaining = length
        while (remaining > 0) {
            val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (count < 0) break
            digest.update(buffer, 0, count)
            remaining -= count
        }
    }
}

/** 进度总长：优先用声明的完整大小；206 用 Content-Range 的总长；否则用本次剩余长度推算。 */
internal fun totalBytes(
    artifact: DownloadArtifact,
    response: Response,
    start: Long,
): Long {
    if (artifact.size > 0) return artifact.size
    if (response.code == HTTP_PARTIAL_CONTENT) {
        contentRange(response.header("Content-Range"))?.let {
            return it.second
        }
    }
    val length = response.body?.contentLength() ?: -1L
    return if (length > 0) start + length else 0
}

/** 解析 `Content-Range: bytes 100-999/1000`，返回 (起点, 总长)。 */
internal fun contentRange(header: String?): Pair<Long, Long>? {
    val match = header?.let { Regex("bytes (\\d+)-(\\d+)/(\\d+)").find(it) } ?: return null
    val (start, _, total) = match.destructured
    return start.toLongOrNull()?.let { begin -> total.toLongOrNull()?.let { begin to it } }
}

/** 校验整体大小与摘要后改名到目标；任何不符都删掉 `.part` 并抛校验失败。 */
internal fun commitVerified(
    artifact: DownloadArtifact,
    partial: Path,
    digest: MessageDigest,
    total: Long,
    progress: (Long, Long) -> Unit,
) {
    val size = partial.fileSize()
    if (artifact.size > 0 && size != artifact.size) {
        partial.deleteIfExists()
        throw DownloadVerificationException(
            I18n.t(
                "networkA.resume.sizeMismatch",
                artifact.target.fileName,
                artifact.size,
                size,
            ),
        )
    }
    if (artifact.sha1.isNotBlank()) {
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (!actual.equals(artifact.sha1, true)) {
            partial.deleteIfExists()
            throw DownloadVerificationException(
                I18n.t("networkA.resume.hashMismatch", artifact.target.fileName),
            )
        }
    }
    Files.move(partial, artifact.target, StandardCopyOption.REPLACE_EXISTING)
    progress(size, if (total > 0) total else size)
}

/** 可选文件失败只记一行警告并跳过，返回 `false` 让整批继续。 */
internal fun skipOptionalDownload(
    logger: (String, Boolean) -> Unit,
    artifact: DownloadArtifact,
    error: Exception,
): Boolean {
    logger(
        I18n.t(
            "networkA.resume.optionalSkipped",
            artifact.target.fileName,
            error.message,
        ),
        false,
    )
    return false
}
