// 负责备份并原子删除真实游戏服务器列表中的条目，保护共享目录和运行中的游戏。

package dev.molifox.core

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlin.io.path.*

data class ServerDeletion(val removed: Int, val backup: Path?)

internal class ServerListEditor(private val store: LauncherStore) {
    @Synchronized
    fun delete(instanceId: String, address: String): ServerDeletion {
        val instance = store.instance(instanceId)
        val directory = store.gamePath(instance).toAbsolutePath().normalize()
        require(
            store.state.value.runningGames.none {
                taskPathKey(store.instancePath(it.instanceId)) == taskPathKey(directory)
            }
        ) {
            "游戏正在使用此服务器列表，请先退出使用该目录的游戏"
        }
        requireSafeServerPath(directory)
        val file = directory.resolve("servers.dat")
        if (!file.exists()) {
            forgetShared(directory, instanceId, address)
            return ServerDeletion(0, null)
        }
        requireSafeServerPath(file)
        val before =
            Files.readAllBytes(
                file.also { require(it.fileSize() <= 8L * 1024 * 1024) { "服务器列表过大" } }
            )
        val document = readNbtDocument(file)
        val servers = requireNotNull(document.root.list("servers")) { "服务器列表格式无效，未修改文件" }
        val target = requireNotNull(parseServerAddress(address)) { "服务器地址无效" }
        val remaining =
            servers.elements.filterNot {
                it is NbtCompound &&
                    sameServerAddress(parseServerAddress(it.string("ip").orEmpty()), target)
            }
        val removed = servers.elements.size - remaining.size
        if (removed == 0) {
            forgetShared(directory, instanceId, address)
            return ServerDeletion(0, null)
        }
        val replacement =
            document.copy(
                root =
                    NbtCompound(
                        document.root.entries +
                            ("servers" to NbtList(remaining, servers.elementType))
                    )
            )
        val bytes = encodeNbt(replacement)
        val backup =
            store.dataDirectory
                .resolve("backups/servers")
                .apply { createDirectories() }
                .resolve("${UUID.randomUUID()}.dat")
        requireSafeServerPath(backup.parent)
        Files.write(backup, before, java.nio.file.StandardOpenOption.CREATE_NEW)
        val temporary = file.resolveSibling(".servers-${UUID.randomUUID()}.part")
        try {
            Files.write(temporary, bytes, java.nio.file.StandardOpenOption.CREATE_NEW)
            requireSafeServerPath(file)
            require(Files.readAllBytes(file).contentEquals(before)) { "游戏服务器列表已变化，请刷新后再删除" }
            Files.move(
                temporary,
                file,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } finally {
            Files.deleteIfExists(temporary)
        }
        forgetShared(directory, instanceId, address)
        store.log("服务器已删除：$address；原列表备份：$backup")
        return ServerDeletion(removed, backup)
    }

    private fun forgetShared(directory: Path, instanceId: String, address: String) {
        val ids =
            store.data.instances
                .filter { taskPathKey(store.gamePath(it)) == taskPathKey(directory) }
                .map { it.id } + instanceId
        ids.distinct().forEach { id ->
            val keys = store.recentUsage.recent(EntryKind.Server, id, 100) + address
            keys
                .distinct()
                .filter { sameServerAddress(parseServerAddress(it), parseServerAddress(address)) }
                .forEach {
                    store.recentUsage.forget(EntryKind.Server, id, it)
                }
        }
    }
}

private fun requireSafeServerPath(path: Path) {
    var candidate: Path? = path
    while (candidate != null) {
        if (Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
            val attributes =
                Files.readAttributes(
                    candidate,
                    java.nio.file.attribute.BasicFileAttributes::class.java,
                    LinkOption.NOFOLLOW_LINKS,
                )
            require(!attributes.isSymbolicLink && !attributes.isOther) { "服务器列表路径不能经过链接或联接" }
        }
        candidate = candidate.parent
    }
}
