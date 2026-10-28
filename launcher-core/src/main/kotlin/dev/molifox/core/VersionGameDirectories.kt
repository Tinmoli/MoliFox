// 管理 versions 下的具名游戏目录，并将旧编号目录安全迁移到版本目录。

package dev.molifox.core

import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import kotlin.io.path.*

internal class VersionGameDirectories(private val store: LauncherStore) {
    fun availableName(root: Path, preferred: String): String {
        val base =
            preferred
                .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "-")
                .trim()
                .trimEnd('.')
                .take(70)
                .ifBlank { "Minecraft" }
        var candidate = base
        var index = 2
        while (
            root.resolve("versions").resolve(candidate).exists(NOFOLLOW_LINKS) ||
                store.data.instances.any {
                    taskPathKey(store.minecraftRoot(it)) == taskPathKey(root) &&
                        it.gameDirectoryName
                            .ifBlank { it.versionId }
                            .equals(candidate, store.platform.osName == "windows")
                }
        ) candidate = "$base-${index++}"
        safeFilename(candidate)
        return candidate
    }

    fun writeProfile(instance: GameInstance) {
        val folder = store.versionGamePath(instance)
        if (folder.fileName.toString() == instance.versionId) return
        // 官方基础配置不能反向继承依赖它的加载器配置，否则会产生循环。
        if (folder.fileName.toString() == instance.gameVersion) return
        val file = folder.resolve("${folder.fileName}.json")
        val profile =
            mapOf(
                "id" to folder.fileName.toString(),
                "inheritsFrom" to instance.versionId,
                "_minecraftVersion" to instance.gameVersion,
            )
        writeJson(file, profile)
    }

    fun migrateLegacy() {
        for (instance in
            store.data.instances.filter { it.managedGameDirectory && !it.externalProfile }) {
            val root = store.minecraftRoot(instance)
            val legacy = root.resolve("instances").resolve(instance.id).toAbsolutePath().normalize()
            require(legacy.parent == root.resolve("instances").toAbsolutePath().normalize())
            if (!legacy.exists(NOFOLLOW_LINKS)) continue
            require(legacy.isDirectory(NOFOLLOW_LINKS) && !legacy.isSymbolicLink()) {
                "旧游戏目录是链接，未自动迁移：$legacy"
            }
            var targetInstance = instance
            var target = store.versionGamePath(targetInstance)
            val files = Files.walk(legacy).use { paths -> paths.filter { it != legacy }.toList() }
            require(
                files.none {
                    it.isSymbolicLink() || it.toRealPath() != it.toAbsolutePath().normalize()
                }
            ) {
                "旧游戏目录含链接，未自动迁移：$legacy"
            }
            val conflict =
                files
                    .filter { it.isRegularFile(NOFOLLOW_LINKS) }
                    .any { source ->
                        val destination = safePath(target, legacy.relativize(source).toString())
                        destination.exists(NOFOLLOW_LINKS) &&
                            (!destination.isRegularFile(NOFOLLOW_LINKS) ||
                                digest(source) != digest(destination))
                    }
            val aliasClash =
                target.fileName.toString() != instance.versionId &&
                    legacy.resolve("${target.fileName}.json").exists(NOFOLLOW_LINKS)
            if (conflict || aliasClash) {
                val preferred = instance.name.take(60)
                var candidate = availableName(root, preferred)
                var attempt = 1
                while (legacy.resolve("$candidate.json").exists(NOFOLLOW_LINKS)) {
                    require(attempt <= 64) { "版本配置名称冲突，原目录保留：$legacy" }
                    candidate = availableName(root, "$preferred-migration-${attempt++}")
                }
                targetInstance = instance.copy(gameDirectoryName = candidate)
                target = store.versionGamePath(targetInstance)
            }
            val ancestor =
                generateSequence(target) { it.parent }.first { it.exists(NOFOLLOW_LINKS) }
            require(ancestor.toRealPath().startsWith(root.toRealPath())) {
                "迁移目标越过游戏目录，原文件保留：$target"
            }
            for (source in files.filter { it.isRegularFile(NOFOLLOW_LINKS) }) {
                val destination = safePath(target, legacy.relativize(source).toString())
                destination.parent.createDirectories()
                if (!destination.exists(NOFOLLOW_LINKS)) Files.copy(source, destination)
                require(
                    destination.isRegularFile(NOFOLLOW_LINKS) &&
                        digest(source) == digest(destination)
                ) {
                    "旧游戏目录迁移校验失败，原文件保留：$source"
                }
            }
            target.createDirectories()
            writeProfile(targetInstance)
            store.updateInstance(instance.id) {
                it.copy(
                    gameDirectoryName = target.fileName.toString(),
                    isolation = VersionIsolation.Inherit,
                )
            }
            val backup = store.directories.instanceBackups.resolve("legacy-${instance.id}")
            if (!backup.exists(NOFOLLOW_LINKS)) {
                try {
                    Files.move(legacy, backup)
                } catch (error: Exception) {
                    store.log("版本目录已迁移，旧目录暂保留：$legacy（${error.message}）", true)
                }
            }
            val retained = if (legacy.exists(NOFOLLOW_LINKS)) legacy else backup
            store.log("游戏目录已迁移到 $target；原编号目录保留为备份 $retained")
        }
    }
}
