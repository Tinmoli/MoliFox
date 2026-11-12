// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 「修改」页签的升级预扫：模组跨版本判定与迁移内容统计；只读，不改动任何文件。

package dev.molifox.core

import dev.molifox.core.i18n.I18n
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.withContext
import java.nio.file.Files
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

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
    private val metadataReader: ModMetadataReader,
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
            val items =
                modUpdates
                    .check(
                        instanceId,
                        includePrerelease,
                        gameVersionOverride = targetGameVersion,
                    ).items
            val declared = readDeclaredConstraints(instanceId)
            items.map { toUpgradeItem(it, declared[it.filename].orEmpty(), targetGameVersion) }
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
     * 可下载新版本 → 有新版本；没有新版本且当前文件兼容（jar 自声明不含目标版本时改判不兼容）→ 无需更新；
     * 「最新」编号比当前文件旧时编号可能嵌着游戏版本，按 jar 自声明决定换装目标版本文件或沿用；
     * 目标版本下没有兼容文件 → 不兼容；
     * 其余（未找到匹配项目、查询失败、无法确认版本、项目页不提供直链）→ 查询失败，不可带入。
     */
    private fun toUpgradeItem(
        item: ModUpdateItem,
        declared: List<String>,
        targetGameVersion: String,
    ): InstanceModUpgradeItem {
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

            item.status == ModUpdateStatus.Latest -> latestItem(base, item, declared, targetGameVersion)

            else ->
                base.copy(state = InstanceModUpgradeState.QueryFailed, note = item.warning.ifBlank { item.failure })
        }
    }

    private fun latestItem(
        base: InstanceModUpgradeItem,
        item: ModUpdateItem,
        declared: List<String>,
        targetGameVersion: String,
    ): InstanceModUpgradeItem {
        val hasDeclaration = targetGameVersion.isNotBlank() && declared.isNotEmpty()
        val declaresThisTarget = hasDeclaration && declared.any { minecraftConstraintMatches(it, targetGameVersion) }
        val declaresOtherTarget = hasDeclaration && !declaresThisTarget
        val declaredNote = declared.joinToString("；")
        return when {
            item.candidate != null ->
                latestCandidateItem(
                    base,
                    item,
                    declaresThisTarget,
                    declaresOtherTarget,
                    targetGameVersion,
                    declaredNote,
                )

            item.warning.isNotBlank() ->
                base.copy(state = InstanceModUpgradeState.Incompatible, note = item.warning)

            // 远端标签可能错标，编号持平时仍需检查文件自身声明。
            declaresOtherTarget ->
                base.copy(
                    state = InstanceModUpgradeState.Incompatible,
                    note = I18n.t("instA2.upgrade.declaredIncompatible", targetGameVersion, declaredNote),
                )

            else -> base.copy(state = InstanceModUpgradeState.CarryAsIs)
        }
    }

    // 跨游戏版本的文件编号不能直接比较；优先考虑正式版和文件自身的兼容声明。
    private fun latestCandidateItem(
        base: InstanceModUpgradeItem,
        item: ModUpdateItem,
        declaresThisTarget: Boolean,
        declaresOtherTarget: Boolean,
        targetGameVersion: String,
        declaredNote: String,
    ): InstanceModUpgradeItem {
        val candidate = requireNotNull(item.candidate)
        val prefersRelease = isPrereleaseVersion(item.currentVersion) && !isPrereleaseVersion(candidate.versionNumber)
        if (!prefersRelease && declaresThisTarget) return base.copy(state = InstanceModUpgradeState.CarryAsIs)
        val note =
            when {
                prefersRelease -> I18n.t("instA2.upgrade.releasePreferred", item.latestVersion)
                declaresOtherTarget -> I18n.t("instA2.upgrade.declaredIncompatible", targetGameVersion, declaredNote)
                else -> item.warning
            }
        return base.copy(
            state = InstanceModUpgradeState.HasNewVersion,
            targetVersion = item.latestVersion,
            candidate = candidate,
            note = note,
        )
    }

    /** 读取 mods 目录各文件声明的 Minecraft 版本约束；元数据刚被检查管线解析过，命中读取缓存。 */
    private fun readDeclaredConstraints(instanceId: String): Map<String, List<String>> {
        val directory = store.instancePath(instanceId).resolve("mods")
        if (!directory.exists()) return emptyMap()
        return runCatching {
            directory
                .listDirectoryEntries()
                .filter {
                    it.isRegularFile() &&
                        !Files.isSymbolicLink(it) &&
                        modJarNamePattern.matches(it.name)
                }.map { it.name to metadataReader.read(it).minecraftConstraints }
                .filter { (_, constraints) -> constraints.isNotEmpty() }
                .toMap()
        }.getOrElse { emptyMap() }
    }
}
