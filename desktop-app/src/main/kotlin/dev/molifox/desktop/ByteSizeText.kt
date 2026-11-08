// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 字节大小的用户可见文案：此前各对话框复制粘贴的换算收敛到此处统一维护。

package dev.molifox.desktop

import java.util.Locale

/** 展示换算统一用 2^20 进制；阈值用 Long 比较，除法转 Double，避免先转 Float 丢精度。 */
private const val KIBIBYTE_BYTES = 1024L
private const val MEBIBYTE_BYTES = 1024L * 1024
private const val GIBIBYTE_BYTES = 1024L * 1024 * 1024
private const val MEBIBYTES_PER_GIBIBYTE = 1024f

/** 自动档：GB/MB/KB 三级、一位小数，不足 1 KiB 时如实显示整数字节；只用于展示，不参与业务计算。 */
internal fun byteSizeText(bytes: Long): String =
    if (bytes >= GIBIBYTE_BYTES) {
        String.format(Locale.ROOT, "%.1f GB", bytes / GIBIBYTE_BYTES.toDouble())
    } else {
        formatFileSize(bytes)
    }

/** 中小文件档：到 MB 为止不升级 GB；目录浏览与内容详情沿用这一档位。 */
internal fun formatFileSize(bytes: Long): String =
    when {
        bytes >= MEBIBYTE_BYTES -> megabytesText(bytes)
        bytes >= KIBIBYTE_BYTES -> kilobytesText(bytes)
        else -> bytes.toString() + " B"
    }

/** 固定 MB 档：一位小数；模组体积等固定以 MB 展示的行内文本。 */
internal fun megabytesText(bytes: Long): String =
    String.format(Locale.ROOT, "%.1f MB", bytes / MEBIBYTE_BYTES.toDouble())

private fun kilobytesText(bytes: Long): String =
    String.format(Locale.ROOT, "%.1f KB", bytes / KIBIBYTE_BYTES.toDouble())

/** 内存设置以 MiB 计：换算成 GB 后保留一位小数，数字格式不随系统区域变化。 */
internal fun gigabytesFromMebibytes(mebibytes: Float): String =
    String.format(Locale.ROOT, "%.1f GB", mebibytes / MEBIBYTES_PER_GIBIBYTE)
