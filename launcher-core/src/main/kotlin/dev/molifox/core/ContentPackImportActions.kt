// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责整合包预览与导入。

package dev.molifox.core

import dev.molifox.core.i18n.I18n
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.zip.ZipFile
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempFile
import kotlin.io.path.deleteIfExists

/** 原版与加载器占整合包安装进度的前 90%；剩余 10% 留给整合包文件。 */
private const val PACK_GAME_PROGRESS_END = 0.9f

/** 整合包校验预览。 */
internal interface ContentPackImportApi {
    suspend fun previewPack(archive: Path): PackPreview
}

/** 实现由 [ContentService] 委托调用；方法体与原实现一致。 */
internal abstract class ContentPackImportActions(
    core: ContentServiceCore,
) : ContentPackStageActions(core),
    ContentPackImportApi {
    override suspend fun previewPack(archive: Path): PackPreview =
        withPackSnapshot(archive) { snapshot ->
            ZipFile(snapshot.toFile()).use { zip ->
                val manifest =
                    readPackManifest(
                        zip,
                        store.temporaryDirectory.resolve("pack-validation"),
                        store.platform.osName,
                    )
                installer.validate(manifest.request)
                PackPreview(
                    archive.toAbsolutePath().toString(),
                    manifest.request.name,
                    manifest.optionalFiles,
                    digest(snapshot, "SHA-256"),
                    manifest.request.gameVersion,
                    manifest.request.loader,
                    manifest.request.loaderVersion,
                    manifest.version,
                    manifest.summary,
                    manifest.requiredFiles,
                )
            }
        }

    internal suspend fun importPack(
        archive: Path,
        context: JobContext,
        repairId: String? = null,
        optionalFiles: Set<String>? = null,
        expectedHash: String? = null,
        gameDirectoryId: String?,
    ) = withPackSnapshot(archive) { snapshot ->
        verifyPackArchive(snapshot, expectedHash)
        ZipFile(snapshot.toFile()).use { zip ->
            val manifest = packManifest(zip)
            val request = manifest.request
            installer.validate(request)
            val launchDefaults =
                if (repairId == null) {
                    manifest.launchOptions?.resolve(store.data.settings, store.platform.osName)
                } else {
                    null
                }
            // 修复只补文件：包内声明的内存/窗口默认值不再覆盖用户已有设置。
            val instanceDefaults = if (repairId == null) manifest.instanceDefaults else null
            verifyRepairTarget(request, repairId)
            verifyOptionalFiles(manifest, optionalFiles)
            val selection =
                optionalFiles ?: repairId?.let { store.instance(it).packOptionalFiles?.toSet() }
            val curseForgeFiles =
                prepareCurseForgeFiles(manifest, selection, archive, context, repairId)
            val instance =
                importInstance(
                    request,
                    launchDefaults,
                    instanceDefaults,
                    repairId,
                    gameDirectoryId,
                )
            context.bindInstance(instance.id)
            if (repairId == null) NewInstallationCleanup(store).register(instance, context)
            val root = store.instancePath(instance.id)
            // Validate every override path before any download or extraction.
            for (prefix in manifest.overridePrefixes) validateArchive(zip, prefix, root)
            val pack = PackImport(manifest, selection, curseForgeFiles, repairId)
            storePackArchive(snapshot, instance.id, pack)
            installPackFiles(instance, snapshot, zip, pack, context)
        }
    }

    protected suspend fun <T> withPackSnapshot(
        archive: Path,
        action: suspend (Path) -> T,
    ): T =
        withContext(Dispatchers.IO) {
            val snapshot =
                Files.createTempFile(
                    store.temporaryDirectory.apply { createDirectories() },
                    "pack-import-",
                    ".zip",
                )
            try {
                Files.copy(archive, snapshot, StandardCopyOption.REPLACE_EXISTING)
                action(snapshot)
            } finally {
                snapshot.deleteIfExists()
            }
        }

    private fun packManifest(zip: ZipFile): PackManifest =
        readPackManifest(
            zip,
            store.temporaryDirectory.resolve("pack-validation"),
            store.platform.osName,
        )

    private fun verifyPackArchive(
        snapshot: Path,
        expectedHash: String?,
    ) {
        require(expectedHash == null || digest(snapshot, "SHA-256").equals(expectedHash, true)) {
            I18n.t("contentA2.contentPackImportActions.archiveChanged")
        }
    }

    private fun verifyRepairTarget(
        request: InstallRequest,
        repairId: String?,
    ) {
        if (repairId == null) return
        val existing = store.instance(repairId)
        require(
            existing.gameVersion == request.gameVersion &&
                existing.loader == request.loader &&
                existing.loaderVersion == request.loaderVersion,
        ) {
            I18n.t("contentA2.contentPackImportActions.repairMismatch")
        }
    }

    private fun verifyOptionalFiles(
        manifest: PackManifest,
        optionalFiles: Set<String>?,
    ) {
        require(optionalFiles == null || optionalFiles.all { it in manifest.optionalFiles }) {
            I18n.t("contentA2.contentPackImportActions.optionalFilesChanged")
        }
    }

    private suspend fun prepareCurseForgeFiles(
        manifest: PackManifest,
        selection: Set<String>?,
        archive: Path,
        context: JobContext,
        repairId: String?,
    ): List<CurseForgePackFile> =
        if (manifest.curseForge == null) {
            emptyList()
        } else {
            prepareCurseForgePack(
                manifest,
                curseForge,
                selection,
                archive.toAbsolutePath().parent.resolve("manual-downloads"),
                context,
                repairId?.let(store::instancePath),
            )
        }

    private suspend fun installPackFiles(
        instance: GameInstance,
        snapshot: Path,
        zip: ZipFile,
        pack: PackImport,
        context: JobContext,
    ) {
        var transaction: PackFileTransaction? = null
        var completed = false
        try {
            // 原版/加载器只占前 90%，剩余进度留给整合包文件；避免这里先报 100% 再倒回 2%。
            installer.install(
                instance.id,
                context,
                freshInstall = pack.repairId == null,
                progressEnd = PACK_GAME_PROGRESS_END,
                // 整合包导入失败要保留实例和存档，供同一实例重试修复。
                discardOnFailure = false,
                // 等整合包文件全部落盘后再重新扫描，避免中间状态把实例列表重建丢。
                refreshAfterInstall = false,
            )
            val root = store.instancePath(instance.id)
            val staged = PackFileTransaction(store, root)
            transaction = staged
            store.updateInstance(instance.id) { it.copy(installed = false) }
            commitPackContent(instance.id, staged, root, snapshot, zip, pack, context)
            completed = true
        } finally {
            try {
                if (!completed) {
                    // 安装中途失败时恢复实例标记：修复失败仍保持已安装，新建失败保持未安装。
                    store.updateInstance(instance.id) {
                        it.copy(installed = pack.repairId != null && instance.installed)
                    }
                }
            } finally {
                transaction?.cleanup()
            }
        }
    }

    /** 落盘整合包文件、提交事务并标记完成；进度接在原版安装之后。 */
    private suspend fun commitPackContent(
        instanceId: String,
        transaction: PackFileTransaction,
        root: Path,
        snapshot: Path,
        zip: ZipFile,
        pack: PackImport,
        context: JobContext,
    ) {
        stagePackContent(
            transaction,
            root,
            zip,
            pack,
            context,
            progressStart = PACK_GAME_PROGRESS_END,
            progressEnd = 1f,
        )
        transaction.commit(context)
        writePackOwnership(root, snapshot, zip, pack)
        store.updateInstance(instanceId) { it.copy(installed = true) }
        applyPackIconSafely(
            instanceId,
            zip,
            pack.manifest,
            I18n.t("contentA2.contentPackImportActions.iconNotApplied"),
        )
        context.progress(1f, I18n.t("contentA2.contentPackImportActions.installed"))
        store.refreshInstances()
    }
}
