// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责整合包导入时携带的实例启动默认值（内存、窗口），0 表示跟随全局。

package dev.molifox.core

/** 初始内存下限，与启动参数解析里的 128 MB 约束保持一致。 */
internal const val MIN_PACK_INITIAL_MEMORY_MB = 128

/**
 * 整合包声明的实例级默认设置：只在新建实例时写入一次，0 表示跟随全局设置。
 * 值在解析阶段已经夹到合法范围，越界项直接归零并在导入预览里给出警告。
 */
internal data class PackInstanceDefaults(
    val memoryMb: Int = 0,
    val initialMemoryMb: Int = 0,
    val width: Int = 0,
    val height: Int = 0,
) {
    val isEmpty: Boolean
        get() = memoryMb == 0 && initialMemoryMb == 0 && width == 0 && height == 0
}

/** 把包内默认值套到新实例上；修复已有实例时不调用，避免覆盖用户改过的设置。 */
internal fun applyPackInstanceDefaults(
    instance: GameInstance,
    defaults: PackInstanceDefaults?,
): GameInstance {
    if (defaults == null || defaults.isEmpty) return instance
    return instance.copy(
        memoryMb = defaults.memoryMb,
        initialMemoryMb = defaults.initialMemoryMb,
        width = defaults.width,
        height = defaults.height,
    )
}
