// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

package dev.molifox.core

import okhttp3.Response
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat
import kotlin.io.path.deleteIfExists
import kotlin.io.path.fileSize
import kotlin.io.path.isRegularFile

private const val IDENTITY_FIELD_COUNT = 3
private const val MAX_IDENTITY_FILE_BYTES = 4096
private const val MAX_ETAG_LENGTH = 1024
private val strongEntityTag = Regex("\"[^\\p{Cntrl}\"]*\"")

internal class ResumeRequest(
    val source: String,
    val offset: Long,
    val identity: ResumeIdentity?,
)

/** 普通断点的源与强 ETag。地址只保存摘要，避免在磁盘保留签名链接。 */
internal data class ResumeIdentity(
    val source: String,
    val response: String,
    val etag: String,
) {
    fun matches(candidate: Response): Boolean =
        response == resumeSourceKey(candidate.request.url.toString()) && etag == candidate.header("ETag")
}

internal fun resumeIdentityFile(partial: Path): Path = partial.resolveSibling("${partial.fileName}.identity.part")

internal fun resumeSourceKey(url: String): String =
    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(url.toByteArray(Charsets.UTF_8)))

internal fun readResumeIdentity(partial: Path): ResumeIdentity? {
    val file = resumeIdentityFile(partial)
    if (!file.isRegularFile(NOFOLLOW_LINKS) || file.fileSize() > MAX_IDENTITY_FILE_BYTES) return null
    val lines = Files.readAllLines(file)
    if (lines.size != IDENTITY_FIELD_COUNT) return null
    val (source, response, tag) = lines
    if (!strongEntityTag.matches(tag)) return null
    return ResumeIdentity(source, response, tag)
}

internal fun recordResumeIdentity(
    partial: Path,
    source: String,
    response: Response,
) {
    val file = resumeIdentityFile(partial)
    val tag = response.header("ETag")
    file.deleteIfExists()
    if (tag == null || tag.length > MAX_ETAG_LENGTH || !strongEntityTag.matches(tag)) return
    Files.write(file, listOf(source, resumeSourceKey(response.request.url.toString()), tag))
}
