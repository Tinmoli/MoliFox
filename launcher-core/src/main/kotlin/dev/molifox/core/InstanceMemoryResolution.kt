// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责实例内存草稿的校验回退与自动内存不足提示；从启动选项解析中拆出，保持单文件职责集中。

package dev.molifox.core

import dev.molifox.core.i18n.I18n

private const val MIN_INITIAL_MEMORY_MB = 128

/** 解析实例最大内存；越界值回退跟随全局并留下一条警告。 */
internal fun validMemoryMb(
    instance: GameInstance,
    memoryLimit: Int,
    warnings: MutableList<String>,
): Int =
    when {
        instance.autoMemory == true -> instance.memoryMb
        instance.memoryMb == 0 -> 0
        instance.memoryMb in MIN_INSTANCE_MEMORY_MB..memoryLimit -> instance.memoryMb
        else -> {
            warnings +=
                I18n.t(
                    "instA2.launchOptions.memoryOutOfRange",
                    instance.memoryMb,
                    MIN_INSTANCE_MEMORY_MB,
                    memoryLimit,
                )
            0
        }
    }

/** 解析实例初始内存；越界值回退跟随全局并留下一条警告。 */
internal fun validInitialMb(
    instance: GameInstance,
    memoryLimit: Int,
    warnings: MutableList<String>,
): Int =
    when {
        instance.initialMemoryMb == 0 -> 0
        instance.initialMemoryMb in MIN_INITIAL_MEMORY_MB..minOf(MAX_INSTANCE_MEMORY_MB, memoryLimit) ->
            instance.initialMemoryMb
        else -> {
            warnings += I18n.t("core.instanceLaunchOptions.9", instance.initialMemoryMb)
            0
        }
    }

/** 自动内存低于实例建议值时提示；不阻止启动。 */
internal fun appendMemoryWarning(
    instance: GameInstance,
    allocation: GameMemoryAllocation,
    warnings: MutableList<String>,
) {
    if (!allocation.automatic) return
    val recommended = recommendedAutomaticMemoryMb(instance.modCount)
    if (allocation.maximumMb >= recommended) return
    warnings +=
        I18n.t(
            "core.instanceLaunchOptions.17",
            allocation.maximumMb,
            recommended,
        )
}
