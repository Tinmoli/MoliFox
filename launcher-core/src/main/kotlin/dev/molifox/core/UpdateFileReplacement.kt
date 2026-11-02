// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

package dev.molifox.core

import dev.molifox.core.i18n.I18n
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID

internal fun requireUpdateDigest(
    temporary: Path,
    sha512: String,
    sha1: String,
) {
    val (expected, algorithm) =
        when {
            sha512.isNotBlank() -> sha512 to "SHA-512"
            sha1.isNotBlank() -> sha1 to "SHA-1"
            else -> return
        }
    require(digest(temporary, algorithm).equals(expected, true)) {
        I18n.t("packsB2.packUpdate.hashMismatch")
    }
}

/** 同目录暂存、失败恢复、成功回收；调用方保留各自的校验与取消时机。 */
internal fun LauncherStore.replaceUpdatedFile(
    directory: Path,
    old: Path,
    target: Path,
    temporary: Path,
    messagePrefix: String,
) {
    val backup = safePath(directory, ".molifox-old-${UUID.randomUUID()}")
    Files.move(old, backup)
    try {
        Files.move(temporary, target)
    } catch (failure: IOException) {
        val restored = runCatching { Files.move(backup, old, StandardCopyOption.REPLACE_EXISTING) }
        if (restored.isFailure) {
            log(I18n.t("$messagePrefix.restoreFailed", backup.toAbsolutePath()), true)
            restored.exceptionOrNull()?.let(failure::addSuppressed)
        }
        throw failure
    }
    try {
        platform.moveToTrash(backup)
    } catch (error: IOException) {
        // 新文件已经落位；回收站失败只记日志并保留备份。
        log(I18n.t("$messagePrefix.trashFailed", backup.toAbsolutePath(), error.message), true)
    }
}
