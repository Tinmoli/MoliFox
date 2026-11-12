// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责整合包预览与导入。

package dev.molifox.core

import dev.molifox.core.i18n.I18n
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.zip.ZipFile
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists

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
            withResolvedPack(snapshot) { packArchive ->
                ZipFile(packArchive.toFile()).use { zip ->
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
        }

    internal suspend fun importPack(
        archive: Path,
        context: JobContext,
        repairId: String? = null,
        optionalFiles: Set<String>? = null,
        expectedHash: String? = null,
        gameDirectoryId: String?,
    ) = context.cleanOnFailure {
        context.enableInstallationTransfers()
        importPackSnapshot(archive, context, repairId, optionalFiles, expectedHash, gameDirectoryId)
    }

    private suspend fun importPackSnapshot(
        archive: Path,
        context: JobContext,
        repairId: String?,
        optionalFiles: Set<String>?,
        expectedHash: String?,
        gameDirectoryId: String?,
    ) = withPackSnapshot(archive) { snapshot ->
        verifyPackArchive(snapshot, expectedHash)
        withResolvedPack(snapshot) { packArchive ->
            ZipFile(packArchive.toFile()).use { zip ->
                val manifest = packManifest(zip)
                val request = manifest.request
                installer.validate(request)
                val launchDefaults =
                    manifest.launchOptions
                        ?.takeIf { repairId == null }
                        ?.resolve(store.data.settings, store.platform.osName)
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
                        context,
                    )
                context.bindInstance(instance.id)
                val root = store.instancePath(instance.id)
                // Validate every override path before any download or extraction.
                for (prefix in manifest.overridePrefixes) validateArchive(zip, prefix, root)
                val pack = PackImport(manifest, selection, curseForgeFiles, repairId)
                val sizes = pack.networkFileSizes(root).map { it.second }
                context.updateInstallationTransfer { it.reservePack(sizes) }
                // 保存内层整合包：之后“修复整合包”可直接按实例文件重新导入，不再依赖外层壳。
                storePackArchive(packArchive, instance.id, pack)
                installPackFiles(instance, packArchive, zip, pack, context)
                if (repairId == null) NewInstallationCleanup(store).disarm(instance, context)
            }
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

    /**
     * 外层 zip 自己不是整合包、但内部带了 modpack.mrpack/modpack.zip 时，先只解出内层包再处理。
     * 解出的临时文件只在本次预览/导入期间有效，实例保存的是内层包。
     */
    private suspend fun <T> withResolvedPack(
        snapshot: Path,
        action: suspend (Path) -> T,
    ): T =
        withContext(Dispatchers.IO) {
            val embedded =
                ZipFile(snapshot.toFile()).use { zip ->
                    if (hasRootPackMetadata(zip)) null else findEmbeddedPack(zip)
                }
            if (embedded == null) {
                action(snapshot)
            } else {
                val extracted = extractEmbeddedPack(snapshot, store.temporaryDirectory, embedded)
                try {
                    action(extracted)
                } finally {
                    extracted.deleteIfExists()
                }
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
        val gameVersion = existing.gameVersion.ifBlank { persistedGameVersion(store, existing) }
        require(
            gameVersion == request.gameVersion &&
                existing.loader == request.loader &&
                existing.loaderVersion == request.loaderVersion,
        ) {
            I18n.t("contentA2.contentPackImportActions.repairMismatch")
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
        val createTransaction = {
            PackFileTransaction(store, store.instancePath(instance.id)).also { transaction = it }
        }
        val stage: suspend (PackFileTransaction) -> Unit = { stagePackContent(it, it.root, zip, pack, context) }
        try {
            val staged =
                if (pack.repairId == null) {
                    stageFreshPack(installer.pipeline, instance.id, context, createTransaction, stage)
                } else {
                    installer.install(instance.id, context, discardOnFailure = false, refreshAfterInstall = false)
                    store.updateInstance(instance.id) { it.copy(installed = false) }
                    createTransaction().also { stage(it) }
                }
            commitPackContent(instance.id, staged, snapshot, zip, pack, context)
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
                if (transaction?.retain == true) context.freshInstallDirectory = null
                transaction?.cleanup()
            }
        }
    }

    /** 基础安装和暂存均完成后，提交整合包文件并发布实例。 */
    private suspend fun commitPackContent(
        instanceId: String,
        transaction: PackFileTransaction,
        snapshot: Path,
        zip: ZipFile,
        pack: PackImport,
        context: JobContext,
    ) {
        context.progressImmediate(0f, I18n.t("pages.tasks.downloadFinalizing"))
        transaction.commit(context)
        writePackOwnership(transaction.root, snapshot, zip, pack)
        store.updateInstance(instanceId) { it.copy(installed = true) }
        if (pack.repairId == null) store.endPendingInstall(store.instance(instanceId).instanceFolderName())
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

/** 基础安装确认目录归属后启动暂存；任一侧失败都会等待另一侧取消。 */
private suspend fun stageFreshPack(
    pipeline: MinecraftInstallPipeline,
    instanceId: String,
    context: JobContext,
    createTransaction: () -> PackFileTransaction,
    stage: suspend (PackFileTransaction) -> Unit,
): PackFileTransaction =
    coroutineScope {
        val target = CompletableDeferred<PackFileTransaction>()
        val staging =
            async {
                target.await().also { stage(it) }
            }
        pipeline.install(
            instanceId,
            context,
            freshInstall = true,
            discardOnFailure = false,
            refreshAfterInstall = false,
            onPackTargetReady = { target.complete(createTransaction()) },
        )
        staging.await()
    }

private fun verifyOptionalFiles(
    manifest: PackManifest,
    optionalFiles: Set<String>?,
) {
    require(optionalFiles == null || optionalFiles.all { it in manifest.optionalFiles }) {
        I18n.t("contentA2.contentPackImportActions.optionalFilesChanged")
    }
}
