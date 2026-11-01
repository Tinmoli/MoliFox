// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责自动和手动游戏内存分配。

package dev.molifox.core

import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries

private const val AUTOMATIC_MEMORY_STEP_MB = 256
private const val MINIMUM_AUTOMATIC_MEMORY_MB = 512
private const val MAXIMUM_AUTOMATIC_MEMORY_MB = 12288
private const val SYSTEM_RESERVE_MB = 512
private const val MILLIS_PER_GIB = 1024.0
private const val MINIMUM_PHYSICAL_MEMORY_MB = 1024
private const val MOD_EXTENSIONS = "jar,zip,litemod"
private const val BASE_MINIMUM_GIB = 0.5
private const val BASE_TARGET_ONE_GIB = 1.5
private const val BASE_TARGET_TWO_GIB = 2.7
private const val BASE_TARGET_THREE_GIB = 4.5
private const val MODS_PER_MINIMUM = 150.0
private const val MODS_PER_TARGET_ONE = 90.0
private const val MODS_PER_TARGET_TWO = 50.0
private const val MODS_PER_TARGET_THREE = 25.0
private const val FIRST_STAGE_RATIO = 1.0
private const val SECOND_STAGE_RATIO = 0.7
private const val THIRD_STAGE_RATIO = 0.4
private const val FOURTH_STAGE_RATIO = 0.15

/**
 * 自动内存分配：按实例的 mod 数量估算需求，再按当前可用内存分阶段给出。
 *
 * 需求公式来自 PCL CE/PCL2（可装 Mod 实例按 mod 数递增）；分配阶段参考 HMCL，
 * 先给系统留出 [SYSTEM_RESERVE_MB]，再按 100%/70%/40%/15% 逐段收紧，
 * 避免只看瞬时可用内存就把堆压到 768 MB 这类过低值。
 */
fun automaticMemoryMb(
    totalMb: Int,
    availableMb: Int,
    architecture: String = "x64",
    modCount: Int = 0,
): Int {
    val total = totalMb.coerceAtLeast(MINIMUM_PHYSICAL_MEMORY_MB)
    val available = availableMb.coerceIn(0, total)
    val usableMb = (available - SYSTEM_RESERVE_MB).coerceAtLeast(MINIMUM_AUTOMATIC_MEMORY_MB)
    val demand = memoryDemandMb(modCount.coerceAtLeast(0))
    var remainingMb = usableMb.toDouble()
    var allocatedMb = 0.0
    for ((deltaMb, ratio) in demand.roundedStages()) {
        allocatedMb += minOf(remainingMb * ratio, deltaMb)
        remainingMb -= deltaMb / ratio
        if (remainingMb < MINIMUM_AUTOMATIC_MEMORY_MB / 2.0) break
    }
    // 可用内存不足时按实际能给的数量分配，不硬塞需求值；启动警告会提示差距。
    val suggested = allocatedMb.coerceAtMost(usableMb.toDouble()).toInt()
    val stepped = suggested / AUTOMATIC_MEMORY_STEP_MB * AUTOMATIC_MEMORY_STEP_MB
    val javaLimit =
        if (architecture.lowercase() in setOf("x86", "i386", "i686", "32")) {
            MINIMUM_PHYSICAL_MEMORY_MB
        } else {
            MAXIMUM_AUTOMATIC_MEMORY_MB
        }
    return stepped.coerceIn(MINIMUM_AUTOMATIC_MEMORY_MB, javaLimit)
}

/** 该 mod 数量下“勉强能带得动”的建议内存（PCL 目标 1），用于低于它时告警。 */
fun recommendedAutomaticMemoryMb(modCount: Int): Int = memoryDemandMb(modCount.coerceAtLeast(0)).targetOneMb

/** PCL 的 mod 数量需求：最低、目标 1/2/3（MB）。 */
private data class MemoryDemand(
    val minimumMb: Int,
    val targetOneMb: Int,
    val targetTwoMb: Int,
    val targetThreeMb: Int,
) {
    fun roundedStages(): List<Pair<Double, Double>> =
        listOf(
            targetOneMb.toDouble() to FIRST_STAGE_RATIO,
            (targetTwoMb - targetOneMb).toDouble() to SECOND_STAGE_RATIO,
            (targetThreeMb - targetTwoMb).toDouble() to THIRD_STAGE_RATIO,
            targetThreeMb.toDouble() to FOURTH_STAGE_RATIO,
        )
}

private fun memoryDemandMb(modCount: Int): MemoryDemand {
    val mods = modCount.toDouble()
    return MemoryDemand(
        minimumMb = gibToMb(BASE_MINIMUM_GIB + mods / MODS_PER_MINIMUM),
        targetOneMb = gibToMb(BASE_TARGET_ONE_GIB + mods / MODS_PER_TARGET_ONE),
        targetTwoMb = gibToMb(BASE_TARGET_TWO_GIB + mods / MODS_PER_TARGET_TWO),
        targetThreeMb = gibToMb(BASE_TARGET_THREE_GIB + mods / MODS_PER_TARGET_THREE),
    )
}

private fun gibToMb(gib: Double): Int = (gib * MILLIS_PER_GIB).toInt()

/** 统计游戏目录下的 mod 文件；目录不存在或读取失败按 0 处理。 */
internal fun countInstanceMods(gamePath: Path): Int {
    val directory = gamePath.resolve("mods")
    if (!directory.isDirectory()) return 0
    return runCatching {
        directory.listDirectoryEntries().count {
            it.isRegularFile() && it.extension.lowercase() in MOD_EXTENSIONS.split(',')
        }
    }.getOrDefault(0)
}

fun effectiveGameMemory(
    settings: LauncherSettings,
    instance: GameInstance,
    platform: PlatformServices,
    architecture: String = platform.architecture,
): GameMemoryAllocation =
    effectiveGameMemory(
        settings,
        instance,
        platform.totalMemoryMb,
        platform.availableMemoryMb,
        architecture,
    )

/** 与平台对象解耦的同一套解析逻辑，供启动计划与测试复用。 */
fun effectiveGameMemory(
    settings: LauncherSettings,
    instance: GameInstance,
    totalMemoryMb: Int,
    availableMemoryMb: Int,
    architecture: String = "x64",
): GameMemoryAllocation {
    // 实例已有明确分配值时保持手动模式，只有用户选择后才采用自动分配。
    val automatic = instance.autoMemory ?: (instance.memoryMb <= 0 && settings.autoMemory)
    val maximum =
        if (automatic) {
            automaticMemoryMb(
                totalMemoryMb,
                availableMemoryMb,
                architecture,
                instance.modCount,
            )
        } else {
            instance.memoryMb.takeIf { it > 0 } ?: settings.memoryMb
        }
    val initial =
        (instance.initialMemoryMb.takeIf { it > 0 } ?: settings.initialMemoryMb).coerceIn(
            128,
            maximum,
        )
    return GameMemoryAllocation(maximum, initial, automatic)
}
