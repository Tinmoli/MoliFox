// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责 MultiMC/Prism 的组件清单与 instance.cfg 生成（内存、JVM、窗口与实例名）。

package dev.molifox.core

private const val FALLBACK_EXPORT_INSTANCE_NAME = "整合包实例"

/** MultiMC / Prism 组件 uid。 */
internal fun multimcLoaderUid(loader: LoaderType): String? =
    when (loader) {
        LoaderType.Vanilla -> null
        LoaderType.Fabric -> "net.fabricmc.fabric-loader"
        LoaderType.Quilt -> "org.quiltmc.quilt-loader"
        LoaderType.Forge -> "net.minecraftforge"
        LoaderType.NeoForge -> "net.neoforged"
    }

internal fun multimcManifest(plan: MrpackExportPlan): Map<String, Any> {
    val gameVersion = plan.components.gameVersion
    require(gameVersion.isNotBlank()) { "实例缺少游戏版本，不能导出 MultiMC/Prism 整合包" }
    val loaderUid = multimcLoaderUid(plan.components.loader)
    // 空版本会让 Prism 无法补全组件，导出的包连本启动器都读不回来：导出前就拒绝。
    require(loaderUid == null || plan.components.loaderVersion.isNotBlank()) {
        "加载器版本未知，不能导出 MultiMC/Prism 整合包；请先用原启动器确认加载器版本"
    }
    val components = mutableListOf<Map<String, Any>>()
    components +=
        mapOf(
            "uid" to "net.minecraft",
            "version" to gameVersion,
            "important" to true,
        )
    if (loaderUid != null) {
        components +=
            mapOf(
                "uid" to loaderUid,
                "version" to plan.components.loaderVersion,
                "important" to true,
            )
    }
    return mapOf("formatVersion" to 1, "components" to components)
}

/** 实例级设置按 Prism 的键写回；0 / 空值表示跟随全局，不写对应键。 */
internal fun multimcInstanceConfig(
    plan: MrpackExportPlan,
    iconKey: String,
): String {
    val instance = plan.instance
    val memory = instance.memoryMb.takeIf { it in MIN_INSTANCE_MEMORY_MB..MAX_INSTANCE_MEMORY_MB }
    val initial = exportedInitialMemory(instance.initialMemoryMb, memory)
    val jvm = exportedJvmArguments(instance.extraJvmArgs)
    val width = instance.width.takeIf { it in MIN_GAME_WINDOW_SIZE..MAX_GAME_WINDOW_SIZE }
    val height = instance.height.takeIf { it in MIN_GAME_WINDOW_SIZE..MAX_GAME_WINDOW_SIZE }
    // MultiMC/Prism 的自由实例：OneSix + 由 mmc-pack.json 声明的组件。
    return buildString {
        appendLine("[General]")
        appendLine("ConfigVersion=1.4")
        appendLine("InstanceType=OneSix")
        appendLine("name=${exportedInstanceName(plan.metadata.name)}")
        appendLine("iconKey=$iconKey")
        appendLine("OverrideMemory=${memory != null}")
        if (memory != null) appendLine("MaxMemAlloc=$memory")
        if (initial != null) appendLine("MinMemAlloc=$initial")
        appendLine("OverrideJavaArgs=${jvm != null}")
        if (jvm != null) appendLine("JvmArgs=$jvm")
        appendLine("OverrideGameTime=false")
        appendLine("joinServerOnLaunch=false")
        if (width != null) appendLine("MinecraftWinWidth=$width")
        if (height != null) appendLine("MinecraftWinHeight=$height")
    }
}

/** 实例名要能被自家导入端接受（≤80 字），超出时截断而不是写出导不回的包。 */
private fun exportedInstanceName(name: String): String {
    val singleLine =
        name
            .map { if (it.isISOControl()) ' ' else it }
            .joinToString("")
            .trim()
            .ifBlank { FALLBACK_EXPORT_INSTANCE_NAME }
    if (singleLine.length <= MAX_MULTIMC_INSTANCE_NAME_CHARS) return singleLine
    val end =
        if (singleLine[MAX_MULTIMC_INSTANCE_NAME_CHARS - 1].isHighSurrogate()) {
            MAX_MULTIMC_INSTANCE_NAME_CHARS - 1
        } else {
            MAX_MULTIMC_INSTANCE_NAME_CHARS
        }
    return singleLine.substring(0, end).trimEnd()
}

/** 初始内存只在显式最大内存存在时写入，并夹到合法区间；0 表示跟随全局。 */
private fun exportedInitialMemory(
    value: Int,
    memory: Int?,
): Int? {
    if (memory == null || value <= 0) return null
    return value.coerceIn(MIN_PACK_INITIAL_MEMORY_MB, memory)
}

/** JVM 参数必须是单行；含启动器保留参数时拒绝导出，而不是静默丢弃。 */
private fun exportedJvmArguments(value: String): String? {
    val singleLine = value.replace(Regex("[\\r\\n]"), " ").trim()
    if (singleLine.isEmpty()) return null
    val failure = runCatching { validateExtraJvmArguments(singleLine) }.exceptionOrNull()
    require(failure == null) {
        "实例的额外 JVM 参数无法写入 MultiMC/Prism 整合包：${failure?.message}"
    }
    return singleLine
}
