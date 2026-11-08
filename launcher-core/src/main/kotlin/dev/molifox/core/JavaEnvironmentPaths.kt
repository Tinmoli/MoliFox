// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

package dev.molifox.core

import java.nio.file.InvalidPathException
import java.nio.file.Path

private const val MAX_JAVA_ENVIRONMENT_EXPANSIONS = 8
private const val MAX_JAVA_ENVIRONMENT_PATH_CHARACTERS = 32767
private val WINDOWS_JAVA_ENVIRONMENT_REFERENCE = Regex("%([^%]+)%")
private val UNIX_JAVA_ENVIRONMENT_REFERENCE =
    Regex("\\$\\{([A-Za-z_][A-Za-z0-9_]*)}|\\$([A-Za-z_][A-Za-z0-9_]*)")

/** 仅解释环境来源的路径；显式配置仍按用户保存的原始文件名处理。 */
internal fun expandJavaEnvironmentPath(
    raw: String,
    windows: Boolean,
    environment: Map<String, String>,
): String {
    val pattern = if (windows) WINDOWS_JAVA_ENVIRONMENT_REFERENCE else UNIX_JAVA_ENVIRONMENT_REFERENCE
    var text = raw.trim().trim('"').trim()
    val seen = mutableSetOf<String>()
    var remaining = MAX_JAVA_ENVIRONMENT_EXPANSIONS
    while (remaining-- > 0 && seen.add(text)) {
        val expanded =
            pattern.replace(text) { match ->
                val name = match.groupValues.drop(1).first { it.isNotEmpty() }
                environment[name]
                    ?: environment.entries.firstOrNull { windows && it.key.equals(name, true) }?.value
                    ?: match.value
            }
        if (expanded == text || expanded.length > MAX_JAVA_ENVIRONMENT_PATH_CHARACTERS) break
        text = expanded.trim().trim('"').trim()
    }
    return text
}

/** 一条自动发现路径无效只影响该条；日志保留来源和原因，不记录整个环境变量内容。 */
internal fun LauncherStore.javaDiscoveryPath(
    raw: String,
    source: String,
    environment: Map<String, String> = System.getenv(),
): Path? =
    expandJavaEnvironmentPath(raw, platform.osName == "windows", environment)
        .takeIf { it.isNotBlank() }
        ?.let { text ->
            runCatching { Path.of(text) }.getOrElse { failure ->
                if (failure !is InvalidPathException) throw failure
                log("Skipped invalid Java discovery path [$source]: ${failure.reason} (index ${failure.index})", true)
                null
            }
        }
