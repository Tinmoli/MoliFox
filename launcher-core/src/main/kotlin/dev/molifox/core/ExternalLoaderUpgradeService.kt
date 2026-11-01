// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责外部版本目录的加载器升级：合并进原 JSON 并在失败时按字节恢复。

package dev.molifox.core

import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import dev.molifox.core.i18n.I18n
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.isSymbolicLink

/** 外部版本升级时加载器配置阶段的进度起点与终点。 */
private const val EXTERNAL_LOADER_PROGRESS_START = 0.05f
private const val EXTERNAL_LOADER_PROGRESS_END = 0.60f

/** 外部版本升级时发布后依赖下载的进度终点。 */
private const val EXTERNAL_DEPENDENCIES_PROGRESS_END = 0.99f

/** 引用链预检失败时统一成一句可读原因。 */
private fun externalMergeFailure(error: Exception): String =
    I18n.t("networkB1.externalLoaderUpgrade.mergeFailed", error.message)

/**
 * 升级外部目录实例的加载器：加载器配置合并进原版本 JSON，保留 `id`、`inheritsFrom`
 * 链和其它字段，不写 `molifoxManaged`；写盘前把原 JSON 复制成
 * `<名字>.json.molifox-backup-<yyyyMMdd-HHmmss>`，失败时按字节恢复。
 */
