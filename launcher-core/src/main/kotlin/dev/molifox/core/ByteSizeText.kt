// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 字节大小的 MB 文案：Java 运行时提示与下载预算日志共用同一套换算与数字格式。

package dev.molifox.core

import java.util.Locale

/** 展示换算用 2^20 进制；除法转 Double，避免先转 Float 丢精度。 */
private const val MEBIBYTE_BYTES = 1024L * 1024

/** 一位小数的 MB 文本；数字格式不随系统区域变化。 */
internal fun megabytesText(bytes: Long): String =
    String.format(Locale.ROOT, "%.1f MB", bytes / MEBIBYTE_BYTES.toDouble())
