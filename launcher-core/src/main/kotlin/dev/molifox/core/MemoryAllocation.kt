// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责自动和手动游戏内存分配。

package dev.molifox.core

/** 每次启动重新计算，给系统、启动器与堆外分配留出余量。 */
fun automaticMemoryMb(
    totalMb: Int,
    availableMb: Int,
    architecture: String = "x64",
): Int {
    val total = totalMb.coerceAtLeast(1024)
    val available = availableMb.coerceIn(0, total)
    val systemReserve = maxOf(1024, total / 8)
    val budget =
        minOf((available - 512).coerceAtLeast(512), (total - systemReserve).coerceAtLeast(512))
    val suggested = minOf(budget * 3L / 5, total / 2L, 12288L).toInt()
    val javaLimit =
        if (architecture.lowercase() in setOf("x86", "i386", "i686", "32")) 1024 else 12288
    return (suggested / 256 * 256).coerceIn(512, javaLimit)
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
            automaticMemoryMb(totalMemoryMb, availableMemoryMb, architecture)
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
