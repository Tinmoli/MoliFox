// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 「修改」页签的开始条件与执行计划组装：纯函数，不持有界面状态。

package dev.molifox.desktop.instance

import dev.molifox.core.InstanceModUpgradeItem
import dev.molifox.core.InstanceModUpgradeState
import dev.molifox.core.InstanceUpgradePlan
import dev.molifox.core.LoaderVersion
import dev.molifox.core.PackExportEntry

/** 开始按钮可用条件：实例运行中一律禁止；升级模式还要求名称可用且预扫完成。 */
internal fun modifyCanStart(
    states: ModifyTabStates,
    upgradeMode: Boolean,
    sameLoader: Boolean,
    busy: Boolean,
    gameRunning: Boolean,
    nameOk: Boolean,
): Boolean =
    // 目标游戏版本没有该加载器的任何发布版本时（如 Forge 未构建），不允许开始。
    when {
        gameRunning -> false
        !upgradeMode -> !sameLoader && !busy
        states.loaderListEmpty() -> false
        else -> nameOk && states.scan.value != null && !states.scanning.value && !busy
    }

/** 当前选中的加载器版本：手动选择仍有效时用之，否则取（排序后）最新的候选。 */
internal fun selectedLoaderVersion(
    picked: String?,
    options: List<LoaderVersion>,
): String = picked?.takeIf { version -> options.any { it.version == version } } ?: options.first().version

/** 组装版本升级执行计划：只带勾选且非「查询失败/不兼容」的模组，迁移只带勾选的顶层条目。 */
internal fun buildUpgradePlan(
    newName: String,
    pickedGameVersion: String?,
    currentGameVersion: String,
    loaderVersion: String,
    mods: List<InstanceModUpgradeItem>,
    selectedMods: Set<String>,
    migration: List<PackExportEntry>,
    selectedKeys: Set<String>,
    excludedPaths: Set<String>,
): InstanceUpgradePlan =
    InstanceUpgradePlan(
        newName = newName,
        targetGameVersion = pickedGameVersion ?: currentGameVersion,
        loaderVersion = loaderVersion,
        mods =
            mods.filter {
                it.filename in selectedMods &&
                    it.state != InstanceModUpgradeState.QueryFailed &&
                    it.state != InstanceModUpgradeState.Incompatible
            },
        // 勾选键是小写名（Windows 比较），计划里换回扫描时的实际名称：Linux 大小写敏感。
        migrationEntries = migration.filter { it.key in selectedKeys }.map { it.name }.toSet(),
        migrationExcluded = excludedPaths,
    )

/** 勾选切换：还没手动改过时从默认集出发增删，避免先取消一项就丢掉其余默认项。 */
internal fun toggledSelection(
    checked: Set<String>?,
    defaults: Set<String>,
    key: String,
    add: Boolean,
): Set<String> {
    val current = checked ?: defaults
    return if (add) current + key else current - key
}

/** 子路径取消/恢复：取消加入排除集，恢复时连同其后代一并移除，与导出对话框同一语义。 */
internal fun toggledExclusion(
    excluded: Set<String>,
    path: String,
    add: Boolean,
): Set<String> =
    if (add) {
        excluded + path
    } else {
        excluded.filterNot { it == path || it.startsWith("$path/") }.toSet()
    }
