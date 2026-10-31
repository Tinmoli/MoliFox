// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责把 MultiMC/Prism 的 JvmArgs、内存与窗口设置转换成实例可用的导入默认值。

package dev.molifox.core

private const val MAX_MULTIMC_JVM_ARGS_CHARS = 4096

/** JvmArgs 转成实例附加参数；会覆盖内存或入口的参数丢弃并警告，不阻断导入。 */
internal fun readMultiMcLaunchOptions(
    ini: Map<String, String>,
    warnings: MutableList<String>,
): PackLaunchOptions? {
    val text = ini["jvmargs"].orEmpty().trim()
    if (text.isEmpty()) return null
    val arguments =
        runCatching { splitArguments(text) }.getOrElse {
            throw IllegalArgumentException("instance.cfg 的 JvmArgs 无法解析：${it.message}")
        }
    val (accepted, dropped) = arguments.partition(::isImportableJvmArgument)
    if (dropped.isNotEmpty()) {
        warnings += "已忽略会覆盖内存或游戏入口的 JVM 参数：${dropped.joinToString(" ")}"
    }
    val quoted = accepted.takeIf { it.isNotEmpty() }?.let(::multiMcArgumentsText)
    val importable = quoted != null && quoted.length <= MAX_MULTIMC_JVM_ARGS_CHARS
    if (accepted.isNotEmpty() && !importable) {
        warnings += "instance.cfg 的 JVM 参数无法无损导入，已忽略"
    }
    return if (importable) {
        PackLaunchOptions(accepted, emptyList(), isMultiMcEnabled(ini["overridejavaargs"]))
    } else {
        null
    }
}

/** 复用实例参数编辑器同一套引号规则；无法无损表示时返回 null 让调用方忽略。 */
private fun multiMcArgumentsText(arguments: List<String>): String? =
    runCatching { packArgumentsText(arguments) }.getOrNull()

/** 与 extraJvmArguments 的保留参数规则一致：这些参数改由内存设置与启动命令决定。 */
private fun isImportableJvmArgument(value: String): Boolean =
    !value.startsWith('@') &&
        value !in setOf("-cp", "-classpath", "--class-path", "-jar") &&
        !value.startsWith("-Xmx") &&
        !value.startsWith("-Xms")

/** 内存与窗口只在原包声明覆盖时迁移；越界值归零（跟随全局）并警告。 */
internal fun readMultiMcInstanceDefaults(
    ini: Map<String, String>,
    warnings: MutableList<String>,
): PackInstanceDefaults? {
    val overrideMemory = isMultiMcEnabled(ini["overridememory"])
    val memory = if (overrideMemory) importableMaximumMemory(ini["maxmemalloc"], warnings) else 0
    val initial =
        if (overrideMemory) importableInitialMemory(ini["minmemalloc"], memory, warnings) else 0
    val defaults =
        PackInstanceDefaults(
            memory,
            initial,
            importableWindowSize(ini["minecraftwinwidth"], "宽度", warnings),
            importableWindowSize(ini["minecraftwinheight"], "高度", warnings),
        )
    return defaults.takeUnless { it.isEmpty }
}

private fun importableMaximumMemory(
    raw: String?,
    warnings: MutableList<String>,
): Int {
    val value = raw.orEmpty().trim().toIntOrNull() ?: return 0
    if (value == 0) return 0
    if (value in MIN_INSTANCE_MEMORY_MB..MAX_INSTANCE_MEMORY_MB) return value
    warnings +=
        "instance.cfg 的最大内存 $value MB 超出 " +
        "$MIN_INSTANCE_MEMORY_MB–$MAX_INSTANCE_MEMORY_MB，已改为跟随全局"
    return 0
}

private fun importableInitialMemory(
    raw: String?,
    maximumMb: Int,
    warnings: MutableList<String>,
): Int {
    val value = raw.orEmpty().trim().toIntOrNull() ?: return 0
    if (value == 0) return 0
    if (value !in MIN_PACK_INITIAL_MEMORY_MB..MAX_INSTANCE_MEMORY_MB) {
        warnings +=
            "instance.cfg 的初始内存 $value MB 超出 " +
            "$MIN_PACK_INITIAL_MEMORY_MB–$MAX_INSTANCE_MEMORY_MB，已改为跟随全局"
        return 0
    }
    if (maximumMb >= MIN_INSTANCE_MEMORY_MB && value > maximumMb) {
        warnings += "instance.cfg 的初始内存 $value MB 大于最大内存 $maximumMb MB，已按最大内存写入"
        return maximumMb
    }
    return value
}

private fun importableWindowSize(
    raw: String?,
    label: String,
    warnings: MutableList<String>,
): Int {
    val value = raw.orEmpty().trim().toIntOrNull() ?: return 0
    if (value == 0) return 0
    if (value in MIN_GAME_WINDOW_SIZE..MAX_GAME_WINDOW_SIZE) return value
    warnings +=
        "instance.cfg 的游戏窗口$label $value 超出 " +
        "$MIN_GAME_WINDOW_SIZE–$MAX_GAME_WINDOW_SIZE，已改为跟随全局"
    return 0
}
