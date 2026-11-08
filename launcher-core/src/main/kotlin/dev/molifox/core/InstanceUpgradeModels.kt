// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 「修改」页签的版本升级模型：模组四状态判定、迁移清单条目与执行计划。

package dev.molifox.core

/** 单个模组在目标游戏版本下的判定结果；界面按状态决定默认勾选与是否可勾。 */
enum class InstanceModUpgradeState {
    /** 有兼容目标版本且可下载的新版本。 */
    HasNewVersion,

    /** 没有找到新版本，当前文件兼容目标版本，按原样带入。 */
    CarryAsIs,

    /** 目标版本下没有它能用的版本，不带入新实例。 */
    Incompatible,

    /** 没查到可靠结论，或项目页不提供直链；不可带入新实例。 */
    QueryFailed,
}

/**
 * 一个模组文件跨版本的判定结果；[candidate] 只在 [InstanceModUpgradeState.HasNewVersion] 时非空。
 * [filename] 是旧实例 mods 目录里的真实文件名（`.disabled` 后缀原样保留）。
 */
data class InstanceModUpgradeItem(
    val filename: String,
    val title: String,
    val currentVersion: String,
    val targetVersion: String = "",
    val note: String = "",
    val size: Long = 0,
    val enabled: Boolean = true,
    val state: InstanceModUpgradeState = InstanceModUpgradeState.CarryAsIs,
    val candidate: ModUpdateCandidate? = null,
)

/**
 * 「修改」页签的预扫结果：模组判定与迁移内容统计，都是只读快照。
 * [migration] 复用导出扫描的顶层条目（含可展开的子项树）：mods 不在其中，
 * 由模组升级结果替代，版本 jar/json 与启动器私有条目同样不出现。
 */
data class InstanceUpgradeScan(
    val mods: List<InstanceModUpgradeItem> = emptyList(),
    val migration: List<PackExportEntry> = emptyList(),
)

/**
 * 一次版本升级的完整计划。[mods] 只包含用户勾选的项（未勾选的不带入新实例）；
 * [migrationEntries] 是勾选的迁移顶层条目（保留扫描时的实际名称，Linux 大小写敏感），
 * [migrationExcluded] 是其中被单独取消的
 * 子路径（相对游戏根目录，如 `config/x.json`），复制时整条跳过。
 * targetGameVersion 必须高于当前版本，同版本走加载器升级路径。
 */
data class InstanceUpgradePlan(
    val newName: String,
    val targetGameVersion: String,
    val loaderVersion: String,
    val mods: List<InstanceModUpgradeItem>,
    val migrationEntries: Set<String>,
    val migrationExcluded: Set<String> = emptySet(),
)
