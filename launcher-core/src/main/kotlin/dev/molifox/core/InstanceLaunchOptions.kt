// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责实例级启动设置的校验、回退与有效值解析。

package dev.molifox.core

import dev.molifox.core.i18n.I18n

private const val INSTANCE_SYSTEM_RESERVE_MB = 512
private const val INSTANCE_MEMORY_STEP_MB = 256

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
        I18n.t("core.instanceLaunchOptions.1", MIN_INSTANCE_MEMORY_MB, memoryLimit)
    }
    require(width == 0 || width in MIN_GAME_WINDOW_SIZE..MAX_GAME_WINDOW_SIZE) {
        I18n.t("core.instanceLaunchOptions.2", MIN_GAME_WINDOW_SIZE, MAX_GAME_WINDOW_SIZE)
    }
    require(height == 0 || height in MIN_GAME_WINDOW_SIZE..MAX_GAME_WINDOW_SIZE) {
        I18n.t("core.instanceLaunchOptions.3", MIN_GAME_WINDOW_SIZE, MAX_GAME_WINDOW_SIZE)
    }
    require(windowMode in gameWindowModes) { I18n.t("core.instanceLaunchOptions.4") }
    validateExtraJvmArguments(extraJvmArgs)
    extraGameArguments(AdvancedLaunchSettings(extraGameArgs = extraGameArgs))
}

/** 实例可设置的固定内存上限；本机内存较小时再按总内存留出 512 MB 给系统。 */
fun instanceMemoryLimitMb(totalMemoryMb: Int? = null): Int =
    totalMemoryMb?.let { total ->
        minOf(
            MAX_INSTANCE_MEMORY_MB,
            maxOf(MIN_INSTANCE_MEMORY_MB, total - INSTANCE_SYSTEM_RESERVE_MB),
        )
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
    (value.toInt() / INSTANCE_MEMORY_STEP_MB * INSTANCE_MEMORY_STEP_MB).coerceIn(
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
            I18n.t(
                "instA2.launchOptions.summary",
                memory.maximumMb,
                width,
                height,
                extraJvmArgs.ifBlank { I18n.t("core.instanceLaunchOptions.5") },
            )
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
    val memory =
        InstanceLaunchOptionResolver.memory(
            settings,
            instance,
            MemoryEnvironment(totalMemoryMb, availableMemoryMb, architecture),
            warnings,
        )
    val (width, height, fullscreen) =
        InstanceLaunchOptionResolver.window(settings, instance, launcherWindowSize, warnings)
    val extraJvmArgs = InstanceLaunchOptionResolver.jvmArguments(settings, instance, warnings)
    val gameArgs = InstanceLaunchOptionResolver.gameArguments(instance, advanced, warnings)

    return ResolvedLaunchOptions(
        memory = memory,
        width = width,
        height = height,
        fullscreen = fullscreen,
        extraJvmArgs = extraJvmArgs,
        extraGameArgs = gameArgs,
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
            warnings += I18n.t("core.instanceLaunchOptions.6", label, value, MIN_GAME_WINDOW_SIZE, MAX_GAME_WINDOW_SIZE)
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
        warnings += I18n.t("core.instanceLaunchOptions.7", label, value, default)
        default
    }

private data class MemoryEnvironment(
    val totalMemoryMb: Int,
    val availableMemoryMb: Int,
    val architecture: String,
)

private data class GameWindowOptions(
    val width: Int,
    val height: Int,
    val fullscreen: Boolean,
)

private object InstanceLaunchOptionResolver {
    fun memory(
        settings: LauncherSettings,
        instance: GameInstance,
        environment: MemoryEnvironment,
        warnings: MutableList<String>,
    ): GameMemoryAllocation {
        val (totalMemoryMb, availableMemoryMb, architecture) = environment
        val memoryLimit = instanceMemoryLimitMb(totalMemoryMb)
        val memoryMb = validMemoryMb(instance, memoryLimit, warnings)
        val initialMb = validInitialMb(instance, memoryLimit, warnings)
        val resolved = instance.copy(memoryMb = memoryMb, initialMemoryMb = initialMb)
        val allocation =
            effectiveGameMemory(
                settings,
                resolved,
                totalMemoryMb,
                availableMemoryMb,
                architecture,
            )
        appendMemoryWarning(instance, allocation, warnings)
        return allocation
    }

    fun window(
        settings: LauncherSettings,
        instance: GameInstance,
        launcherWindowSize: Pair<Int, Int>?,
        warnings: MutableList<String>,
    ): GameWindowOptions {
        val instanceWidth = windowValue(instance.width, I18n.t("core.instanceLaunchOptions.10"), warnings)
        val instanceHeight = windowValue(instance.height, I18n.t("core.instanceLaunchOptions.11"), warnings)
        val globalWidth = globalWindowValue(settings.width, 1280, I18n.t("core.instanceLaunchOptions.10"), warnings)
        val globalHeight = globalWindowValue(settings.height, 720, I18n.t("core.instanceLaunchOptions.11"), warnings)
        val fullscreenMode =
            if (instance.windowMode in gameWindowModes) {
                instance.windowMode
            } else {
                warnings += I18n.t("core.instanceLaunchOptions.12")
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
        val fullscreen = resolveFullscreen(settings, fullscreenMode)

        return GameWindowOptions(width, height, fullscreen)
    }

    private fun resolveFullscreen(
        settings: LauncherSettings,
        fullscreenMode: String,
    ): Boolean =
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

    fun jvmArguments(
        settings: LauncherSettings,
        instance: GameInstance,
        warnings: MutableList<String>,
    ): String {
        val requestedJvmArgs =
            instance.extraJvmArgs.ifBlank {
                settings.extraJvmArgs.ifBlank { DEFAULT_EXTRA_JVM_ARGS }
            }
        return try {
            extraJvmArguments(requestedJvmArgs)
            requestedJvmArgs
        } catch (failure: IllegalArgumentException) {
            val fallback =
                settings.extraJvmArgs
                    .takeIf { runCatching { extraJvmArguments(it) }.isSuccess }
                    ?.ifBlank { DEFAULT_EXTRA_JVM_ARGS }
                    ?: ""
            warnings +=
                if (fallback.isBlank()) {
                    I18n.t("instA2.launchOptions.jvmInvalidDefault", failure.message)
                } else {
                    I18n.t("instA2.launchOptions.jvmInvalidGlobal", failure.message)
                }
            fallback
        }
    }

    fun gameArguments(
        instance: GameInstance,
        advanced: AdvancedLaunchSettings,
        warnings: MutableList<String>,
    ): List<String> {
        val advancedGameArgs =
            try {
                extraGameArguments(advanced)
            } catch (failure: IllegalArgumentException) {
                warnings += I18n.t("core.instanceLaunchOptions.15", failure.message)
                emptyList()
            }
        val instanceGameArgs =
            if (instance.extraGameArgs.isBlank()) {
                emptyList()
            } else {
                try {
                    extraGameArguments(
                        AdvancedLaunchSettings(extraGameArgs = instance.extraGameArgs),
                    )
                } catch (failure: IllegalArgumentException) {
                    warnings += I18n.t("core.instanceLaunchOptions.16", failure.message)
                    emptyList()
                }
            }

        return advancedGameArgs + instanceGameArgs
    }
}