internal class ExternalLoaderUpgradeService(
    private val store: LauncherStore,
    private val loaders: LoaderProfileFetcher,
    private val pipeline: MinecraftInstallPipeline,
) {
    /**
     * 外部实例升级前的结构预检：返回不能安全合并的具体原因，null 表示可以升级。
     *
     * 只读：不下载、不写文件。外部 JSON 继承的基础版本在本地缺失时直接说明原因，
     * 界面据此不显示升级按钮。
     */
    fun externalLoaderUpgradeBlocker(instance: GameInstance): String? {
        if (!instance.externalProfile) return null
        val folderResult = runCatching { store.versionGamePath(instance) }
        val folder = folderResult.getOrNull()
        return if (folder == null) {
            I18n.t(
                "networkB1.externalLoaderUpgrade.instanceFolderUnavailable",
                folderResult.exceptionOrNull()?.message,
            )
        } else {
            externalConfigBlocker(instance, folder)
        }
    }

    /** 检查外部版本的 JSON 是否存在，再把继承链预检包成可读原因（不受异常中断）。 */
    private fun externalConfigBlocker(
        instance: GameInstance,
        folder: Path,
    ): String? {
        val json = folder.resolve("${folder.fileName}.json")
        if (!json.isRegularFile(NOFOLLOW_LINKS) || json.isSymbolicLink()) {
            return I18n.t("networkB1.externalLoaderUpgrade.missingProfile", json)
        }
        val rootResult = runCatching { store.minecraftRoot(instance) }
        val root = rootResult.getOrNull()
        return if (root == null) {
            rootResult.exceptionOrNull()?.message
        } else {
            try {
                inheritsChainBlocker(root, json, folder)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: IOException) {
                externalMergeFailure(error)
            } catch (error: IllegalArgumentException) {
                externalMergeFailure(error)
            } catch (error: IllegalStateException) {
                externalMergeFailure(error)
            } catch (error: JsonParseException) {
                externalMergeFailure(error)
            }
        }
    }

    /** 只读检查原版本的 `inheritsFrom` 链：自继承、断链、缺字段都给出可读原因。 */
    private fun inheritsChainBlocker(
        root: Path,
        json: Path,
        folder: Path,
    ): String? {
        val original = readObject(json)
        val parent = original.string("inheritsFrom").takeIf { it.isNotBlank() }
        return if (parent != null) {
            when {
                parent == folder.fileName.toString() ->
                    I18n.t("networkB1.externalLoaderUpgrade.selfInheritance", parent)
                !root
                    .resolve("versions")
                    .resolve(parent)
                    .resolve("$parent.json")
                    .isRegularFile(NOFOLLOW_LINKS) ->
                    I18n.t("networkB1.externalLoaderUpgrade.parentMissing", parent)
                else -> {
                    // 校验整条 inheritsFrom 链，断链或循环都给出可读原因。
                    VersionResolver(root, store.platform).resolve(parent)
                    null
                }
            }
        } else if (
            original.string("mainClass").isBlank() && original.array("libraries").isEmpty()
        ) {
            I18n.t("networkB1.externalLoaderUpgrade.missingMainClass")
        } else {
            null
        }
    }

    suspend fun upgradeExternalLoader(
        current: GameInstance,
        loaderVersion: String,
        context: JobContext,
    ) {
        val folder = store.versionGamePath(current)
        val jsonPath = folder.resolve("${folder.fileName}.json")
        val root = store.minecraftRoot(current)
        val blocker = externalLoaderUpgradeBlocker(current)
        require(blocker == null) { blocker.orEmpty() }
        val original = readObject(jsonPath)
        val parent = original.string("inheritsFrom").takeIf { it.isNotBlank() }
        val base =
            if (parent != null) {
                VersionResolver(root, store.platform).resolve(parent)
            } else {
                VersionResolver(root, store.platform).resolve(current.versionId)
            }
        val target = current.copy(loaderVersion = loaderVersion)
        val loader =
            loaders.fetchLoaderProfile(
                target,
                base,
                context,
                EXTERNAL_LOADER_PROGRESS_START,
                EXTERNAL_LOADER_PROGRESS_END,
            )
        writeExternalUpgrade(
            current,
            loaderVersion,
            original,
            loader,
            jsonPath,
            folder,
            root,
            context,
        )
    }

    /** 写回合并后的配置并补齐依赖；失败时按字节恢复备份的 JSON。 */
    private suspend fun writeExternalUpgrade(
        current: GameInstance,
        loaderVersion: String,
        original: JsonObject,
        loader: JsonObject,
        jsonPath: Path,
        folder: Path,
        root: Path,
        context: JobContext,
    ) {
        val merged = mergeExternalLoaderProfile(original, loader)
        context.checkCancelled()
        // 备份不是任务产物：取消/失败都不删除，留在原 JSON 旁供用户核对或手动回退。
        val backup = backupExternalProfile(jsonPath)
        try {
            context.writeOutput(jsonPath) { writeJson(jsonPath, merged) }
            context.checkCancelled()
            pipeline.installDependencies(
                VersionResolver(root, store.platform).resolve(folder.fileName.toString()),
                context,
                root,
                EXTERNAL_LOADER_PROGRESS_END,
                EXTERNAL_DEPENDENCIES_PROGRESS_END,
            )
            context.checkCancelled()
            store.updateInstance(current.id) {
                it.copy(loaderVersion = loaderVersion, installed = true)
            }
            store.refreshInstances()
            context.progress(
                1f,
                I18n.t(
                    "networkB1.externalLoaderUpgrade.upgradedWithBackup",
                    current.loader.title,
                    loaderVersion,
                ),
            )
        } catch (expected: Throwable) {
            restoreExternalProfileBackup(backup, jsonPath)
            throw expected
        }
    }

    private suspend fun restoreExternalProfileBackup(
        backup: Path,
        jsonPath: Path,
    ) {
        withContext(NonCancellable + Dispatchers.IO) {
            runCatching {
                Files.copy(
                    backup,
                    jsonPath,
                    StandardCopyOption.REPLACE_EXISTING,
                )
                // 只恢复这一个版本 JSON，其它文件与备份都保留。
                store.refreshInstances()
            }.onFailure {
                store.log(
                    I18n.t("networkB1.externalLoaderUpgrade.restoreFailed", it.message, backup),
                    true,
                )
            }
        }
    }

    /** 把外部版本 JSON 原样复制到同目录的时间戳备份；同一秒内重名时追加序号。 */
    private fun backupExternalProfile(jsonPath: Path): Path {
        require(jsonPath.isRegularFile(NOFOLLOW_LINKS) && !jsonPath.isSymbolicLink()) {
            I18n.t("networkB1.externalLoaderUpgrade.missingProfile", jsonPath)
        }
        val stamp =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(LocalDateTime.now())
        var backup = jsonPath.resolveSibling("${jsonPath.fileName}.molifox-backup-$stamp")
        var index = 1
        while (backup.exists(NOFOLLOW_LINKS)) {
            backup =
                jsonPath.resolveSibling("${jsonPath.fileName}.molifox-backup-$stamp-$index")
            index++
        }
        Files.copy(jsonPath, backup)
        store.log(I18n.t("networkB1.externalLoaderUpgrade.backedUp", backup))
        return backup
    }
}
