// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责整合包原地升级。

package dev.molifox.core

import dev.molifox.core.i18n.I18n
import java.nio.file.Path
import java.util.zip.ZipFile

/** 整合包升级预览与执行。 */
internal interface ContentPackUpgradeApi {
    suspend fun previewPackUpgrade(
        instanceId: String,
        archive: Path,
        policy: PackUpgradePolicy = PackUpgradePolicy(restoreUserDeleted = true),
    ): PackUpgradePlan

    /**
     * 按预览计划原地升级整合包；[expectedArchiveSha256] 防止预览后归档被替换。
     * 需改游戏版本或加载器时由调用方确认后置 [allowComponentChange]，任务队列里执行并可取消。
     */
    suspend fun upgradePack(
        instanceId: String,
        archive: Path,
        expectedArchiveSha256: String,
        allowComponentChange: Boolean,
        context: JobContext,
        policy: PackUpgradePolicy = PackUpgradePolicy(restoreUserDeleted = true),
    ): PackUpgradePlan
}

/** 升级预览与执行共用的中间结果：清单、新账本、计划与可选文件选择。 */
private data class PreparedPackUpgrade(
    val manifest: PackManifest,
    val ownership: PackOwnership,
    val plan: PackUpgradePlan,
    val selection: Set<String>?,
)

/** 实现由 [ContentService] 委托调用；方法体与原实现一致。 */
internal abstract class ContentPackUpgradeActions(
    core: ContentServiceCore,
) : ContentPackImportActions(core),
    ContentPackUpgradeApi {
    override suspend fun previewPackUpgrade(
        instanceId: String,
        archive: Path,
        policy: PackUpgradePolicy,
    ): PackUpgradePlan =
        withPackSnapshot(archive) { snapshot ->
            val instance = store.instance(instanceId)
            require(instance.packFile.isNotBlank()) { I18n.t("core.contentPackUpgradeActions.1") }
            val root = store.instancePath(instanceId)
            ZipFile(snapshot.toFile()).use { zip ->
                preparePackUpgrade(instance, root, snapshot, zip, policy).plan
            }
        }

    override suspend fun upgradePack(
        instanceId: String,
        archive: Path,
        expectedArchiveSha256: String,
        allowComponentChange: Boolean,
        context: JobContext,
        policy: PackUpgradePolicy,
    ): PackUpgradePlan =
        withPackSnapshot(archive) { snapshot ->
            verifyUpgradeArchive(snapshot, expectedArchiveSha256)
            val instance = store.instance(instanceId)
            require(instance.installed) { I18n.t("packsB2.worldPack.instanceNotInstalled") }
            require(instance.packFile.isNotBlank()) { I18n.t("core.contentPackUpgradeActions.1") }
            val root = store.instancePath(instanceId)
            ZipFile(snapshot.toFile()).use { zip ->
                val prepared = preparePackUpgrade(instance, root, snapshot, zip, policy)
                require(!prepared.plan.requiresComponentChange || allowComponentChange) {
                    I18n.t("core.contentPackUpgradeActions.2")
                }
                val savedPack = upgradeArchivePath(instance.id, prepared.manifest.index != null)
                runPreparedUpgrade(instance, prepared, root, zip, snapshot, savedPack, context)
                prepared.plan
            }
        }

    private fun preparePackUpgrade(
        instance: GameInstance,
        root: Path,
        snapshot: Path,
        zip: ZipFile,
        policy: PackUpgradePolicy,
    ): PreparedPackUpgrade {
        val manifest =
            readPackManifest(
                zip,
                store.temporaryDirectory.resolve("pack-validation"),
                store.platform.osName,
            )
        installer.validate(manifest.request)
        require(manifest.curseForge == null) { I18n.t("core.contentPackUpgradeActions.3") }
        val selection =
            instance.packOptionalFiles?.let { chosen ->
                manifest.optionalFiles.filter { it in chosen }.toSet()
            }
        val ownership =
            PackOwnershipStore.buildOwnership(
                manifest,
                digest(snapshot, "SHA-256"),
                selection,
                zip,
                root,
            )
        val old =
            PackOwnershipStore.read(root, store) ?: legacyPackOwnership(instance, root, selection)
        val paths = (old.files.map { it.path } + ownership.files.map { it.path }).toSet()
        val plan =
            PackUpgradePlanner.plan(
                instance.id,
                old,
                ownership,
                scanPackFileStates(root, paths),
                policy,
            )
        return PreparedPackUpgrade(manifest, ownership, plan, selection)
    }

    /** 没有账本的老实例：优先用旧归档推断，再用实例记录补齐版本与组件信息。 */
    private fun legacyPackOwnership(
        instance: GameInstance,
        root: Path,
        selection: Set<String>?,
    ): PackOwnership {
        val archive =
            instance.packFile
                .takeIf { it.isNotBlank() }
                ?.let { path -> runCatching { Path.of(path) }.getOrNull() }
        val baseline =
            PackOwnershipStore.migrationBaseline(
                root,
                archive,
                null,
                selection,
                store.platform.osName,
            )
        return baseline.copy(
            name = baseline.name.ifBlank { instance.name },
            gameVersion = baseline.gameVersion.ifBlank { instance.gameVersion },
            loader = baseline.loader.ifBlank { instance.loader.name },
            loaderVersion = baseline.loaderVersion.ifBlank { instance.loaderVersion },
            versionId = baseline.versionId.ifBlank { instance.versionId },
        )
    }

    private fun verifyUpgradeArchive(
        snapshot: Path,
        expectedArchiveSha256: String,
    ) {
        require(
            expectedArchiveSha256.isNotBlank() &&
                digest(snapshot, "SHA-256").equals(expectedArchiveSha256, true),
        ) {
            I18n.t("core.contentPackUpgradeActions.4")
        }
    }

    private suspend fun runPreparedUpgrade(
        instance: GameInstance,
        prepared: PreparedPackUpgrade,
        root: Path,
        zip: ZipFile,
        snapshot: Path,
        savedPack: Path,
        context: JobContext,
    ) {
        val staged = PackFileTransaction(store, root)
        try {
            stagePackFiles(
                prepared.manifest,
                zip,
                staged,
                root,
                prepared.selection,
                false,
                context,
                concurrency = store.data.settings.downloadThreads,
            ) {
                network.download(it) { done, total ->
                    context.transfer(it.target.toString(), done, total)
                }
            }
            context.checkCancelled()
            applyPreparedUpgrade(instance, prepared, staged, snapshot, savedPack, zip, context)
        } finally {
            staged.cleanup()
        }
    }

    private suspend fun applyPreparedUpgrade(
        instance: GameInstance,
        prepared: PreparedPackUpgrade,
        staged: PackFileTransaction,
        snapshot: Path,
        savedPack: Path,
        zip: ZipFile,
        context: JobContext,
    ) {
        val root = store.instancePath(instance.id)
        val transaction = PackUpgradeTransaction(store, root, upgradeWorkDirectory(root))
        try {
            transaction.apply(
                plan = prepared.plan,
                staging = staged.content,
                archiveSource = snapshot,
                archiveTarget = savedPack,
                ownership = prepared.ownership,
                instanceId = instance.id,
                updateInstance = {
                    it.copy(
                        packFile = savedPack.toString(),
                        installed = true,
                        packOptionalFiles = prepared.selection?.toList(),
                    )
                },
                context = context,
            )
            applyPackIconSafely(instance.id, zip, prepared.manifest, I18n.t("core.contentPackUpgradeActions.5"))
        } finally {
            transaction.cleanup()
        }
    }
}
