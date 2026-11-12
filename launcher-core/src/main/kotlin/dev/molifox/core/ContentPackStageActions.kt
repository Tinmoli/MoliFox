// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责整合包文件落盘与图标。

package dev.molifox.core

import dev.molifox.core.i18n.I18n
import kotlinx.coroutines.CancellationException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.zip.ZipFile
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile

/** 导入已解析、后续存档与安装共用的整合包信息。 */
internal data class PackImport(
    val manifest: PackManifest,
    val selection: Set<String>?,
    val curseForgeFiles: List<CurseForgePackFile>,
    val repairId: String?,
)

internal fun PackImport.networkFileSizes(root: Path): List<Pair<String, Long>> =
    buildList {
        for (file in manifest.selectedIndexFiles(selection)) {
            add(file.string("path") to file.get("fileSize").asLong)
        }
        for (file in curseForgeFiles) {
            if (file.artifact != null) add(file.path to file.size)
        }
        for (file in manifest.checkedFiles) {
            if (file.url.isNotBlank() && (repairId == null || !packPath(root, file.path).exists())) {
                add(file.path to 0L)
            }
        }
    }.distinctBy { it.first }

/** 实现由 [ContentService] 委托调用；方法体与原实现一致。 */
internal abstract class ContentPackStageActions(
    core: ContentServiceCore,
) : ContentLocalActions(core) {
    protected suspend fun importInstance(
        request: InstallRequest,
        launchDefaults: PackLaunchDefaults?,
        instanceDefaults: PackInstanceDefaults?,
        repairId: String?,
        gameDirectoryId: String?,
        context: JobContext,
    ): GameInstance {
        if (repairId != null) return store.instance(repairId)
        val versionType =
            if (store.data.settings.effectiveVersionIsolationPolicy.usesVersionType) {
                installer
                    .versions()
                    .firstOrNull { it.id == request.gameVersion }
                    ?.type
                    .orEmpty()
            } else {
                ""
            }
        val created = installer.createInstance(request, gameDirectoryId)
        NewInstallationCleanup(store).register(created, context)
        store.updateInstance(created.id) {
            applyPackInstanceDefaults(
                it.copy(
                    isolation = VersionIsolation.Inherit,
                    gameVersionType = versionType,
                    extraJvmArgs = launchDefaults?.jvm ?: it.extraJvmArgs,
                    advancedLaunch = launchDefaults?.advanced ?: it.advancedLaunch,
                ),
                instanceDefaults,
            )
        }
        return store.instance(created.id)
    }

    protected fun storePackArchive(
        snapshot: Path,
        instanceId: String,
        pack: PackImport,
    ) {
        val savedPack = savePackArchive(snapshot, instanceId, pack.manifest.index != null)
        store.updateInstance(instanceId) {
            it.copy(
                packFile = savedPack.toString(),
                installed = false,
                packOptionalFiles = pack.selection?.toList(),
            )
        }
    }

    private fun savePackArchive(
        snapshot: Path,
        instanceId: String,
        mrpack: Boolean,
    ): Path {
        val savedPack =
            store.directories.packArchives.resolve(
                "$instanceId.${if (mrpack) "mrpack" else "zip"}",
            )
        savedPack.parent.createDirectories()
        val replacement = Files.createTempFile(savedPack.parent, "${savedPack.fileName}.", ".part")
        try {
            Files.copy(snapshot, replacement, StandardCopyOption.REPLACE_EXISTING)
            replaceStagedFile(replacement, savedPack)
        } finally {
            replacement.deleteIfExists()
        }
        return savedPack
    }

    protected suspend fun stagePackContent(
        transaction: PackFileTransaction,
        root: Path,
        zip: ZipFile,
        pack: PackImport,
        context: JobContext,
        progressStart: Float = 0f,
        progressEnd: Float = 1f,
    ) {
        val artifacts =
            pack.networkFileSizes(root).map { (path, size) ->
                DownloadArtifact("", packPath(transaction.content, path), size = size)
            }
        context.updateInstallationTransfer { it.beginPack(artifacts) }
        stageCurseForgePackFiles(
            pack.curseForgeFiles,
            transaction,
            context,
            progressStart,
            progressEnd,
            concurrency = store.data.settings.downloadThreads,
        ) {
            network.download(it) { done, total ->
                context.transfer(it.target.toString(), done, total)
            }
        }
        stagePackFiles(
            pack.manifest,
            zip,
            transaction,
            root,
            pack.selection,
            pack.repairId != null,
            context,
            progressStart,
            progressEnd,
            concurrency = store.data.settings.downloadThreads,
        ) { artifact, urls ->
            network.downloadFrom(artifact, urls) { done, total ->
                context.transfer(artifact.target.toString(), done, total)
            }
        }
    }

    protected fun writePackOwnership(
        root: Path,
        snapshot: Path,
        zip: ZipFile,
        pack: PackImport,
    ) {
        runCatching {
            PackOwnershipStore.write(
                root,
                PackOwnershipStore.buildOwnership(
                    pack.manifest,
                    digest(snapshot, "SHA-256"),
                    pack.selection,
                    zip,
                    root,
                ),
            )
        }.onFailure {
            store.log(
                I18n.t("contentA2.contentPackStageActions.ownershipWriteFailed", it.message),
                true,
            )
        }
    }

    protected suspend fun applyPackIconSafely(
        instanceId: String,
        zip: ZipFile,
        manifest: PackManifest,
        logLabel: String,
    ) {
        runCatching { applyPackIcon(store, instanceId, zip, manifest) }
            .onFailure {
                if (it is CancellationException) throw it
                store.log("$logLabel：${it.message}", true)
            }
    }

    /** 只扫描升级计划可能涉及的路径，避免遍历整个实例目录。 */
    protected fun scanPackFileStates(
        root: Path,
        paths: Set<String>,
    ): Map<String, PackFileState> =
        paths.associateWith { relative ->
            val target = packPath(root, relative)
            if (!target.isRegularFile() || Files.isSymbolicLink(target)) {
                PackFileState(false, null)
            } else {
                PackFileState(true, runCatching { digest(target) }.getOrNull())
            }
        }

    protected fun upgradeArchivePath(
        instanceId: String,
        mrpack: Boolean,
    ): Path {
        val savedPack =
            store.directories.packArchives.resolve(
                "$instanceId.${if (mrpack) "mrpack" else "zip"}",
            )
        savedPack.parent.createDirectories()
        return savedPack
    }

    protected fun upgradeWorkDirectory(root: Path): Path = root.resolve(".molifox-pack-upgrade-${UUID.randomUUID()}")
}
