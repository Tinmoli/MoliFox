// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责实例级启动设置的校验、回退与有效值解析。

package dev.molifox.core

const val MIN_INSTANCE_MEMORY_MB = 512
const val MAX_INSTANCE_MEMORY_MB = 32768
const val MIN_GAME_WINDOW_SIZE = 320
const val MAX_GAME_WINDOW_SIZE = 7680

private val gameWindowModes = setOf("inherit", "windowed", "fullscreen")

/**
 * 实例启动设置是否可用：内存、窗口宽高为 0 表示跟随全局。
 * [totalMemoryMb] 不为 null 时还会拒绝超过本机可用预算的内存，避免保存后必然启动失败。
 */
fun validateInstanceLaunchOptions(
    memoryMb: Int,
    width: Int,
    height: Int,
    windowMode: String,
    extraJvmArgs: String,
    extraGameArgs: String,
    totalMemoryMb: Int? = null,
) {
    val memoryLimit = instanceMemoryLimitMb(totalMemoryMb)
    require(memoryMb == 0 || memoryMb in MIN_INSTANCE_MEMORY_MB..memoryLimit) {
        "最大内存需为 0（跟随全局）或 $MIN_INSTANCE_MEMORY_MB–$memoryLimit MB"
    }
    require(width == 0 || width in MIN_GAME_WINDOW_SIZE..MAX_GAME_WINDOW_SIZE) {
        "游戏窗口宽度需为 0 或 $MIN_GAME_WINDOW_SIZE–$MAX_GAME_WINDOW_SIZE"
    }
    require(height == 0 || height in MIN_GAME_WINDOW_SIZE..MAX_GAME_WINDOW_SIZE) {
        "游戏窗口高度需为 0 或 $MIN_GAME_WINDOW_SIZE–$MAX_GAME_WINDOW_SIZE"
    }
    require(windowMode in gameWindowModes) { "窗口模式无效" }
    validateExtraJvmArguments(extraJvmArgs)
    extraGameArguments(AdvancedLaunchSettings(extraGameArgs = extraGameArgs))
}

/** 实例可设置的固定内存上限；本机内存较小时再按总内存留出 512 MB 给系统。 */
fun instanceMemoryLimitMb(totalMemoryMb: Int? = null): Int =
    totalMemoryMb?.let { total ->
        minOf(MAX_INSTANCE_MEMORY_MB, maxOf(MIN_INSTANCE_MEMORY_MB, total - 512))
    } ?: MAX_INSTANCE_MEMORY_MB

/** 实例最大内存策略：继承全局、自动分配、手动指定；只有手动分配会保存显式数值。 */
enum class InstanceMemoryPolicy {
    Inherit,
    Automatic,
    Manual,
}

/**
 * 从实例记录解析内存策略。旧数据没有显式策略却有正数内存时按「手动分配」处理，
 * 避免升级后静默丢掉用户原来的数值。
 */
fun instanceMemoryPolicy(instance: GameInstance): InstanceMemoryPolicy =
    when {
        instance.autoMemory == true -> InstanceMemoryPolicy.Automatic
        instance.autoMemory == false -> InstanceMemoryPolicy.Manual
        instance.memoryMb > 0 -> InstanceMemoryPolicy.Manual
        else -> InstanceMemoryPolicy.Inherit
    }

/**
 * 切换内存策略：继承全局与自动分配都清掉显式值，避免切回手动时又把旧数字带回来；
 * 手动分配在还没有显式值时以 [defaultMemoryMb]（通常是全局最大值）起步。
 */
