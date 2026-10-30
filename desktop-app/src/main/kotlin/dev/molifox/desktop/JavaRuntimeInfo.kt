// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责 Java 运行时的文案与路径比较：版本标签、位数推断、来源与占用，以及路径省略。

package dev.molifox.desktop

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import dev.molifox.core.JavaRuntime
import dev.molifox.core.JavaRuntimeSource
import java.nio.file.Path
import kotlin.math.max

private const val JAVA_X86_BITS = 32
private const val JAVA_X64_BITS = 64
private const val RUNTIME_GIGABYTE_BYTES = 1024L * 1024 * 1024
private const val RUNTIME_GIGABYTE_DIVISOR = 1073741824f
private const val RUNTIME_MEGABYTE_DIVISOR = 1048576f

/** 版本标签：`<完整版本或主版本>（<位数> 位）`；位数取启动器探测结果，缺失时退回架构推断。 */
internal fun javaRuntimeLabel(runtime: JavaRuntime): String = javaRuntimeLabel(runtime, "x64")

/** 兼容调用方显式传入本机架构：探测结果里没有位数时用它兜底。 */
internal fun javaRuntimeLabel(
    runtime: JavaRuntime,
    fallbackArchitecture: String,
): String {
    if (runtime.majorVersion <= 0) return "未知版本"
    val version = runtime.version.ifBlank { runtime.majorVersion.toString() }
    return version + "（" + javaBits(runtime, fallbackArchitecture) + " 位）"
}

internal fun javaBits(
    runtime: JavaRuntime,
    fallbackArchitecture: String,
): String {
    if (runtime.bits == JAVA_X86_BITS || runtime.bits == JAVA_X64_BITS) {
        return runtime.bits.toString()
    }
    return when (runtime.architecture.lowercase()) {
        "amd64",
        "x86_64",
        "x64",
        "aarch64",
        "arm64",
        "ppc64le",
        "s390x",
        -> "64"
        "x86",
        "i386",
        "i486",
        "i586",
        "i686",
        "arm",
        "arm32",
        -> "32"
        else ->
            when (fallbackArchitecture.lowercase()) {
                "x86" -> "32"
                else -> "64"
            }
    }
}

/** 来源文案：启动器自己安装的 Java 标成「启动器安装」，与系统安装区分开。 */
internal fun javaSourceTitle(source: JavaRuntimeSource): String =
    when (source) {
        JavaRuntimeSource.Bundled -> "启动器安装"
        JavaRuntimeSource.System -> "系统"
        JavaRuntimeSource.Custom -> "自定义"
    }

/** 占用文案：只有启动器安装的运行时能可靠统计；未知时返回空串。 */
internal fun readableJavaSize(bytes: Long): String =
    when {
        bytes <= 0 -> ""
        bytes >= RUNTIME_GIGABYTE_BYTES -> "%.2f GB".format(bytes / RUNTIME_GIGABYTE_DIVISOR)
        else -> "%.0f MB".format(bytes / RUNTIME_MEGABYTE_DIVISOR)
    }

@Composable
internal fun sourceColor(source: JavaRuntimeSource): Color =
    if (source == JavaRuntimeSource.Bundled) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

/** 运行时标题：`Java 21.0.5（64 位）`；不可用条目直接给出原因。 */
internal fun javaRuntimeHeading(
    runtime: JavaRuntime,
    fallbackArchitecture: String = "x64",
): String {
    if (!runtime.usable) return "不可用的 Java（" + runtime.invalidReason + "）"
    val version = runtime.version.ifBlank { runtime.majorVersion.toString() }
    return "Java " + version + "（" + javaBits(runtime, fallbackArchitecture) + " 位）"
}

/** 当前 Java 的可读名称：空路径表示自动，找不到运行时按自定义处理。 */
internal fun javaChoiceName(
    path: String,
    runtimes: List<JavaRuntime>,
    ignoreCase: Boolean,
): String {
    if (path.isBlank()) return "自动按游戏版本匹配"
    return runtimes.firstOrNull { sameJavaPath(it.path, path, ignoreCase) }?.let {
        javaRuntimeHeading(it)
    } ?: "自定义 Java"
}

/** 路径比较：Windows 忽略大小写；解析失败时只比较原文。 */
internal fun sameJavaPath(
    left: String,
    right: String,
    ignoreCase: Boolean,
): Boolean {
    if (left.isBlank() || right.isBlank()) return false
    if (left.equals(right, ignoreCase)) return true
    return runCatching {
        val a = Path.of(left).toAbsolutePath().normalize()
        val b = Path.of(right).toAbsolutePath().normalize()
        a.toString().equals(b.toString(), ignoreCase)
    }.getOrDefault(false)
}

/** 从左侧省略路径，保留最后的目录段与文件名；文件名本身放不下时按可用宽度截断。 */
internal fun leftEllipsizedPath(
    path: String,
    maxWidthPx: Int,
    measurer: TextMeasurer,
    style: TextStyle,
): String {
    if (path.isEmpty() || maxWidthPx <= 0) return path

    fun fits(text: String): Boolean =
        measurer.measure(AnnotatedString(text), style, maxLines = 1, softWrap = false).size.width <=
            maxWidthPx
    if (fits(path)) return path
    val nameStart = max(path.lastIndexOf('/'), path.lastIndexOf('\\')) + 1
    var low = 0
    var high = nameStart
    var best = nameStart
    while (low <= high) {
        val mid = (low + high) / 2
        if (fits("…" + path.substring(mid))) {
            best = mid
            low = mid + 1
        } else {
            high = mid - 1
        }
    }
    return "…" + path.substring(best)
}
