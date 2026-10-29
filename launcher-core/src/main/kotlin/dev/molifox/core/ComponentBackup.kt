// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 保存组件变更前的版本配置链，并在加载器升级失败时恢复原配置。

package dev.molifox.core

import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.util.UUID
import kotlin.io.path.*

internal class ComponentBackup(private val store: LauncherStore) {
    fun capture(instance: GameInstance): String {
        val id = UUID.randomUUID().toString()
        val folder = store.directories.instanceBackups.resolve("components").resolve(id)
        folder.createDirectories()
        val root = store.minecraftRoot(instance)
        val records = mutableListOf<Map<String, String>>()
        val visited = mutableSetOf<String>()
        var version = instance.versionId
        var total = 0L
        while (version.isNotBlank() && visited.add(version)) {
            require(visited.size <= 64) { "版本配置继承层级过深" }
            val file = safePath(root, "versions/${safeFilename(version)}/$version.json")
            if (!file.exists(NOFOLLOW_LINKS)) break
            require(
                file.isRegularFile(NOFOLLOW_LINKS) &&
                    !file.isSymbolicLink() &&
                    file.fileSize() <= 8 * 1024 * 1024
            ) {
                "版本配置路径不安全：$file"
            }
            total += file.fileSize()
            require(total <= 32 * 1024 * 1024) { "版本配置过大" }
            val target = folder.resolve("${records.size}.json")
            Files.copy(file, target)
            records.add(
                mapOf(
                    "path" to root.relativize(file).joinToString("/"),
                    "file" to target.name,
                    "sha256" to digest(target, "SHA-256"),
                )
            )
            version = readObject(file).string("inheritsFrom")
        }
        val alias =
            store
                .versionGamePath(instance)
                .resolve("${store.versionGamePath(instance).fileName}.json")
        if (
            alias.isRegularFile(NOFOLLOW_LINKS) &&
                records.none { it["path"] == root.relativize(alias).joinToString("/") }
        ) {
            require(!alias.isSymbolicLink() && alias.fileSize() <= 8 * 1024 * 1024)
            val file = "${records.size}.json"
            Files.copy(alias, folder.resolve(file))
            records.add(
                mapOf(
                    "path" to root.relativize(alias).joinToString("/"),
                    "file" to file,
                    "sha256" to digest(alias, "SHA-256"),
                )
            )
        }
        writeJson(
            folder.resolve("manifest.json"),
            mapOf("instance" to instance, "root" to taskPathKey(root), "profiles" to records),
        )
        return id
    }

    fun restore(id: String, current: GameInstance) {
        val manifest = manifest(id, current)
        val folder = directory(id)
        val root = store.minecraftRoot(current)
        val records = manifest.array("profiles").map { it.asJsonObject }
        val files = records.map { record ->
            val relative = record.string("path")
            require(relative.matches(Regex("versions/[^/]+/[^/]+\\.json")))
            val source = safePath(folder, safeFilename(record.string("file")))
            require(
                source.isRegularFile(NOFOLLOW_LINKS) &&
                    !source.isSymbolicLink() &&
                    digest(source, "SHA-256") == record.string("sha256")
            ) {
                "版本配置备份已改变，未恢复"
            }
            source to safePath(root, relative)
        }
        files.forEach { (source, target) ->
            require(!target.isSymbolicLink())
            target.parent.createDirectories()
            Files.copy(source, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }
    }

    /** 升级成功或回滚完成后丢弃备份，避免 `backups/components/` 随升级次数无限增长。 */
    fun discard(id: String) {
        val folder = directory(id)
        if (!folder.isDirectory(NOFOLLOW_LINKS) || folder.isSymbolicLink()) return
        runCatching {
            Files.walk(folder).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
            }
        }
    }

    private fun manifest(id: String, current: GameInstance): com.google.gson.JsonObject {
        val result = readObject(directory(id).resolve("manifest.json"))
        require(
            result.obj("instance").string("id") == current.id &&
                result.string("root") == taskPathKey(store.minecraftRoot(current))
        ) {
            "组件备份不属于当前实例或游戏目录"
        }
        return result
    }

    private fun directory(id: String): java.nio.file.Path {
        require(runCatching { UUID.fromString(id) }.isSuccess) { "组件备份编号无效" }
        return safePath(store.directories.instanceBackups.resolve("components"), id)
    }
}
