// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责游戏目录登记、扫描和现有实例识别。

package dev.molifox.core

import dev.molifox.core.i18n.I18n
import java.nio.file.Path

/** Register standard Minecraft roots by reference; shared assets and user data stay in place. */
class GameDirectoryService(
    private val store: LauncherStore,
) {
    private val scanner = GameDirectoryScanner(store)
    private val registry = GameDirectoryRegistry(store)

    /** Repair metadata saved by older builds without rescanning/removing or copying instances. */
    fun refreshVersionMetadata() {
        val versions =
            store.data.instances
                .filter { it.externalProfile }
                .mapNotNull { instance ->
                    runCatching {
                        val resolver =
                            VersionResolver(store.minecraftRoot(instance), store.platform)
                        instance.id to
                            resolver.minecraftVersion(resolver.resolve(instance.versionId))
                    }.getOrNull()
                }.toMap()
        if (
            store.data.instances.any {
                versions[it.id]?.let { version -> version != it.gameVersion } == true
            }
        ) {
            store.mutate { data ->
                data.copy(
                    instances =
                        data.instances.map { instance ->
                            versions[instance.id]?.let { instance.copy(gameVersion = it) }
                                ?: instance
                        },
                )
            }
        }
    }

    /** 用户手动选择目录：登记后设为当前目录，空目录也接受（作为后续安装目标）。 */
    fun add(source: Path): Int {
        val instances = register(registry.resolveRoot(source), activate = true)
        store.notify(I18n.t("core.gameDirectoryService.1", instances))
        return instances
    }

    /**
     * 启动时自动识别：把启动器自身目录旁/内发现的现有游戏登记进来，复用 [add] 的扫描与实例物化， 因此版本会直接出现在实例列表里，不需要手动「导入」。
     *
     * 只读扫描，不写用户的 `.minecraft`；已登记的目录不会重复添加，也不改动用户已有的目录与实例
     * 选择（只有本次新登记的目录才会被设为当前目录，等价于手动导入一次）。没有发现时保持安静。
     */
    fun discover(roots: List<StartupRoot>): Int {
        // 发现以磁盘为准：记录还在但版本/游戏目录已被手动删掉的实例先清掉，列表里不再出现点不动的空记录。
        store.pruneMissingInstanceRecords()
        var added: GameDirectory? = null
        for (root in roots) {
            val existing = registry.registered(root.path)
            val instances = register(root.path, activate = false)
            store.log(
                if (existing == null) {
                    I18n.t("instA1.gameDirectory.discoveredNew", root.path, root.versions)
                } else {
                    I18n.t("instA1.gameDirectory.discoveredExisting", root.path, root.versions)
                },
            )
            val newDiscovery = existing == null && added == null && instances > 0
            if (newDiscovery && !registry.sameDirectory(root.path, store.minecraftRoot)) {
                added = registry.registered(root.path)
            }
        }
        // 新发现的目录自动选中，实例列表立刻可见；已登记目录不会抢占用户的选择。
        added?.let { directory ->
            store.mutate { data ->
                data.copy(
                    activeGameDirectoryId = directory.id,
                    selectedInstanceId =
                        data.instances.firstOrNull { it.gameDirectoryId == directory.id }?.id,
                )
            }
        }
        return roots.size
    }

    /** 登记或刷新一个已确认的游戏根；只有 [activate] 时才改动当前目录与选中实例。 */
    private fun register(
        root: Path,
        activate: Boolean,
    ): Int {
        val existing = registry.registered(root)
        val isDefault = registry.sameDirectory(root, store.minecraftRoot)
        val directory = existing ?: registry.newDirectory(root)
        val directoryId = if (isDefault) null else directory.id
        val allInstances = scanner.scan(directory).map { it.copy(gameDirectoryId = directoryId) }
        val represented = store.data.instances.filter { it.gameDirectoryId == directoryId }
        val instances = registry.mergeScannedInstances(allInstances, represented)
        val registration =
            GameDirectoryRegistration(
                existing = existing,
                isDefault = isDefault,
                directory = directory,
                directoryId = directoryId,
                instances = instances,
            )
        store.mutate { data -> applyRegistration(data, registration, activate) }
        return instances.size
    }

    private fun applyRegistration(
        data: LauncherData,
        registration: GameDirectoryRegistration,
        activate: Boolean,
    ): LauncherData {
        val directoryId = registration.directoryId
        val current = data.instances.filter { it.gameDirectoryId == directoryId }
        val candidates =
            registry.refreshedInstances(current, registration.instances) +
                registry.freshInstances(current, registration.instances)
        return data.copy(
            gameDirectories =
                if (registration.existing == null && !registration.isDefault) {
                    data.gameDirectories + registration.directory
                } else {
                    data.gameDirectories
                },
            instances = data.instances.filter { it.gameDirectoryId != directoryId } + candidates,
            activeGameDirectoryId = if (activate) directoryId else data.activeGameDirectoryId,
            selectedInstanceId =
                if (activate) {
                    registry.selectedCandidate(data.selectedInstanceId, candidates)
                } else {
                    data.selectedInstanceId
                },
        )
    }

    fun select(id: String?) {
        require(id == null || store.data.gameDirectories.any { it.id == id }) { I18n.t("core.gameDirectoryService.2") }
        store.mutate { data ->
            data.copy(
                activeGameDirectoryId = id,
                selectedInstanceId = data.instances.firstOrNull { it.gameDirectoryId == id }?.id,
            )
        }
    }

    fun refresh(): Int {
        val directory =
            store.data.gameDirectories.find { it.id == store.data.activeGameDirectoryId }
        return register(
            directory?.let { Path.of(it.path) } ?: store.minecraftRoot,
            activate = false,
        )
    }

    fun remove(id: String) {
        store.mutate { data ->
            val instances = data.instances.filter { it.gameDirectoryId != id }
            val activeDirectory =
                if (data.activeGameDirectoryId == id) null else data.activeGameDirectoryId
            data.copy(
                gameDirectories = data.gameDirectories.filter { it.id != id },
                instances = instances,
                activeGameDirectoryId = activeDirectory,
                selectedInstanceId =
                    data.selectedInstanceId?.takeIf { selected ->
                        instances.any { it.id == selected && it.gameDirectoryId == activeDirectory }
                    } ?: instances.firstOrNull { it.gameDirectoryId == activeDirectory }?.id,
            )
        }
        store.notify(I18n.t("core.gameDirectoryService.3"))
    }
}

/** 一次「登记或刷新」所需的目录信息与扫描结果，避免更新函数排出七个参数。 */
private class GameDirectoryRegistration(
    val existing: GameDirectory?,
    val isDefault: Boolean,
    val directory: GameDirectory,
    val directoryId: String?,
    val instances: List<GameInstance>,
)
