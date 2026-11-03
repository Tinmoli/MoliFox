// EasyTier 单目录布局、旧版迁移与安装元数据。
package dev.molifox.multiplayer.easytier

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

internal const val INSTALL_METADATA = "install.json"
private const val STAGING_DIRECTORY = "staging"

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
    if (pkg.requiredFiles.any { !isUsableFile(legacy.resolve(it)) }) return null
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
    runCatching { Files.isRegularFile(file) && Files.size(file) > 0 }.getOrDefault(false)

private fun metadata(pkg: EasyTierPackage): String =
    "{\"version\":\"${pkg.version}\",\"platform\":\"${pkg.classifier}\"}\n"
