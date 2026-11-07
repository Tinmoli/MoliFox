// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责实例扫描与实例路径解析。

package dev.molifox.core

import dev.molifox.core.i18n.I18n
import java.nio.file.Path
import kotlin.io.path.isDirectory

/** 实例扫描：重新读取磁盘上的 `versions/` 目录。 */
internal interface StoreScanApi {
    fun refreshInstances(): Int

    fun refreshInstancesIfChanged(): Boolean

    fun pruneMissingInstanceRecords(): Int
}

/** 实例路径与隔离状态。 */
internal interface StorePathApi {
    fun minecraftRoot(instance: GameInstance): Path

    fun isolated(instance: GameInstance): Boolean

    fun isolationSource(instance: GameInstance): IsolationSource

    fun isolationEvidence(instance: GameInstance): String?

    fun isolationTitle(instance: GameInstance): String

    fun instancePath(id: String): Path

    fun gamePath(instance: GameInstance): Path

    fun versionGamePath(instance: GameInstance): Path
}

/** 以磁盘 `versions/<目录>/` 为准重建实例列表。 */
internal abstract class StoreScanActions(
    core: LauncherStoreCore,
) : StorePathActions(core),
    StoreScanApi {
    /** 载入完成后的收尾：补写 CurseForge 回退、刷新实例并说明迁移结果。 */
    fun startup() {
        if (core.curseForgeBaseFallback) {
            log(
                I18n.t(
                    "miscA2.storeScan.curseForgeFallback",
                    DEFAULT_CURSE_FORGE_BASE_URL,
                ),
                true,
            )
        }
        refreshInstances()
        // 已删目录留下的设置键保持惰性，不因一次启动就删掉；下一次保存时由 mutate 磁盘核对后统一清理。
        core.recoveryMessage?.let { log(it, true) }
        val legacy = core.legacyRecords
        if (legacy.isNotEmpty()) {
            val current = core.data
            val legacyKeys =
                legacy.map { it.instanceSettingsKey() }.filter { it.isNotBlank() }.toSet()
            // 迁移只为磁盘上仍存在的目录写入设置键；这里按最终设置表计数。
            val keptKeys =
                legacyKeys.filter { key -> current.instanceSettings.containsKey(key) }
            log(
                I18n.t(
                    "miscA2.storeScan.instanceMigrationSummary",
                    keptKeys.size,
                    legacyKeys.size - keptKeys.size,
                ),
            )
        }
    }

    /**
     * 重新扫描所有游戏根的 `versions/<目录>/`，重建运行时实例列表。配置只更新选中项，
     * 不写入实例记录；新增目录出现、删除目录消失、损坏 JSON 变成「无法识别」都在这里统一发生。
     *
     * @return 可启动（未损坏）的实例数量
     */
    @Synchronized
    override fun refreshInstances(): Int {
        val current = core.data
        val roots = InstanceCatalog.roots(current)
        val previous =
            current.instances.associateBy {
                it.gameDirectoryId to
                    it.instanceSettingsKey()
            }
        val (instances, normalizedIds) = scanInstances(current, previous)
        val selected = scanSelectedInstance(current, instances) { core.migrationSelectedFolder }
        core.migrationSelectedFolder = null
        core.lastScanSignature = InstanceCatalog.signature(roots)
        val next = current.copy(instances = instances, selectedInstanceId = selected)
        if (next != current) updateState { it.copy(data = next) }
        // 选中项从旧随机 id / 已删除 id 收敛到扫描结果时写回，下一次启动不会再退回第一个目录。
        if (next.selectedInstanceId != current.selectedInstanceId) writeJson(core.config, next)
        if (normalizedIds > 0) {
            log(I18n.t("miscA2.storeScan.normalizedIds", normalizedIds))
        }
        logIsolationInference(instances)
        return instances.count { !it.broken }
    }

    /** 页面重新可见时调用：目录名或版本 JSON 的 mtime/size 没变就不重扫，不建立轮询。 */
    override fun refreshInstancesIfChanged(): Boolean {
        val signature = InstanceCatalog.signature(InstanceCatalog.roots(core.data))
        if (signature == core.lastScanSignature) return false
        refreshInstances()
        return true
    }

    /**
     * 启动/发现时用磁盘核对实例设置：`versions/<目录名>` 在所有扫描根里都不存在时，这条记录已没有文件，
     * 从配置中移除并记一行日志；目录还在的一律保留，避免把刚创建、尚未下载完的实例误删。
     *
     * 不判断 `libraries/`、`assets/` 或共用游戏根；返回被清理的实例显示名，没有失效记录时不写配置、不产生日志。
     */
    @Synchronized
    override fun pruneMissingInstanceRecords(): Int {
        val current = core.data
        val roots = InstanceCatalog.roots(current)

        fun directoryExists(key: String): Boolean =
            roots.any { root ->
                runCatching {
                    root.path
                        .resolve("versions")
                        .resolve(key)
                        .isDirectory()
                }.getOrDefault(false)
            }
        val missing = current.instanceSettings.filterKeys { !directoryExists(it) }
        if (missing.isEmpty()) return 0
        val keys = missing.keys
        mutate { data ->
            val instances = data.instances.filter { it.instanceSettingsKey() !in keys }
            data.copy(
                instances = instances,
                selectedInstanceId =
                    data.selectedInstanceId?.takeIf { id -> instances.any { it.id == id } }
                        ?: instances
                            .firstOrNull { it.gameDirectoryId == data.activeGameDirectoryId }
                            ?.id
                        ?: instances.firstOrNull()?.id,
            )
        }
        missing.forEach { (key, settings) ->
            log(I18n.t("miscA2.storeScan.removedMissingInstance", settings.displayName.ifBlank { key }))
        }
        return missing.size
    }

    /** 扫描所有游戏根，按「游戏目录 + 设置键」复用旧记录；返回实例列表与规范化 id 的计数。 */
    private fun scanInstances(
        current: LauncherData,
        previous: Map<Pair<String?, String>, GameInstance>,
    ): Pair<List<GameInstance>, Int> {
        var normalizedIds = 0
        val instances =
            InstanceCatalog.scan(current).mapNotNull { scanned ->
                val key = scanned.instanceSettingsKey()
                val old = previous[scanned.gameDirectoryId to key]
                // 正在安装的空目录没有 JSON，扫描会判成损坏；此时保留内存里的目标实例（若有），
                // 绝不把构造中的目录显示成「无法识别」。安装结束后扫描到有效 JSON 再收敛 id。
                if (scanned.broken && core.pendingInstalls.containsKey(key)) {
                    return@mapNotNull old?.copy(broken = false, brokenReason = "")
                }
                val settings = current.instanceSettings[key] ?: old?.let(InstanceSettings::of)
                // 有效隔离由 isolationSource 统一解析：显式设置 > 目录证据 > 全局默认。
                // 推断不写进实例记录，避免把推断值当成用户选择持久化。
                val decorated = settings?.applyTo(scanned) ?: scanned
                if (old != null && old.id != scanned.id) {
                    core.instanceIdAliases[old.id] = scanned.id
                    normalizedIds++
                }
                decorated.copy(id = scanned.id)
            }
        return instances to normalizedIds
    }

    /** 外部启动器的配置读不到，只按目录证据推断；每个实例每个会话只记一行。 */
    private fun logIsolationInference(instances: List<GameInstance>) {
        for (instance in instances) {
            val evidence = isolationEvidence(instance) ?: continue
            val key = "${instance.gameDirectoryId.orEmpty()}/${instance.instanceSettingsKey()}"
            if (core.inferenceLogged.add(key)) {
                log(I18n.t("miscA2.storeScan.isolationInferred", instance.name, evidence))
            }
        }
    }
}

