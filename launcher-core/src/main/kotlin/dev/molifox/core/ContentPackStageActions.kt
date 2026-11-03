// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责整合包文件落盘与图标。

package dev.molifox.core

import dev.molifox.core.i18n.I18n
import kotlinx.coroutines.CancellationException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.zip.ZipFile
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempFile
import kotlin.io.path.deleteIfExists
import kotlin.io.path.isRegularFile
import kotlin.io.path.isSymbolicLink

/** 导入已解析、后续存档与安装共用的整合包信息。 */
internal data class PackImport(
    val manifest: PackManifest,
    val selection: Set<String>?,
    val curseForgeFiles: List<CurseForgePackFile>,
    val repairId: String?,
)

/** 实现由 [ContentService] 委托调用；方法体与原实现一致。 */
internal abstract class ContentPackStageActions(
    core: ContentServiceCore,
) : ContentLocalActions(core) {
    protected fun importInstance(
        request: InstallRequest,
        launchDefaults: PackLaunchDefaults?,
        instanceDefaults: PackInstanceDefaults?,
        repairId: String?,
        gameDirectoryId: String?,
    ): GameInstance =
        repairId?.let { store.instance(it) }
            ?: installer.createInstance(request, gameDirectoryId).let { created ->
                store.updateInstance(created.id) {
                    applyPackInstanceDefaults(
                        it.copy(
                            isolation = VersionIsolation.Inherit,
                            extraJvmArgs = launchDefaults?.jvm ?: it.extraJvmArgs,
                            advancedLaunch = launchDefaults?.advanced ?: it.advancedLaunch,
                        ),
                        instanceDefaults,
                    )
                }
                store.instance(created.id)
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
            try {
                Files.move(
                    replacement,
                    savedPack,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(replacement, savedPack, StandardCopyOption.REPLACE_EXISTING)
            }
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
        ) {
            network.download(it) { done, total ->
                context.transfer(it.target.toString(), done, total)
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
