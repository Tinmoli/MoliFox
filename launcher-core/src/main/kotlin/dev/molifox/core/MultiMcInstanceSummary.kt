// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责生成 MultiMC/Prism 导入预览摘要：已迁移项、未迁移提示与越界警告。

package dev.molifox.core

/** 原包声明了但本启动器不迁移的设置：只提示，不改变实例行为。 */
internal fun readMultiMcUnmigratedNotes(ini: Map<String, String>): List<String> =
    buildList {
        if (ini["prelaunchcommand"].orEmpty().isNotBlank()) {
            add("原包的启动前命令出于安全原因未执行，也未迁移。")
        }
        if (isMultiMcEnabled(ini["joinserveronlaunch"])) {
            add("原包设置为启动后自动进入服务器，本启动器未迁移该设置。")
        }
        if (isMultiMcEnabled(ini["ignorejavacompatibility"])) {
            add("原包设置为忽略 Java 兼容性检查，本启动器未迁移该设置。")
        }
    }

/** 预览摘要：首句保留“官方元数据”口径，随后列已迁移设置与全部提示。 */
internal fun multiMcSummary(
    launchOptions: PackLaunchOptions?,
    defaults: PackInstanceDefaults?,
    notes: List<String>,
    warnings: List<String>,
): String =
    buildString {
        append("MultiMC/Prism 实例：保留游戏内容，游戏依赖按官方元数据重建，不复制原启动器的 Java 路径或账户。")
        val migrated = migratedSettingLabels(launchOptions, defaults)
        if (migrated.isNotEmpty()) {
            append("\n已迁移的实例设置：${migrated.joinToString("、")}。")
        }
        for (line in warnings + notes) append("\n$line")
    }

private fun migratedSettingLabels(
    launchOptions: PackLaunchOptions?,
    defaults: PackInstanceDefaults?,
): List<String> =
    buildList {
        if (launchOptions != null) add("JVM 参数")
        if ((defaults?.memoryMb ?: 0) > 0) add("最大内存")
        if ((defaults?.initialMemoryMb ?: 0) > 0) add("初始内存")
        if ((defaults?.width ?: 0) > 0 || (defaults?.height ?: 0) > 0) add("窗口尺寸")
    }