fun applyInstanceMemoryPolicy(
    instance: GameInstance,
    policy: InstanceMemoryPolicy,
    defaultMemoryMb: Int,
): GameInstance =
    when (policy) {
        InstanceMemoryPolicy.Inherit -> instance.copy(autoMemory = null, memoryMb = 0)
        InstanceMemoryPolicy.Automatic -> instance.copy(autoMemory = true, memoryMb = 0)
        InstanceMemoryPolicy.Manual ->
            instance.copy(
                autoMemory = false,
                memoryMb =
                    instance.memoryMb.takeIf { it > 0 }
                        ?: defaultMemoryMb.coerceIn(MIN_INSTANCE_MEMORY_MB, MAX_INSTANCE_MEMORY_MB),
            )
    }

/** 实例内存滑块的取值：按 256 MB 步进并限制在本机预算内。 */
fun instanceMemorySliderValue(
    value: Float,
    totalMemoryMb: Int? = null,
): Int =
    (value.toInt() / 256 * 256).coerceIn(
        MIN_INSTANCE_MEMORY_MB,
        instanceMemoryLimitMb(totalMemoryMb),
    )

/** 窗口宽高输入框回填值：0 表示跟随全局，显示为空，不再显示字面 0。 */
fun instanceLaunchNumberField(value: Int): String = value.takeIf { it > 0 }?.toString() ?: ""

/** 解析窗口宽高输入：空白或 0 都表示跟随全局（返回 0）；非数字返回 null 交给范围校验。 */
fun parseInstanceLaunchNumberInput(text: String): Int? {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return 0
    return trimmed.toIntOrNull()
}

/** 输入框实时归一化：输入 0 等同于留空，避免把字面 0 写进「跟随全局」的字段。 */
fun normalizeInstanceLaunchNumberInput(text: String): String =
    if (parseInstanceLaunchNumberInput(text) == 0) "" else text.trim()

/**
 * 真正进入启动命令的有效值：实例显式值优先，0 / 空值回落到全局；
 * 无法解析的持久值只产生警告并回退，绝不阻止启动。
 */
data class ResolvedLaunchOptions(
    val memory: GameMemoryAllocation,
    val width: Int,
    val height: Int,
    val fullscreen: Boolean,
    val extraJvmArgs: String,
    val extraGameArgs: List<String>,
    val warnings: List<String> = emptyList(),
) {
    /** 启动日志的一行摘要，格式与「启动参数：内存 4096 MB，窗口 1920×1080，额外 JVM -XX…」一致。 */
    val summary: String
        get() =
            "启动参数：内存 ${memory.maximumMb} MB，窗口 $width×$height，额外 JVM " +
                extraJvmArgs.ifBlank { "无" }
}

