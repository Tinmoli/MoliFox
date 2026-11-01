// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责备份并原子删除真实游戏服务器列表中的条目，保护共享目录和运行中的游戏。

package dev.molifox.core

import dev.molifox.core.i18n.I18n
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.isSymbolicLink
import kotlin.io.path.readAttributes

data class ServerDeletion(
    val removed: Int,
    val backup: Path?,
)

internal class ServerListEditor(
    private val store: LauncherStore,
) {
    @Synchronized
    fun delete(
        instanceId: String,
        address: String,
    ): ServerDeletion {
        val instance = store.instance(instanceId)
        val directory = store.gamePath(instance).toAbsolutePath().normalize()
        require(
            store.state.value.runningGames.none {
                taskPathKey(store.instancePath(it.instanceId)) == taskPathKey(directory)
            },
        ) {
            I18n.t("miscA2.serverList.inUse")
        }
        requireSafeServerPath(directory)
        val file = directory.resolve("servers.dat")
        val deletion = if (file.exists()) deleteFromFile(file, address) else null
        forgetShared(directory, instanceId, address)
        if (deletion != null) {
            store.log(I18n.t("miscA2.serverList.deleted", address, deletion.backup))
        }
        return deletion ?: ServerDeletion(0, null)
    }

    /** 主文件存在时删除条目；没有匹配条目返回 null，且不写备份、不改动原文件。 */
    private fun deleteFromFile(
        file: Path,
        address: String,
    ): ServerDeletion? {
        requireSafeServerPath(file)
        val before =
            Files.readAllBytes(
                file.also {
                    require(it.fileSize() <= 8L * 1024 * 1024) {
                        I18n.t("miscA2.serverList.tooLarge")
                    }
                },
            )
        val document = readNbtDocument(file)
        val servers =
            requireNotNull(document.root.list("servers")) {
                I18n.t("miscA2.serverList.invalidFormat")
            }
        val target =
            requireNotNull(parseServerAddress(address)) {
                I18n.t("diagA1.gameLaunchTarget.invalidServer")
            }
        val remaining =
            servers.elements.filterNot {
                it is NbtCompound &&
                    sameServerAddress(parseServerAddress(it.string("ip").orEmpty()), target)
            }
        val removed = servers.elements.size - remaining.size
        return if (removed == 0) {
            null
        } else {
            val replacement =
                document.copy(
                    root =
                        NbtCompound(
                            document.root.entries +
                                ("servers" to NbtList(remaining, servers.elementType)),
                        ),
                )
            replaceServerList(file, before, encodeNbt(replacement), removed)
        }
    }

    /** 用临时文件原子替换服务器列表；替换前先备份原字节，失败时清掉临时文件。 */
    private fun replaceServerList(
        file: Path,
        before: ByteArray,
        bytes: ByteArray,
        removed: Int,
    ): ServerDeletion {
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
            require(Files.readAllBytes(file).contentEquals(before)) {
                I18n.t("miscA2.serverList.changed")
            }
            Files.move(
                temporary,
                file,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } finally {
            Files.deleteIfExists(temporary)
        }
        return ServerDeletion(removed, backup)
    }

    private fun forgetShared(
        directory: Path,
        instanceId: String,
        address: String,
    ) {
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
            require(!attributes.isSymbolicLink && !attributes.isOther) {
                I18n.t("miscA2.serverList.unsafePath")
            }
        }
        candidate = candidate.parent
    }
}
