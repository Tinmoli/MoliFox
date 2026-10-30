// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 集中模组替换及失败复位，确保查询逻辑不直接管理安装事务。

package dev.molifox.core

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
        require(newBase.endsWith(".jar", true)) { "更新文件不是 JAR" }
        val newName = newBase + if (disabled) ".disabled" else ""
        val target = requireVacantTarget(mods, newName, old)
        val temporary = safePath(mods, ".molifox-update-${UUID.randomUUID()}.jar")
        context?.progress(0f, "下载 $newBase")
        try {
            downloadAndVerify(candidate, temporary, context)
            replaceModFile(mods, old, target, temporary)
            store.log("更新模组：${item.title} ${item.currentVersion} → ${candidate.versionNumber}")
            context?.progress(1f, "更新完成")
        } finally {
            temporary.deleteIfExists()
        }
    }

    private fun requireApplicable(item: ModUpdateItem): ModUpdateCandidate {
        require(
            item.status == ModUpdateStatus.Updatable && item.candidate != null &&
                item.applicable,
        ) {
            "没有可应用的更新"
        }
        return requireNotNull(item.candidate)
    }

    private fun requireSupported(
        instance: GameInstance,
        candidate: ModUpdateCandidate,
    ) {
        require(candidate.gameVersions.any { it.equals(instance.gameVersion, true) }) {
            "更新文件不支持当前游戏版本"
        }
        require(
            instance.loader != LoaderType.Vanilla &&
                candidate.loaders.any { it.equals(instance.loader.name, true) },
        ) {
            "更新文件不支持当前加载器"
        }
    }

    private fun requireUnchanged(
        old: Path,
        item: ModUpdateItem,
    ) {
        require(old.isRegularFile() && !Files.isSymbolicLink(old)) {
            "模组文件已不存在，请重新检查更新"
        }
        require(
            old.fileSize() == item.size &&
                old.getLastModifiedTime().toMillis() == item.modified,
        ) {
            "模组文件已改变，请重新检查更新"
        }
    }

    private fun requireVacantTarget(
        mods: Path,
        newName: String,
        old: Path,
    ): Path {
        val target = safePath(mods, newName)
        require(!target.exists() || target == old) {
            "目标文件已存在，请先处理重复模组：$newName"
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
        require(temporary.isRegularFile() && temporary.fileSize() > 0) { "更新文件为空" }
        if (candidate.size > 0) {
            require(temporary.fileSize() == candidate.size) { "更新文件大小不符" }
        }
        requireMatchingDigest(temporary, candidate)
        ZipFile(temporary.toFile()).use { zip ->
            require(zip.entries().hasMoreElements()) { "更新文件不是有效的 JAR" }
        }
        context?.checkCancelled()
    }

    private fun requireMatchingDigest(
        temporary: Path,
        candidate: ModUpdateCandidate,
    ) {
        if (candidate.sha512.isNotBlank()) {
            require(digest(temporary, "SHA-512").equals(candidate.sha512, true)) {
                "更新文件哈希不符"
            }
        } else if (candidate.sha1.isNotBlank()) {
            require(digest(temporary, "SHA-1").equals(candidate.sha1, true)) {
                "更新文件哈希不符"
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
                    "更新模组失败且原文件未能复位，备份保留在 ${backup.toAbsolutePath()}",
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
                "旧模组未能移入回收站，保留在 ${backup.toAbsolutePath()}：${error.message}",
                true,
            )
        }
    }
}
