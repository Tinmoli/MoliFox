// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 集中模组替换及失败复位，确保查询逻辑不直接管理安装事务。

package dev.molifox.core

import dev.molifox.core.i18n.I18n
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.zip.ZipFile
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

private fun ModUpdateCandidate.toRemoteVersion(): RemoteModVersion =
    RemoteModVersion(
        RemoteModProject(projectId, "", projectTitle, provider),
        versionId,
        versionNumber,
        versionNumber,
        gameVersions,
        loaders,
        "release",
        downloadUrl,
        filename,
        size,
        sha512,
        sha1,
    )

internal class ModUpdateInstaller(
    private val store: LauncherStore,
    private val downloader: ModUpdateDownloader,
) {
    /**
     * 应用一次更新：先下载到 mods 内的临时 JAR 并校验大小/摘要/ZIP 结构，再把旧文件移到回收站。 任何一步失败都不动旧文件；旧文件的
     * `.disabled` 状态沿用新文件名。
     */
    suspend fun apply(
        instanceId: String,
        item: ModUpdateItem,
        context: JobContext? = null,
    ) = withContext(Dispatchers.IO) {
        val candidate = requireApplicable(item)
        val instance = store.instance(instanceId)
        requireSupported(instance, candidate)
        val mods = store.instancePath(instanceId).resolve("mods")
        val old = safePath(mods, safeFilename(item.filename))
        requireUnchanged(old, item)
        val disabled = item.filename.endsWith(".disabled", true)
        val newBase = safeFilename(contentBaseFilename(candidate.filename.trim()))
        require(newBase.endsWith(".jar", true)) { I18n.t("packsC.modUpdate.notJar") }
        val newName = newBase + if (disabled) ".disabled" else ""
        val target = requireVacantTarget(mods, newName, old)
        val temporary = safePath(mods, ".molifox-update-${UUID.randomUUID()}.jar")
        context?.progress(0f, I18n.t("packsB2.packUpdate.downloading", newBase))
        try {
            downloadAndVerify(candidate, temporary, context)
            replaceModFile(mods, old, target, temporary)
            store.log(I18n.t("packsC.modUpdate.applied", item.title, item.currentVersion, candidate.versionNumber))
            context?.progress(1f, I18n.t("packsB2.packUpdate.done"))
        } finally {
            temporary.deleteIfExists()
        }
    }

    private fun requireApplicable(item: ModUpdateItem): ModUpdateCandidate {
        require(
            item.status == ModUpdateStatus.Updatable && item.candidate != null &&
                item.applicable,
        ) {
            I18n.t("packsB2.packUpdate.noneApplicable")
        }
        return requireNotNull(item.candidate)
    }

    private fun requireSupported(
        instance: GameInstance,
        candidate: ModUpdateCandidate,
    ) {
        require(candidate.gameVersions.any { it.equals(instance.gameVersion, true) }) {
            I18n.t("packsB2.packUpdate.unsupportedGameVersion")
        }
        require(
            instance.loader != LoaderType.Vanilla &&
                candidate.loaders.any { it.equals(instance.loader.name, true) },
        ) {
            I18n.t("packsC.modUpdate.unsupportedLoader")
        }
    }

    private fun requireUnchanged(
        old: Path,
        item: ModUpdateItem,
    ) {
        require(old.isRegularFile() && !Files.isSymbolicLink(old)) {
            I18n.t("packsC.modUpdate.fileMissing")
        }
        require(
            old.fileSize() == item.size &&
                old.getLastModifiedTime().toMillis() == item.modified,
        ) {
            I18n.t("packsC.modUpdate.fileChanged")
        }
    }

    private fun requireVacantTarget(
        mods: Path,
        newName: String,
        old: Path,
    ): Path {
        val target = safePath(mods, newName)
        require(!target.exists() || target == old) {
            I18n.t("packsC.modUpdate.targetExists", newName)
        }
        return target
    }

    private suspend fun downloadAndVerify(
        candidate: ModUpdateCandidate,
        temporary: Path,
        context: JobContext?,
    ) {
        downloader(candidate.toRemoteVersion(), temporary, context)
        context?.checkCancelled()
        require(temporary.isRegularFile() && temporary.fileSize() > 0) { I18n.t("packsB2.packUpdate.empty") }
        if (candidate.size > 0) {
            require(temporary.fileSize() == candidate.size) { I18n.t("packsB2.packUpdate.sizeMismatch") }
        }
        requireMatchingDigest(temporary, candidate)
        ZipFile(temporary.toFile()).use { zip ->
            require(zip.entries().hasMoreElements()) { I18n.t("packsC.modUpdate.invalidJar") }
        }
        context?.checkCancelled()
    }

    private fun requireMatchingDigest(
        temporary: Path,
        candidate: ModUpdateCandidate,
    ) {
        if (candidate.sha512.isNotBlank()) {
            require(digest(temporary, "SHA-512").equals(candidate.sha512, true)) {
                I18n.t("packsB2.packUpdate.hashMismatch")
            }
        } else if (candidate.sha1.isNotBlank()) {
            require(digest(temporary, "SHA-1").equals(candidate.sha1, true)) {
                I18n.t("packsB2.packUpdate.hashMismatch")
            }
        }
    }

    private fun replaceModFile(
        mods: Path,
        old: Path,
        target: Path,
        temporary: Path,
    ) {
        val backup = safePath(mods, ".molifox-old-${UUID.randomUUID()}")
        // 旧文件先移到同目录暂存名；新文件落位失败时原样搬回。
        Files.move(old, backup)
        try {
            Files.move(temporary, target)
        } catch (failure: IOException) {
            val restored =
                runCatching {
                    Files.move(backup, old, StandardCopyOption.REPLACE_EXISTING)
                }
            if (restored.isFailure) {
                store.log(
                    I18n.t("packsC.modUpdate.restoreFailed", backup.toAbsolutePath()),
                    true,
                )
                restored.exceptionOrNull()?.let(failure::addSuppressed)
            }
            throw failure
        }
        try {
            store.platform.moveToTrash(backup)
        } catch (error: IOException) {
            // 旧文件已由新文件取代，回收站搬运是尽力而为：失败只记日志并保留备份。
            store.log(
                I18n.t("packsC.modUpdate.trashFailed", backup.toAbsolutePath(), error.message),
                true,
            )
        }
    }
}
