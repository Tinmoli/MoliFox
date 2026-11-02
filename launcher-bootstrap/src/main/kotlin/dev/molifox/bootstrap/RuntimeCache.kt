// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

package dev.molifox.bootstrap

import dev.molifox.core.i18n.I18n
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.time.LocalDateTime
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

/** 运行依赖直接放在 `MoliFox/runtime/`；不再为启动器版本创建子目录。 */
internal fun runtimeCacheDirectory(data: Path): Path = data.resolve("runtime")

internal fun runtimeMarkerMatches(
    marker: Path,
    manifest: RuntimeManifest,
): Boolean =
    marker.isRegularFile(LinkOption.NOFOLLOW_LINKS) &&
        runCatching {
            Files.readAllLines(marker).firstOrNull() == "version=${manifest.version}"
        }.getOrDefault(false)

internal fun isRuntimeDependencyCached(
    target: Path,
    dependency: RuntimeDependency,
): Boolean {
    if (!target.isRegularFile(LinkOption.NOFOLLOW_LINKS) || Files.size(target) != dependency.size) return false
    return sha256Of(target).equals(dependency.sha256, ignoreCase = true)
}

/** 标记匹配时只检查文件存在；清单变化或旧缓存迁移后才逐文件重新校验。 */
internal fun runtimeCacheReady(
    data: Path,
    cache: Path,
    marker: Path,
    manifest: RuntimeManifest,
): Boolean {
    val markedReady =
        runtimeMarkerMatches(marker, manifest) &&
            manifest.dependencies.all { Files.isRegularFile(cache.resolve(it.filename)) }
    val verified =
        markedReady || manifest.dependencies.all { isRuntimeDependencyCached(cache.resolve(it.filename), it) }
    if (!verified) return false
    if (!markedReady) writeRuntimeMarker(marker, manifest)
    cleanupRuntimeCache(data, cache, manifest.dependencies)
    log(I18n.t("bootstrapB.startup.alreadyReady"))
    return true
}

internal fun writeRuntimeMarker(
    marker: Path,
    manifest: RuntimeManifest,
) {
    Files.writeString(
        marker,
        "version=${manifest.version}\nverified=${LocalDateTime.now()}\n" +
            "files=${manifest.dependencies.size}\n",
    )
}

/** 首次采用稳定目录时，从旧版本缓存中复用逐字节校验通过的文件。 */
internal fun migrateLegacyRuntime(
    data: Path,
    cache: Path,
    dependencies: List<RuntimeDependency>,
) {
    val root = data.resolve("runtime")
    if (!root.isDirectory(LinkOption.NOFOLLOW_LINKS)) return
    val legacy =
        Files.list(root).use { paths ->
            paths
                .filter(::isLegacyRuntimeDirectory)
                .toList()
        }
    dependencies.forEach { dependency ->
        val target = cache.resolve(dependency.filename)
        if (isRuntimeDependencyCached(target, dependency)) return@forEach
        val source =
            legacy
                .asSequence()
                .map { it.resolve(dependency.filename) }
                .firstOrNull { isRuntimeDependencyCached(it, dependency) } ?: return@forEach
        val part = Files.createTempFile(cache, ".molifox-migrate-", ".part")
        try {
            Files.copy(source, part, StandardCopyOption.REPLACE_EXISTING)
            moveRuntimeIntoPlace(part, target)
            log(I18n.t("bootstrapB.cache.migrated", dependency.filename))
        } finally {
            Files.deleteIfExists(part)
        }
    }
}

/** 新清单全部就绪后，清理当前目录中的过期 JAR 与旧版缓存目录。 */
internal fun cleanupRuntimeCache(
    data: Path,
    cache: Path,
    dependencies: List<RuntimeDependency>,
) {
    val expected = dependencies.mapTo(HashSet()) { it.filename }
    Files.list(cache).use { paths ->
        paths
            .filter { path ->
                path.isRegularFile(LinkOption.NOFOLLOW_LINKS) &&
                    path.name.endsWith(".jar", true) &&
                    path.name !in expected
            }.forEach { stale ->
                runCatching { Files.deleteIfExists(stale) }
                    .onSuccess { log(I18n.t("bootstrapB.cache.removed", stale.name)) }
                    .onFailure { log(I18n.t("bootstrapB.cache.cleanupFailed", stale.name, it.message.orEmpty())) }
            }
    }
    val root = data.resolve("runtime")
    Files.list(root).use { paths ->
        paths.filter(::isLegacyRuntimeDirectory).forEach { legacy ->
            runCatching { deleteRuntimeDirectory(legacy) }
                .onFailure { log(I18n.t("bootstrapB.cache.cleanupFailed", legacy.name, it.message.orEmpty())) }
        }
    }
}

private fun isLegacyRuntimeDirectory(path: Path): Boolean {
    if (!LEGACY_RUNTIME_DIRECTORY.matches(path.name)) return false
    val attributes =
        runCatching {
            Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        }.getOrNull() ?: return false
    return attributes.isDirectory && !attributes.isOther && !attributes.isSymbolicLink
}

private val LEGACY_RUNTIME_DIRECTORY = Regex(".+-[0-9a-fA-F]{16}")

private fun deleteRuntimeDirectory(root: Path) {
    Files.walkFileTree(
        root,
        object : SimpleFileVisitor<Path>() {
            override fun visitFile(
                file: Path,
                attrs: BasicFileAttributes,
            ): FileVisitResult {
                if (!attrs.isSymbolicLink && !attrs.isOther) Files.deleteIfExists(file)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(
                dir: Path,
                error: java.io.IOException?,
            ): FileVisitResult {
                if (error == null) Files.deleteIfExists(dir)
                return FileVisitResult.CONTINUE
            }
        },
    )
}

internal fun moveRuntimeIntoPlace(
    part: Path,
    target: Path,
) {
    try {
        Files.move(part, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
        Files.move(part, target, StandardCopyOption.REPLACE_EXISTING)
    }
}
