// EasyTier 单目录布局、旧版迁移与安装元数据。
package dev.molifox.multiplayer.easytier

import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes

internal const val INSTALL_METADATA = "install.json"
private const val STAGING_DIRECTORY = "staging"

/** 备份放在本次事务目录，避免与并发启动清理旧缓存的任务发生冲突。 */
internal fun moveIntoPlace(
    staging: Path,
    target: Path,
    logger: (String, Boolean) -> Unit,
) {
    target.parent?.let { Files.createDirectories(it) }
    val backup = staging.resolveSibling("previous")
    deleteRecursively(backup, logger)
    if (Files.exists(target)) Files.move(target, backup)
    try {
        runCatching { Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE) }
            .recoverCatching { Files.move(staging, target) }
            .getOrThrow()
    } catch (failure: IOException) {
        deleteRecursively(target, logger)
        if (Files.exists(backup)) Files.move(backup, target)
        throw failure
    }
    applyExecutablePermissions(target, logger)
    deleteRecursively(backup, logger)
}

internal suspend fun extractRequiredFiles(
    pkg: EasyTierPackage,
    archive: Path,
    staging: Path,
) {
    ZipExtractor.extract(
        archive = archive,
        destination = staging,
        rootPrefix = pkg.rootPrefix,
        includedFiles = pkg.requiredFiles.toSet(),
    )
}

internal fun migrateLegacyStaging(
    pkg: EasyTierPackage,
    work: Path,
    target: Path,
): Path? {
    val legacy = target.resolve(pkg.version).resolve(pkg.classifier)
    val directories = listOf(target, target.resolve(pkg.version), legacy)
    if (directories.any { !Files.exists(it, NOFOLLOW_LINKS) } ||
        pkg.requiredFiles.any { !isUsableFile(legacy.resolve(it)) }
    ) {
        return null
    }
    for (directory in directories) {
        val attributes = Files.readAttributes(directory, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        check(attributes.isDirectory && !attributes.isSymbolicLink && !attributes.isOther) {
            "EasyTier legacy installation is not an ordinary directory: $directory"
        }
    }
    val staging = work.resolve(STAGING_DIRECTORY)
    Files.createDirectories(staging)
    pkg.requiredFiles.forEach { name ->
        Files.copy(legacy.resolve(name), staging.resolve(name), StandardCopyOption.REPLACE_EXISTING)
    }
    writeMetadata(staging, pkg)
    return staging
}

internal fun metadataMatches(
    directory: Path,
    pkg: EasyTierPackage,
): Boolean =
    runCatching { Files.readString(directory.resolve(INSTALL_METADATA)) == metadata(pkg) }
        .getOrDefault(false)

internal fun writeMetadata(
    directory: Path,
    pkg: EasyTierPackage,
) {
    Files.writeString(directory.resolve(INSTALL_METADATA), metadata(pkg))
}

internal fun isUsableFile(file: Path): Boolean =
    runCatching { Files.isRegularFile(file, NOFOLLOW_LINKS) && Files.size(file) > 0 }.getOrDefault(false)

private fun metadata(pkg: EasyTierPackage): String =
    "{\"version\":\"${pkg.version}\",\"platform\":\"${pkg.classifier}\"}\n"
