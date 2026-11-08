// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 执行「修改」页签的版本升级：新建实例 → 安装游戏与加载器 → 模组升级 → 迁移内容 → 校验。
// 任何一步失败或被取消都删除新建实例的记录与目录；旧实例全程只读。
// 依赖补齐见 UpgradeDependencyResolver，校验统计见 InstanceUpgradeChecks。

package dev.molifox.core

import dev.molifox.core.i18n.I18n
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Locale
import kotlin.io.path.createDirectories
import kotlin.io.path.isRegularFile

/** 版本升级各阶段的进度锚点：界面浮层按这些阈值把进度映射到五个步骤。 */
private const val UPGRADE_CREATE_PROGRESS = 0.02f
internal const val UPGRADE_INSTALL_END = 0.45f
private const val UPGRADE_MODS_END = 0.72f
private const val UPGRADE_MIGRATION_END = 0.94f

internal class InstanceUpgradeService(
    private val store: LauncherStore,
    private val installer: MinecraftInstaller,
    private val modInstaller: ModUpdateInstaller,
    private val dependencies: UpgradeDependencyResolver,
    private val checks: InstanceUpgradeChecks,
) {
    /**
     * 完整升级流程。新实例与旧实例同名目录不冲突（名称唯一性由登记校验保证）；
     * 失败时旧实例记录与文件不动，半成品新实例整体清理。
     */
    suspend fun upgradeVersion(
        source: GameInstance,
        plan: InstanceUpgradePlan,
        context: JobContext,
    ) {
        val created = createInstanceForUpgrade(source, plan)
        try {
            installTarget(created, context)
            val mods = installMods(created, source, plan, context)
            migrateContent(created, source, plan, context)
            verify(created, source, plan, mods, context)
            finish(created, source)
        } catch (expected: Throwable) {
            rollback(created, context)
            throw expected
        }
    }

    /** 步骤一：登记新实例。隔离强制开启——迁移内容写进版本目录，不与其它实例共享游戏根。 */
    private fun createInstanceForUpgrade(
        source: GameInstance,
        plan: InstanceUpgradePlan,
    ): GameInstance {
        val created =
            installer.createInstance(
                InstallRequest(plan.newName, plan.targetGameVersion, source.loader, plan.loaderVersion),
                source.gameDirectoryId,
            )
        store.updateInstance(created.id) {
            it.copy(
                isolation = VersionIsolation.Enabled,
                javaPath = source.javaPath,
                memoryMb = source.memoryMb,
                autoMemory = source.autoMemory,
                initialMemoryMb = source.initialMemoryMb,
                width = source.width,
                height = source.height,
                windowMode = source.windowMode,
                extraJvmArgs = source.extraJvmArgs,
                extraGameArgs = source.extraGameArgs,
                advancedLaunch = source.advancedLaunch,
            )
        }
        return store.instance(created.id)
    }

    /** 步骤二：下载 Minecraft 与加载器并完成安装；进度收口到升级自己的区间。 */
    private suspend fun installTarget(
        created: GameInstance,
        context: JobContext,
    ) {
        context.checkCancelled()
        context.progress(UPGRADE_CREATE_PROGRESS, I18n.t("instA2.upgrade.creating", created.name))
        installer.install(
            created.id,
            context,
            freshInstall = true,
            progressEnd = UPGRADE_INSTALL_END,
            refreshAfterInstall = false,
        )
    }

    /** 步骤三：按勾选下载升级文件或原样带入旧文件，最后补齐一层缺失的前置依赖。 */
    private suspend fun installMods(
        created: GameInstance,
        source: GameInstance,
        plan: InstanceUpgradePlan,
        context: JobContext,
    ): Path {
        val mods = store.instancePath(created.id).resolve("mods")
        val sourceMods = store.instancePath(source.id).resolve("mods")
        Files.createDirectories(mods)
        val total = plan.mods.size.coerceAtLeast(1)
        var done = 0
        for (item in plan.mods) {
            context.checkCancelled()
            val fraction = UPGRADE_INSTALL_END + (UPGRADE_MODS_END - UPGRADE_INSTALL_END) * done / total
            context.progress(fraction, I18n.t("instA2.upgrade.mods.single", item.title))
            val finalName =
                when {
                    item.state == InstanceModUpgradeState.HasNewVersion && item.candidate != null -> {
                        val base = modInstaller.downloadCandidate(item.candidate, mods)
                        // 旧文件是禁用状态时，升级文件沿用禁用后缀，行为与实例内更新一致。
                        if (!item.enabled) {
                            Files.move(mods.resolve(base), mods.resolve("$base.disabled"))
                            "$base.disabled"
                        } else {
                            base
                        }
                    }

                    else -> {
                        Files.copy(sourceMods.resolve(item.filename), mods.resolve(item.filename))
                        item.filename
                    }
                }
            context.checkCancelled()
            require(mods.resolve(finalName).isRegularFile()) {
                I18n.t("instA2.upgrade.modsMissing", finalName)
            }
            done++
        }
        dependencies.completeDependencies(created, mods, plan.targetGameVersion, context)
        context.progress(UPGRADE_MODS_END, I18n.t("instA2.upgrade.modsDone", done))
        return mods
    }

    /**
     * 步骤四：按勾选的顶层条目复制旧实例内容到新实例目录。
     * 不跟随链接；启动器临时文件与用户单独取消的子路径不进新实例。
     */
    private suspend fun migrateContent(
        created: GameInstance,
        source: GameInstance,
        plan: InstanceUpgradePlan,
        context: JobContext,
    ) {
        val sourceRoot = store.instancePath(source.id)
        val names = migrationEntryOrder(sourceRoot, plan.migrationEntries)
        if (names.isEmpty()) {
            context.progress(UPGRADE_MIGRATION_END, I18n.t("instA2.upgrade.migrationDone", 0))
            return
        }
        val targetRoot = store.instancePath(created.id)
        for ((index, name) in names.withIndex()) {
            context.checkCancelled()
            val fraction = UPGRADE_MODS_END + (UPGRADE_MIGRATION_END - UPGRADE_MODS_END) * index / names.size
            context.progress(fraction, I18n.t("instA2.upgrade.migration.entry", name))
            if (Files.isDirectory(sourceRoot.resolve(name))) {
                copyTree(sourceRoot.resolve(name), targetRoot.resolve(name), name, plan.migrationExcluded, context)
            } else {
                copyFile(sourceRoot.resolve(name), targetRoot.resolve(name))
            }
        }
        context.progress(UPGRADE_MIGRATION_END, I18n.t("instA2.upgrade.migrationDone", names.size))
    }

    /** 复制顺序与界面一致：目录在前、名称不分大小写排序；执行时按实际目录属性判断，清单大小写保留原样。 */
    private fun migrationEntryOrder(
        sourceRoot: Path,
        entries: Set<String>,
    ): List<String> =
        entries.sortedWith(
            compareBy<String> { !Files.isDirectory(sourceRoot.resolve(it)) }.thenBy { it.lowercase(Locale.ROOT) },
        )

    /** 递归复制一个迁移条目目录；链接条目原样跳过，被单独取消的子路径不进新实例。 */
    private suspend fun copyTree(
        sourceRoot: Path,
        targetRoot: Path,
        entryName: String,
        excluded: Set<String>,
        context: JobContext,
    ) {
        Files.walk(sourceRoot).use { stream ->
            stream.forEach { entry ->
                if (Files.isSymbolicLink(entry)) return@forEach
                val inner = sourceRoot.relativize(entry).joinToString("/")
                if (migrationEntrySkipped("$entryName/$inner", excluded)) return@forEach
                when {
                    Files.isDirectory(entry) -> targetRoot.resolve(inner).createDirectories()
                    entry.isRegularFile() -> copyFile(entry, targetRoot.resolve(inner))
                }
            }
        }
        context.checkCancelled()
    }

    private fun copyFile(
        source: Path,
        target: Path,
    ) {
        if (!source.isRegularFile() || Files.isSymbolicLink(source)) return
        target.parent?.createDirectories()
        // 目标是全新实例目录，理论不会重名；带 REPLACE 兜底重复执行时不炸。
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING)
    }

    /** 步骤五：版本配置落盘、每个勾选项与迁移分组都必须在新实例里真实存在。 */
    private suspend fun verify(
        created: GameInstance,
        source: GameInstance,
        plan: InstanceUpgradePlan,
        mods: Path,
        context: JobContext,
    ) {
        context.checkCancelled()
        context.progress(UPGRADE_MIGRATION_END, I18n.t("instA2.upgrade.verify"))
        val folder = store.versionGamePath(created)
        require(folder.resolve("${created.versionId}.json").isRegularFile()) {
            I18n.t("instA2.upgrade.verificationFailed", I18n.t("instA2.upgrade.missingProfile", created.versionId))
        }
        val sourceMods = store.instancePath(source.id).resolve("mods")
        for (item in plan.mods) {
            val finalName =
                if (item.state == InstanceModUpgradeState.HasNewVersion && item.candidate != null) {
                    val base = safeFilename(contentBaseFilename(item.candidate.filename.trim()))
                    if (item.enabled) base else "$base.disabled"
                } else {
                    item.filename
                }
            require(mods.resolve(finalName).isRegularFile()) {
                I18n.t("instA2.upgrade.verificationFailed", I18n.t("instA2.upgrade.modsMissing", finalName))
            }
            require(sourceMods.resolve(item.filename).isRegularFile()) {
                I18n.t("instA2.upgrade.verificationFailed", I18n.t("packsC.modUpdate.fileMissing"))
            }
        }
        val sourceRoot = store.instancePath(source.id)
        val targetRoot = store.instancePath(created.id)
        for (name in plan.migrationEntries.sorted()) {
            val copied = checks.countFiles(targetRoot, name, plan.migrationExcluded)
            require(copied > 0 || checks.countFiles(sourceRoot, name, plan.migrationExcluded) == 0) {
                I18n.t(
                    "instA2.upgrade.verificationFailed",
                    I18n.t("instA2.upgrade.migrationMissing", name),
                )
            }
        }
        context.progress(1f, I18n.t("instA2.upgrade.verifyDone"))
    }

    /** 收尾：刷新实例列表并选中新实例，让用户直接看到结果；旧实例原样保留。 */
    private fun finish(
        created: GameInstance,
        source: GameInstance,
    ) {
        store.refreshInstances()
        store.mutate { data ->
            data.copy(
                selectedInstanceId = created.id,
                activeGameDirectoryId = created.gameDirectoryId,
            )
        }
        store.log(I18n.t("instA2.upgrade.finished", source.name, created.name))
    }

    /** 失败/取消的收尾：删除新实例的版本目录与记录；旧实例只读，全程未被写入。 */
    private suspend fun rollback(
        created: GameInstance,
        context: JobContext,
    ) {
        withContext(NonCancellable + Dispatchers.IO) {
            val cleanup = NewInstallationCleanup(store)
            // 目录删除可能被传输占用挡住：即便失败也要把记录摘掉，否则半成品会以实例身份出现在列表里。
            runCatching {
                cleanup.removeVersionFolder(context, store.versionGamePath(created))
            }.onFailure {
                store.log(I18n.t("instA2.upgrade.rollbackFailed", created.name, it.message), true)
            }
            runCatching {
                store.endPendingInstall(created.name)
                cleanup.removeInstanceRecord(created)
                store.refreshInstances()
                store.log(I18n.t("instA2.upgrade.rolledBack", created.name))
            }.onFailure {
                store.log(I18n.t("instA2.upgrade.rollbackFailed", created.name, it.message), true)
            }
        }
    }
}
