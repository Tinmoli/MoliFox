// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责整合包预览与导入。

package dev.molifox.core

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
            verifyRepairTarget(request, repairId)
            verifyOptionalFiles(manifest, optionalFiles)
            val selection =
                optionalFiles ?: repairId?.let { store.instance(it).packOptionalFiles?.toSet() }
            val curseForgeFiles =
                prepareCurseForgeFiles(manifest, selection, archive, context, repairId)
            val instance = importInstance(request, launchDefaults, repairId, gameDirectoryId)
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
            "整合包文件已改变，请重新选择"
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
            "修复包的游戏版本或加载器与实例不符，请新建实例导入；修复不能替代升级"
        }
    }

    private fun verifyOptionalFiles(
        manifest: PackManifest,
        optionalFiles: Set<String>?,
    ) {
        require(optionalFiles == null || optionalFiles.all { it in manifest.optionalFiles }) {
            "所选可选文件已不在整合包中，请重新选择"
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
            installer.install(instance.id, context)
            val root = store.instancePath(instance.id)
            val staged = PackFileTransaction(store, root)
            transaction = staged
            store.updateInstance(instance.id) { it.copy(installed = false) }
            stagePackContent(staged, root, zip, pack, context)
            staged.commit(context)
            writePackOwnership(root, snapshot, zip, pack)
            store.updateInstance(instance.id) { it.copy(installed = true) }
            applyPackIconSafely(instance.id, zip, pack.manifest, "整合包图标未采用，使用已有或默认图标")
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
}
