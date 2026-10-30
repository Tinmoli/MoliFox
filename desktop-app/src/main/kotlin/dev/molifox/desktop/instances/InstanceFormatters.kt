// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 实例列表的文本与排序辅助：信息行、最后游玩、占用大小、版本分段与目录探测。

package dev.molifox.desktop.instances

import dev.molifox.core.GameDirectory
import dev.molifox.core.GameInstance
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant

private const val SECONDS_PER_MINUTE = 60L
private const val SECONDS_PER_HOUR = 3600L
private const val SECONDS_PER_DAY = 86400L
private const val BYTES_PER_KIB = 1024L
private const val BYTES_PER_MIB = 1048576L
private const val BYTES_PER_GIB = 1073741824L

/** 列表行信息：版本 · 加载器 · 最后游玩 · 占用大小；选中显示备注时把备注接在末尾（先被省略）。 */
internal fun instanceMetaLine(
    instance: GameInstance,
    sizeBytes: Long?,
    showNotes: Boolean,
): String {
    val note =
        if (showNotes && instance.notes.isNotBlank()) {
            " · " + instance.notes.replace('\n', ' ')
        } else {
            ""
        }
    return instance.gameVersion.ifBlank { "版本未知" } +
        " · " +
        (if (instance.broken) "无法识别" else instance.loader.title) +
        " · " +
        lastPlayedText(instance.lastPlayed) +
        " · " +
        instanceSizeText(sizeBytes) +
        note
}

/** 最后游玩：相对时间，例如「3 天前」；从未游玩（或时间无法解析）显示「未游玩」。 */
private fun lastPlayedText(lastPlayed: String?): String {
    val played = lastPlayed?.takeIf { it.isNotBlank() } ?: return "未游玩"
    val instant = runCatching { Instant.parse(played) }.getOrNull() ?: return "未游玩"
    val seconds = Duration.between(instant, Instant.now()).seconds.coerceAtLeast(0)
    return when {
        seconds < SECONDS_PER_MINUTE -> "刚刚"
        seconds < SECONDS_PER_HOUR -> "${seconds / SECONDS_PER_MINUTE} 分钟前"
        seconds < SECONDS_PER_DAY -> "${seconds / SECONDS_PER_HOUR} 小时前"
        else -> "${seconds / SECONDS_PER_DAY} 天前"
    }
}

/** 占用文案：尚未统计完成时显示「统计中…」，目录读不到时如实显示「占用未知」。 */
private fun instanceSizeText(bytes: Long?): String =
    when {
        bytes == null -> "统计中…"
        bytes < 0 -> "占用未知"
        bytes >= BYTES_PER_GIB -> "%.1f GB".format(bytes / BYTES_PER_GIB.toFloat())
        bytes >= BYTES_PER_MIB -> "%.1f MB".format(bytes / BYTES_PER_MIB.toFloat())
        bytes >= BYTES_PER_KIB -> "%.0f KB".format(bytes / BYTES_PER_KIB.toFloat())
        else -> bytes.toString() + " B"
    }

/** 版本筛选按数字段从新到旧排列：26.3 在 1.21.8 之前，无法解析的段按 0 处理。 */
internal val instanceVersionOrder =
    Comparator<String> { left, right ->
        val first = versionSegments(left)
        val second = versionSegments(right)
        var result = 0
        for (index in 0 until maxOf(first.size, second.size)) {
            val diff = first.getOrElse(index) { 0 }.compareTo(second.getOrElse(index) { 0 })
            if (diff != 0) {
                result = -diff
                break
            }
        }
        if (result != 0) result else left.compareTo(right)
    }

private fun versionSegments(version: String): List<Int> =
    version
        .split('.', '-', '_', ' ')
        .map { it.takeWhile { char -> char.isDigit() }.toIntOrNull() ?: 0 }

/** 只读地检查目录是否还存在、是否为文件夹、是否可读；在 IO 线程调用。 */
internal fun probeGameDirectory(directory: GameDirectory): String =
    runCatching {
        val path = Path.of(directory.path)
        when {
            !Files.exists(path) -> "找不到游戏目录：" + directory.path
            !Files.isDirectory(path) -> "这不是文件夹：" + directory.path
            !Files.isReadable(path) -> "没有读取权限：" + directory.path
            else -> ""
        }
    }.getOrElse { "无法读取游戏目录：" + (it.message ?: directory.path) }
