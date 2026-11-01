// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 流式读取大小受限的图像文件，避免将异常大文件完整读进内存。

package dev.molifox.core.platform.files

import dev.molifox.core.i18n.I18n
import java.nio.file.Files
import java.nio.file.Path

fun readBoundedImage(
    path: Path,
    maximumBytes: Int,
): Result<ByteArray> =
    runCatching {
        require(maximumBytes in 1 until Int.MAX_VALUE)
        Files.newInputStream(path).use { input ->
            val bytes = input.readNBytes(maximumBytes + 1)
            require(bytes.isNotEmpty() && bytes.size <= maximumBytes) { I18n.t("coreD.image.emptyOrTooLarge") }
            bytes
        }
    }
