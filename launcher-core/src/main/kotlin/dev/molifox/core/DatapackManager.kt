// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责存档数据包的列出、安装、删除，以及写回 level.dat 的启用/禁用状态。

package dev.molifox.core

import dev.molifox.core.i18n.I18n
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.UUID
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

/**
 * `saves/<世界>/datapacks/` 里的一个数据包：`.zip` 文件或文件夹。
 *
 * [enabled] 为 null 表示 level.dat 无法解析、状态未知；此时界面只展示、不提供开关。
 */
data class DatapackEntry(
    /** 文件或文件夹名（含 `.zip` 后缀），也是 `level.dat` 里 `file/<名称>` 的标识部分。 */
    val name: String,
    val path: Path,
    val directory: Boolean,
    val size: Long,
    val enabled: Boolean?,
)

/** 一个世界的数据包清单；[toggleAvailable] 为 false 时开关只读，[note] 说明原因。 */
data class DatapackListing(
    val world: String,
    val entries: List<DatapackEntry>,
    val toggleAvailable: Boolean,
    val note: String,
)

/**
 * 数据包管理：读 `Data.Packs.Enabled`/`Disabled` 判断启用状态，安装时校验 `pack.mcmeta` 与 `pack_format`。
 *
 * 约定：
 * - `level.dat` 是 gzip 压缩的 NBT，复用现有 [readNbtDocument]/[encodeNbt]（覆盖 1–12 号标签、保留根名称与压缩形式）；
 *   解析失败时抛中文错误且**不写文件**，界面据此退回只读。
 * - 写回先写临时文件、核对 `level.dat` 未变化，再原子改名；原文件滚动备份到启动器数据的
 *   `backups/level-dat/`，每次覆盖同一个名字，不会无限堆积。
 * - `session.lock` 被占用（世界正被游戏打开）时拒绝安装、删除与切换。
 * - 删除走 [PlatformServices.moveToTrash]，并尽力清掉 `level.dat` 里对应的旧条目（失败只记日志）。
 */
class DatapackManager(
    private val store: LauncherStore,
) {
    private val paths = DatapackPaths(store)
    private val levelDat = DatapackLevelDat(store)

    /** 列出一个世界的数据包与启用状态；世界不存在或 level.dat 无法解析时不抛异常，返回只读清单。 */
    fun list(
        instanceId: String,
        worldName: String,
    ): DatapackListing {
        val world = paths.worldDirectory(instanceId, worldName)
        val packs = levelDat.readPacks(world)
        val entries = paths.listEntries(world, packs)
        val locked = sessionLockHeld(world)
        val note =
            when {
                packs == null -> I18n.t("saveA1.datapackManager.packsUnreadable")
                locked -> I18n.t("saveA1.datapackManager.locked")
                else -> ""
            }
        return DatapackListing(
            world = worldName,
            entries = entries,
            toggleAvailable = packs != null && !locked,
            note = note,
        )
    }

    /**
     * 把用户选的 `.zip` 安装到世界的 `datapacks/`，返回安装后的文件名。
     *
     * 只接受根目录带 `pack.mcmeta`、且 `pack.pack_format` 为数字的压缩包；同名文件已存在时拒绝，不覆盖。
     */
    fun install(
        instanceId: String,
        worldName: String,
        archive: Path,
    ): String {
        val world = paths.worldDirectory(instanceId, worldName)
        require(archive.isRegularFile(LinkOption.NOFOLLOW_LINKS)) {
            I18n.t("saveA1.datapackManager.fileNotFound", archive)
        }
        require(archive.name.endsWith(".zip", true)) { I18n.t("saveA1.datapackManager.mustBeZip") }
        require(!paths.isLink(archive)) { I18n.t("saveA1.datapackManager.noLinks") }
        val packFormat = validatePackArchive(archive)
        val name = archive.name
        val directory = paths.datapacksDirectory(world, create = true)
        val target = directory.resolve(name)
        require(target.parent.toAbsolutePath().normalize() == paths.absolute(directory)) {
            I18n.t("saveA1.datapackManager.invalidName", name)
        }
        require(!target.exists(LinkOption.NOFOLLOW_LINKS)) {
            I18n.t("saveA1.datapackManager.nameConflict", name)
        }
        val staging = directory.resolve(".molifox-${UUID.randomUUID()}.tmp")
        try {
            Files.copy(archive, staging)
            replaceFile(staging, target)
        } finally {
            Files.deleteIfExists(staging)
        }
        // 世界没开着才动 level.dat；开着时新文件会被游戏在下次进入世界时自动收进世界数据包列表。
        if (!sessionLockHeld(world)) runCatching { levelDat.toggle(world, name, true) }
        store.log(
            I18n.t(
                "saveA1.datapackManager.logInstalled",
                store.instance(instanceId).name,
                worldName,
                name,
                packFormat,
            ),
        )
        return name
    }

    /**
     * 启用或禁用 `datapacks/` 里的一个数据包。
     *
     * 启用 = 从 `Disabled` 移除并加入 `Enabled`，禁用反之；其他条目（`vanilla`、其他数据包）原样保留。
     * 写入前要求 `level.dat` 可解析、世界未被游戏打开，写入用临时文件 + 原子改名。
     */
    fun setEnabled(
        instanceId: String,
        worldName: String,
        name: String,
        enabled: Boolean,
    ) {
        val world = enabledWorld(instanceId, worldName)
        paths.requireEntry(world, name)
        if (!levelDat.toggle(world, name, enabled)) return
        val logKey =
            if (enabled) "saveA1.datapackManager.logEnabled" else "saveA1.datapackManager.logDisabled"
        store.log(I18n.t(logKey, store.instance(instanceId).name, worldName, name))
    }

    /** 删除一个数据包（移入回收站），并尽力清理 `level.dat` 里的旧条目；找不到或失败返回 false。 */
    fun delete(
        instanceId: String,
        worldName: String,
        name: String,
    ): Boolean {
        val world = paths.worldDirectory(instanceId, worldName)
        require(!sessionLockHeld(world)) {
            I18n.t("saveA1.datapackManager.deleteLocked", worldName)
        }
        val entry = runCatching { paths.datapackEntry(world, name) }.getOrNull() ?: return false
        return runCatching {
            store.platform.moveToTrash(entry)
            store.log(
                I18n.t(
                    "saveA1.datapackManager.logTrashed",
                    store.instance(instanceId).name,
                    worldName,
                    name,
                ),
            )
            runCatching { levelDat.forget(world, name) }
                .onFailure {
                    store.log(
                        I18n.t("saveA1.datapackManager.logForgetFailed", it.message ?: name),
                        true,
                    )
                }
            true
        }.getOrElse {
            store.log(I18n.t("saveA1.datapackManager.logDeleteFailed", it.message ?: name), true)
            false
        }
    }

    /** 切换数据包前的世界检查：只要求世界没有被游戏打开，游戏本体可以继续运行。 */
    private fun enabledWorld(
        instanceId: String,
        worldName: String,
    ): Path {
        val world = paths.worldDirectory(instanceId, worldName)
        require(!sessionLockHeld(world)) {
            I18n.t("saveA1.datapackManager.toggleLocked", worldName)
        }
        return world
    }
}
