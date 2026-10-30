// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责实例的校验、目录登记和安装中间目录的回收。

package dev.molifox.core

import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries

/** 实例名称允许的最大字符数，与界面提示保持一致。 */
private const val MAX_INSTANCE_NAME_LENGTH = 80

/** 实例目录的登记与清理：显示名就是目录名，冲突时在动磁盘前报错。 */
internal class InstanceProvisioner(
    private val store: LauncherStore,
) {
    fun validate(request: InstallRequest) {
        require(request.name.isNotBlank() && request.name.length <= MAX_INSTANCE_NAME_LENGTH) {
            "实例名称需要 1–80 个字符"
        }
        safeFilename(request.gameVersion)
        if (request.loader != LoaderType.Vanilla) {
            require(request.loaderVersion.isNotBlank()) { "请选择加载器版本" }
            safeFilename(request.loaderVersion)
        }
        // 显示名就是目录名：系统保留名等无法落盘的名字在动磁盘前就报错。
        instanceDirectoryName(request.name.trim())
    }

    /**
     * 登记一个新实例：先确定唯一的实际目录名，目录已存在时直接报错。
     *
     * 不再自动生成 `-2` 之类的第二个目录，也不再让显示名与目录名分叉；安装期间目录登记为
     * 「安装中」，扫描不会把还没有 JSON 的空目录显示成无法识别的实例。
     */
    fun createInstance(
        request: InstallRequest,
        gameDirectoryId: String? = store.data.activeGameDirectoryId,
    ): GameInstance {
        validate(request)
        val root = installRoot(gameDirectoryId)
        val directoryName = instanceDirectoryName(request.name.trim())
        val folder = safePath(root.resolve("versions"), directoryName)
        requireAvailableDirectory(root, directoryName, folder)
        // 身份就是目录名：id 由目录名和游戏根算出来，和扫描结果是同一个。
        val id = store.instanceIdFor(directoryName, gameDirectoryId)
        val loaderId = loaderIdOf(request)
        store.beginPendingInstall(directoryName, request.gameVersion, loaderId)
        return try {
            folder.createDirectories()
            registerInstance(request, directoryName, gameDirectoryId, id)
        } catch (expected: Throwable) {
            // 登记失败不留空目录，也不留着「安装中」标记；Error 也要清理，所以必须捕获 Throwable。
            cleanupFailedRegistration(directoryName, folder)
            throw expected
        }
    }

    /** 实例记录只认所选游戏根；没有指定时落到启动器自己的游戏根。 */
    private fun installRoot(gameDirectoryId: String?): Path =
        gameDirectoryId?.let { id ->
            Path.of(
                store.data.gameDirectories
                    .first { it.id == id }
                    .path,
            )
        } ?: store.minecraftRoot

    /** 目录名既不能与磁盘上的目录重名，也不能与已有实例的目录名只差大小写。 */
    private fun requireAvailableDirectory(
        root: Path,
        directoryName: String,
        folder: Path,
    ) {
        require(!folder.exists(NOFOLLOW_LINKS)) {
            "versions/$directoryName 已存在，请换一个实例名称或先处理该目录"
        }
        val windows = store.platform.osName == "windows"
        store.data.instances
            .firstOrNull {
                taskPathKey(store.minecraftRoot(it)) == taskPathKey(root) &&
                    it.instanceFolderName().equals(directoryName, windows)
            }?.let { clash ->
                throw IllegalArgumentException("已有实例「${clash.name}」使用这个名称")
            }
    }

    private fun loaderIdOf(request: InstallRequest): String =
        if (request.loader == LoaderType.Vanilla) {
            ""
        } else {
            "${request.loader.name.lowercase()}-${request.loaderVersion}"
        }

    private fun registerInstance(
        request: InstallRequest,
        directoryName: String,
        gameDirectoryId: String?,
        id: String,
    ): GameInstance {
        val instance =
            GameInstance(
                id = id,
                name = directoryName,
                gameVersion = request.gameVersion,
                versionId = directoryName,
                gameDirectoryName = directoryName,
                loader = request.loader,
                loaderVersion = request.loaderVersion,
                gameDirectoryId = gameDirectoryId,
                managedGameDirectory = true,
            )
        store.mutate {
            it.copy(
                instances = it.instances + instance,
                selectedInstanceId =
                    if (it.activeGameDirectoryId == gameDirectoryId) {
                        instance.id
                    } else {
                        it.selectedInstanceId
                    },
            )
        }
        return store.instance(id)
    }

    private fun cleanupFailedRegistration(
        directoryName: String,
        folder: Path,
    ) {
        store.endPendingInstall(directoryName)
        runCatching {
            if (folder.isDirectory(NOFOLLOW_LINKS) && folder.listDirectoryEntries().isEmpty()) {
                Files.deleteIfExists(folder)
            }
        }
    }

    /** 新安装必须在写盘前确认目标目录可用：显示名就是目录名，被占用时拒绝而不是另造目录。 */
    fun requireNewInstanceDirectory(
        root: Path,
        target: Path,
    ) {
        require(target.startsWith(root.resolve("versions"))) { "实例目录不在所选游戏根下" }
        if (!target.exists(NOFOLLOW_LINKS)) return
        // 目录由 createInstance 建好时是空的；里面已经有内容说明名字被别的来源占用了。
        require(target.isDirectory(NOFOLLOW_LINKS) && target.listDirectoryEntries().isEmpty()) {
            "versions/${target.fileName} 已被占用，请换一个实例名称"
        }
    }

    /** 新安装失败后不留下空目录和记录：它下次会被扫描成「无法识别」的实例。 */
    fun discardFailedFreshInstall(
        instance: GameInstance,
        folderName: String,
    ) {
        runCatching {
            val folder = store.versionGamePath(instance)
            if (folder.isDirectory(NOFOLLOW_LINKS) && folder.listDirectoryEntries().isEmpty()) {
                Files.deleteIfExists(folder)
            }
        }
        runCatching {
            store.mutate { data ->
                data.copy(
                    instances = data.instances.filter { it.instanceSettingsKey() != folderName },
                    selectedInstanceId =
                        if (data.selectedInstanceId == instance.id) {
                            null
                        } else {
                            data.selectedInstanceId
                        },
                )
            }
        }
    }

    /**
     * 回收本任务新建的中间版本目录（基础版本目录等）。
     *
     * 只处理本次确认「原本不存在」的目录；目标实例自己的目录永远保留，其它实例仍按实际目录身份、
     * `inheritsFrom` 或 `jar` 引用它时也保留并写日志。同名游戏版本不算引用。
     */
    fun reclaimCreatedVersionDirectories(
        created: Set<Path>,
        instanceId: String,
        context: JobContext,
    ) {
        if (created.isEmpty()) return
        // 取消时实例记录可能已经被回收，此时没有引用可判断，交给任务产物清理。
        val instance = store.data.instances.find { it.id == instanceId } ?: return
        val versionsRoot =
            store
                .minecraftRoot(instance)
                .toAbsolutePath()
                .normalize()
                .resolve("versions")
        val target = store.versionGamePath(instance).toAbsolutePath().normalize()
        for (directory in created.sortedByDescending { it.nameCount }) {
            val path = directory.toAbsolutePath().normalize()
            if (path == target || path.parent != versionsRoot) continue
            runCatching {
                if (!path.isDirectory(NOFOLLOW_LINKS) ||
                    Files.isSymbolicLink(path)
                ) {
                    return@runCatching
                }
                require(path.toRealPath() == path) { "目录已变为链接，未删除" }
                if (!context.canRemoveDownload(path)) {
                    store.log("中间版本目录仍被其他实例引用，已保留：versions/${path.fileName}")
                    return@runCatching
                }
                val paths = Files.walk(path).use { it.toList() }
                require(paths.all { !Files.isSymbolicLink(it) && it.toRealPath() == it }) {
                    "目录包含链接，未删除"
                }
                paths.sortedByDescending { it.nameCount }.forEach(Files::deleteIfExists)
                store.log("已回收安装中间版本目录：versions/${path.fileName}")
            }.onFailure {
                store.log("回收安装中间目录失败：versions/${path.fileName}（${it.message}）", true)
            }
        }
    }
}
