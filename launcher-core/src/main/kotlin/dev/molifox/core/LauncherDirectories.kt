// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责便携数据、依赖和缓存目录的解析。

package dev.molifox.core

import dev.molifox.core.i18n.I18n
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import kotlin.io.path.createDirectory
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isSymbolicLink
import kotlin.io.path.readAttributes

/** All launcher-owned paths live beneath this portable root; game directories are separate. */
class LauncherDirectories(
    root: Path,
) {
    val root: Path = root.toAbsolutePath().normalize()
    val config = this.root.resolve("config")
    val configuration = config.resolve("launcher.json")

    /** 旧版凭据目录：只读，用于把旧凭据迁移到系统凭据位置；不再写入。 */
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
            customPath.takeIf { it.isNotBlank() }?.let(Path::of) ?: root.resolve("cache"),
        )

    internal fun initialize() {
        createStorageDirectory(root)
        listOf(
            config,
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
        ).forEach(::createStorageDirectory)
    }
}

class LauncherCacheDirectories(
    root: Path,
) {
    val root: Path = root.toAbsolutePath().normalize()
    val downloads = this.root.resolve("downloads")
    val metadata = this.root.resolve("metadata")
    val skins = this.root.resolve("skins")
    val temporary = this.root.resolve("tmp")

    internal fun initialize() {
        listOf(this.root, downloads, metadata, skins, temporary).forEach(::createStorageDirectory)
    }
}

/** Reject links/junctions before a directory is used for launcher storage. */
internal fun createStorageDirectory(path: Path) {
    val normalized = path.toAbsolutePath().normalize()
    normalized.parent?.let(::createStorageDirectory)
    if (!normalized.exists(NOFOLLOW_LINKS)) {
        normalized.createDirectory()
    }
    val attributes =
        Files.readAttributes(normalized, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
    require(attributes.isDirectory && !attributes.isSymbolicLink && !attributes.isOther) {
        I18n.t("miscA1.directories.storageNotDirectory", normalized)
    }
}
