// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责游戏目录与文件夹操作。

package dev.molifox.core

import dev.molifox.core.i18n.I18n
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import kotlin.io.path.createDirectories
import kotlin.io.path.isDirectory
import kotlin.io.path.readAttributes

/** 游戏目录切换、发现与界面文件夹。 */
internal interface LauncherDirectoryApi {
    fun deleteServer(
        instanceId: String,
        address: String,
    ): ServerDeletion

    fun selectGameDirectory(id: String?)

    fun refreshGameDirectory()

    /** 实例页重新可见时调用；签名没变就不做全量扫描。 */
    fun refreshInstancesIfChanged(): Boolean

    /**
     * 启动时自动发现：按启动器自身位置（[LauncherLocation]，不是 user.dir）解析旁边的 `.minecraft`、
     * 启动器自身即 `.minecraft`/游戏根、以及 `MoliFox/` 旁的目录，只读登记现有版本。
     * 已登记目录不会重复添加，也不会覆盖用户的选择；没有发现时不产生任何日志或提示。
     */
    fun discoverStartupGameDirectories(): Int

    fun removeGameDirectory(id: String)

    fun importExisting(source: Path): String

    fun openFolder(
        id: String,
        subfolder: String = "",
    )
}

/** 实现由 [LauncherController] 委托调用；方法体与原实现一致。 */
internal abstract class LauncherDirectoryActions(
    core: LauncherControllerCore,
) : LauncherInstanceActions(core),
    LauncherDirectoryApi {
    override fun deleteServer(
        instanceId: String,
        address: String,
    ): ServerDeletion {
        requireIdle(instanceId)
        return synchronized(store.lock) { ServerListEditor(store).delete(instanceId, address) }
    }

    override fun selectGameDirectory(id: String?) {
        directories.select(id)
        store.refreshInstances()
    }

    override fun refreshGameDirectory() {
        requireIdle()
        directories.refresh()
        store.refreshInstances()
    }

    /** 实例页重新可见时调用；签名没变就不做全量扫描。 */
    override fun refreshInstancesIfChanged(): Boolean = store.refreshInstancesIfChanged()

    /**
     * 启动时自动发现：按启动器自身位置（[LauncherLocation]，不是 user.dir）解析旁边的 `.minecraft`、
     * 启动器自身即 `.minecraft`/游戏根、以及
     * `MoliFox/` 旁的目录，只读登记现有版本。已登记目录不会重复添加，也不会覆盖用户的选择；
     * 没有发现时不产生任何日志或提示。
     */

    override fun discoverStartupGameDirectories(): Int {
        val discovered =
            directories.discover(
                StartupGameRoots.resolve(LauncherLocation.locate()),
            )
        store.refreshInstances()
        return discovered
    }

    override fun removeGameDirectory(id: String) {
        requireIdle()
        directories.remove(id)
        store.refreshInstances()
    }

    override fun importExisting(source: Path): String {
        requireIdle()
        return jobs.enqueue(
            I18n.t("miscA1.directoryActions.discoverGameDirectory"),
            kind = LauncherTaskKind.DiscoverDirectory,
        ) {
            directories.add(source)
            store.refreshInstances()
        }
    }

    override fun openFolder(
        id: String,
        subfolder: String,
    ) {
        require(
            subfolder in
                listOf(
                    "",
                    "mods",
                    "saves",
                    "logs",
                    "resourcepacks",
                    "shaderpacks",
                    "screenshots",
                    "config",
                    "schematics",
                    "crash-reports",
                    "datapacks",
                ),
        )
        val target = store.instancePath(id).resolve(subfolder)
        try {
            require(Files.readAttributes(target, BasicFileAttributes::class.java).isDirectory) {
                I18n.t("miscA1.directoryActions.targetNotDirectory", target)
            }
        } catch (_: NoSuchFileException) {
            target.createDirectories()
        }
        platform.openFolder(target)
    }
}
