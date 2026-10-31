// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责解析 MultiMC/Prism 的 instance.cfg：实例名、通用键值、名称回退与文本读取。

package dev.molifox.core

import java.util.zip.ZipFile

/** 与实例创建校验共用同一个名称上限：导入、导出和新建实例必须一致。 */
internal const val MAX_MULTIMC_INSTANCE_NAME_CHARS = 80

private const val MAX_MULTIMC_METADATA_BYTES = 1024 * 1024
private const val MULTIMC_HEX_RADIX = 16
private const val FALLBACK_INSTANCE_NAME = "MultiMC 实例"
private const val GENERAL_SECTION = "General"

/** cfg 里表示布尔开启的 `key=true`（Prism 写小写，这里兼容大小写与空白）。 */
internal fun isMultiMcEnabled(value: String?): Boolean =
    value.orEmpty().trim().equals("true", ignoreCase = true)

/** instance.cfg 解析后的导入结果：名称、预览摘要与两类可迁移设置。 */
internal data class MultiMcInstanceSettings(
    val name: String,
    val summary: String,
    val launchOptions: PackLaunchOptions?,
    val instanceDefaults: PackInstanceDefaults?,
)

/**
 * 读取 instance.cfg；文件缺失时按 Prism 习惯容忍，名称回退到实例目录名或压缩包名。
 * 启动前命令、自动进服与忽略 Java 兼容性只生成提示，不改变实例设置。
 */
internal fun readMultiMcInstanceConfig(
    zip: ZipFile,
    prefix: String,
    fallbackName: String,
): MultiMcInstanceSettings {
    val ini = multiMcText(zip, "${prefix}instance.cfg")?.let(::parseMultiMcIni).orEmpty()
    val warnings = mutableListOf<String>()
    val fallback = sanitizeMultiMcName(fallbackName)
    val name = readMultiMcName(ini, fallback, warnings)
    val launchOptions = readMultiMcLaunchOptions(ini, warnings)
    val defaults = readMultiMcInstanceDefaults(ini, warnings)
    val summary = multiMcSummary(launchOptions, defaults, readMultiMcUnmigratedNotes(ini), warnings)
    return MultiMcInstanceSettings(name, summary, launchOptions, defaults)
}

/** 解析 INI 的 `[General]` 段：键名大小写不敏感，值去掉首尾空白与成对引号。 */
internal fun parseMultiMcIni(text: String): Map<String, String> {
    val values = linkedMapOf<String, String>()
    var section = GENERAL_SECTION
    for (line in text.lineSequence()) {
        val trimmed = line.trim()
        if (trimmed.startsWith('[') && trimmed.endsWith(']')) {
            section = trimmed.substring(1, trimmed.length - 1)
        } else if (isMultiMcIniEntry(trimmed, section)) {
            val key = trimmed.substringBefore('=').trim().lowercase()
            if (key.isNotEmpty() && !values.containsKey(key)) {
                values[key] = trimmed.substringAfter('=').trim().removeSurrounding("\"")
            }
        }
    }
    return values
}

/** 空白、注释行与其它段的键值都不算 [General] 段的配置项。 */
private fun isMultiMcIniEntry(
    trimmed: String,
    section: String,
): Boolean =
    section == GENERAL_SECTION &&
        '=' in trimmed &&
        !trimmed.startsWith(';') &&
        !trimmed.startsWith('#')

/** 还原 instance.cfg 中以 \xHHHH 形式保存的 Unicode 字符。 */
internal fun decodeMultiMcName(name: String): String =
    name.replace(Regex("\\\\x([0-9a-fA-F]{1,4})")) {
        it.groupValues[1]
            .toInt(MULTIMC_HEX_RADIX)
            .toChar()
            .toString()
    }

/** 读取压缩包内的小体积文本元数据，拒绝缺失或超限条目。 */
internal fun multiMcText(
    zip: ZipFile,
    name: String,
): String? {
    val entry = zip.getEntry(name) ?: return null
    require(entry.size in 1..MAX_MULTIMC_METADATA_BYTES.toLong()) { "MultiMC/Prism 元数据过大或为空" }
    val bytes = zip.getInputStream(entry).use { it.readNBytes(MAX_MULTIMC_METADATA_BYTES + 1) }
    require(bytes.size <= MAX_MULTIMC_METADATA_BYTES) { "MultiMC/Prism 元数据过大" }
    return bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF")
}

/** 没有 instance.cfg 时的名称来源：外层文件夹名，其次是压缩包名。 */
internal fun multiMcFallbackName(
    prefix: String,
    archive: String,
): String =
    prefix.trimEnd('/').substringAfterLast('/').ifBlank {
        archive.substringAfterLast('/').substringAfterLast('\\').substringBeforeLast('.')
    }

/** 显式名称无效时保留失败；名称缺失属于可容忍的旧包，回退并提示。 */
private fun readMultiMcName(
    ini: Map<String, String>,
    fallback: String,
    warnings: MutableList<String>,
): String {
    val raw = decodeMultiMcName(ini["name"].orEmpty()).trim()
    if (raw.isEmpty()) {
        warnings += "instance.cfg 缺少实例名，已使用「$fallback」"
        return fallback
    }
    require(
        raw.length <= MAX_MULTIMC_INSTANCE_NAME_CHARS && raw.none { it.isISOControl() },
    ) {
        "MultiMC/Prism 实例名称过长或包含控制字符（最多 $MAX_MULTIMC_INSTANCE_NAME_CHARS 字）"
    }
    return raw
}

/** 回退名称来自目录或文件，先去掉控制字符并截断，保证能通过实例名校验。 */
private fun sanitizeMultiMcName(value: String): String {
    val cleaned =
        value
            .map { if (it.isISOControl()) ' ' else it }
            .joinToString("")
            .trim()
            .ifBlank { FALLBACK_INSTANCE_NAME }
    if (cleaned.length <= MAX_MULTIMC_INSTANCE_NAME_CHARS) return cleaned
    val end =
        if (cleaned[MAX_MULTIMC_INSTANCE_NAME_CHARS - 1].isHighSurrogate()) {
            MAX_MULTIMC_INSTANCE_NAME_CHARS - 1
        } else {
            MAX_MULTIMC_INSTANCE_NAME_CHARS
        }
    return cleaned.substring(0, end).trimEnd()
}
