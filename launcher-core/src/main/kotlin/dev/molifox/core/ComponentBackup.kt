// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 保存组件变更前的版本配置链，并在加载器升级失败时恢复原配置。

package dev.molifox.core

import dev.molifox.core.i18n.I18n
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.util.UUID
import kotlin.io.path.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.isSymbolicLink
import kotlin.io.path.name
import kotlin.io.path.walk

/** 继承链层级上限：正常实例只有几级，挡住自引用或构造出的超长链。 */
private const val MAX_VERSION_CHAIN_DEPTH = 64

/** 单个版本配置与整条链的总大小上限，避免异常数据写满备份目录。 */
private const val MAX_VERSION_FILE_BYTES = 8L * 1024 * 1024
private const val MAX_VERSION_CHAIN_BYTES = 32L * 1024 * 1024

internal class ComponentBackup(
    private val store: LauncherStore,
) {
    fun capture(instance: GameInstance): String {
        val id = UUID.randomUUID().toString()
        val folder =
            store.directories.instanceBackups
                .resolve("components")
                .resolve(id)
        folder.createDirectories()
        val root = store.minecraftRoot(instance)
        val records = mutableListOf<Map<String, String>>()
        captureVersionChain(root, folder, instance.versionId, records)
        captureAlias(instance, root, folder, records)
        writeJson(
            folder.resolve("manifest.json"),
            mapOf("instance" to instance, "root" to taskPathKey(root), "profiles" to records),
        )
        return id
    }

    /** 沿 `inheritsFrom` 逐级复制版本配置，直到链尾、重复或文件缺失为止。 */
    private fun captureVersionChain(
        root: java.nio.file.Path,
        folder: java.nio.file.Path,
        startVersion: String,
        records: MutableList<Map<String, String>>,
    ) {
        val visited = mutableSetOf<String>()
        var version = startVersion
        var total = 0L
        while (version.isNotBlank() && visited.add(version)) {
            require(visited.size <= MAX_VERSION_CHAIN_DEPTH) { I18n.t("diagA1.componentBackup.depthExceeded") }
            val file = safePath(root, "versions/${safeFilename(version)}/$version.json")
            if (!file.exists(NOFOLLOW_LINKS)) break
            require(
                file.isRegularFile(NOFOLLOW_LINKS) &&
                    !file.isSymbolicLink() &&
                    file.fileSize() <= MAX_VERSION_FILE_BYTES,
            ) {
                I18n.t("diagA1.componentBackup.unsafeVersionPath", file)
            }
            total += file.fileSize()
            require(total <= MAX_VERSION_CHAIN_BYTES) { I18n.t("diagA1.componentBackup.versionChainTooLarge") }
            val target = folder.resolve("${records.size}.json")
            Files.copy(file, target)
            records.add(
                mapOf(
                    "path" to root.relativize(file).joinToString("/"),
                    "file" to target.name,
                    "sha256" to digest(target, "SHA-256"),
                ),
            )
            version = readObject(file).string("inheritsFrom")
        }
    }

    /** 备份实例自己的版本别名文件；继承链里已经备过同一路径时跳过。 */
    private fun captureAlias(
        instance: GameInstance,
        root: java.nio.file.Path,
        folder: java.nio.file.Path,
        records: MutableList<Map<String, String>>,
    ) {
        val alias =
            store
                .versionGamePath(instance)
                .resolve("${store.versionGamePath(instance).fileName}.json")
        if (
            alias.isRegularFile(NOFOLLOW_LINKS) &&
            records.none { it["path"] == root.relativize(alias).joinToString("/") }
        ) {
            require(!alias.isSymbolicLink() && alias.fileSize() <= MAX_VERSION_FILE_BYTES)
            val file = "${records.size}.json"
            Files.copy(alias, folder.resolve(file))
            records.add(
                mapOf(
                    "path" to root.relativize(alias).joinToString("/"),
                    "file" to file,
                    "sha256" to digest(alias, "SHA-256"),
                ),
            )
        }
    }

    fun restore(
        id: String,
        current: GameInstance,
    ) {
        val manifest = manifest(id, current)
        val folder = directory(id)
        val root = store.minecraftRoot(current)
        val records = manifest.array("profiles").map { it.asJsonObject }
        val files =
            records.map { record ->
                val relative = record.string("path")
                require(relative.matches(Regex("versions/[^/]+/[^/]+\\.json")))
                val source = safePath(folder, safeFilename(record.string("file")))
                require(
                    source.isRegularFile(NOFOLLOW_LINKS) &&
                        !source.isSymbolicLink() &&
                        digest(source, "SHA-256") == record.string("sha256"),
                ) {
                    I18n.t("diagA1.componentBackup.backupChanged")
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

    private fun manifest(
        id: String,
        current: GameInstance,
    ): com.google.gson.JsonObject {
        val result = readObject(directory(id).resolve("manifest.json"))
        require(
            result.obj("instance").string("id") == current.id &&
                result.string("root") == taskPathKey(store.minecraftRoot(current)),
        ) {
            I18n.t("diagA1.componentBackup.foreignBackup")
        }
        return result
    }

    private fun directory(id: String): java.nio.file.Path {
        require(runCatching { UUID.fromString(id) }.isSuccess) { I18n.t("diagA1.componentBackup.invalidId") }
        return safePath(store.directories.instanceBackups.resolve("components"), id)
    }
}
