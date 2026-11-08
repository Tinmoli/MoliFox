// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 「修改」页签的界面状态束：主组合按实例创建，数据加载效果与卡片按需读写。

package dev.molifox.desktop.instance

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import dev.molifox.core.InstanceUpgradeScan
import dev.molifox.core.LoaderVersion
import dev.molifox.core.ReleaseVersion

/**
 * 「修改」页签的全部界面状态。以实例为界整体重建；换目标游戏版本时不重建对象，
 * 由预扫效果在重启时清空模组判定与迁移勾选，避免半份旧结果混进新版本的界面。
 */
internal class ModifyTabStates {
    /** 外部实例能否修改的预检结论；null 表示可以修改。 */
    val externalBlocker: MutableState<String?> = mutableStateOf(null)

    /** 目标游戏版本；null 表示保持当前版本（仅更换加载器）。 */
    val pickedGameVersion: MutableState<String?> = mutableStateOf(null)

    val gameVersions: MutableState<List<ReleaseVersion>> = mutableStateOf(emptyList())
    val supportedGameVersions: MutableState<Set<String>?> = mutableStateOf(null)
    val gameVersionLoading: MutableState<Boolean> = mutableStateOf(false)
    val gameVersionError: MutableState<String?> = mutableStateOf(null)
    val gameVersionRetry: MutableState<Int> = mutableIntStateOf(0)

    val loaderVersions: MutableState<List<LoaderVersion>> = mutableStateOf(emptyList())
    val loaderLoading: MutableState<Boolean> = mutableStateOf(false)
    val loaderError: MutableState<String?> = mutableStateOf(null)
    val loaderRetry: MutableState<Int> = mutableIntStateOf(0)

    val scan: MutableState<InstanceUpgradeScan?> = mutableStateOf(null)
    val scanning: MutableState<Boolean> = mutableStateOf(false)
    val scanError: MutableState<String?> = mutableStateOf(null)
    val scanRetry: MutableState<Int> = mutableIntStateOf(0)
    val checkedMods: MutableState<Set<String>?> = mutableStateOf(null)
    val checkedMigration: MutableState<Set<String>?> = mutableStateOf(null)

    /** 已勾选文件夹里被单独取消的子路径（相对游戏根目录，如 `config/x.json`）。 */
    val checkedMigrationExcluded: MutableState<Set<String>> = mutableStateOf(emptySet())

    /** 目标加载器清单是否为空：加载完成且无错误、没有任何发布版本。 */
    fun loaderListEmpty(): Boolean = !loaderLoading.value && loaderError.value == null && loaderVersions.value.isEmpty()
}