fun resolveInstanceLaunchOptions(
    settings: LauncherSettings,
    instance: GameInstance,
    launcherWindowSize: Pair<Int, Int>?,
    totalMemoryMb: Int,
    availableMemoryMb: Int,
    architecture: String = "x64",
    advanced: AdvancedLaunchSettings = effectiveLaunchSettings(settings, instance),
): ResolvedLaunchOptions {
    val warnings = mutableListOf<String>()
    val memoryLimit = instanceMemoryLimitMb(totalMemoryMb)
    val memoryMb =
        when {
            instance.autoMemory == true -> instance.memoryMb
            instance.memoryMb == 0 -> 0
            instance.memoryMb in MIN_INSTANCE_MEMORY_MB..memoryLimit -> instance.memoryMb
            else -> {
                warnings +=
                    "实例最大内存 ${instance.memoryMb} MB 超出 " +
                    "$MIN_INSTANCE_MEMORY_MB–$memoryLimit MB，已跟随全局设置"
                0
            }
        }
    val initialMb =
        when {
            instance.initialMemoryMb == 0 -> 0
            instance.initialMemoryMb in 128..minOf(MAX_INSTANCE_MEMORY_MB, memoryLimit) ->
                instance.initialMemoryMb
            else -> {
                warnings += "实例初始内存 ${instance.initialMemoryMb} MB 无效，已跟随全局设置"
                0
            }
        }
    val resolved = instance.copy(memoryMb = memoryMb, initialMemoryMb = initialMb)
    val memory =
        effectiveGameMemory(
            settings,
            resolved,
            totalMemoryMb,
            availableMemoryMb,
            architecture,
        )

    val instanceWidth = windowValue(instance.width, "宽度", warnings)
    val instanceHeight = windowValue(instance.height, "高度", warnings)
    val globalWidth = globalWindowValue(settings.width, 1280, "宽度", warnings)
    val globalHeight = globalWindowValue(settings.height, 720, "高度", warnings)
    val fullscreenMode =
        if (instance.windowMode in gameWindowModes) {
            instance.windowMode
        } else {
            warnings += "实例窗口模式无效，已跟随全局设置"
            "inherit"
        }
    val width =
        instanceWidth.takeIf { it > 0 }
            ?: (if (settings.gameWindowMode == "launcher") launcherWindowSize?.first else null)
            ?: globalWidth
    val height =
        instanceHeight.takeIf { it > 0 }
            ?: (if (settings.gameWindowMode == "launcher") launcherWindowSize?.second else null)
            ?: globalHeight
    val fullscreen =
        when (fullscreenMode) {
            "fullscreen" -> true
            "windowed" -> false
            else ->
                when (settings.gameWindowMode) {
                    "fullscreen" -> true
                    "launcher" -> false
                    else -> settings.fullscreen
                }
        }

    val requestedJvmArgs = instance.extraJvmArgs.ifBlank { settings.extraJvmArgs }
    val extraJvmArgs =
        try {
            extraJvmArguments(requestedJvmArgs)
            requestedJvmArgs
        } catch (failure: IllegalArgumentException) {
            val fallback =
                settings.extraJvmArgs.takeIf {
                    runCatching { extraJvmArguments(it) }.isSuccess
                } ?: ""
            warnings +=
                "额外 JVM 参数无效（${failure.message}），已改用" +
                (if (fallback.isBlank()) "默认值" else "全局设置")
            fallback
        }
    val advancedGameArgs =
        try {
            extraGameArguments(advanced)
        } catch (failure: IllegalArgumentException) {
            warnings += "高级启动设置里的附加游戏参数无效（${failure.message}），已忽略"
            emptyList()
        }
    val instanceGameArgs =
        if (instance.extraGameArgs.isBlank()) {
            emptyList()
        } else {
            try {
                extraGameArguments(AdvancedLaunchSettings(extraGameArgs = instance.extraGameArgs))
            } catch (failure: IllegalArgumentException) {
                warnings += "实例额外游戏参数无效（${failure.message}），已忽略"
                emptyList()
            }
        }

    return ResolvedLaunchOptions(
        memory = memory,
        width = width,
        height = height,
        fullscreen = fullscreen,
        extraJvmArgs = extraJvmArgs,
        extraGameArgs = advancedGameArgs + instanceGameArgs,
        warnings = warnings,
    )
}

/** 0 表示继承；越界值改为 0 并留下警告，保证坏值不会进入启动命令。 */
private fun windowValue(
    value: Int,
    label: String,
    warnings: MutableList<String>,
): Int =
    when {
        value == 0 -> 0
        value in MIN_GAME_WINDOW_SIZE..MAX_GAME_WINDOW_SIZE -> value
        else -> {
            warnings += "游戏窗口$label $value 超出 $MIN_GAME_WINDOW_SIZE–$MAX_GAME_WINDOW_SIZE，已跟随全局设置"
            0
        }
    }

/** 全局窗口值异常时使用同一套默认尺寸，避免把 0 或越界值写进游戏参数。 */
private fun globalWindowValue(
    value: Int,
    default: Int,
    label: String,
    warnings: MutableList<String>,
): Int =
    if (value in MIN_GAME_WINDOW_SIZE..MAX_GAME_WINDOW_SIZE) {
        value
    } else {
        warnings += "全局游戏窗口$label $value 无效，已改用 $default"
        default
    }
