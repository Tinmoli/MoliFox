package dev.molifox.core

import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import kotlin.io.path.*

/** All launcher-owned paths live beneath this portable root; game directories are separate. */
class LauncherDirectories(root: Path) {
    val root: Path = root.toAbsolutePath().normalize()
    val config = this.root.resolve("config")
    val configuration = config.resolve("launcher.json")
    val credentials = this.root.resolve("credentials")
    val appearance = this.root.resolve("appearance")
    val backgrounds = appearance.resolve("backgrounds")
    val fonts = appearance.resolve("fonts")
    val music = appearance.resolve("music")
    val themes = appearance.resolve("themes")
    val skins = this.root.resolve("skins")
    val icons = this.root.resolve("icons")
    val runtimes = this.root.resolve("runtimes")
    val dependencies = this.root.resolve("dependencies")
    val logs = this.root.resolve("logs")
    val launcherLog = logs.resolve("launcher.log")
    val previousLog = logs.resolve("launcher.previous.log")
    val backups = this.root.resolve("backups")
    val configurationBackups = backups.resolve("config")
    val previousConfiguration = configurationBackups.resolve("launcher.previous.json")
    val packArchives = backups.resolve("packs")
    val instanceBackups = backups.resolve("instances")

    fun cache(customPath: String = "") =
        LauncherCacheDirectories(
            customPath.takeIf { it.isNotBlank() }?.let(Path::of) ?: root.resolve("cache")
        )

    internal fun initialize(): Map<String, String> {
        createStorageDirectory(root)
        listOf(
                config,
                credentials,
                backgrounds,
                fonts,
                music,
                themes,
                skins,
                icons,
                runtimes,
                dependencies,
                logs,
                configurationBackups,
                packArchives,
                instanceBackups,
            )
            .forEach(::createStorageDirectory)
        val moves =
            StorageMigration(root, config.resolve("storage-layout.json"), backups.resolve("legacy"))
        moves.move("launcher.json", "config/launcher.json")
        moves.move("launcher.json.bak", "backups/config/launcher.previous.json")
        moves.move("launcher.log", "logs/launcher.log")
        moves.move("launcher.previous.log", "logs/launcher.previous.log")
        moves.move("packs", "backups/packs")
        moves.move("backup", "backups/instances")
        moves.move("launcher", "dependencies/launcher")
        moves.move("cache/skiko", "dependencies/native")
        moves.move("appearance/background.img", "appearance/backgrounds/background.img")
        if (root.exists())
            root.listDirectoryEntries("launcher.json.damaged-*").forEach {
                moves.move(it.name, "backups/config/${it.name}")
            }
        moves.complete()
        return moves.relocations
    }
}

class LauncherCacheDirectories(root: Path) {
    val root: Path = root.toAbsolutePath().normalize()
    val downloads = this.root.resolve("downloads")
    val metadata = this.root.resolve("metadata")
    val skins = this.root.resolve("skins")
    val temporary = this.root.resolve("tmp")

    internal fun initialize() {
        listOf(this.root, downloads, metadata, skins, temporary).forEach(::createStorageDirectory)
        val moves =
            StorageMigration(
                root,
                metadata.resolve("storage-layout.json"),
                metadata.resolve("legacy"),
            )
        moves.move("versions.json", "metadata/versions.json")
        moves.move("java21.json", "metadata/java21.json")
        moves.move("java21.zip.part", "downloads/java/java21.zip.part")
        moves.move("java21.tar.gz.part", "downloads/java/java21.tar.gz.part")
        moves.move("pack-downloads", "downloads/packs")
        for (file in root.listDirectoryEntries()) {
            if (
                Regex("java-[0-9]+-[a-zA-Z0-9_-]+\\.(?:zip|tar\\.gz)(?:\\.part)?")
                    .matches(file.name)
            )
                moves.move(file.name, "downloads/java/${file.name}")
            if (Regex("(?:forge|neoforge)-.+-installer\\.jar(?:\\.part)?").matches(file.name))
                moves.move(file.name, "downloads/loaders/${file.name}")
        }
        moves.complete()
    }
}

