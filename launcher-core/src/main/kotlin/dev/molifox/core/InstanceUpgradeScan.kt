// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 「修改」页签的升级预扫：模组跨版本判定与迁移内容统计；只读，不改动任何文件。

package dev.molifox.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.withContext

/** 迁移清单不出现的顶层条目：mods 由模组升级结果替代，避免旧版本模组混进新实例。 */
internal val MIGRATION_EXCLUDED_ROOTS = setOf("mods")

/** 迁移默认勾选的顶层条目：确定是用户内容的部分；未知名、日志与崩溃报告默认不勾，由用户确认。 */
internal val MIGRATION_DEFAULT_KEYS =
    setOf(
        "config",
        "defaultconfigs",
        "kubejs",
        "scripts",
        "saves",
        "shaderpacks",
        "resourcepacks",
        "schematics",
        "options.txt",
        "optionsof.txt",
        "optionsshaders.txt",
        "servers.dat",
        "servers.dat_old",
    )

/** 迁移清单的默认勾选集：只勾确定是用户内容的顶层条目，与界面共用避免计数与实际复制不一致。 */
fun defaultMigrationEntries(entries: List<PackExportEntry>): Set<String> =
    entries.filter { it.key in MIGRATION_DEFAULT_KEYS }.map { it.key }.toSet()

/** 纯环境声明键不是模组，依赖补齐时跳过。 */
internal val NON_MOD_DEPENDENCIES =
    setOf("minecraft", "fabricloader", "quilt_loader", "java", "forgeloader", "neoforgeloader")

/** 「修改」页签的预扫服务：把模组更新管线对准目标游戏版本，并统计可迁移内容。 */
internal class InstanceUpgradeScanService(
    private val store: LauncherStore,
    private val modUpdates: ModUpdateService,
) {
    /**
     * 用「目标游戏版本的伪实例」跑完整更新管线，再把结果翻译成四状态。
     * 全部内容源都失败时抛出（与实例更新检查一致），由界面整体提示重试。
     */
    suspend fun scanMods(
        instanceId: String,
        targetGameVersion: String,
        includePrerelease: Boolean = false,
    ): List<InstanceModUpgradeItem> =
        withContext(Dispatchers.IO) {
            modUpdates
                .check(
                    instanceId,
                    includePrerelease,
                    gameVersionOverride = targetGameVersion,
                ).items
                .map(::toUpgradeItem)
        }

    /**
     * 迁移内容统计：扫描游戏根目录的实际顶层条目，复用导出扫描的排除规则
     * （版本 jar/json、启动器私有数据、符号链接、`.molifox-*`），再额外整条隐藏 mods。
     * `isolated = true` 让共享目录的条目同样可选——迁移本来就是把共享根里的用户内容搬进隔离新实例。
     */
    suspend fun scanMigration(instanceId: String): List<PackExportEntry> =
        withContext(Dispatchers.IO) {
            currentCoroutineContext().ensureActive()
            val instance = store.instance(instanceId)
            val scanner =
                PackExportScanner(
                    store.instancePath(instanceId).toAbsolutePath().normalize(),
                    instance.versionId,
                    isolated = true,
                    excludedRoots = MIGRATION_EXCLUDED_ROOTS,
                )
            scanner.scan().last().entries
        }

    /**
     * 把更新检查的判定映射成四状态：
     * 可下载新版本 → 有新版本；没有新版本且当前文件兼容 → 无需更新；
     * 目标版本下没有兼容文件（含可用版本比当前文件还旧的「最新也不兼容」）→ 不兼容；
     * 其余（未找到匹配项目、查询失败、无法确认版本、项目页不提供直链）→ 查询失败，不可带入。
     */
    private fun toUpgradeItem(item: ModUpdateItem): InstanceModUpgradeItem {
        val base =
            InstanceModUpgradeItem(
                filename = item.filename,
                title = item.title,
                currentVersion = item.currentVersion,
                size = item.size,
                enabled = item.enabled,
            )
        return when {
            item.status == ModUpdateStatus.Updatable && item.candidate != null && item.applicable ->
                base.copy(
                    state = InstanceModUpgradeState.HasNewVersion,
                    targetVersion = item.candidate.versionNumber,
                    candidate = item.candidate,
                    note = item.warning,
                )

            // 有新版本但项目页不提供直链（少数作者关闭了第三方分发）：没有可用的自动更新，按没查到处理。
            item.status == ModUpdateStatus.Updatable ->
                base.copy(
                    state = InstanceModUpgradeState.QueryFailed,
                    note = item.warning.ifBlank { item.failure },
                )

            // 提示非空：要么版本池没有兼容目标版本的文件，要么可用版本比当前文件还旧——都带不过去。
            item.status == ModUpdateStatus.Latest && item.warning.isNotBlank() ->
                base.copy(state = InstanceModUpgradeState.Incompatible, note = item.warning)

            item.status == ModUpdateStatus.Latest ->
                base.copy(state = InstanceModUpgradeState.CarryAsIs)

            // 未找到匹配项目、查询失败或无法确认版本：没有可靠结论，不可带入。
            else ->
                base.copy(state = InstanceModUpgradeState.QueryFailed, note = item.warning.ifBlank { item.failure })
        }
    }
}
