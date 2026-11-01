// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 数据包管理用到的路径定位与校验：世界目录、datapacks 目录与单个数据包条目。

package dev.molifox.core

import dev.molifox.core.i18n.I18n
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries

/** 存档里数据包所在的直接子目录名。 */
private const val DATAPACKS_DIRECTORY = "datapacks"

/** 世界名与数据包名的长度上限：只做输入校验，不参与业务计算。 */
private const val MAX_WORLD_NAME_LENGTH = 80
private const val MAX_DATAPACK_NAME_LENGTH = 200

/**
 * 数据包的路径解析与只读校验：不读写 `level.dat`，也不移动任何文件；
 * 只有 [datapacksDirectory] 在 `create = true` 且目录不存在时新建 `datapacks/`。
 *
 * 所有入口都拒绝符号链接、目录联接与越界路径，保证后续操作只落在世界的 `datapacks/` 里。
 */
internal class DatapackPaths(
    private val store: LauncherStore,
) {
    /** 列出一个世界 `datapacks/` 下可展示的条目；没有启用状态时 [packs] 为 null。 */
    fun listEntries(
        world: Path,
        packs: DatapackPackLists?,
    ): List<DatapackEntry> {
        val directory = world.resolve(DATAPACKS_DIRECTORY)
        val children =
            if (!directory.isDirectory(LinkOption.NOFOLLOW_LINKS) || isLink(directory)) {
                emptyList()
            } else {
                runCatching { directory.listDirectoryEntries() }.getOrDefault(emptyList())
            }
        return children
            .mapNotNull { child -> describe(child, packs) }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
    }

    /** 数据包必须存在且是普通文件或文件夹。 */
    fun requireEntry(
        world: Path,
        name: String,
    ) {
        val entry =
            datapackEntry(world, name)
                ?: throw IllegalArgumentException(I18n.t("saveA1.datapackPaths.entryNotFound", name))
        require(
            entry.isRegularFile(LinkOption.NOFOLLOW_LINKS) ||
                entry.isDirectory(LinkOption.NOFOLLOW_LINKS),
        ) {
            I18n.t("saveA1.datapackPaths.entryNotFile", name)
        }
    }

    /** 世界目录：必须是 `saves/` 的直接普通子目录，并带 level.dat。 */
    fun worldDirectory(
        instanceId: String,
        worldName: String,
    ): Path {
        require(worldName.isNotBlank() && worldName.length <= MAX_WORLD_NAME_LENGTH) {
            I18n.t("saveA1.datapackPaths.worldNameInvalid")
        }
        require('/' !in worldName && '\\' !in worldName && ':' !in worldName) {
            I18n.t("saveA1.datapackPaths.worldNameInvalidNamed", worldName)
        }
        require(store.instance(instanceId).id.isNotBlank())
        val saves = store.instancePath(instanceId).resolve("saves")
        val world =
            runCatching { safePath(saves, worldName) }.getOrElse {
                throw IllegalArgumentException(I18n.t("saveA1.datapackPaths.worldNameUnsafe", worldName))
            }
        require(world.parent.toAbsolutePath().normalize() == absolute(saves)) {
            I18n.t("saveA1.datapackPaths.worldOutsideSaves", worldName)
        }
        require(world.isDirectory(LinkOption.NOFOLLOW_LINKS) && !isLink(world)) {
            I18n.t("saveA1.datapackPaths.worldNotFound", worldName)
        }
        require(
            world.resolve("level.dat").isRegularFile(LinkOption.NOFOLLOW_LINKS) &&
                !isLink(world.resolve("level.dat")),
        ) {
            I18n.t("saveA1.datapackPaths.worldMissingLevelDat", worldName)
        }
        return world
    }

    /** 世界的 `datapacks/` 目录；[create] 为真且目录不存在时创建。 */
    fun datapacksDirectory(
        world: Path,
        create: Boolean,
    ): Path {
        val directory = world.resolve(DATAPACKS_DIRECTORY)
        if (directory.exists(LinkOption.NOFOLLOW_LINKS)) {
            require(directory.isDirectory(LinkOption.NOFOLLOW_LINKS) && !isLink(directory)) {
                I18n.t("saveA1.datapackPaths.datapacksNotDirectory", directory)
            }
        } else if (create) {
            directory.createDirectories()
        }
        return directory
    }

    /** 解析世界的 `datapacks/<name>`；名称非法或越界时抛错，不存在时返回 null。 */
    fun datapackEntry(
        world: Path,
        name: String,
    ): Path? {
        require(name.isNotBlank() && name.length <= MAX_DATAPACK_NAME_LENGTH) {
            I18n.t("saveA1.datapackPaths.nameInvalid")
        }
        require('/' !in name && '\\' !in name && ':' !in name) {
            I18n.t("saveA1.datapackPaths.nameInvalidNamed", name)
        }
        val directory = world.resolve(DATAPACKS_DIRECTORY)
        val path = runCatching { safePath(directory, name) }.getOrNull()
        return if (path == null) {
            null
        } else {
            require(path.parent.toAbsolutePath().normalize() == absolute(directory)) {
                I18n.t("saveA1.datapackPaths.nameOutside", name)
            }
            path.takeIf { it.exists(LinkOption.NOFOLLOW_LINKS) && !isLink(it) }
        }
    }

    /** 游戏运行中的实例不允许安装、删除或切换数据包。 */
    fun requireGameStopped(instanceId: String) {
        val target = taskPathKey(store.instancePath(instanceId))
        require(
            store.state.value.runningGames.none {
                taskPathKey(store.instancePath(it.instanceId)) ==
                    target
            },
        ) {
            I18n.t("saveA1.datapackPaths.gameRunning")
        }
    }

    fun absolute(path: Path): Path = path.toAbsolutePath().normalize()

    fun isLink(path: Path): Boolean =
        runCatching {
            Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        }.map { it.isSymbolicLink || it.isOther }
            .getOrDefault(false)

    private fun describe(
        path: Path,
        packs: DatapackPackLists?,
    ): DatapackEntry? {
        val attributes =
            runCatching {
                Files.readAttributes(
                    path,
                    BasicFileAttributes::class.java,
                    LinkOption.NOFOLLOW_LINKS,
                )
            }.getOrNull() ?: return null
        if (attributes.isSymbolicLink || attributes.isOther) return null
        val name = path.fileName?.toString() ?: return null
        // 安装过程的临时文件不列出。
        if (name.startsWith(".molifox-")) return null
        val directory = attributes.isDirectory
        if (!directory && !(attributes.isRegularFile && name.endsWith(".zip", true))) return null
        // 两条列表都没有 = 原版默认启用。
        val enabled = packs?.enabledOf(name)
        return DatapackEntry(
            name = name,
            path = path,
            directory = directory,
            size = if (directory) directorySize(path) else attributes.size(),
            enabled = enabled,
        )
    }

    private fun directorySize(directory: Path): Long {
        var total = 0L
        runCatching {
            Files.walkFileTree(
                directory,
                object : SimpleFileVisitor<Path>() {
                    override fun preVisitDirectory(
                        dir: Path,
                        attrs: BasicFileAttributes,
                    ): FileVisitResult =
                        if (dir != directory && (attrs.isOther || attrs.isSymbolicLink)) {
                            FileVisitResult.SKIP_SUBTREE
                        } else {
                            FileVisitResult.CONTINUE
                        }

                    override fun visitFile(
                        file: Path,
                        attrs: BasicFileAttributes,
                    ): FileVisitResult {
                        if (attrs.isRegularFile && !attrs.isOther) {
                            total += runCatching { file.fileSize() }.getOrDefault(0L)
                        }
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFileFailed(
                        file: Path,
                        error: IOException,
                    ): FileVisitResult = FileVisitResult.CONTINUE
                },
            )
        }
        return total
    }
}