/** Reject links/junctions before a directory is used as a migration destination. */
internal fun createStorageDirectory(path: Path) {
    val normalized = path.toAbsolutePath().normalize()
    normalized.parent?.let(::createStorageDirectory)
    if (!normalized.exists(NOFOLLOW_LINKS)) {
        normalized.createDirectory()
    }
    val attributes =
        Files.readAttributes(normalized, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
    require(attributes.isDirectory && !attributes.isSymbolicLink && !attributes.isOther) {
        "启动器数据目录不是普通文件夹：$normalized"
    }
}

/**
 * Journal each relocation before moving a file. Interrupted migrations are replayable; conflicting
 * legacy content is retained by hash rather than overwriting the current layout. Paths in the
 * journal are relative to the owning root and contain no configuration values or credentials.
 */
private class StorageMigration(
    private val root: Path,
    private val journal: Path,
    private val conflicts: Path,
) {
    val relocations = linkedMapOf<String, String>()

    init {
        if (journal.exists(NOFOLLOW_LINKS)) {
            require(journal.isRegularFile(NOFOLLOW_LINKS) && !Files.isSymbolicLink(journal))
            val previous = readObject(journal)
            require(previous.int("layoutVersion") == 1) { "数据目录版本不受支持" }
            previous.obj("relocations").entrySet().forEach { (from, to) ->
                safePath(root, from)
                safePath(root, to.asString)
                relocations[from] = to.asString
            }
        }
    }

    fun move(from: String, to: String) {
        val source = safePath(root, from)
        if (!source.exists(NOFOLLOW_LINKS)) return
        val destination = safePath(root, to)
        val attributes =
            Files.readAttributes(source, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        require(!attributes.isSymbolicLink && !attributes.isOther) { "旧数据包含链接，未迁移：$source" }
        if (attributes.isDirectory) {
            createStorageDirectory(destination)
            source.listDirectoryEntries().forEach { child ->
                move(
                    root.relativize(child).joinToString("/"),
                    root.relativize(destination.resolve(child.name)).joinToString("/"),
                )
            }
            if (source.listDirectoryEntries().isEmpty()) source.deleteExisting()
            return
        }
        require(attributes.isRegularFile) { "旧数据不是普通文件：$source" }
        createStorageDirectory(destination.parent)
        val sourceHash = digest(source, "SHA-256")
        var target = destination
        if (target.exists(NOFOLLOW_LINKS)) {
            require(target.isRegularFile(NOFOLLOW_LINKS) && !Files.isSymbolicLink(target)) {
                "迁移目标不是普通文件：$target"
            }
            if (digest(target, "SHA-256") != sourceHash) {
                val nameHash =
                    java.security.MessageDigest.getInstance("SHA-256")
                        .digest(from.toByteArray())
                        .joinToString("") { "%02x".format(it) }
                        .take(16)
                createStorageDirectory(conflicts)
                target = conflicts.resolve("$nameHash-$sourceHash-${source.name}")
                require(
                    !target.exists(NOFOLLOW_LINKS) ||
                        target.isRegularFile(NOFOLLOW_LINKS) &&
                            !Files.isSymbolicLink(target) &&
                            digest(target, "SHA-256") == sourceHash
                ) {
                    "旧数据保留位置存在冲突：$target"
                }
            }
        }
        relocations[from] = root.relativize(target).joinToString("/")
        save(false)
        if (target.exists(NOFOLLOW_LINKS)) {
            // Only remove a redundant legacy copy after verifying both complete contents.
            require(digest(target, "SHA-256") == sourceHash)
            source.deleteExisting()
        } else Files.move(source, target)
    }

    fun complete() = save(true)

    private fun save(complete: Boolean) {
        val value =
            com.google.gson.JsonObject().apply {
                addProperty("layoutVersion", 1)
                addProperty("complete", complete)
                add("relocations", gson.toJsonTree(relocations))
            }
        if (!journal.exists() || readObject(journal) != value) writeJson(journal, value)
    }
}
