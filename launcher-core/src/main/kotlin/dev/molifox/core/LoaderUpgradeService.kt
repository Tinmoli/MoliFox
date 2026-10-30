// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责已安装实例的加载器升级：备份、合成、失败恢复。

package dev.molifox.core

import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import kotlin.io.path.isRegularFile

/** 升级时加载器配置阶段的进度起点与终点。 */
private const val UPGRADE_LOADER_PROGRESS_START = 0.05f
private const val UPGRADE_LOADER_PROGRESS_END = 0.90f

/** 升级时发布后依赖下载的进度终点。 */
private const val UPGRADE_DEPENDENCIES_PROGRESS_END = 0.99f

/** 本地缺少基础版本配置时，回查版本清单的进度点。 */
private const val METADATA_PROGRESS = 0.02f

/**
 * 加载器升级：把实例升级到**同一加载器**的更新版本，游戏版本、实例目录名与 `molifoxManaged`
 * 标记都不变；任何一步失败都恢复备份的版本配置和原来的加载器版本。
 */
internal class LoaderUpgradeService(
    private val store: LauncherStore,
    private val network: NetworkClient,
    private val catalog: MinecraftVersionCatalog,
    private val loaders: LoaderProfileFetcher,
    private val publisher: InstanceProfilePublisher,
    private val pipeline: MinecraftInstallPipeline,
    private val externalUpgrades: ExternalLoaderUpgradeService,
) {
    /**
     * 先备份当前版本配置，再用「真实基础版本 + 新加载器配置」合成一份自包含 JSON 写回原实例目录，
     * 模组、存档与游戏配置都不动。
     */
    suspend fun upgradeLoader(
        instanceId: String,
        loaderVersion: String,
        context: JobContext,
    ) {
        val current = store.instance(instanceId)
        requireUpgradeRequest(current, loaderVersion)
        // 外部实例属于原启动器：合并进原 JSON，写前做时间戳备份，失败按字节恢复。
        if (current.externalProfile) {
            externalUpgrades.upgradeExternalLoader(current, loaderVersion, context)
            return
        }
        val backups = ComponentBackup(store)
        val backupId = backups.capture(current)
        val target = current.copy(loaderVersion = loaderVersion)
        try {
            store.updateInstance(instanceId) {
                it.copy(loaderVersion = loaderVersion, installed = false)
            }
            val base = baseProfileForUpgrade(current, context)
            val loader = fetchUpgradeLoader(target, base, context)
            applyUpgrade(target, base, loader, backups, backupId, context)
            context.progress(1f, "${current.loader.title} 已升级到 $loaderVersion")
        } catch (expected: Throwable) {
            restoreAfterFailedUpgrade(instanceId, current, backups, backupId)
            throw expected
        }
    }

    /** 升级前的结构预检：条件不满足时保持实例原样。 */
    private fun requireUpgradeRequest(
        current: GameInstance,
        loaderVersion: String,
    ) {
        require(current.installed) { "请先完成游戏安装，再升级加载器" }
        require(current.loader != LoaderType.Vanilla) { "原版实例没有可升级的加载器" }
        require(current.packFile.isBlank()) { "整合包的加载器由包清单维护，请通过整合包更新维护" }
        require(current.gameVersion.isNotBlank() && current.versionId.isNotBlank()) {
            "实例缺少游戏版本标识，请先重新识别游戏目录"
        }
        safeFilename(loaderVersion)
        require(compareLoaderVersions(loaderVersion, current.loaderVersion) > 0) {
            "只能升级到 ${current.loader.title} 的更新版本"
        }
    }

    private suspend fun fetchUpgradeLoader(
        target: GameInstance,
        base: ResolvedProfile,
        context: JobContext,
    ): JsonObject =
        when (target.loader) {
            LoaderType.Vanilla -> error("原版实例没有可升级的加载器")
            LoaderType.Fabric,
            LoaderType.Quilt,
            ->
                loaders.fetchFabricProfile(
                    target,
                    context,
                    UPGRADE_LOADER_PROGRESS_START,
                    UPGRADE_LOADER_PROGRESS_END,
                )
            LoaderType.Forge,
            LoaderType.NeoForge,
            ->
                loaders.fetchForgeProfile(
                    target,
                    base,
                    context,
                    UPGRADE_LOADER_PROGRESS_START,
                    UPGRADE_LOADER_PROGRESS_END,
                )
        }

    /** 合成并写回原实例目录，再补齐新加载器引入的依赖。 */
    private suspend fun applyUpgrade(
        target: GameInstance,
        base: ResolvedProfile,
        loader: JsonObject,
        backups: ComponentBackup,
        backupId: String,
        context: JobContext,
    ) {
        val root = store.minecraftRoot(target)
        context.checkCancelled()
        val folder = store.versionGamePath(target)
        publisher.publishInstanceProfile(
            instance = target,
            base = base,
            loader = loader,
            target = folder,
            root = root,
            selfContained = true,
            context = context,
        )
        context.checkCancelled()
        pipeline.installDependencies(
            VersionResolver(root, store.platform).resolve(folder.fileName.toString()),
            context,
            root,
            UPGRADE_LOADER_PROGRESS_END,
            UPGRADE_DEPENDENCIES_PROGRESS_END,
        )
        context.checkCancelled()
        store.updateInstance(target.id) {
            it.copy(loaderVersion = target.loaderVersion, installed = true)
        }
        store.refreshInstances()
        backups.discard(backupId)
    }

    private suspend fun restoreAfterFailedUpgrade(
        instanceId: String,
        current: GameInstance,
        backups: ComponentBackup,
        backupId: String,
    ) {
        withContext(NonCancellable + Dispatchers.IO) {
            runCatching {
                backups.restore(backupId, current)
                backups.discard(backupId)
                store.updateInstance(instanceId) {
                    it.copy(
                        loaderVersion = current.loaderVersion,
                        installed = current.installed,
                    )
                }
                store.refreshInstances()
            }.onFailure {
                store.log("升级加载器失败后恢复原版本配置也失败：${it.message}", true)
            }
        }
    }

    /** 升级合成配置用的基础版本：优先用本机已有的版本配置，缺失时按版本清单取官方 JSON。 */
    private suspend fun baseProfileForUpgrade(
        instance: GameInstance,
        context: JobContext,
    ): ResolvedProfile {
        val root = store.minecraftRoot(instance)
        val baseId = safeFilename(instance.gameVersion)
        if (root.resolve("versions/$baseId/$baseId.json").isRegularFile(NOFOLLOW_LINKS)) {
            return VersionResolver(root, store.platform).resolve(baseId)
        }
        context.progress(METADATA_PROGRESS, "获取 Minecraft ${instance.gameVersion} 元数据…")
        context.checkCancelled()
        val metadata =
            catalog.versions().find { it.id == instance.gameVersion } ?: error("此游戏版本不存在")
        val json = network.json(metadata.url).asJsonObject
        require(json.string("mainClass").isNotBlank()) { "此游戏版本尚未支持" }
        return ResolvedProfile(baseId, baseId, json)
    }
}
