// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责世界数据包管理。

package dev.molifox.core

import java.nio.file.LinkOption
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.isDirectory
import kotlin.io.path.name

/** 世界的 `datapacks/` 列表、安装与启停。 */
internal interface LauncherDatapackApi {
    /** 列出某个世界 `datapacks/` 的内容与启用状态；level.dat 无法解析时返回只读清单。 */
    fun listDatapacks(
        instanceId: String,
        worldName: String,
    ): DatapackListing

    /** 把本地 `.zip` 安装到世界的 `datapacks/`，校验 pack.mcmeta 与 pack_format。 */
    fun installDatapackFile(
        instanceId: String,
        worldName: String,
        archive: Path,
    ): String

    /** 启用/禁用数据包；写回 level.dat 的 Data.Packs，世界被打开时抛错。 */
    fun setDatapackEnabled(
        instanceId: String,
        worldName: String,
        name: String,
        enabled: Boolean,
    )

    /** 删除数据包（移入回收站）。 */
    fun deleteDatapack(
        instanceId: String,
        worldName: String,
        name: String,
    ): Boolean

    /** 打开某个世界的 `datapacks/` 文件夹。 */
    fun openWorldDatapacks(
        instanceId: String,
        worldName: String,
    )
}

private const val MAX_WORLD_NAME_LENGTH = 80

/** 实现由 [LauncherController] 委托调用；方法体与原实现一致。 */
internal abstract class LauncherDatapackActions(
    core: LauncherControllerCore,
) : LauncherSaveActions(core),
    LauncherDatapackApi {
    /** 列出某个世界 `datapacks/` 的内容与启用状态；level.dat 无法解析时返回只读清单。 */
    override fun listDatapacks(
        instanceId: String,
        worldName: String,
    ): DatapackListing = datapacks.list(instanceId, worldName)

    /** 把本地 `.zip` 安装到世界的 `datapacks/`，校验 pack.mcmeta 与 pack_format。 */
    override fun installDatapackFile(
        instanceId: String,
        worldName: String,
        archive: Path,
    ): String = datapacks.install(instanceId, worldName, archive)

    /** 启用/禁用数据包；写回 level.dat 的 Data.Packs，世界被打开时抛错。 */
    override fun setDatapackEnabled(
        instanceId: String,
        worldName: String,
        name: String,
        enabled: Boolean,
    ) {
        datapacks.setEnabled(instanceId, worldName, name, enabled)
    }

    /** 删除数据包（移入回收站）。 */
    override fun deleteDatapack(
        instanceId: String,
        worldName: String,
        name: String,
    ): Boolean = datapacks.delete(instanceId, worldName, name)

    /** 打开某个世界的 `datapacks/` 文件夹。 */
    override fun openWorldDatapacks(
        instanceId: String,
        worldName: String,
    ) {
        val world = worldPath(instanceId, worldName)
        val directory = world.resolve("datapacks")
        if (!directory.isDirectory(LinkOption.NOFOLLOW_LINKS)) directory.createDirectories()
        platform.openFolder(directory)
    }

    /** 世界目录必须是 `saves/` 的直接子目录，防止把任意外部路径交给系统打开。 */
    private fun worldPath(
        instanceId: String,
        worldName: String,
    ): Path {
        require(
            worldName.isNotBlank() && worldName.length <= MAX_WORLD_NAME_LENGTH,
        ) { "世界名称无效" }
        require('/' !in worldName && '\\' !in worldName && ':' !in worldName) {
            "世界名称无效：$worldName"
        }
        val saves = store.instancePath(instanceId).resolve("saves")
        val world =
            runCatching { safePath(saves, worldName) }.getOrElse {
                error("世界名称不安全：$worldName")
            }
        require(
            world.parent.toAbsolutePath().normalize() == saves.toAbsolutePath().normalize(),
        ) {
            "只能打开 saves 目录下的世界：$worldName"
        }
        require(world.isDirectory(LinkOption.NOFOLLOW_LINKS)) { "找不到世界存档：$worldName" }
        return world
    }
}