/** 实例路径与隔离判定；每一项都只读当前配置与磁盘。 */
internal abstract class StorePathActions(
    core: LauncherStoreCore,
) : StoreLogActions(core),
    StorePathApi {
    override fun minecraftRoot(instance: GameInstance): Path =
        instance.gameDirectoryId?.let { directoryId ->
            Path.of(
                core.data.gameDirectories
                    .find { it.id == directoryId }
                    ?.path
                    ?: error(I18n.t("miscA2.storePath.gameDirectoryRemoved")),
            )
        } ?: core.minecraftRoot

    /**
     * 实例的有效隔离状态。显式选择永远优先；未选择时先用目录证据推断（外部启动器的配置
     * 读不到，只看 `versions/<名字>/` 里有没有自己的数据），再回落到全局开关。true 时工作目录是
     * `versions/<实例目录>`，false 时是游戏根。实例覆盖优先、未覆盖才用全局默认。
     */
    override fun isolated(instance: GameInstance): Boolean =
        when (isolationSource(instance)) {
            IsolationSource.Explicit -> instance.isolation == VersionIsolation.Enabled
            IsolationSource.Inferred -> true
            IsolationSource.GlobalDefault -> core.data.settings.isolateVersions
        }

    /**
     * 有效隔离值的来源：显式设置（版本隔离 / 共用游戏目录）> 目录推断 > 全局默认。
     * 「继承全局设置」不是显式选择，因此仍允许推断；用户保存具体项后就会覆盖推断。
     */
    override fun isolationSource(instance: GameInstance): IsolationSource =
        when (instance.isolation) {
            VersionIsolation.Enabled,
            VersionIsolation.Disabled,
            -> IsolationSource.Explicit
            VersionIsolation.Inherit ->
                if (isolationEvidence(instance) != null) {
                    IsolationSource.Inferred
                } else {
                    IsolationSource.GlobalDefault
                }
        }

    /** 界面展示用：推断值明确标注为「自动推断」，不冒充用户选择。 */
    override fun isolationTitle(instance: GameInstance): String =
        when (isolationSource(instance)) {
            IsolationSource.Explicit ->
                if (instance.isolation == VersionIsolation.Enabled) {
                    I18n.t("core.isolationMigration.2")
                } else {
                    I18n.t("core.isolationMigration.1")
                }
            IsolationSource.Inferred -> I18n.t("miscA2.storePath.isolationInferredTitle")
            IsolationSource.GlobalDefault ->
                if (core.data.settings.isolateVersions) {
                    I18n.t("miscA2.storePath.isolationGlobalDefault")
                } else {
                    I18n.t("miscA2.storePath.sharedGlobalDefault")
                }
        }

    /** 推断依据的目录条目名（saves、mods 等）；显式设置或没有证据时为 null。 */
    override fun isolationEvidence(instance: GameInstance): String? =
        if (instance.isolation != VersionIsolation.Inherit) {
            null
        } else {
            runCatching {
                InstanceCatalog.gameDataMarker(
                    versionGamePath(instance),
                )
            }.getOrNull()
        }

    override fun instancePath(id: String): Path = gamePath(instance(id))

    override fun gamePath(instance: GameInstance): Path {
        require(instance.id.isNotBlank()) { I18n.t("miscA2.storePath.invalidInstanceId") }
        if (!isolated(instance)) return minecraftRoot(instance)
        return versionGamePath(instance)
    }

    override fun versionGamePath(instance: GameInstance): Path =
        safePath(
            minecraftRoot(instance).resolve("versions"),
            safeFilename(instance.instanceFolderName()),
        )
}

/** 全部存储动作的最终实现：各分组共享同一个监视器与同一份状态。 */
internal class LauncherStoreActions(
    core: LauncherStoreCore,
) : StoreScanActions(core)

private fun scanSelectedInstance(
    current: LauncherData,
    instances: List<GameInstance>,
    migrationSelectedFolder: () -> String?,
): String? {
    val previousSelected = current.instances.find { it.id == current.selectedInstanceId }
    return instances.find { it.id == current.selectedInstanceId }?.id
        ?: previousSelected
            ?.let { old ->
                instances
                    .find {
                        it.gameDirectoryId == old.gameDirectoryId &&
                            it.instanceSettingsKey() == old.instanceSettingsKey()
                    }?.id
            }
        ?: migrationSelectedFolder()?.let { folder ->
            instances.find { it.instanceSettingsKey() == folder }?.id
        }
        ?: instances
            .filter { it.gameDirectoryId == current.activeGameDirectoryId }
            .firstOrNull()
            ?.id
        ?: instances.firstOrNull()?.id
}
