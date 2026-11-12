// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责生成 实例组件格式 导入预览摘要：已迁移项、未迁移提示与越界警告。

package dev.molifox.core

import dev.molifox.core.i18n.I18n

/** 原包声明了但本启动器不迁移的设置：只提示，不改变实例行为。 */
internal fun readMultiMcUnmigratedNotes(ini: Map<String, String>): List<String> =
    buildList {
        if (ini["prelaunchcommand"].orEmpty().isNotBlank()) {
            add(I18n.t("packsC.multiMc.notePrelaunchCommand"))
        }
        if (isMultiMcEnabled(ini["joinserveronlaunch"])) {
            add(I18n.t("packsC.multiMc.noteJoinServer"))
        }
        if (isMultiMcEnabled(ini["ignorejavacompatibility"])) {
            add(I18n.t("packsC.multiMc.noteIgnoreJavaCompatibility"))
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
        append(I18n.t("packsC.multiMc.summaryIntro"))
        val migrated = migratedSettingLabels(launchOptions, defaults)
        if (migrated.isNotEmpty()) {
            append(I18n.t("packsC.multiMc.migratedSettings", migrated.joinToString("、")))
        }
        for (line in warnings + notes) append("\n$line")
    }

private fun migratedSettingLabels(
    launchOptions: PackLaunchOptions?,
    defaults: PackInstanceDefaults?,
): List<String> =
    buildList {
        if (launchOptions != null) add(I18n.t("packsC.multiMc.settingJvmArgs"))
        if ((defaults?.memoryMb ?: 0) > 0) add(I18n.t("packsC.multiMc.settingMaxMemory"))
        if ((defaults?.initialMemoryMb ?: 0) > 0) add(I18n.t("packsC.multiMc.settingInitialMemory"))
        if ((defaults?.width ?: 0) > 0 || (defaults?.height ?: 0) > 0) add(I18n.t("packsC.multiMc.settingWindowSize"))
    }
